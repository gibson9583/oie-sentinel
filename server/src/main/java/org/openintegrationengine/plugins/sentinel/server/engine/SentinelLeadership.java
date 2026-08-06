/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.controllers.ConfigurationController;

import org.openintegrationengine.plugins.sentinel.server.db.NodeLeaseRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.NodeLease;

/**
 * Decides which engine node runs Sentinel's exclusive background work, using
 * the {@code sentinel_node_lease} row as the single shared source of truth.
 *
 * <p><b>The problem.</b> Every node of a multi-node engine deployment loads
 * this plugin, and every node points at the same database. Without
 * coordination each one would run its own collector tick against the same
 * channels — every message delta written twice, every volume baseline and
 * every rollup bucket doubled — while their evaluators raced each other on
 * {@code sentinel_trigger_state} and paged twice for one problem. This class
 * is what makes exactly one node do that work.</p>
 *
 * <p><b>The shape of the fix.</b> The scheduler deliberately stays on Quartz's
 * {@code RAMJobStore} and every node keeps ticking on its own timers; only the
 * job bodies are conditional, each opening with a
 * {@code if (!SentinelLeadership.isLeader()) { return; }} guard so a
 * non-leader's tick costs nothing but the call. The rejected alternative — a
 * clustered {@code JDBCJobStore} — would couple Sentinel's scheduling to the
 * engine's database configuration (Quartz's own DDL, vendor-specific delegate
 * classes, clock-synchronisation requirements) to solve a problem a single
 * lease row already solves.</p>
 *
 * <p><b>Timings, and why these numbers.</b> The lease runs {@value
 * #LEASE_SECONDS} seconds and is renewed every {@value #HEARTBEAT_SECONDS} —
 * one third of it, so leadership survives two consecutive missed heartbeats
 * (a stalled query, a long GC pause, a brief database blip) before it lapses.
 * A lease that long is also comfortably wider than any clock skew a
 * time-synchronised cluster will show, which matters because the expiry
 * comparison uses the application's clock and not the database's — see
 * {@link NodeLeaseRepository#stealExpiredNodeLease}. The cost of the
 * generosity is failover latency: after an <em>unclean</em> stop a peer takes
 * over between {@value #LEASE_SECONDS}s (expiry) and
 * {@value #LEASE_SECONDS}s + {@value #HEARTBEAT_SECONDS}s (expiry plus its
 * next heartbeat) later. After a clean {@link #stopHeartbeat()} the lease is
 * deleted outright and a peer takes over within one heartbeat instead.</p>
 *
 * <p>Nothing here is derived from the operator-configurable collector and
 * evaluator intervals, on purpose: failover latency is an availability
 * property of the deployment and must not silently change because someone
 * retuned a sampling rate. The heartbeat also runs on its own thread rather
 * than piggybacking on a job tick, so a collector interval of 600 seconds
 * (the configurable maximum) cannot stretch the renewal past the expiry.</p>
 *
 * <p><b>Cost of a failover.</b> A non-leader never populates
 * {@link CollectorState}, so its counter snapshots stay cold. The new leader's
 * first tick therefore finds no previous counters and records zero deltas
 * rather than banking each channel's entire cumulative counter as one tick of
 * traffic — the same first-observation rule that already covers a server
 * restart. One tick of delta baseline is lost, which is the correct trade: the
 * alternative would fire every LOW_VOLUME and ANOMALY monitor on the server
 * the moment leadership moved.</p>
 *
 * <p>Stateless static methods with process-wide state, like
 * {@code ActionDispatcher}'s dispatch pool: the plugin lifecycle starts and
 * stops it while Quartz job threads read {@link #isLeader()}, so there is one
 * copy per JVM and the lifecycle methods are {@code synchronized}.</p>
 */
public final class SentinelLeadership {

    private static final Logger log = LoggerFactory.getLogger(SentinelLeadership.class);

    /**
     * The one lease guarding all four background jobs. A single name (rather
     * than one per job) is deliberate: the collector and the evaluator must
     * agree about which node is authoritative, since the evaluator judges the
     * very samples the collector writes, and splitting them across nodes would
     * let one evaluate windows the other had not finished filling.
     */
    private static final String LEASE_NAME = "sentinel-engine";

    /** Lease duration; see class Javadoc for why 90 and not less. */
    private static final int LEASE_SECONDS = 90;

