/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;

/**
 * In-memory state shared between the Sentinel background jobs and the REST
 * layer: the previous cumulative channel counters the collector diffs
 * against, the live connector connection states maintained by
 * {@link SentinelConnectorStatusListener}, and the last-run timestamps the
 * dashboard reports as plugin health.
 *
 * <p>This is one of the few stateful singletons in the plugin (most Sentinel
 * classes are stateless static-method utilities): the collector job, the
 * connector-status listener thread, the evaluator job and per-request REST
 * threads all need to read and write the same maps, so the state must live
 * in one shared place. Everything here is either a {@link ConcurrentHashMap}
 * or a {@code volatile} field, so no method needs synchronization — each
 * entry is written atomically and readers tolerate slightly stale values
 * (a dashboard timestamp or connector state that lags by one tick is
 * harmless).</p>
 *
 * <p>Deliberately never persisted: on a server restart the counters map
 * starts empty, which the collector treats as "first observation" and
 * records a zero delta rather than the full cumulative counter — losing one
 * tick of data is correct, because the alternative (counting every message
 * the channel ever processed as this tick's traffic) would fire every
 * volume monitor at once.</p>
 */
public final class CollectorState {

    private static final CollectorState INSTANCE = new CollectorState();

    private final ConcurrentMap<String, Counters> previousCounters = new ConcurrentHashMap<>();

    /**
     * Live connector states keyed by channel id, then connector metadata id.
     * Nested maps (rather than a composite "channelId_metadataId" key like
     * the engine's dashboardstatus plugin uses) so
     * {@link #listConnectorStates(String)} is a direct lookup instead of a
     * scan-and-split over every connector on the server.
     */
    private final ConcurrentMap<String, ConcurrentMap<Integer, ConnectorState>> connectorStates =
            new ConcurrentHashMap<>();

    /**
     * When each channel was first observed in STARTED state by the collector
     * (entries are removed the moment a channel is observed not-started).
     * Exists so the window-based evaluators can tell "the channel received
     * nothing while running" apart from "the channel was stopped for part of
     * the window": the collector deliberately keeps sampling deployed-but-
     * stopped channels (zero-delta rows, for queue-depth continuity), so
     * sample presence alone cannot prove the channel was actually running.
     */
    private final ConcurrentMap<String, Instant> startedSince = new ConcurrentHashMap<>();

    private volatile Instant lastCollectorRun;
    private volatile Instant lastEvaluatorRun;
    private volatile Instant lastConnectorEvent;

    private CollectorState() {
    }

    /**
     * Returns the single shared instance. Eagerly initialized — the class
     * holds no resources, so there is nothing to defer, and an eager final
     * field is the simplest thread-safe singleton.
     */
    public static CollectorState getInstance() {
        return INSTANCE;
    }

    /**
     * Immutable snapshot of a channel's cumulative engine counters as seen
     * at one collector tick. Immutable so a reference handed out by
     * {@link #getPreviousCounters(String)} can never be mutated by a later
     * tick racing with the reader.
     */
    public static final class Counters {

        public final long received;
        public final long sent;
        public final long error;
        public final long filtered;

        public Counters(long received, long sent, long error, long filtered) {
            this.received = received;
            this.sent = sent;
            this.error = error;
            this.filtered = filtered;
        }
    }

    /**
     * Returns the cumulative counters recorded for a channel at the previous
     * collector tick, or {@code null} if the collector has never observed
     * this channel (first tick after plugin start, redeploy of a forgotten
     * channel). A {@code null} here is the collector's signal to record a
     * zero delta instead of diffing.
     *
     * @param channelId the OIE channel id
     * @return the previous tick's counters, or {@code null} on first observation
     */
    public Counters getPreviousCounters(String channelId) {
        return previousCounters.get(channelId);
    }

    /**
     * Records the counters observed at the current collector tick so the
     * next tick can diff against them.
     *
     * @param channelId the OIE channel id
     * @param c         the counters just read from the engine
     */
    public void putCounters(String channelId, Counters c) {
        previousCounters.put(channelId, c);
    }

    /**
     * Drops all in-memory state for a channel — its previous counters, its
     * live connector states, and its started-since stamp. Called by the
     * collector's eviction sweep when a channel leaves the
     * deployed set so that a later redeploy is treated as a fresh first
     * observation (zero delta, no stale connector state): redeploying resets
     * the engine's cumulative counters, so diffing against pre-undeploy
     * values would produce a huge negative (clamped-to-zero) or bogus delta,
     * and the old connector states describe connectors that no longer exist.
     *
     * @param channelId the OIE channel id to forget
     */
    public void forgetChannel(String channelId) {
        previousCounters.remove(channelId);
        connectorStates.remove(channelId);
        startedSince.remove(channelId);
    }

    /**
     * Every channel id this state currently holds anything for — the
     * collector's eviction sweep diffs this against the deployed set to find
     * channels that left without a fresh event ever overwriting their
     * entries. A snapshot copy, so the sweep can call
     * {@link #forgetChannel(String)} while iterating.
     */
    public Set<String> knownChannelIds() {
        Set<String> ids = new HashSet<>(previousCounters.keySet());
        ids.addAll(connectorStates.keySet());
        ids.addAll(startedSince.keySet());
        return ids;
    }

