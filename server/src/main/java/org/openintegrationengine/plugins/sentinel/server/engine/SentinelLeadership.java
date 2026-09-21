/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.donkey.server.channel.Channel;

import org.openintegrationengine.plugins.sentinel.server.db.NodeLeaseRepository;
import org.openintegrationengine.plugins.sentinel.server.db.LeaseFence;
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
 * All expiry values and takeover comparisons use the database clock, so
 * skew between engine nodes cannot create overlapping claims. The cost of the
 * generosity is failover latency: after an <em>unclean</em> stop a peer takes
 * over between {@value #LEASE_SECONDS}s (expiry) and
 * {@value #LEASE_SECONDS}s + {@value #HEARTBEAT_SECONDS}s (expiry plus its
 * next heartbeat) later. After a clean {@link #stopHeartbeat()} the lease is
 * expired and fenced forward, and a peer takes over within one heartbeat.</p>
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

    /** Namespace for per-node liveness leases consumed by connector-state union evaluation. */
    private static final String PRESENCE_LEASE_PREFIX = "sentinel-presence-";

    /** Lease duration; see class Javadoc for why 90 and not less. */
    private static final int LEASE_SECONDS = 90;

    /** Renewal interval — one third of the lease, so two misses are survivable. */
    private static final int HEARTBEAT_SECONDS = 30;

    /**
     * How long before the written expiry this node stops trusting its cached
     * claim. Expiry itself is decided solely by the database clock; this
     * margin covers scheduling and timestamp precision while the monotonic
     * deadline also charges all time spent reading and writing the claim.
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

    /** Stable bounded lease key for this node's liveness row. */
    private static final String PRESENCE_LEASE_NAME = presenceLeaseName(NODE_ID);

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
     * Monotonic deadline after which this node stops trusting its own
     * leadership. Zero means it holds no local claim. Derived from the same
     * duration the database adds to its own clock, less
     * {@link #LEASE_SAFETY_MARGIN_SECONDS}.
     *
     * <p>Storing a monotonic deadline rather than a boolean is what keeps a cached answer
     * honest. {@link NodeLeaseRepository} warns that a node which stalls long
     * enough loses its lease without being told; because leadership here is
     * time-bounded by the very expiry this node last wrote, a heartbeat that
     * stops running expires the local claim on the same schedule a peer becomes
     * able to steal it, with no database round trip on the job path.</p>
     */
    private static volatile long leadershipValidUntilNanos;

    /** Current fencing epoch, or null while this node holds no managed claim. */
    private static volatile Long leadershipEpoch;

    /**
     * Advances on every start and stop. A heartbeat captures its generation,
     * so a JDBC call that ignores interruption cannot mutate a later
     * lifecycle's cached claim when it eventually returns.
     */
    private static volatile long lifecycleGeneration;

    /** The heartbeat thread, or {@code null} whenever the plugin is stopped. */
    private static volatile ScheduledExecutorService heartbeatExecutor;

    /**
     * Raised for the duration of {@link #stopHeartbeat()} so no heartbeat pass
     * can claim the lease while this node is on its way out.
     *
     * <p>The pass that matters is the straggler: one already inside a database
     * call when shutdown began, which outlives the short wait for the heartbeat
     * thread to finish. Without this flag its renewal would find the row
     * {@link #standDown()} had just expired, read that as "lost the lease", and
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
        return captureFence() != null;
    }

    /**
     * Returns this JVM's stable Sentinel node identity. Connector-state rows,
     * dashboard diagnostics and Prometheus labels all use this exact value so
     * operators can correlate them with leadership log messages.
     */
    public static String nodeId() {
        return NODE_ID;
    }

    /** Captures the exact lease epoch a job must carry through its writes. */
    public static LeaseFence captureFence() {
        if (!leadershipEngaged) {
            return LeaseFence.unmanaged();
        }
        Long epoch = leadershipEpoch;
        return epoch != null && leadershipValidUntilNanos - System.nanoTime() > 0
                ? new LeaseFence(LEASE_NAME, NODE_ID, epoch)
                : null;
    }

    /** Cheap local re-check used between independent work units. */
    public static boolean holdsFence(LeaseFence fence) {
        if (fence == null) {
            return false;
        }
        if (!fence.isManaged()) {
            return !leadershipEngaged;
        }
        Long current = leadershipEpoch;
        return current != null && current.equals(fence.epoch())
                && leadershipValidUntilNanos - System.nanoTime() > 0;
    }

    /** Re-checks the captured epoch and expiry against the database clock. */
    public static boolean recheckFence(LeaseFence fence) {
        return holdsFence(fence) && NodeLeaseRepository.isFenceCurrent(fence);
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
        long generation = ++lifecycleGeneration;

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sentinel-leadership");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor = executor;

        heartbeat(generation);

        executor.scheduleAtFixedRate(() -> heartbeat(generation),
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the heartbeat and stands this node down. Called from
     * {@code SentinelServicePlugin.stop()} after the scheduler has shut down,
     * so no job tick can be in flight when leadership is surrendered.
     *
     * <p>The heartbeat thread is stopped first and the lease released second,
     * so a pass already in flight cannot renew the row a moment after it was
     * released and strand leadership on a node that is shutting down.</p>
     *
     * <p>Never throws: {@code stop()} runs during server shutdown or redeploy,
     * where an exception could disrupt the rest of the extension teardown, and
     * a lease that could not be released simply expires instead.</p>
     */
    public static synchronized void stopHeartbeat() {
        standingDown = true;
        lifecycleGeneration++;

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
        releaseNodePresence();
    }

    /**
     * Releases the lease so a peer picks the work up within one heartbeat
     * rather than waiting out the full expiry — the difference between a
     * rolling restart costing seconds of monitoring and costing minutes.
     *
     * <p>Clears the local claim before the release, so no straggling caller can
     * read {@code true} from {@link #isLeader()} between the two. Release
     * atomically expires the row and increments its epoch; retaining the row
     * prevents a later insert from reusing epoch one. It is conditional on both
     * holder and epoch, so a stale node cannot disturb the current claim.</p>
     */
    private static void standDown() {
        Long epoch = leadershipEpoch;
        if (epoch == null) {
            return;
        }
        clearLocalClaim();

        try {
            if (NodeLeaseRepository.releaseNodeLease(LEASE_NAME, NODE_ID, epoch)) {
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

    /** Expires this node's union-membership row on an orderly stop. */
    private static void releaseNodePresence() {
        try {
            NodeLease presence = NodeLeaseRepository.getNodeLease(PRESENCE_LEASE_NAME);
            if (presence != null && NODE_ID.equals(presence.getNodeId())
                    && presence.getLeaseEpoch() != null) {
                NodeLeaseRepository.releaseNodeLease(
                        PRESENCE_LEASE_NAME, NODE_ID, presence.getLeaseEpoch());
            }
        } catch (Throwable t) {
            log.warn("Sentinel node {} could not release its connector-state presence; it expires within {}s",
                    NODE_ID, LEASE_SECONDS, t);
        }
    }

    /**
     * One heartbeat pass: renew the lease if this node holds it, otherwise try
     * to take it.
     *
     * <p>A failed renewal is not treated as an error. It means another node
     * holds the lease now — this node stalled past its expiry and was taken
     * over — so it stands down and falls through to the acquisition path in the
     * same pass. That fall-through also covers a lease row removed manually:
     * the renewal matches nothing and the insert immediately re-establishes it.</p>
     *
     * <p>Any failure to reach the database deliberately leaves
     * the cached monotonic deadline untouched rather than standing down. A
     * transient blip must not trigger a failover, and it cannot cause a split
     * either: the claim is already time-bounded, so if the outage outlasts the
     * lease this node stops leading at almost exactly the moment a peer becomes
     * able to take over.</p>
     *
     * <p>Generation checks surround every blocking call and are repeated
     * atomically with each local state mutation. They turn queued passes into
     * no-ops and prevent a pass from an earlier start/stop lifecycle from
     * clearing or replacing the restarted heartbeat's claim.</p>
     */
    static void heartbeat(long generation) {
        if (!isCurrentGeneration(generation)) {
            return;
        }

        Long previousEpoch = leadershipEpoch;

        try {
            long databaseClockReadStarted = System.nanoTime();
            Instant databaseNow = NodeLeaseRepository.getDatabaseTime();
            if (!isCurrentGeneration(generation)) {
                return;
            }
            heartbeatNodePresence(databaseNow, generation);
            if (!isCurrentGeneration(generation)) {
                return;
            }
            if (previousEpoch != null) {
                boolean locallyLapsed = leadershipValidUntilNanos - System.nanoTime() <= 0;
                boolean renewed = NodeLeaseRepository.renewNodeLease(
                        LEASE_NAME, NODE_ID, previousEpoch, LEASE_SECONDS);
                if (!isCurrentGeneration(generation)) {
                    return;
                }
                if (renewed) {
                    if (locallyLapsed) {
                        log.warn("Sentinel node {} renewed the '{}' leader lease after its local validity had "
                                        + "already lapsed; the background jobs skipped their ticks in "
                                        + "that gap. A heartbeat that late means a stalled database or a long "
                                        + "GC pause on this node.",
                                NODE_ID, LEASE_NAME);
                    }
                    if (!cacheClaimIfCurrent(previousEpoch, databaseClockReadStarted, generation)) {
                        return;
                    }
                    return;
                }

                if (!clearClaimIfCurrent(previousEpoch, generation)) {
                    return;
                }
                log.info("Sentinel node {} lost the '{}' leader lease; its collector, evaluator, rollup and "
                        + "retention prune stand down until it wins the lease back", NODE_ID, LEASE_NAME);
            }

            if (!isCurrentGeneration(generation)) {
                return;
            }
            acquire(databaseNow, databaseClockReadStarted, generation);
        } catch (Throwable t) {
            log.warn("Sentinel node {} could not reach the '{}' leader lease; retrying in {}s. Leadership is "
                            + "not surrendered here — it simply lapses when the lease this node last wrote "
                            + "expires.", NODE_ID, LEASE_NAME, HEARTBEAT_SECONDS, t);
        }
    }

    /**
     * Maintains a separate database-clock lease for this node's membership in
     * the connector-state union. Unlike the exclusive leader row, every node
     * owns one presence row. A clean stop expires it immediately; a crash
     * removes the node from evaluation when the ordinary lease duration lapses.
     */
    private static void heartbeatNodePresence(Instant databaseNow, long generation) {
        EngineController engine = ControllerFactory.getFactory().createEngineController();
        Set<String> deployed = engine.getDeployedIds();
        if (deployed == null) {
            throw new IllegalStateException("Local deployment inventory is unavailable");
        }
        deployed = Set.copyOf(deployed);
        Map<String, NodeLeaseRepository.ChannelDeployment> inventory = new HashMap<>();
        for (String channelId : deployed) {
            Channel channel = engine.getDeployedChannel(channelId);
            if (channel == null || channel.getMetaDataIds() == null || channel.getDeployDate() == null) {
                throw new IllegalStateException("Runtime connector inventory is unavailable for " + channelId);
            }
            Set<Integer> metadataIds = Set.copyOf(channel.getMetaDataIds());
            if (metadataIds.isEmpty()) {
                throw new IllegalStateException("Deployed channel has no runtime connector inventory: " + channelId);
            }
            inventory.put(channelId, new NodeLeaseRepository.ChannelDeployment(metadataIds, channel.getDeployDate().toInstant()));
        }
        if (!deployed.equals(engine.getDeployedIds())) {
            throw new IllegalStateException("Deployment inventory changed while reading connector identities");
        }
        if (!isCurrentGeneration(generation)) {
            return;
        }
        if (NodeLeaseRepository.refreshNodePresence(PRESENCE_LEASE_NAME, NODE_ID, LEASE_SECONDS,
                inventory, () -> isCurrentGeneration(generation))) {
            releasePresenceWrittenByStalePass(generation);
        }
    }

    /**
     * Cleans up a presence write that completed after this lifecycle stopped.
     * The class monitor serializes the decision with a possible restart: if a
     * newer lifecycle already began, it deliberately adopts the same stable
     * presence row; otherwise the stale pass expires it before returning.
     */
    private static void releasePresenceWrittenByStalePass(long generation) {
        if (isCurrentGeneration(generation)) {
            return;
        }
        synchronized (SentinelLeadership.class) {
            if (!isCurrentGeneration(generation) && standingDown) {
                releaseNodePresence();
            }
        }
    }

    /**
     * Tries to take the lease when this node does not hold it.
     *
     * <p>The read that opens this method chooses which conditional write to
     * attempt; it never decides the outcome. {@link NodeLeaseRepository} warns
     * against read-then-act, and rightly so — but every branch below still
     * carries its precondition into the database (a primary key for the insert,
     * database-clock expiry plus the observed epoch for the takeover, and
     * holder plus epoch for renewal), so a read that is already stale when acted on
     * costs a wasted round trip and nothing more. It is the row count, not the
     * read, that grants leadership.</p>
     *
     * <p>Preferring the read to a speculative insert also keeps the steady
     * state quiet: a follower does exactly one SELECT per heartbeat, instead of
     * provoking a key violation — and a vendor error-log entry — every
     * {@value #HEARTBEAT_SECONDS} seconds for as long as it runs.</p>
     *
     * @param now     this pass's database-clock snapshot, used only to avoid
     *                speculative takeover attempts against a visibly live row
     * @param generation lifecycle generation this heartbeat belongs to
     */
    private static void acquire(Instant now, long databaseClockReadStarted, long generation) {
        NodeLease current = NodeLeaseRepository.getNodeLease(LEASE_NAME);
        if (!isCurrentGeneration(generation)) {
            return;
        }

        if (current == null) {
            NodeLease claim = new NodeLease();
            claim.setLeaseName(LEASE_NAME);
            claim.setNodeId(NODE_ID);
            claim.setLeaseEpoch(1L);
            if (NodeLeaseRepository.insertNodeLease(claim, LEASE_SECONDS)) {
                becomeLeader(claim.getLeaseEpoch(), null, databaseClockReadStarted, generation);
            }
            return;
        }

        Instant currentExpiry = current.getExpiresTime();
        if (currentExpiry != null && currentExpiry.isAfter(now)) {
            log.debug("Sentinel node {} is a follower; node {} holds the '{}' leader lease until {}",
                    NODE_ID, current.getNodeId(), LEASE_NAME, currentExpiry);
            return;
        }

        Long currentEpoch = current.getLeaseEpoch();
        if (currentEpoch == null) {
            log.error("Sentinel lease '{}' has no fencing epoch; refusing an unsafe takeover", LEASE_NAME);
            return;
        }
        if (NodeLeaseRepository.stealExpiredNodeLease(
                LEASE_NAME, NODE_ID, currentEpoch, LEASE_SECONDS)) {
            becomeLeader(currentEpoch + 1L, current.getNodeId(), databaseClockReadStarted, generation);
        }
    }

    /**
     * Records a won lease and logs the transition. At INFO because "which node
     * is doing the work" is the first question asked during an HA incident, and
     * it must be answerable from the log of a node that has since been
     * restarted.
     *
     * @param epoch          the exact epoch just written
     * @param previousHolder the node the lease was taken from, this node's own
     *                       id when reclaiming after a restart, or {@code null}
     *                       when the lease had never been held
     */
    private static void becomeLeader(long epoch, String previousHolder,
            long databaseClockReadStarted, long generation) {
        boolean releaseInstead;
        synchronized (SentinelLeadership.class) {
            releaseInstead = !isCurrentGeneration(generation);
            if (!releaseInstead) {
                cacheClaim(epoch, databaseClockReadStarted);
            }
        }

        if (releaseInstead) {
            NodeLeaseRepository.releaseNodeLease(LEASE_NAME, NODE_ID, epoch);
            log.info("Sentinel node {} released the '{}' leader lease won during shutdown", NODE_ID, LEASE_NAME);
            return;
        }

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

    private static void cacheClaim(long epoch, long databaseClockReadStarted) {
        long trustedMillis = TimeUnit.SECONDS.toMillis(
                LEASE_SECONDS - LEASE_SAFETY_MARGIN_SECONDS);
        leadershipEpoch = epoch;
        leadershipValidUntilNanos = databaseClockReadStarted
                + TimeUnit.MILLISECONDS.toNanos(trustedMillis);
    }

    private static boolean cacheClaimIfCurrent(long epoch, long databaseClockReadStarted,
            long generation) {
        synchronized (SentinelLeadership.class) {
            if (!isCurrentGeneration(generation)) {
                return false;
            }
            cacheClaim(epoch, databaseClockReadStarted);
            return true;
        }
    }

    private static boolean clearClaimIfCurrent(long expectedEpoch, long generation) {
        synchronized (SentinelLeadership.class) {
            if (!isCurrentGeneration(generation)
                    || leadershipEpoch == null || leadershipEpoch.longValue() != expectedEpoch) {
                return false;
            }
            clearLocalClaim();
            return true;
        }
    }

    private static void clearLocalClaim() {
        leadershipEpoch = null;
        leadershipValidUntilNanos = 0L;
    }

    private static boolean isCurrentGeneration(long generation) {
        return !standingDown && lifecycleGeneration == generation;
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
     * <p>The fallback is hostname plus a digest of the engine's application
     * data directory (or process working directory if even that controller
     * property is unavailable). It is stable across restarts while still
     * distinguishing two engine installations on one host. Stability is now
     * load-bearing because connector-state rows carry this identity.</p>
     *
     * <p>Resolved once into a {@code static final}: it is read on the class's
     * first use, which is the plugin start that runs well after the engine's
     * controllers are up.</p>
     */
    private static String resolveNodeId() {
        ConfigurationController controller = null;
        String stableLocation = System.getProperty("user.dir", "unknown-directory");
        try {
            controller = ConfigurationController.getInstance();
            String serverId = controller.getServerId();
            if (serverId != null && !serverId.isBlank()) {
                return truncate(serverId.trim());
            }
        } catch (Throwable t) {
            log.debug("Engine server id unavailable; deriving the Sentinel node id from the host instead", t);
        }

        if (controller != null) {
            try {
                String appData = controller.getApplicationDataDir();
                if (appData != null && !appData.isBlank()) {
                    stableLocation = appData.trim();
                }
            } catch (Throwable t) {
                log.debug("Engine application-data directory unavailable for Sentinel node identity", t);
            }
        }

        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Throwable t) {
            host = "unknown-host";
        }
        String nodeId = fallbackNodeId(host, stableLocation);

        log.warn("The engine reported no server id, so Sentinel identifies this node as '{}'. The "
                + "location digest keeps it stable across restarts and distinct from other engine "
                + "installations on this host.", nodeId);
        return nodeId;
    }

    static String fallbackNodeId(String host, String stableLocation) {
        String safeHost = host == null || host.isBlank() ? "unknown-host" : host.trim();
        String locationDigest = stableDigest(stableLocation);
        int hostLimit = NODE_ID_MAX_LENGTH - locationDigest.length() - 1;
        String boundedHost = safeHost.length() <= hostLimit
                ? safeHost : safeHost.substring(0, hostLimit);
        return boundedHost + "-" + locationDigest;
    }

    private static String presenceLeaseName(String nodeId) {
        return PRESENCE_LEASE_PREFIX + stableDigest(nodeId);
    }

    private static String stableDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Trims a derived id to what {@code sentinel_node_lease.node_id} accepts. */
    private static String truncate(String value) {
        return value.length() <= NODE_ID_MAX_LENGTH ? value : value.substring(0, NODE_ID_MAX_LENGTH);
    }
}