    /** Renewal interval — one third of the lease, so two misses are survivable. */
    private static final int HEARTBEAT_SECONDS = 30;

    /**
     * How long before the written expiry this node stops considering itself
     * leader. Closes the window in which a slow clock could let this node
     * still believe it leads while a peer's faster clock already sees the
     * lease as expired and steals it: this node gives up {@value} seconds
     * early, so the two beliefs cannot overlap under skew smaller than that.
     */
    private static final int LEASE_SAFETY_MARGIN_SECONDS = 5;

    /** Longest the heartbeat thread is given to finish its current pass during shutdown. */
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    /** {@code node_id} is {@code VARCHAR(128)}; a derived id is truncated to fit rather than failing the insert. */
    private static final int NODE_ID_MAX_LENGTH = 128;

    /**
     * This node's identity in the lease row — resolved once at class load and
     * never changed, so every acquire, renew and release in this JVM speaks
     * for the same node. See {@link #resolveNodeId()}.
     */
    private static final String NODE_ID = resolveNodeId();

    /**
     * Set by {@link #startHeartbeat()} and never cleared — a one-way latch, not
     * a running flag.
     *
     * <p>It exists so {@link #isLeader()} can distinguish "this node lost the
     * election" from "leadership was never engaged in this JVM at all". The
     * latter is the single-node and unit-test case: with no heartbeat running
     * there is no peer to collide with, and a strict answer there would simply
     * switch the background jobs off. Never cleared on stop because once a JVM
     * has taken part in an election it may have live peers, and falling back to
     * the unguarded behaviour between a plugin stop and the next start is
     * exactly the double-collection this class exists to prevent.</p>
     *
     * <p>The safety of the "not engaged" answer rests on one wiring invariant,
     * enforced in {@code SentinelServicePlugin}: the heartbeat starts before
     * the scheduler and stops after it, so no job tick can ever observe this as
     * false while a peer holds the lease.</p>
     */
    private static volatile boolean leadershipEngaged;

    /**
     * The instant this node stops trusting its own leadership, or {@code null}
     * when it holds no claim at all. Set from the expiry written to the row,
     * less {@link #LEASE_SAFETY_MARGIN_SECONDS}.
     *
     * <p>Storing an instant rather than a boolean is what keeps a cached answer
     * honest. {@link NodeLeaseRepository} warns that a node which stalls long
     * enough loses its lease without being told; because leadership here is
     * time-bounded by the very expiry this node last wrote, a heartbeat that
     * stops running expires the local claim on the same schedule a peer becomes
     * able to steal it, with no database round trip on the job path.</p>
     */
    private static volatile Instant leadershipValidUntil;

    /** The heartbeat thread, or {@code null} whenever the plugin is stopped. */
    private static volatile ScheduledExecutorService heartbeatExecutor;

    /**
     * Raised for the duration of {@link #stopHeartbeat()} so no heartbeat pass
     * can claim the lease while this node is on its way out.
     *
     * <p>The pass that matters is the straggler: one already inside a database
     * call when shutdown began, which outlives the short wait for the heartbeat
     * thread to finish. Without this flag its renewal would find the row
     * {@link #standDown()} had just deleted, read that as "lost the lease", and
     * helpfully insert a fresh one — parking leadership on a node that is
     * shutting down for a full lease duration, which is precisely the outcome
     * releasing the lease exists to avoid.</p>
     */
    private static volatile boolean standingDown;

    private SentinelLeadership() {
    }

    /**
     * Whether this node may run Sentinel's exclusive background work — the
     * check {@link ActivityCollectorJob}, {@code TriggerEvaluatorJob},
     * {@link ActivityRollupJob} and {@link RetentionPruneJob} each open with.
     *
     * <p>True when this node holds an unexpired lease, and also when no
     * heartbeat has ever run in this JVM (see {@link #leadershipEngaged} — a
     * single-node or unit-test process has no peer to yield to). False in every
     * other case, including while the database is unreachable: a node that
     * cannot prove it leads must not act as though it does.</p>
     *
     * <p>Deliberately free of any database access — it is called on every tick
     * of every job — and safe to call from any thread.</p>
     *
     * @return whether this node should do the work of the tick
     */
    public static boolean isLeader() {
        if (!leadershipEngaged) {
            return true;
        }
        Instant validUntil = leadershipValidUntil;
        return validUntil != null && Instant.now().isBefore(validUntil);
    }

