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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * minDurationSeconds, rollup}}. Defaults: {@code alertOnStates =
 * ["DISCONNECTED"]} (the one state that is unambiguously bad for every
 * connector type), {@code minDurationSeconds = 0} — duration-based debouncing
 * is opt-in because the monitor's {@code minConsecutiveBreaches} hysteresis
 * already provides tick-level damping — and {@code rollup = "CONNECTOR"},
 * which is the historical behavior, so monitors saved before rollup existed
 * are unaffected.</p>
 *
 * <p>Unlike the other evaluators this one reads no database history: its
 * source of truth is {@link CollectorState}'s live in-memory connector-state
 * map, maintained by the plugin's own {@code CONNECTION_STATUS} event
 * listener (the engine's {@code ConnectionStatusLogController} state map NPEs
 * on non-TCP connectors, so Sentinel deliberately keeps its own — see
 * architecture correction #2).</p>
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
 *   <li><b>INSUFFICIENT_DATA</b> if every connector is INSUFFICIENT_DATA,
 *       including the no-state-observed case below. "We cannot know" never
 *       aggregates up into "we are fine".</li>
 *   <li><b>OK</b> otherwise, which means every connector that could be judged
 *       is healthy. The rolled-up problem therefore clears only once every
 *       connector has recovered.</li>
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
 * no connector has emitted a state event yet), there is nothing to judge: a
 * single {@code INSUFFICIENT_DATA} outcome is returned — under the source
 * connector's metadata id (0) per connector, or under {@code null} when
 * rolled up — so the trigger surfaces as "not evaluated yet" rather than
 * silently vanishing or falsely reporting OK.</p>
 *
 * <p>Value JSON shape per connector: {@code {"state", "sinceIso",
 * "durationSeconds"}}. Rolled up to the channel: {@code {"rollup",
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

        List<CollectorState.ConnectorState> liveStates =
                CollectorState.getInstance().listConnectorStates(channelId);

        if (liveStates.isEmpty()) {
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

        List<ConnectorEvaluation> evaluations = new ArrayList<>(liveStates.size());
        // Built only when rolling up: it is the diagnostic detail that
        // survives the collapse, so there is nothing to accumulate when every
        // connector keeps its own trigger and its own value JSON.
        ArrayNode connectors = rollUpToChannel ? Json.mapper().createArrayNode() : null;

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

            EvaluationOutcome outcome;
            if (alertOnStates.contains(stateName) && durationSeconds >= minDurationSeconds) {
                outcome = EvaluationOutcome.breach(valueJson,
                        String.format("Connector %d has been %s for %ds",
                                live.metadataId, stateName, durationSeconds));
            } else {
                outcome = EvaluationOutcome.ok(valueJson);
            }
            evaluations.add(new ConnectorEvaluation(live.metadataId, outcome));

            if (connectors != null) {
                // valueJson is already serialized above, so enriching the node
                // now adds the connector's identity and verdict to the rollup
                // array only — which is precisely what the collapsed outcome
                // would otherwise lose.
                node.put("metadataId", live.metadataId);
                node.put("result", outcome.getResult().name());
                connectors.add(node);
            }
        }

        if (!rollUpToChannel) {
            return evaluations;
        }
        return List.of(rollUp(evaluations, connectors));
    }

    /**
     * Aggregates one channel's per-connector evaluations into the single
     * channel-level evaluation that {@code CHANNEL} rollup emits.
     *
     * <p>The verdict is any-breach-wins, all-insufficient-stays-insufficient,
     * OK otherwise — see the class Javadoc for why. The
     * INSUFFICIENT_DATA branch is unreachable from today's per-connector loop
     * (which only ever produces BREACH or OK; the only INSUFFICIENT_DATA path
     * short-circuits before this method), but the rule is written out rather
     * than assumed so the aggregation stays total if a connector-level
     * "cannot judge" verdict is ever introduced. Silently folding an unknown
     * into OK there would resolve a live problem on no evidence, which is the
     * one thing the three-way verdict exists to prevent.</p>
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

        boolean allInsufficient = evaluations.stream()
                .allMatch(e -> e.outcome.getResult() == EvaluationOutcome.Result.INSUFFICIENT_DATA);
        return new ConnectorEvaluation(null, allInsufficient
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
