/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Evaluates CONNECTION_STATUS monitors: breach when a connector has been
 * reporting a configured connection state (typically DISCONNECTED) for at
 * least a configured duration.
 *
 * <p>Config JSON: {@code {alertOnStates: [ConnectionStatusEventType names],
 * minDurationSeconds}}. Defaults: {@code alertOnStates = ["DISCONNECTED"]}
 * (the one state that is unambiguously bad for every connector type) and
 * {@code minDurationSeconds = 0} — duration-based debouncing is opt-in
 * because the monitor's {@code minConsecutiveBreaches} hysteresis already
 * provides tick-level damping.</p>
 *
 * <p>Unlike the other evaluators this one reads no database history: its
 * source of truth is {@link CollectorState}'s live in-memory connector-state
 * map, maintained by the plugin's own {@code CONNECTION_STATUS} event
 * listener (the engine's {@code ConnectionStatusLogController} state map NPEs
 * on non-TCP connectors, so Sentinel deliberately keeps its own — see
 * architecture correction #2). Because a channel has several connectors that
 * fail independently, this evaluator returns one outcome <b>per connector</b>
 * ({@code metadataId}), and the evaluator job keeps a separate trigger state
 * row for each.</p>
 *
 * <p>If the listener has never observed the channel (plugin just started and
 * no connector has emitted a state event yet), there is nothing to judge:
 * a single {@code INSUFFICIENT_DATA} outcome is returned under the source
 * connector's metadata id (0) so the trigger surfaces as "not evaluated yet"
 * rather than silently vanishing or falsely reporting OK.</p>
 *
 * <p>Value JSON shape (per connector): {@code {"state", "sinceIso",
 * "durationSeconds"}}.</p>
 */
public final class ConnectionStatusEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStatusEvaluator.class);

    private ConnectionStatusEvaluator() {
    }

    /**
     * One connector's evaluation: the connector's metadata id paired with its
     * outcome. Plain public-final fields — a short-lived in-process value
     * object handed straight to the evaluator job's state machine.
     */
    public static final class ConnectorEvaluation {

        /** The connector metadata id this outcome belongs to (source = 0). */
        public final int metadataId;
        /** The verdict for this connector. */
        public final EvaluationOutcome outcome;

        public ConnectorEvaluation(int metadataId, EvaluationOutcome outcome) {
            this.metadataId = metadataId;
            this.outcome = outcome;
        }
    }

    /**
     * Evaluates every live-observed connector of one channel against a
     * CONNECTION_STATUS monitor.
     *
     * @param monitor   the monitor whose {@code configJson} supplies the
     *                  alertable states and minimum duration
     * @param channelId the OIE channel id (a UUID string) to evaluate
     * @param now       the evaluation instant, used to measure how long each
     *                  connector has been in its current state
     * @return one evaluation per observed connector; or a single
     *         INSUFFICIENT_DATA entry (metadata id 0) when the listener has
     *         not observed this channel yet — never empty
     */
    public static List<ConnectorEvaluation> evaluate(Monitor monitor, String channelId, Instant now) {
        JsonNode config = parseConfig(monitor.getConfigJson());
        Set<String> alertOnStates = parseAlertOnStates(config);
        long minDurationSeconds = Math.max(0L, config.path("minDurationSeconds").asLong(0L));

        List<CollectorState.ConnectorState> liveStates =
                CollectorState.getInstance().listConnectorStates(channelId);

        if (liveStates.isEmpty()) {
            ObjectNode node = Json.mapper().createObjectNode();
            node.putNull("state");
            node.putNull("sinceIso");
            node.putNull("durationSeconds");
            node.put("note", "No connector state observed for this channel yet");
            return List.of(new ConnectorEvaluation(0, EvaluationOutcome.insufficientData(Json.write(node))));
        }

        List<ConnectorEvaluation> evaluations = new ArrayList<>(liveStates.size());
        for (CollectorState.ConnectorState live : liveStates) {
            // name(), never toString(): ConnectionStatusEventType.toString() is
            // overridden to capitalized display text ("Waiting For Response").
            String stateName = live.state != null ? live.state.name() : "UNKNOWN";
            long durationSeconds = live.since != null
                    ? Math.max(0L, Duration.between(live.since, now).getSeconds())
                    : 0L;

            ObjectNode node = Json.mapper().createObjectNode();
            node.put("state", stateName);
            if (live.since != null) {
                node.put("sinceIso", live.since.toString());
            } else {
                node.putNull("sinceIso");
            }
            node.put("durationSeconds", durationSeconds);
            String valueJson = Json.write(node);

            if (alertOnStates.contains(stateName) && durationSeconds >= minDurationSeconds) {
                evaluations.add(new ConnectorEvaluation(live.metadataId, EvaluationOutcome.breach(
                        valueJson, String.format("Connector %d has been %s for %ds",
                                live.metadataId, stateName, durationSeconds))));
            } else {
                evaluations.add(new ConnectorEvaluation(live.metadataId, EvaluationOutcome.ok(valueJson)));
            }
        }
        return evaluations;
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