    /**
     * Starts the leadership heartbeat. Called from
     * {@code SentinelServicePlugin.start()} immediately before the scheduler,
     * so the very first collector and evaluator ticks already have a
     * leadership answer to consult rather than racing the first renewal.
     *
     * <p>The first acquire-or-renew pass runs synchronously on the caller's
     * thread for exactly that reason; the periodic passes then follow on a
     * single named daemon thread. Named so a thread dump during an HA incident
     * shows immediately whether the heartbeat is stuck, daemon so a heartbeat
     * blocked in a database call can never hold the JVM open.</p>
     *
     * <p>Idempotence guard mirrors {@code SentinelScheduler.start} and
     * {@code ActionDispatcher.startDispatchExecutor}: a second call logs and
     * returns rather than orphaning a live heartbeat thread that nothing is
     * left to stop.</p>
     *
     * <p>Never throws for a leadership reason — a database that cannot be
     * reached leaves this node a non-leader and the next pass retries. The
     * plugin start it is called from is guarded regardless.</p>
     */
    public static synchronized void startHeartbeat() {
        if (heartbeatExecutor != null) {
            log.warn("Sentinel leadership heartbeat already started; ignoring duplicate start request");
            return;
        }

        log.info("Sentinel leadership heartbeat starting as node {} (lease '{}', {}s duration, "
                        + "renewed every {}s); background jobs run only while this node holds the lease",
                NODE_ID, LEASE_NAME, LEASE_SECONDS, HEARTBEAT_SECONDS);

        // Set before the first pass, so from this moment isLeader() answers
        // strictly rather than falling back to single-node semantics.
        leadershipEngaged = true;
        // Cleared here rather than at the end of stopHeartbeat, so a straggling
        // pass from the previous run cannot slip through between the two.
        standingDown = false;

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sentinel-leadership");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor = executor;

        heartbeat();

        executor.scheduleAtFixedRate(SentinelLeadership::heartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the heartbeat and stands this node down. Called from
     * {@code SentinelServicePlugin.stop()} after the scheduler has shut down,
     * so no job tick can be in flight when leadership is surrendered.
     *
     * <p>The heartbeat thread is stopped first and the lease released second,
     * so a pass already in flight cannot renew the row a moment after it was
     * deleted and strand leadership on a node that is shutting down.</p>
     *
     * <p>Never throws: {@code stop()} runs during server shutdown or redeploy,
     * where an exception could disrupt the rest of the extension teardown, and
     * a lease that could not be released simply expires instead.</p>
     */
    public static synchronized void stopHeartbeat() {
        standingDown = true;

        ScheduledExecutorService executor = heartbeatExecutor;
        heartbeatExecutor = null;

        if (executor != null) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    log.warn("Sentinel leadership heartbeat did not finish its pass within {}s; interrupted",
                            SHUTDOWN_WAIT_SECONDS);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log.warn("Failed to stop the Sentinel leadership heartbeat cleanly", t);
            }
        }

        standDown();
    }

    /**
     * Releases the lease so a peer picks the work up within one heartbeat
     * rather than waiting out the full expiry — the difference between a
     * rolling restart costing seconds of monitoring and costing minutes.
     *
     * <p>Clears the local claim before the delete, so no straggling caller can
     * read {@code true} from {@link #isLeader()} between the two. The delete is
     * conditional on {@code node_id}, so a node that had already lost the lease
     * cannot take it away from whoever holds it now — that case simply reports
     * nothing to release.</p>
     */
    private static void standDown() {
        if (leadershipValidUntil == null) {
            return;
        }
        leadershipValidUntil = null;

        try {
            if (NodeLeaseRepository.deleteNodeLease(LEASE_NAME, NODE_ID)) {
                log.info("Sentinel node {} stood down and released the '{}' leader lease; a peer can take "
                                + "over within {}s instead of waiting out the {}s expiry",
                        NODE_ID, LEASE_NAME, HEARTBEAT_SECONDS, LEASE_SECONDS);
            } else {
                log.info("Sentinel node {} stood down; the '{}' leader lease was no longer held here, "
                        + "so there was nothing to release", NODE_ID, LEASE_NAME);
            }
        } catch (Throwable t) {
            log.warn("Sentinel node {} could not release the '{}' leader lease while standing down; a peer "
                    + "takes over when it expires, within {}s", NODE_ID, LEASE_NAME, LEASE_SECONDS, t);
        }
    }

