/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.Event;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.EventType;
import com.mirth.connect.server.event.EventListener;

import org.openintegrationengine.plugins.sentinel.server.db.ConnectorStatusRepository;

/**
 * Sentinel's own {@code CONNECTION_STATUS} event listener — the source of
 * truth for live connector connection states and the writer of
 * {@code sentinel_connector_status_event} transition rows.
 *
 * <p>Why a listener instead of reading the engine's dashboardstatus plugin:
 * {@code ConnectionStatusLogController.getConnectionStatesForServer()} throws
 * {@link NullPointerException} as soon as any non-TCP connector reports a
 * state, because its per-connector count maps are only populated for
 * {@code ConnectorCountEvent}s (TCP/MLLP family) while the state map is
 * populated for every connector type — a confirmed engine bug. Registering
 * our own listener on the donkey event bus (exactly what the engine's
 * {@code DashboardConnectorStatusMonitor} does) sidesteps the bug entirely
 * and also removes any dependency on the dashboardstatus extension being
 * installed and enabled.</p>
 *
 * <p>The {@link EventListener} base class starts its consumer thread in its
 * constructor and delivers events on that thread, so this class must only be
 * instantiated when the plugin actually starts, and everything it touches
 * ({@link CollectorState}, the repository) is thread-safe. It is registered
 * in {@code SentinelServicePlugin.start()} via
 * {@code EventController.addListener} and torn down by
 * {@code removeListener}, which also invokes {@code shutdown()} on the
 * worker thread.</p>
 */
public class SentinelConnectorStatusListener extends EventListener {

    private static final Logger log = LoggerFactory.getLogger(SentinelConnectorStatusListener.class);

    /**
     * Subscribes to connection-status events only — the event controller
     * routes each listener exclusively to the queues for the types it
     * declares, so declaring the minimal set keeps message-volume events off
     * this thread entirely.
     */
    @Override
    public Set<EventType> getEventTypes() {
        return EnumSet.of(EventType.CONNECTION_STATUS);
    }

    /**
     * Nothing to release: state lives in {@link CollectorState} (which
     * deliberately survives listener shutdown so a plugin restart does not
     * blank the dashboard) and the repository is stateless.
     */
    @Override
    protected void onShutdown() {
    }

    /**
     * Handles one connection-status event: ignores transient non-state
     * notifications, and on a genuine state transition writes a history row
     * and updates the live in-memory state.
     *
     * <p>The whole body is wrapped in try/catch because this runs on the
     * listener's single consumer thread — an uncaught exception would not
     * kill the thread (the base class swallows {@link Throwable}), but
     * logging here preserves the diagnostic that the base class silently
     * discards.</p>
     */
    @Override
    protected void processEvent(Event event) {
        try {
            if (!(event instanceof ConnectionStatusEvent)) {
                return;
            }
            ConnectionStatusEvent statusEvent = (ConnectionStatusEvent) event;

            ConnectionStatusEventType state = statusEvent.getState();
            // INFO/FAILURE are transient one-off messages, not connection
            // states — recording them would churn the transition history with
            // entries a "connector is DISCONNECTED for N seconds" monitor
            // must never match. They are excluded BY NAME rather than via
            // isState(): the enum declares DISCONNECTED(false) too, so an
            // isState() filter would silently drop the one transition the
            // CONNECTION_STATUS monitor type exists to catch — connectors
            // really do dispatch DISCONNECTED on connection loss, and
            // filtering it out would leave the in-memory state frozen at the
            // last healthy value forever.
            if (state == null
                    || state == ConnectionStatusEventType.INFO
                    || state == ConnectionStatusEventType.FAILURE) {
                return;
            }

            String channelId = statusEvent.getChannelId();
            // Source connectors report metadata id 0; a null (never observed
            // in practice, but the getter is a boxed Integer) is folded into
            // 0 rather than dropped so the event is still attributable.
            int metadataId = statusEvent.getMetaDataId() != null ? statusEvent.getMetaDataId() : 0;

            CollectorState collectorState = CollectorState.getInstance();
            CollectorState.ConnectorState previous = collectorState.getConnectorState(channelId, metadataId);

            // Enum comparison by == per the engine's overridden toString():
            // DeployedState/ConnectionStatusEventType render capitalized
            // human text, so name()/identity are the only safe comparisons.
            if (previous != null && previous.state == state) {
                return; // steady state (e.g. repeated IDLE) — no transition to record
            }

            Instant now = Instant.now();

            org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent row =
                    new org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent();
            row.setChannelId(channelId);
            row.setMetadataId(metadataId);
            row.setPreviousState(previous != null ? previous.state.name() : null);
            row.setNewState(state.name());
            row.setChangedTime(now);
            ConnectorStatusRepository.insertConnectorStatusEvent(row);

            collectorState.putConnectorState(channelId, metadataId, state, now);
            collectorState.recordConnectorEvent(now);
        } catch (Throwable t) {
            // Never propagate: this thread services every connector's status
            // events; one bad event (or a transient DB outage) must not stop
            // state tracking for the rest of the server.
            log.error("Failed to process connector status event", t);
        }
    }
}
