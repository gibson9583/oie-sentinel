/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.ConnectorStatusRepository;
import org.openintegrationengine.plugins.sentinel.server.db.NodeLeaseRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Evaluates CONNECTION_STATUS monitors: breach when a connector has been
 * reporting a configured connection state (typically DISCONNECTED) for at
 * least a configured duration.
 *
 * <p>Config JSON: {@code {alertOnStates: [ConnectionStatusEventType names],
 * minDurationSeconds, rollup}}. Defaults: {@code alertOnStates =
 * ["DISCONNECTED"]} (the one state that is unambiguously bad for every
 * connector type), {@code minDurationSeconds = 0} — duration-based debouncing
 * is opt-in because the monitor's {@code minConsecutiveBreaches} hysteresis
 * already provides tick-level damping — and {@code rollup = "CONNECTOR"},
 * which is the historical behavior, so monitors saved before rollup existed
 * are unaffected.</p>
 *
 * <p>The source of truth is the durable latest transition for every
 * {@code (connector, node)} pair. Every cluster node runs Sentinel's event
 * listener and writes its own identity; the elected leader evaluates their
 * union. A connector breaches when any reporting node breaches, so evaluating
 * on a node that happens not to host or observe the failing connector cannot
 * hide the failure. Rows written before node identities existed use the
 * reserved {@code legacy} identity. Rows without the current runtime
 * deployment marker remain unknown until a fresh state event arrives.</p>
 *
 * <h2>Rollup</h2>
 *
 * <p>Because a channel has several connectors that fail independently, the
 * default {@code CONNECTOR} rollup returns one outcome <b>per connector</b>
 * ({@code metadataId}) and the evaluator job keeps a separate trigger state
 * row, and therefore a separate problem and a separate notification, for
 * each. That is the right shape when destinations are independently
 * actionable, and the wrong shape when they are not: one channel losing its
 * upstream then pages once per destination.</p>
 *
 * <p>{@code CHANNEL} rollup collapses the whole channel into a single
 * evaluation carrying {@code metadataId == null}. Nothing downstream needed
 * to change for that: {@code TriggerEvaluatorJob.applyOutcome} already
 * accepts a null metadata id (it is what the activity monitors pass), so the
 * hysteresis state machine, maintenance-window suppression, dispatch, and
 * resolve paths all work unchanged on a channel-level trigger. Aggregation is
 * the only new behavior. The aggregate verdict is:</p>
 *
 * <ul>
 *   <li><b>BREACH</b> if <em>any</em> connector breaches — a channel with one
 *       dead destination is a channel with a problem, and waiting for all of
 *       them would make the monitor useless.</li>
 *   <li><b>INSUFFICIENT_DATA</b> if any connector cannot be completely
 *       judged and none breaches,
 *       including the no-state-observed case below. "We cannot know" never
 *       aggregates up into "we are fine".</li>
 *   <li><b>OK</b> only when every current connector has a healthy
 *       observation from each active deploying node. The rolled-up problem
 *       therefore clears only once every connector has recovered.</li>
 * </ul>
 *
 * <p>What gets collapsed is the alert <em>fan-out</em>, not the diagnostic
 * detail: the rolled-up value JSON keeps the full per-connector array, so the
 * problem detail pane still names exactly which connector failed and for how
 * long. Collapsing the detail as well would trade a paging problem for a
 * troubleshooting one.</p>
 *
 * <p>{@code SCOPE} (one problem for the monitor's entire scope) is reserved
 * but not implemented; {@code MonitorService} rejects it at save time. It is
 * named now because widening the key later would be a breaking rename.</p>
 *
 * <p>If the listener has never observed the channel (plugin just started and
 * no connector has emitted a state event yet), each deployed connector is
 * {@code INSUFFICIENT_DATA}. When no deployment inventory exists yet, one
 * such outcome uses source id 0, or {@code null} for channel rollup. Missing
 * observations therefore cannot silently resolve an existing incident.</p>
 *
 * <p>Value JSON shape per connector: {@code {"state", "sinceIso",
 * "durationSeconds", "nodesEvaluated", "nodesBreaching", "nodes"}}.
 * Rolled up to the channel: {@code {"rollup",
 * "connectorsEvaluated", "connectorsBreaching", "connectors": [per-connector
 * shape plus "metadataId" and "result"]}}.</p>
 */
public final class ConnectionStatusEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStatusEvaluator.class);

    /**
     * {@code rollup} value (and the default): one trigger, one problem, and
     * one notification per connector. The historical behavior, so an existing
     * monitor with no {@code rollup} key keeps evaluating exactly as before.
     */
    public static final String ROLLUP_CONNECTOR = "CONNECTOR";

    /**
     * {@code rollup} value: one channel-level trigger aggregating every
     * connector — see the class Javadoc for the aggregation rule.
     */
    public static final String ROLLUP_CHANNEL = "CHANNEL";

    /**
     * Reserved {@code rollup} value: one trigger for the monitor's whole
     * scope. Not implemented — {@link
     * org.openintegrationengine.plugins.sentinel.server.service.MonitorService}
     * rejects it at save time. Named here so the key never has to be renamed
     * when scope-level rollup ships.
     */
    public static final String ROLLUP_SCOPE = "SCOPE";

    private ConnectionStatusEvaluator() {
    }

    /**
     * One evaluation: the connector's metadata id paired with its outcome.
     * Plain public-final fields — a short-lived in-process value object
     * handed straight to the evaluator job's state machine.
     */
    public static final class ConnectorEvaluation {

        /**
         * The connector metadata id this outcome belongs to (source = 0), or
         * {@code null} when the outcome is the channel-level aggregate
         * produced by {@code CHANNEL} rollup. Boxed rather than primitive
         * precisely so that null is expressible: it is the same "no
         * particular connector" signal the activity evaluators already pass
         * through {@code applyOutcome}, which is why channel-level rollup
         * needed no changes to the state machine.
         */
        public final Integer metadataId;
        /** The verdict for this connector, or for the channel when rolled up. */
        public final EvaluationOutcome outcome;

        public ConnectorEvaluation(Integer metadataId, EvaluationOutcome outcome) {
            this.metadataId = metadataId;
            this.outcome = outcome;
        }
    }

    /**
     * Connection outcomes paired with the conclusive connector inventory used
     * to produce them. State can be insufficient while inventory is certain;
     * keeping those facts separate lets the trigger sweep retire deleted
     * connector identities without treating an unobserved current connector
     * as healthy.
     */
    public static final class ChannelEvaluation {
        public final List<ConnectorEvaluation> evaluations;
        public final Set<Integer> currentMetadataIds;

        public ChannelEvaluation(List<ConnectorEvaluation> evaluations, Set<Integer> currentMetadataIds) {
            this.evaluations = List.copyOf(evaluations);
            this.currentMetadataIds = Set.copyOf(currentMetadataIds);
        }
    }

    /**
     * Evaluates every live-observed connector of one channel against a
     * CONNECTION_STATUS monitor, then collapses the result to a single
     * channel-level evaluation when the monitor's {@code rollup} says so.
     *
     * @param monitor   the monitor whose {@code configJson} supplies the
     *                  alertable states, minimum duration and rollup mode
     * @param channelId the OIE channel id (a UUID string) to evaluate
     * @param now       the evaluation instant, used to measure how long each
     *                  connector has been in its current state
     * @return under {@code CONNECTOR} rollup, one evaluation per observed
     *         connector, or a single INSUFFICIENT_DATA entry under metadata
     *         id 0 when the listener has not observed this channel yet; under
     *         {@code CHANNEL} rollup, always exactly one evaluation with a
     *         {@code null} metadata id. Never empty.
     */
    public static List<ConnectorEvaluation> evaluate(Monitor monitor, String channelId, Instant now) {
        return evaluateChannel(monitor, channelId, now).evaluations;
    }

    /** Evaluates one channel and preserves its action-time connector inventory for departure cleanup. */
    public static ChannelEvaluation evaluateChannel(Monitor monitor, String channelId, Instant now) {
        Map<Integer, Map<String, Instant>> connectorNodes = NodeLeaseRepository.listActiveConnectorDeployments(channelId);
        if (connectorNodes.isEmpty()) {
            return new ChannelEvaluation(evaluateStates(monitor, now, List.of()), connectorNodes.keySet());
        }
        List<ConnectorStatusEvent> persisted =
                ConnectorStatusRepository.listLatestConnectorStatusEvents(channelId);
        List<ConnectorStatusEvent> current = new ArrayList<>();
        for (ConnectorStatusEvent event : persisted) {
            if (event == null || !connectorNodes.containsKey(event.getMetadataId())) {
                continue;
            }
            String nodeId = normalizedNodeId(event.getNodeId());
            Instant deployment = connectorNodes.get(event.getMetadataId()).get(nodeId);
            Instant observedAt = event.getChangedTime();
            // The listener checked the original event timestamp against the
            // deployment before writing. Older changed_time columns lose
            // sub-second precision on some vendors, so compare the precise
            // deployment marker rather than repeating that lower time bound.
            // Historical rows without a marker remain unknown after upgrade.
            if (deployment != null && deployment.equals(event.getDeploymentTime())
                    && observedAt != null && !observedAt.isAfter(now)) {
                current.add(event);
            }
        }
        // The engine supplies a wall-clock deployment timestamp, not an
        // immutable incarnation token. Matching markers reject historical
        // rows across ordinary/backward redeploys, while the listener rejects
        // origin times outside the current deployment's interval. Ambiguous
        // queued events after overlapping wall-clock rollback cannot be
        // identified perfectly without an engine-origin deployment token.
        // Presence publishes a complete deployment snapshot, so every node
        // listed here is known to host this channel. A missing observation
        // is not a healthy connector: preserve it as unknown until that node
        // reports, including while legacy history is replaced after upgrade.
        Map<Integer, Set<String>> observedNodes = new HashMap<>();
        for (ConnectorStatusEvent event : current) {
            observedNodes.computeIfAbsent(event.getMetadataId(), ignored -> new HashSet<>())
                    .add(normalizedNodeId(event.getNodeId()));
        }
        for (Map.Entry<Integer, Map<String, Instant>> connector : connectorNodes.entrySet()) {
            int metadataId = connector.getKey();
            for (String nodeId : connector.getValue().keySet()) {
                if (!observedNodes.getOrDefault(metadataId, Set.of()).contains(nodeId)) {
                    ConnectorStatusEvent missing = new ConnectorStatusEvent();
                    missing.setMetadataId(metadataId);
                    missing.setNodeId(nodeId);
                    current.add(missing);
                }
            }
        }
        return new ChannelEvaluation(evaluateStates(monitor, now, current), connectorNodes.keySet());
    }

    /** Package-visible seam for deterministic union tests; production reads through the repository. */
    static List<ConnectorEvaluation> evaluateStates(
            Monitor monitor, Instant now, List<ConnectorStatusEvent> latestEvents) {
        JsonNode config = parseConfig(monitor.getConfigJson());
        Set<String> alertOnStates = parseAlertOnStates(config);
        long minDurationSeconds = Math.max(0L, config.path("minDurationSeconds").asLong(0L));
        // Trimmed and case-insensitive, matching exactly what
        // MonitorService.validateRollup accepts — if this read were stricter
        // than the validator, a saved-and-accepted CHANNEL monitor would
        // silently evaluate per connector, which is the one failure mode that
        // validation exists to prevent. Anything else evaluates per connector,
        // including the reserved-but-unimplemented SCOPE and any value a
        // direct DB edit slipped past validation: fan-out is the noisier
        // failure but never the silent one, whereas degrading an unrecognized
        // rollup to CHANNEL would merge alerts an operator expected to
        // receive separately.
        boolean rollUpToChannel = ROLLUP_CHANNEL.equalsIgnoreCase(
                config.path("rollup").asText(ROLLUP_CONNECTOR).trim());

        Map<Integer, Map<String, ConnectorStatusEvent>> latestByConnector = latestByConnector(latestEvents);
        if (latestByConnector.isEmpty()) {
            ObjectNode node = Json.mapper().createObjectNode();
            node.putNull("state");
            node.putNull("sinceIso");
            node.putNull("durationSeconds");
            node.put("note", "No connector state observed for this channel yet");
            // Null when rolled up so this parks the same channel-level trigger
            // a real evaluation would, rather than opening a second row under
            // the source connector's id that nothing would ever revisit.
            Integer metadataId = rollUpToChannel ? null : Integer.valueOf(0);
            return List.of(new ConnectorEvaluation(metadataId,
                    EvaluationOutcome.insufficientData(Json.write(node))));
        }

        List<ConnectorEvaluation> evaluations = new ArrayList<>(latestByConnector.size());
        // Built only when rolling up: it is the diagnostic detail that
        // survives the collapse, so there is nothing to accumulate when every
        // connector keeps its own trigger and its own value JSON.
        ArrayNode connectors = rollUpToChannel ? Json.mapper().createArrayNode() : null;

        for (Map.Entry<Integer, Map<String, ConnectorStatusEvent>> connector : latestByConnector.entrySet()) {
            int metadataId = connector.getKey();
            List<ConnectorStatusEvent> active = activeNodeRows(connector.getValue());
            List<NodeReading> readings = new ArrayList<>(active.size());
            ArrayNode nodes = Json.mapper().createArrayNode();

            for (ConnectorStatusEvent event : active) {
                String stateName = event.getNewState() != null ? event.getNewState() : "UNKNOWN";
                long durationSeconds = event.getChangedTime() != null
                        ? Math.max(0L, Duration.between(event.getChangedTime(), now).getSeconds())
                        : 0L;
                boolean breaching = alertOnStates.contains(stateName)
                        && durationSeconds >= minDurationSeconds;
                NodeReading reading = new NodeReading(
                        normalizedNodeId(event.getNodeId()), stateName, event.getChangedTime(),
                        durationSeconds, breaching, event.getNewState() != null);
                readings.add(reading);

                ObjectNode node = Json.mapper().createObjectNode();
                node.put("nodeId", reading.nodeId());
                node.put("state", reading.stateName());
                putSince(node, reading.since());
                node.put("durationSeconds", reading.durationSeconds());
                node.put("result", reading.breaching() ? "BREACH" : reading.observed() ? "OK" : "INSUFFICIENT_DATA");
                nodes.add(node);
            }

            NodeReading representative = representative(readings);
            long breachingCount = readings.stream().filter(NodeReading::breaching).count();
            ObjectNode value = Json.mapper().createObjectNode();
            value.put("state", representative.stateName());
            putSince(value, representative.since());
            value.put("durationSeconds", representative.durationSeconds());
            value.put("nodesEvaluated", readings.size());
            value.put("nodesBreaching", breachingCount);
            value.set("nodes", nodes);
            String valueJson = Json.write(value);

            EvaluationOutcome outcome;
            if (breachingCount > 0) {
                String nodeIds = readings.stream().filter(NodeReading::breaching)
                        .map(NodeReading::nodeId).collect(Collectors.joining(", "));
                outcome = EvaluationOutcome.breach(valueJson,
                        String.format("Connector %d is alerting on %d of %d nodes (node ids: %s)",
                                metadataId, breachingCount, readings.size(), nodeIds));
            } else if (readings.stream().anyMatch(reading -> !reading.observed())) {
                outcome = EvaluationOutcome.insufficientData(valueJson);
            } else {
                outcome = EvaluationOutcome.ok(valueJson);
            }
            evaluations.add(new ConnectorEvaluation(metadataId, outcome));

            if (connectors != null) {
                // valueJson is already serialized above, so enriching the value
                // now adds the connector's identity and verdict to the rollup
                // array only — which is precisely what the collapsed outcome
                // would otherwise lose.
                value.put("metadataId", metadataId);
                value.put("result", outcome.getResult().name());
                connectors.add(value);
            }
        }

        if (!rollUpToChannel) {
            return evaluations;
        }
        return List.of(rollUp(evaluations, connectors));
    }

    /** One node's current reading, normalized for deterministic aggregation. */
    private record NodeReading(String nodeId, String stateName, Instant since,
            long durationSeconds, boolean breaching, boolean observed) {
    }

    /**
     * Defensively reduces arbitrary repository/test input to one newest row
     * per connector/node. The mapped query already guarantees this, but doing
     * it again keeps one duplicate row from doubling a breach count if a
     * vendor plan or future query revision regresses.
     */
    private static Map<Integer, Map<String, ConnectorStatusEvent>> latestByConnector(
            List<ConnectorStatusEvent> events) {
        Map<Integer, Map<String, ConnectorStatusEvent>> grouped = new TreeMap<>();
        if (events == null) {
            return grouped;
        }
        for (ConnectorStatusEvent event : events) {
            if (event == null) {
                continue;
            }
            String nodeId = normalizedNodeId(event.getNodeId());
            grouped.computeIfAbsent(event.getMetadataId(), ignored -> new TreeMap<>())
                    .merge(nodeId, event, ConnectionStatusEvaluator::newer);
        }
        return grouped;
    }

    private static ConnectorStatusEvent newer(ConnectorStatusEvent left, ConnectorStatusEvent right) {
        long leftId = left.getId() != null ? left.getId() : Long.MIN_VALUE;
        long rightId = right.getId() != null ? right.getId() : Long.MIN_VALUE;
        if (leftId != rightId) {
            return leftId > rightId ? left : right;
        }
        // Test/defensive input can lack generated ids; production rows never
        // do. Timestamp is only a fallback when arrival order is unavailable.
        int byTime = Comparator.nullsFirst(Instant::compareTo)
                .compare(left.getChangedTime(), right.getChangedTime());
        return byTime >= 0 ? left : right;
    }

    /** Drops upgrade-only legacy state as soon as a real node has reported this connector. */
    private static List<ConnectorStatusEvent> activeNodeRows(Map<String, ConnectorStatusEvent> byNode) {
        List<ConnectorStatusEvent> active = new ArrayList<>(byNode.values());
        if (active.size() > 1 && active.stream()
                .anyMatch(event -> event.getNewState() != null
                        && !"legacy".equals(normalizedNodeId(event.getNodeId())))) {
            active.removeIf(event -> "legacy".equals(normalizedNodeId(event.getNodeId())));
        }
        active.sort(Comparator.comparing(event -> normalizedNodeId(event.getNodeId())));
        return active;
    }

    /** Breaching nodes win; within one verdict the longest-lived reading is the summary. */
    private static NodeReading representative(List<NodeReading> readings) {
        return readings.stream().max(Comparator
                .comparing(NodeReading::breaching)
                .thenComparingLong(NodeReading::durationSeconds)
                .thenComparing(NodeReading::nodeId, Comparator.reverseOrder()))
                .orElseThrow();
    }

    private static String normalizedNodeId(String nodeId) {
        return nodeId == null || nodeId.isBlank() ? "legacy" : nodeId;
    }

    private static void putSince(ObjectNode node, Instant since) {
        if (since != null) {
            node.put("sinceIso", since.toString());
        } else {
            node.putNull("sinceIso");
        }
    }

    /**
     * Aggregates one channel's per-connector evaluations into the single
     * channel-level evaluation that {@code CHANNEL} rollup emits.
     *
     * <p>The verdict is any-breach-wins, incomplete-coverage-stays-insufficient,
     * OK only with complete healthy coverage. Missing connector observations
     * remain insufficient even when other connectors are healthy, so a
     * partial snapshot cannot resolve a live incident.</p>
     *
     * @param evaluations the per-connector evaluations; never empty
     * @param connectors  the per-connector detail array preserved verbatim in
     *                    the aggregate value JSON
     * @return the single channel-level evaluation, always with a {@code null}
     *         metadata id
     */
    private static ConnectorEvaluation rollUp(List<ConnectorEvaluation> evaluations, ArrayNode connectors) {
        List<ConnectorEvaluation> breaching = evaluations.stream()
                .filter(e -> e.outcome.getResult() == EvaluationOutcome.Result.BREACH)
                .toList();

        ObjectNode rolled = Json.mapper().createObjectNode();
        rolled.put("rollup", ROLLUP_CHANNEL);
        rolled.put("connectorsEvaluated", evaluations.size());
        rolled.put("connectorsBreaching", breaching.size());
        rolled.set("connectors", connectors);
        String valueJson = Json.write(rolled);

        if (!breaching.isEmpty()) {
            String ids = breaching.stream()
                    .map(e -> String.valueOf(e.metadataId))
                    .collect(Collectors.joining(", "));
            // The message is the alert's subject line, so it names the failing
            // connectors inline: the operator must be able to act on the page
            // without opening the detail pane to find out which one broke.
            return new ConnectorEvaluation(null, EvaluationOutcome.breach(valueJson,
                    breaching.size() + " of " + evaluations.size()
                            + " connectors in alerting state (metadata ids: " + ids + ")"));
        }

        boolean incomplete = evaluations.stream()
                .anyMatch(e -> e.outcome.getResult() == EvaluationOutcome.Result.INSUFFICIENT_DATA);
        return new ConnectorEvaluation(null, incomplete
                ? EvaluationOutcome.insufficientData(valueJson)
                : EvaluationOutcome.ok(valueJson));
    }

    /**
     * Parses {@code alertOnStates} into an upper-cased name set, defaulting
     * to {@code {"DISCONNECTED"}} when absent or empty. Names are matched
     * against {@code ConnectionStatusEventType.name()} textually rather than
     * via {@code valueOf} so an unknown name in the config degrades to
     * "matches nothing" instead of throwing mid-tick.
     */
    private static Set<String> parseAlertOnStates(JsonNode config) {
        Set<String> states = new HashSet<>();
        JsonNode array = config.path("alertOnStates");
        if (array.isArray()) {
            for (JsonNode entry : array) {
                String name = entry.asText(null);
                if (name != null && !name.isBlank()) {
                    states.add(name.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (states.isEmpty()) {
            states.add("DISCONNECTED");
        }
        return states;
    }

    /**
     * Parses the monitor's config JSON, degrading to an empty object (all
     * defaults) on null/blank/malformed input — a broken config on one
     * monitor must produce a defaulted evaluation plus a warning, never an
     * aborted tick. MonitorService validates configs at save time, so this
     * path is defensive.
     */
    private static JsonNode parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Json.mapper().createObjectNode();
        }
        try {
            return Json.mapper().readTree(configJson);
        } catch (Exception e) {
            log.warn("Unparsable CONNECTION_STATUS config '{}'; using defaults", configJson, e);
            return Json.mapper().createObjectNode();
        }
    }
}
