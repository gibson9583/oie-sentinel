/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.server.controllers.ControllerFactory;
import org.openintegrationengine.plugins.sentinel.server.db.ConnectorStatusRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Latest uncommitted state per deployed connector, retried without blocking node heartbeats. */
public final class PendingConnectorObservations {
    private static final Logger log = LoggerFactory.getLogger(PendingConnectorObservations.class);
    private record Identity(String channelId, int metadataId) { }
    /** The row's changedTime is the onset; observedThrough orders later callbacks. */
    record PendingObservation(ConnectorStatusEvent row, Instant observedThrough) { }
    private static final ConcurrentHashMap<Identity, PendingObservation> pending = new ConcurrentHashMap<>();
    private static final ReentrantLock writer = new ReentrantLock();
    private static final Object retryWorkerLock = new Object();
    private static ExecutorService retryExecutor;
    private static boolean retryInFlight;
    private PendingConnectorObservations() { }

    /**
     * Admission follows the listener's lifetime, not heartbeat readiness: a
     * newly registered listener must retain events before the first heartbeat.
     * The predicate only reads that listener's retirement flag.
     */
    static void observe(ConnectorStatusEvent row, long generation, BooleanSupplier acceptingObservations) {
        Identity key = new Identity(row.getChannelId(), row.getMetadataId());
        boolean alreadyPending = pending.containsKey(key);
        boolean[] admitted = { false };
        // The same per-identity operation also publishes committed state below.
        // No JDBC or engine inventory reads are allowed inside it: new events
        // must still be admitted while the single writer is blocked.
        pending.compute(key, (ignored, prior) -> {
            // Serialize the retirement decision with current admission and
            // publication. Checking only before compute could clobber a newer
            // listener's observation after waiting for this connector's lock.
            if (!acceptingObservations.getAsBoolean()) return prior;
            admitted[0] = true;
            CollectorState collector = CollectorState.getInstance();
            CollectorState.ConnectorState committed = collector.getConnectorState(key.channelId(), key.metadataId());
            if (committed != null && (!row.getDeploymentTime().equals(committed.deploymentTime)
                    || committed.since == null || committed.since.isBefore(row.getDeploymentTime()))) {
                committed = null;
            }
            if (prior != null && prior.row().getDeploymentTime().equals(row.getDeploymentTime())) {
                if (prior.observedThrough().isAfter(row.getChangedTime())) return prior;
                if (prior.row().getNewState().equals(row.getNewState())) {
                    // Keep the original continuous-state onset, but remember
                    // this newer observation so delayed different states lose.
                    return new PendingObservation(prior.row(), row.getChangedTime());
                }
            } else if (committed != null) {
                if (committed.observedThrough != null && committed.observedThrough.isAfter(row.getChangedTime())) {
                    return prior;
                }
                if (committed.state.name().equals(row.getNewState())) {
                    collector.putConnectorState(key.channelId(), key.metadataId(), committed.state,
                            committed.since, committed.deploymentTime, row.getChangedTime());
                    return null; // current steady state, with its ordering watermark advanced
                }
            }
            row.setPreviousState(committed != null ? committed.state.name() : null);
            return new PendingObservation(row, row.getChangedTime());
        });
        if (!admitted[0]) return;
        // New pending identities also prune while JDBC is blocked. Do this
        // after admission so steady committed callbacks do not scan every
        // other pending connector just to discover they are duplicates.
        if (!alreadyPending && pending.containsKey(key)) pruneObsolete(acceptingObservations);
        // A callback admitted before heartbeat startup may retain its intent
        // for the new heartbeat, but must not borrow that lifecycle to flush.
        flush(key, generation);
    }

    private static void pruneObsolete(BooleanSupplier acceptingObservations) {
        for (var entry : pending.entrySet()) {
            if (!acceptingObservations.getAsBoolean()) return;
            try {
                Channel current = ControllerFactory.getFactory().createEngineController()
                        .getDeployedChannel(entry.getKey().channelId());
                if (current == null || (current.getDeployDate() != null
                        && !current.getDeployDate().toInstant().equals(entry.getValue().row().getDeploymentTime()))
                        || (current.getMetaDataIds() != null
                        && !current.getMetaDataIds().contains(entry.getKey().metadataId()))) {
                    pending.computeIfPresent(entry.getKey(), (ignored, latest) ->
                            acceptingObservations.getAsBoolean() && latest == entry.getValue() ? null : latest);
                }
            } catch (Exception uncertainInventory) {
                // Keep an uncertain observation; retry after inventory recovers.
                log.debug("Cannot prune pending connector observation", uncertainInventory);
            }
        }
    }

    public static boolean isPending(String channelId, int metadataId) {
        return pending.containsKey(new Identity(channelId, metadataId));
    }