    /**
     * Drops a channel's connector-state entries whose metadata id is not in
     * {@code liveMetadataIds}. Needed because connector states are only ever
     * written by events: a destination deleted in a channel edit stops
     * emitting events, so its (possibly DISCONNECTED) entry would otherwise
     * survive the redeploy forever and feed the CONNECTION_STATUS evaluator
     * a phantom connector that breaches every tick.
     *
     * @param channelId       the OIE channel id
     * @param liveMetadataIds the metadata ids that currently exist on the channel
     */
    public void retainConnectorStates(String channelId, Set<Integer> liveMetadataIds) {
        ConcurrentMap<Integer, ConnectorState> byConnector = connectorStates.get(channelId);
        if (byConnector != null) {
            byConnector.keySet().retainAll(liveMetadataIds);
        }
    }

    /**
     * Records that the collector observed the channel in STARTED state.
     * {@code putIfAbsent} so the stamp marks the beginning of the current
     * continuous started stretch, not the most recent observation.
     *
     * @param channelId  the OIE channel id
     * @param observedAt the collector tick that saw the channel started
     */
    public void markChannelStarted(String channelId, Instant observedAt) {
        startedSince.putIfAbsent(channelId, observedAt);
    }

    /**
     * Records that the collector observed the channel NOT started (paused or
     * stopped), ending any current started stretch so the next start begins
     * a fresh one.
     *
     * @param channelId the OIE channel id
     */
    public void markChannelStopped(String channelId) {
        startedSince.remove(channelId);
    }

    /**
     * Whether the channel has been observed continuously STARTED since at or
     * before {@code windowStart}. False when the channel has never been
     * observed started (plugin just started — conservatively treated as "not
     * proven running", which evaluators surface as INSUFFICIENT_DATA rather
     * than a possible false breach) or when its current started stretch
     * began inside the window.
     *
     * @param channelId   the OIE channel id
     * @param windowStart start of the evaluation window being judged
     */
    public boolean isChannelStartedThroughout(String channelId, Instant windowStart) {
        Instant since = startedSince.get(channelId);
        return since != null && !since.isAfter(windowStart);
    }

    /**
     * Stamps the completion of a collector tick. The dashboard surfaces this
     * so an operator can tell "no data" apart from "collector stopped
     * running".
     */
    public void recordCollectorRun(Instant t) {
        lastCollectorRun = t;
    }

    /** Returns when the collector last completed a tick, or {@code null} before its first run. */
    public Instant getLastCollectorRun() {
        return lastCollectorRun;
    }

    /** Stamps the completion of an evaluator tick (dashboard health indicator, like the collector stamp). */
    public void recordEvaluatorRun(Instant t) {
        lastEvaluatorRun = t;
    }

    /** Returns when the evaluator last completed a tick, or {@code null} before its first run. */
    public Instant getLastEvaluatorRun() {
        return lastEvaluatorRun;
    }

    /**
     * Stamps the arrival of a connector status transition. Unlike the two
     * job stamps this is event-driven, so a long-idle value is normal on a
     * quiet server — the dashboard shows it as "last event", not "last run".
     */
    public void recordConnectorEvent(Instant t) {
        lastConnectorEvent = t;
    }

    /** Returns when the listener last recorded a connector state transition, or {@code null} if never. */
    public Instant getLastConnectorEvent() {
        return lastConnectorEvent;
    }

    /**
     * Immutable snapshot of one connector's current connection state and
     * when it entered that state. {@code since} is what lets the
     * CONNECTION_STATUS evaluator require a minimum duration before
     * alerting, filtering out momentary disconnect/reconnect blips.
     */
    public static final class ConnectorState {

        public final String channelId;
        public final int metadataId;
        public final ConnectionStatusEventType state;
        public final Instant since;

        public ConnectorState(String channelId, int metadataId, ConnectionStatusEventType state, Instant since) {
            this.channelId = channelId;
            this.metadataId = metadataId;
            this.state = state;
            this.since = since;
        }
    }

    /**
     * Returns the live state of one connector, or {@code null} if the
     * listener has never observed it (channel not deployed since plugin
     * start, or a connector type that emits no status events).
     *
     * @param channelId  the OIE channel id
     * @param metadataId the connector metadata id (source = 0)
     */
    public ConnectorState getConnectorState(String channelId, int metadataId) {
        ConcurrentMap<Integer, ConnectorState> byConnector = connectorStates.get(channelId);
        return byConnector != null ? byConnector.get(metadataId) : null;
    }

    /**
     * Returns a snapshot list of every observed connector state for a
     * channel. A copy rather than a live view so the CONNECTION_STATUS
     * evaluator can iterate without racing the listener thread's writes.
     *
     * @param channelId the OIE channel id
     * @return the channel's connector states; empty (never {@code null}) if none observed
     */
    public List<ConnectorState> listConnectorStates(String channelId) {
        ConcurrentMap<Integer, ConnectorState> byConnector = connectorStates.get(channelId);
        if (byConnector == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(byConnector.values());
    }

    /**
     * Records a connector's new connection state. Replaces the previous
     * entry wholesale — the listener only calls this on an actual state
     * transition, so {@code since} always marks when the new state began.
     *
     * @param channelId  the OIE channel id
     * @param metadataId the connector metadata id (source = 0)
     * @param state      the state the connector just entered
     * @param since      when the transition was observed
     */
    public void putConnectorState(String channelId, int metadataId, ConnectionStatusEventType state, Instant since) {
        connectorStates
                .computeIfAbsent(channelId, id -> new ConcurrentHashMap<>())
                .put(metadataId, new ConnectorState(channelId, metadataId, state, since));
    }
}