    /**
     * One heartbeat pass: renew the lease if this node holds it, otherwise try
     * to take it.
     *
     * <p>A failed renewal is not treated as an error. It means another node
     * holds the lease now — this node stalled past its expiry and was taken
     * over — so it stands down and falls through to the acquisition path in the
     * same pass. That fall-through also covers the recovery case where the row
     * was deleted out from under a live leader: the renewal matches nothing and
     * the insert immediately re-establishes it.</p>
     *
     * <p>Any failure to reach the database deliberately leaves
     * {@link #leadershipValidUntil} untouched rather than standing down. A
     * transient blip must not trigger a failover, and it cannot cause a split
     * either: the claim is already time-bounded, so if the outage outlasts the
     * lease this node stops leading at almost exactly the moment a peer becomes
     * able to take over.</p>
     *
     * <p>Both {@link #standingDown} checks are deliberate: the first turns a
     * pass that was already queued when shutdown began into a no-op, the second
     * stops a pass that was mid-renewal from re-acquiring the lease
     * {@link #standDown()} has just released.</p>
     */
    private static void heartbeat() {
        if (standingDown) {
            return;
        }

        Instant now = Instant.now();
        Instant expires = now.plusSeconds(LEASE_SECONDS);
        Instant previousValidUntil = leadershipValidUntil;

        try {
            if (previousValidUntil != null) {
                if (NodeLeaseRepository.renewNodeLease(LEASE_NAME, NODE_ID, expires)) {
                    if (previousValidUntil.isBefore(now)) {
                        log.warn("Sentinel node {} renewed the '{}' leader lease after its local validity had "
                                        + "already lapsed at {}; the background jobs skipped their ticks in "
                                        + "that gap. A heartbeat that late means a stalled database or a long "
                                        + "GC pause on this node.",
                                NODE_ID, LEASE_NAME, previousValidUntil);
                    }
                    leadershipValidUntil = expires.minusSeconds(LEASE_SAFETY_MARGIN_SECONDS);
                    return;
                }

                leadershipValidUntil = null;
                log.info("Sentinel node {} lost the '{}' leader lease; its collector, evaluator, rollup and "
                        + "retention prune stand down until it wins the lease back", NODE_ID, LEASE_NAME);
            }

            if (standingDown) {
                return;
            }
            acquire(now, expires);
        } catch (Throwable t) {
            log.warn("Sentinel node {} could not reach the '{}' leader lease; retrying in {}s. Leadership is "
                            + "not surrendered here — it simply lapses when the lease this node last wrote "
                            + "expires.", NODE_ID, LEASE_NAME, HEARTBEAT_SECONDS, t);
        }
    }

    /**
     * Tries to take the lease when this node does not hold it.
     *
     * <p>The read that opens this method chooses which conditional write to
     * attempt; it never decides the outcome. {@link NodeLeaseRepository} warns
     * against read-then-act, and rightly so — but every branch below still
     * carries its precondition into the database (a primary key for the insert,
     * {@code expires_time <} now for the takeover, {@code node_id} for the
     * renewal), so a read that is already stale by the time it is acted on
     * costs a wasted round trip and nothing more. It is the row count, not the
     * read, that grants leadership.</p>
     *
     * <p>Preferring the read to a speculative insert also keeps the steady
     * state quiet: a follower does exactly one SELECT per heartbeat, instead of
     * provoking a key violation — and a vendor error-log entry — every
     * {@value #HEARTBEAT_SECONDS} seconds for as long as it runs.</p>
     *
     * @param now     this pass's clock reading, also the takeover's expiry comparison point
     * @param expires the expiry to write if this node wins
     */
    private static void acquire(Instant now, Instant expires) {
        NodeLease current = NodeLeaseRepository.getNodeLease(LEASE_NAME);

        if (current == null) {
            NodeLease claim = new NodeLease();
            claim.setLeaseName(LEASE_NAME);
            claim.setNodeId(NODE_ID);
            claim.setAcquiredTime(now);
            claim.setExpiresTime(expires);
            if (NodeLeaseRepository.insertNodeLease(claim)) {
                becomeLeader(expires, null);
            }
            return;
        }

        if (NODE_ID.equals(current.getNodeId())) {
            // This node's own lease, left behind by an unclean stop — the node
            // id is stable across restarts, so nobody else can be holding it.
            // Renewing rather than waiting out the expiry is what makes a
            // single-node restart resume monitoring immediately.
            if (NodeLeaseRepository.renewNodeLease(LEASE_NAME, NODE_ID, expires)) {
                becomeLeader(expires, NODE_ID);
            }
            return;
        }

        Instant currentExpiry = current.getExpiresTime();
        if (currentExpiry != null && currentExpiry.isAfter(now)) {
            log.debug("Sentinel node {} is a follower; node {} holds the '{}' leader lease until {}",
                    NODE_ID, current.getNodeId(), LEASE_NAME, currentExpiry);
            return;
        }

        if (NodeLeaseRepository.stealExpiredNodeLease(LEASE_NAME, NODE_ID, now, expires, now)) {
            becomeLeader(expires, current.getNodeId());
        }
    }

