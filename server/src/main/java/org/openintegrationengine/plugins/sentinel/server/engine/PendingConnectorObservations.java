/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.server.controllers.ControllerFactory;
import org.openintegrationengine.plugins.sentinel.server.db.ConnectorStatusRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Latest uncommitted state per deployed connector, retried by the existing node heartbeat. */
public final class PendingConnectorObservations {
    private static final Logger log = LoggerFactory.getLogger(PendingConnectorObservations.class);
    private record Identity(String channelId, int metadataId) { }
    private static final ConcurrentHashMap<Identity, ConnectorStatusEvent> pending = new ConcurrentHashMap<>();
    private static final ReentrantLock writer = new ReentrantLock();
    private PendingConnectorObservations() { }

    static void observe(ConnectorStatusEvent row) {
        Identity key = new Identity(row.getChannelId(), row.getMetadataId());
        // Prune on new identities too: a blocked JDBC writer must not retain
        // an unbounded succession of undeployed channels until it returns.
        if (!pending.containsKey(key)) pruneObsolete();
        pending.compute(key, (ignored, prior) -> prior != null
                && prior.getDeploymentTime().equals(row.getDeploymentTime())
                && prior.getChangedTime().isAfter(row.getChangedTime()) ? prior : row);
        flush(key);
    }

    private static void pruneObsolete() {
        for (var entry : pending.entrySet()) {
            try {
                Channel current = ControllerFactory.getFactory().createEngineController()
                        .getDeployedChannel(entry.getKey().channelId());
                if (current == null || (current.getDeployDate() != null
                        && !current.getDeployDate().toInstant().equals(entry.getValue().getDeploymentTime()))
                        || (current.getMetaDataIds() != null
                        && !current.getMetaDataIds().contains(entry.getKey().metadataId()))) {
                    pending.remove(entry.getKey(), entry.getValue());
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

    /**
     * Coalescing bounds retention to one latest observation per deployed identity;
     * undeploy/redeploy removes obsolete entries on every heartbeat. Only one
     * writer may persist rows, while new events can replace a blocked write's
     * pending value. Compare-and-remove cannot discard that newer observation.
     */
    static void flush() { flush(null); }

    private static void flush(Identity only) {
        if (pending.isEmpty() || !writer.tryLock()) return;
        try {
            long generation = SentinelLeadership.connectorObservationGeneration();
            ConnectorStatusEvent selected = only == null ? null : pending.get(only);
            if (only != null && selected == null) return;
            var entries = only == null ? pending.entrySet()
                    : java.util.Set.of(java.util.Map.entry(only, selected));
            for (var entry : entries) {
                Identity key = entry.getKey();
                ConnectorStatusEvent row = entry.getValue();
                try {
                    Channel channel = ControllerFactory.getFactory().createEngineController()
                            .getDeployedChannel(key.channelId());
                    if (channel == null || (channel.getDeployDate() != null
                            && !channel.getDeployDate().toInstant().equals(row.getDeploymentTime()))
                            || (channel.getMetaDataIds() != null && !channel.getMetaDataIds().contains(key.metadataId()))) {
                        pending.remove(key, row);
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
                            pending.remove(key, row);
                            return;
                        }
                        if (current.getDeployDate() == null || current.getMetaDataIds() == null) return;
                        CollectorState.getInstance().putConnectorState(key.channelId(), key.metadataId(),
                                ConnectionStatusEventType.valueOf(row.getNewState()), row.getChangedTime(), row.getDeploymentTime());
                        CollectorState.getInstance().recordConnectorEvent(Instant.now());
                        pending.remove(key, row);
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