    static boolean hasPending() { return !pending.isEmpty(); }

    /** One worker and no queued retries, including while an old lifecycle's JDBC call is stuck. */
    static void requestFlush(long generation) {
        synchronized (retryWorkerLock) {
            if (!hasPending() || !SentinelLeadership.isConnectorObservationGenerationCurrent(generation)) return;
            if (retryExecutor != null && retryExecutor.isShutdown() && !retryExecutor.isTerminated()) return;
            if (retryInFlight) return;
            if (retryExecutor == null || retryExecutor.isTerminated()) {
                retryExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(), runnable -> {
                            Thread thread = new Thread(runnable, "sentinel-observation-retry");
                            thread.setDaemon(true);
                            return thread;
                        });
            }
            retryInFlight = true;
            try {
                retryExecutor.execute(() -> {
                    try {
                        flush(null, generation);
                    } finally {
                        synchronized (retryWorkerLock) { retryInFlight = false; }
                    }
                });
            } catch (RejectedExecutionException busyOrStopped) {
                // A prior task may be leaving the executor after clearing its flag.
                // The pending map retains the work for a later heartbeat.
                retryInFlight = false;
            }
        }
    }

    static void stopRetryWorker() {
        synchronized (retryWorkerLock) {
            if (retryExecutor != null) retryExecutor.shutdownNow();
            // Keep the executor until it terminates: interrupt-insensitive JDBC
            // must not accumulate one blocked worker on each plugin restart.
            // Never join here: publication acquires SentinelLeadership's monitor.
        }
    }

    /**
     * Coalescing bounds retention to one latest observation per deployed identity;
     * undeploy/redeploy removes obsolete entries on every heartbeat. Only one
     * writer may persist rows, while new events can replace a blocked write's
     * pending value. Compare-and-remove cannot discard that newer observation.
     */
    static void flush() { flush(null); }

    private static void flush(Identity only) {
        flush(only, SentinelLeadership.connectorObservationGeneration());
    }

    private static void flush(Identity only, long generation) {
        if (!SentinelLeadership.isConnectorObservationGenerationCurrent(generation)) return;
        if (pending.isEmpty() || !writer.tryLock()) return;
        try {
            PendingObservation selected = only == null ? null : pending.get(only);
            if (only != null && selected == null) return;
            var entries = only == null ? pending.entrySet()
                    : java.util.Set.of(java.util.Map.entry(only, selected));
            for (var entry : entries) {
                if (!SentinelLeadership.isConnectorObservationGenerationCurrent(generation)) return;
                Identity key = entry.getKey();
                PendingObservation observation = entry.getValue();
                ConnectorStatusEvent row = observation.row();
                try {
                    Channel channel = ControllerFactory.getFactory().createEngineController()
                            .getDeployedChannel(key.channelId());
                    if (channel == null || (channel.getDeployDate() != null
                            && !channel.getDeployDate().toInstant().equals(row.getDeploymentTime()))
                            || (channel.getMetaDataIds() != null && !channel.getMetaDataIds().contains(key.metadataId()))) {
                        pending.remove(key, observation);
                        continue;
                    }
                    if (channel.getDeployDate() == null || channel.getMetaDataIds() == null) continue;
                    ConnectorStatusRepository.insertConnectorStatusEvent(row);
                    SentinelLeadership.publishConnectorObservation(generation, () -> {
                        Channel current = ControllerFactory.getFactory().createEngineController()
                                .getDeployedChannel(key.channelId());
                        if (current == null || (current.getDeployDate() != null
                                && !current.getDeployDate().toInstant().equals(row.getDeploymentTime()))
                                || (current.getMetaDataIds() != null && !current.getMetaDataIds().contains(key.metadataId()))) {
                            pending.remove(key, observation);
                            return;
                        }
                        if (current.getDeployDate() == null || current.getMetaDataIds() == null) return;
                        pending.compute(key, (ignored, latest) -> {
                            // Same-state observations admitted during the write
                            // share its row, so no duplicate INSERT is needed.
                            boolean sameTransition = latest != null && latest.row() == row;
                            Instant observedThrough = sameTransition ? latest.observedThrough() : observation.observedThrough();
                            CollectorState.getInstance().putConnectorState(key.channelId(), key.metadataId(),
                                    ConnectionStatusEventType.valueOf(row.getNewState()), row.getChangedTime(),
                                    row.getDeploymentTime(), observedThrough);
                            CollectorState.getInstance().recordConnectorEvent(Instant.now());
                            return sameTransition ? null : latest;
                        });
                    });
                } catch (Exception failure) {
                    log.warn("Connector observation for {}/{} remains pending; heartbeat will retry",
                            key.channelId(), key.metadataId(), failure);
                }
            }
        } finally {
            writer.unlock();
        }
    }
}