    /**
     * Records a won lease and logs the transition. At INFO because "which node
     * is doing the work" is the first question asked during an HA incident, and
     * it must be answerable from the log of a node that has since been
     * restarted.
     *
     * @param expires        the expiry just written to the row
     * @param previousHolder the node the lease was taken from, this node's own
     *                       id when reclaiming after a restart, or {@code null}
     *                       when the lease had never been held
     */
    private static void becomeLeader(Instant expires, String previousHolder) {
        leadershipValidUntil = expires.minusSeconds(LEASE_SAFETY_MARGIN_SECONDS);

        if (previousHolder == null) {
            log.info("Sentinel node {} acquired the previously unheld '{}' leader lease; this node now runs "
                    + "the collector, evaluator, rollup and retention prune", NODE_ID, LEASE_NAME);
        } else if (NODE_ID.equals(previousHolder)) {
            log.info("Sentinel node {} reclaimed the '{}' leader lease it left behind at its last stop; this "
                    + "node now runs the collector, evaluator, rollup and retention prune",
                    NODE_ID, LEASE_NAME);
        } else {
            log.info("Sentinel node {} took the '{}' leader lease over from node {}, whose lease had expired; "
                            + "this node now runs the collector, evaluator, rollup and retention prune. Its "
                            + "first collector tick records zero deltas, since a non-leader keeps no counter "
                            + "baseline.", NODE_ID, LEASE_NAME, previousHolder);
        }
    }

    /**
     * Derives this node's identity, preferring the engine's own server id.
     *
     * <p>The server id is the right answer wherever it is available: the engine
     * generates it once per installation and keeps it across restarts, so it is
     * both stable and genuinely per-node — two nodes of a cluster have their
     * own, even when they share a host. Sentinel already uses it to attribute
     * audit events ({@code SentinelAuditLog}), so a lease row and a server
     * event name the same node with the same string, which is what makes the
     * two logs correlatable during an incident.</p>
     *
     * <p>The fallback is hostname plus a random suffix. The suffix is the
     * load-bearing half: a hostname alone cannot tell two engine JVMs on one
     * host apart, and two nodes sharing a node id would each happily renew the
     * other's lease and both collect. Being random it changes on every restart,
     * which costs only the reclaim path above — the old lease is then waited
     * out rather than renewed.</p>
     *
     * <p>Resolved once into a {@code static final}: it is read on the class's
     * first use, which is the plugin start that runs well after the engine's
     * controllers are up.</p>
     */
    private static String resolveNodeId() {
        try {
            String serverId = ConfigurationController.getInstance().getServerId();
            if (serverId != null && !serverId.isBlank()) {
                return truncate(serverId.trim());
            }
        } catch (Throwable t) {
            log.debug("Engine server id unavailable; deriving the Sentinel node id from the host instead", t);
        }

        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Throwable t) {
            host = "unknown-host";
        }
        String nodeId = truncate(host + "-" + UUID.randomUUID().toString().substring(0, 8));

        log.warn("The engine reported no server id, so Sentinel identifies this node as '{}'. The random "
                + "suffix keeps it distinct from any other engine JVM on this host, but it changes on "
                + "every restart.", nodeId);
        return nodeId;
    }

    /** Trims a derived id to what {@code sentinel_node_lease.node_id} accepts. */
    private static String truncate(String value) {
        return value.length() <= NODE_ID_MAX_LENGTH ? value : value.substring(0, NODE_ID_MAX_LENGTH);
    }
}
