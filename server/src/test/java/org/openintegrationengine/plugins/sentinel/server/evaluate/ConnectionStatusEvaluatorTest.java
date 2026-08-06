/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;

import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.evaluate.ConnectionStatusEvaluator.ConnectorEvaluation;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * CONNECTION_STATUS evaluation, with particular attention to the
 * {@code rollup} mode that decides whether a channel with four dead
 * destinations pages once or four times.
 *
 * <p>This evaluator is the only one with no database history behind it: its
 * source of truth is {@link CollectorState}'s live connector-state map, which
 * the plugin's own status listener writes. That map is a process-wide
 * singleton with public seed ({@code putConnectorState}) and clear
 * ({@code forgetChannel}) methods, so these tests drive the real thing rather
 * than mocking it — the evaluator sees exactly the value object the listener
 * would have written. {@link #clearCollectorState()} removes the test
 * channel after every case because the singleton outlives the test class.</p>
 *
 * <p>The aggregation rule under test is deliberately asymmetric, and each
 * direction is here for a reason: <b>any</b> breach makes the channel breach
 * (waiting for every connector to fail would make the monitor useless), but
 * only <b>every</b> connector being unjudgeable makes the channel unjudgeable
 * (an unknown must never fold into an OK and resolve a live problem on no
 * evidence). What the collapse costs is alert fan-out and nothing else: the
 * per-connector detail array survives into the rolled-up value JSON, which is
 * asserted directly, because trading a paging problem for a troubleshooting
 * problem would be a poor bargain.</p>
 */
@DisplayName("ConnectionStatusEvaluator")
class ConnectionStatusEvaluatorTest {

    private static final String CHANNEL_ID = "1c8f0a7e-5b3d-4d21-9a6f-2e7c4b91d0aa";

    /** The evaluation instant; every seeded state is expressed as an age relative to it. */
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    @AfterEach
    void clearCollectorState() {
        CollectorState.getInstance().forgetChannel(CHANNEL_ID);
    }

    // ---------------------------------------------------------------- helpers

    /** Seeds one connector's live state as of {@code secondsAgo} seconds before {@link #NOW}. */
    private static void seed(int metadataId, ConnectionStatusEventType state, long secondsAgo) {
        CollectorState.getInstance().putConnectorState(CHANNEL_ID, metadataId, state,
                NOW.minusSeconds(secondsAgo));
    }

    /**
     * Builds a CONNECTION_STATUS monitor. Config JSON is written with single
     * quotes for legibility inline; JSON has no single-quoted string form, so
     * the substitution is unambiguous.
     */
    private static Monitor monitor(String configJson) {
        Monitor m = new Monitor();
        m.setId(11);
        m.setName("Connector health");
        m.setMonitorType(MonitorType.CONNECTION_STATUS);
        m.setSeverity(Severity.HIGH);
        m.setEnabled(true);
        m.setConfigJson(configJson != null ? configJson.replace('\'', '"') : null);
        return m;
    }

    private static List<ConnectorEvaluation> evaluate(String configJson) {
        return ConnectionStatusEvaluator.evaluate(monitor(configJson), CHANNEL_ID, NOW);
    }

    /** The single evaluation a CHANNEL-rollup config must always produce. */
    private static ConnectorEvaluation onlyEvaluation(String configJson) {
        List<ConnectorEvaluation> evaluations = evaluate(configJson);
        assertEquals(1, evaluations.size(), "rollup must collapse to exactly one evaluation");
        return evaluations.get(0);
    }

    private static ConnectorEvaluation byMetadataId(List<ConnectorEvaluation> evaluations, int metadataId) {
        return evaluations.stream()
                .filter(e -> e.metadataId != null && e.metadataId == metadataId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no evaluation for connector " + metadataId));
    }

    private static Set<Integer> metadataIds(List<ConnectorEvaluation> evaluations) {
        Set<Integer> ids = new HashSet<>();
        evaluations.forEach(e -> ids.add(e.metadataId));
        return ids;
    }

    private static JsonNode value(ConnectorEvaluation evaluation) {
        try {
            return Json.mapper().readTree(evaluation.outcome.getValueJson());
        } catch (Exception e) {
            throw new AssertionError("value JSON is not parseable: "
                    + evaluation.outcome.getValueJson(), e);
        }
    }

    private static EvaluationOutcome.Result result(ConnectorEvaluation evaluation) {
        return evaluation.outcome.getResult();
    }

    @Nested
    @DisplayName("per-connector rollup (the default)")
    class PerConnector {

        @Test
        @DisplayName("one evaluation per observed connector, each under its own metadata id")
        void oneEvaluationPerConnector() {
            // The historical shape, and the one the evaluator job turns into a
            // separate trigger state row — and therefore a separate problem —
            // per connector. A non-null metadata id is what keeps those rows
            // apart, so it is asserted for every entry rather than just the
            // count.
            seed(0, ConnectionStatusEventType.CONNECTED, 3600);
            seed(1, ConnectionStatusEventType.CONNECTED, 3600);
            seed(2, ConnectionStatusEventType.CONNECTED, 3600);

            List<ConnectorEvaluation> evaluations = evaluate("{'rollup':'CONNECTOR'}");
            assertEquals(3, evaluations.size());
            assertEquals(Set.of(0, 1, 2), metadataIds(evaluations));
            evaluations.forEach(e -> assertNotNull(e.metadataId,
                    "a per-connector evaluation must name its connector"));
        }

        @Test
        @DisplayName("a monitor with no rollup key still evaluates per connector")
        void missingRollupKeyIsPerConnector() {
            // Monitors saved before rollup existed carry no key at all, and
            // they must keep producing exactly the alerts they always did.
            seed(0, ConnectionStatusEventType.CONNECTED, 60);
            seed(1, ConnectionStatusEventType.CONNECTED, 60);
            assertEquals(2, evaluate("{'minDurationSeconds':0}").size());
        }

        @Test
        @DisplayName("only the disconnected connector breaches")
        void onlyTheFailingConnectorBreaches() {
            seed(0, ConnectionStatusEventType.CONNECTED, 3600);
            seed(1, ConnectionStatusEventType.DISCONNECTED, 300);
            seed(2, ConnectionStatusEventType.CONNECTED, 3600);

            List<ConnectorEvaluation> evaluations = evaluate("{'rollup':'CONNECTOR'}");
            assertEquals(EvaluationOutcome.Result.OK, result(byMetadataId(evaluations, 0)));
            assertEquals(EvaluationOutcome.Result.BREACH, result(byMetadataId(evaluations, 1)));
            assertEquals(EvaluationOutcome.Result.OK, result(byMetadataId(evaluations, 2)));
            assertEquals("Connector 1 has been DISCONNECTED for 300s",
                    byMetadataId(evaluations, 1).outcome.getMessage());
        }

        @Test
        @DisplayName("the per-connector value JSON carries state, since and duration")
        void perConnectorValueJsonShape() {
            seed(1, ConnectionStatusEventType.DISCONNECTED, 300);

            JsonNode node = value(byMetadataId(evaluate("{}"), 1));
            assertEquals("DISCONNECTED", node.path("state").asText());
            assertEquals("2026-01-15T11:55:00Z", node.path("sinceIso").asText());
            assertEquals(300, node.path("durationSeconds").asLong());
            // The rollup-only keys belong to the aggregate, not to a
            // per-connector reading.
            assertTrue(node.path("rollup").isMissingNode());
            assertTrue(node.path("connectors").isMissingNode());
        }

        @Test
        @DisplayName("minDurationSeconds is an inclusive floor")
        void minDurationIsInclusive() {
            // The threshold debounces disconnect/reconnect blips, so the
            // boundary is where a monitor configured "alert after 5 minutes"
            // either fires at 5 minutes or never does.
            seed(1, ConnectionStatusEventType.DISCONNECTED, 299);
            assertEquals(EvaluationOutcome.Result.OK,
                    result(byMetadataId(evaluate("{'minDurationSeconds':300}"), 1)));

            seed(1, ConnectionStatusEventType.DISCONNECTED, 300);
            assertEquals(EvaluationOutcome.Result.BREACH,
                    result(byMetadataId(evaluate("{'minDurationSeconds':300}"), 1)));
        }

        @Test
        @DisplayName("alertOnStates defaults to DISCONNECTED alone")
        void alertOnStatesDefaultsToDisconnected() {
            // DISCONNECTED is the one state that is unambiguously bad for
            // every connector type; CONNECTING is a normal transient.
            seed(0, ConnectionStatusEventType.CONNECTING, 3600);
            seed(1, ConnectionStatusEventType.DISCONNECTED, 3600);

            List<ConnectorEvaluation> evaluations = evaluate("{}");
            assertEquals(EvaluationOutcome.Result.OK, result(byMetadataId(evaluations, 0)));
            assertEquals(EvaluationOutcome.Result.BREACH, result(byMetadataId(evaluations, 1)));
        }

        @Test
        @DisplayName("configured states are matched case-insensitively and trimmed")
        void alertOnStatesAreNormalized() {
            seed(0, ConnectionStatusEventType.CONNECTING, 3600);
            List<ConnectorEvaluation> evaluations = evaluate("{'alertOnStates':[' connecting ']}");
            assertEquals(EvaluationOutcome.Result.BREACH, result(byMetadataId(evaluations, 0)));
        }

        @Test
        @DisplayName("an unknown state name in the config matches nothing rather than throwing")
        void unknownConfiguredStateMatchesNothing() {
            // Names are compared textually, never through valueOf: a typo in
            // one monitor's config must not abort the tick for every other
            // monitor on the server.
            seed(0, ConnectionStatusEventType.DISCONNECTED, 3600);
            assertEquals(EvaluationOutcome.Result.OK,
                    result(byMetadataId(evaluate("{'alertOnStates':['DISCONECTED']}"), 0)));
        }

        @Test
        @DisplayName("the state name is the enum name, not its display text")
        void stateNameIsTheEnumName() {
            // ConnectionStatusEventType.toString() is overridden to capitalized
            // display text ("Waiting For Response"), which would never match a
            // stored alertOnStates entry.
            seed(0, ConnectionStatusEventType.WAITING_FOR_RESPONSE, 3600);
            JsonNode node = value(byMetadataId(evaluate("{}"), 0));
            assertEquals("WAITING_FOR_RESPONSE", node.path("state").asText());
            assertEquals(EvaluationOutcome.Result.BREACH,
                    result(byMetadataId(evaluate("{'alertOnStates':['WAITING_FOR_RESPONSE']}"), 0)));
        }

        @Test
        @DisplayName("a connector with no since stamp reads as zero duration")
        void nullSinceIsZeroDuration() {
            CollectorState.getInstance()
                    .putConnectorState(CHANNEL_ID, 0, ConnectionStatusEventType.DISCONNECTED, null);
            JsonNode node = value(byMetadataId(evaluate("{}"), 0));
            assertTrue(node.path("sinceIso").isNull());
            assertEquals(0, node.path("durationSeconds").asLong());
            // Zero duration still breaches a zero-minimum monitor, but any
            // configured minimum holds it off — there is no evidence it has
            // been failing for long enough.
            assertEquals(EvaluationOutcome.Result.BREACH, result(byMetadataId(evaluate("{}"), 0)));
            assertEquals(EvaluationOutcome.Result.OK,
                    result(byMetadataId(evaluate("{'minDurationSeconds':60}"), 0)));
        }

        @Test
        @DisplayName("a connector with no state reads as UNKNOWN and does not breach by default")
        void nullStateIsUnknown() {
            CollectorState.getInstance().putConnectorState(CHANNEL_ID, 0, null, NOW.minusSeconds(60));
            JsonNode node = value(byMetadataId(evaluate("{}"), 0));
            assertEquals("UNKNOWN", node.path("state").asText());
            assertEquals(EvaluationOutcome.Result.OK, result(byMetadataId(evaluate("{}"), 0)));
        }

        @Test
        @DisplayName("a future since stamp clamps to zero rather than a negative duration")
        void futureSinceClampsToZero() {
            // Clock skew between the listener's stamp and the evaluator's
            // instant is possible; a negative duration in the alert message
            // would be nonsense and would also compare oddly against any
            // configured minimum.
            seed(0, ConnectionStatusEventType.DISCONNECTED, -120);
            assertEquals(0, value(byMetadataId(evaluate("{}"), 0)).path("durationSeconds").asLong());
        }
    }

    @Nested
    @DisplayName("no connector state observed yet")
    class NoStateObserved {

        @Test
        @DisplayName("per connector: one INSUFFICIENT_DATA under the source connector's id")
        void perConnectorParksUnderMetadataZero() {
            // Never empty and never OK: the trigger has to surface as "not
            // evaluated yet" rather than vanishing or claiming health the
            // plugin has no evidence for.
            List<ConnectorEvaluation> evaluations = evaluate("{}");
            assertEquals(1, evaluations.size());
            assertEquals(Integer.valueOf(0), evaluations.get(0).metadataId);
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(evaluations.get(0)));
            assertEquals("No connector state observed for this channel yet",
                    value(evaluations.get(0)).path("note").asText());
        }

        @Test
        @DisplayName("rolled up: the same verdict under a null metadata id")
        void rolledUpParksUnderNull() {
            // Under CHANNEL rollup the placeholder has to land on the same
            // channel-level trigger a real evaluation would, not open a second
            // row under connector 0 that nothing would ever revisit and
            // nothing would ever clear.
            ConnectorEvaluation evaluation = onlyEvaluation("{'rollup':'CHANNEL'}");
            assertNull(evaluation.metadataId);
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(evaluation));
        }
    }

    @Nested
    @DisplayName("CHANNEL rollup aggregation")
    class ChannelRollup {

        @Test
        @DisplayName("any breaching connector breaches the channel")
        void anyBreachBreachesTheChannel() {
            seed(0, ConnectionStatusEventType.CONNECTED, 3600);
            seed(1, ConnectionStatusEventType.CONNECTED, 3600);
            seed(2, ConnectionStatusEventType.DISCONNECTED, 600);

            ConnectorEvaluation evaluation = onlyEvaluation("{'rollup':'CHANNEL'}");
            assertNull(evaluation.metadataId, "a rolled-up evaluation names no single connector");
            assertEquals(EvaluationOutcome.Result.BREACH, result(evaluation));
            assertEquals("1 of 3 connectors in alerting state (metadata ids: 2)",
                    evaluation.outcome.getMessage());
        }

        @Test
        @DisplayName("the message names the count and every failing connector")
        void messageNamesCountAndIds() {
            // The message is the alert's subject line, so it has to be
            // actionable on a phone at 3am without opening the detail pane.
            // Connector order comes from a concurrent map, so the ids are
            // asserted by membership rather than by position.
            seed(0, ConnectionStatusEventType.CONNECTED, 3600);
            seed(1, ConnectionStatusEventType.DISCONNECTED, 600);
            seed(2, ConnectionStatusEventType.DISCONNECTED, 900);

            String message = onlyEvaluation("{'rollup':'CHANNEL'}").outcome.getMessage();
            assertTrue(message.startsWith("2 of 3 connectors in alerting state"), message);
            assertTrue(message.contains("1"), message);
            assertTrue(message.contains("2"), message);
        }

        @Test
        @DisplayName("every connector healthy rolls up to OK")
        void allHealthyIsOk() {
            seed(0, ConnectionStatusEventType.CONNECTED, 3600);
            seed(1, ConnectionStatusEventType.IDLE, 3600);

            ConnectorEvaluation evaluation = onlyEvaluation("{'rollup':'CHANNEL'}");
            assertNull(evaluation.metadataId);
            assertEquals(EvaluationOutcome.Result.OK, result(evaluation));
            assertNull(evaluation.outcome.getMessage(), "an OK outcome carries no alert message");
        }

        @Test
        @DisplayName("the rolled-up problem clears only once every connector has recovered")
        void oneStillFailingConnectorKeepsTheChannelBreaching() {
            // The corollary of any-breach-wins: recovery of one destination
            // out of three must not resolve a channel-level problem while
            // another is still down.
            seed(0, ConnectionStatusEventType.DISCONNECTED, 600);
            seed(1, ConnectionStatusEventType.DISCONNECTED, 600);
            assertEquals(EvaluationOutcome.Result.BREACH, result(onlyEvaluation("{'rollup':'CHANNEL'}")));

            seed(0, ConnectionStatusEventType.CONNECTED, 10);
            assertEquals(EvaluationOutcome.Result.BREACH, result(onlyEvaluation("{'rollup':'CHANNEL'}")));

            seed(1, ConnectionStatusEventType.CONNECTED, 5);
            assertEquals(EvaluationOutcome.Result.OK, result(onlyEvaluation("{'rollup':'CHANNEL'}")));
        }

        @Test
        @DisplayName("the per-connector detail survives the collapse")
        void perConnectorDetailSurvivesInTheValueJson() {
            // What rollup collapses is the alert fan-out; the diagnostics are
            // deliberately kept whole, so the problem detail pane can still
            // name which connector failed and for how long. Losing this array
            // would trade a paging problem for a troubleshooting one.
            seed(0, ConnectionStatusEventType.CONNECTED, 3600);
            seed(1, ConnectionStatusEventType.DISCONNECTED, 600);
            seed(2, ConnectionStatusEventType.CONNECTED, 3600);

            JsonNode node = value(onlyEvaluation("{'rollup':'CHANNEL'}"));
            assertEquals("CHANNEL", node.path("rollup").asText());
            assertEquals(3, node.path("connectorsEvaluated").asInt());
            assertEquals(1, node.path("connectorsBreaching").asInt());

            JsonNode connectors = node.path("connectors");
            assertTrue(connectors.isArray());
            assertEquals(3, connectors.size());

            JsonNode failing = null;
            for (JsonNode connector : connectors) {
                // Every entry carries its own identity and verdict — the two
                // fields the collapsed outcome would otherwise lose.
                assertTrue(connector.has("metadataId"));
                assertTrue(connector.has("result"));
                assertTrue(connector.has("state"));
                assertTrue(connector.has("durationSeconds"));
                if (connector.path("metadataId").asInt() == 1) {
                    failing = connector;
                }
            }
            assertNotNull(failing, "the failing connector is missing from the rollup detail");
            assertEquals("DISCONNECTED", failing.path("state").asText());
            assertEquals("BREACH", failing.path("result").asText());
            assertEquals(600, failing.path("durationSeconds").asLong());
            assertEquals("2026-01-15T11:50:00Z", failing.path("sinceIso").asText());
        }

        @Test
        @DisplayName("a healthy rollup still carries the full detail array")
        void detailSurvivesOnOkToo() {
            // The value JSON is persisted on the trigger state whatever the
            // verdict, so an operator opening a healthy channel-level trigger
            // sees the same per-connector readings.
            seed(0, ConnectionStatusEventType.CONNECTED, 3600);
            seed(1, ConnectionStatusEventType.CONNECTED, 3600);

            JsonNode node = value(onlyEvaluation("{'rollup':'CHANNEL'}"));
            assertEquals(2, node.path("connectorsEvaluated").asInt());
            assertEquals(0, node.path("connectorsBreaching").asInt());
            assertEquals(2, node.path("connectors").size());
        }
    }

    @Nested
    @DisplayName("rollup value parsing")
    class RollupParsing {

        @Test
        @DisplayName("the rollup name is case-insensitive")
        void rollupIsCaseInsensitive() {
            seed(0, ConnectionStatusEventType.CONNECTED, 60);
            seed(1, ConnectionStatusEventType.CONNECTED, 60);
            assertNull(onlyEvaluation("{'rollup':'channel'}").metadataId);
        }

        @Test
        @DisplayName("surrounding whitespace does not silently un-roll the monitor")
        void rollupIsTrimmed() {
            // This read has to accept exactly what MonitorService.validateRollup
            // accepts. If it were stricter, a monitor an operator saved as
            // CHANNEL — and that validation approved — would quietly fan back
            // out to one alert per connector, which is the single failure mode
            // the validation exists to prevent.
            seed(0, ConnectionStatusEventType.CONNECTED, 60);
            seed(1, ConnectionStatusEventType.CONNECTED, 60);
            assertNull(onlyEvaluation("{'rollup':'  CHANNEL '}").metadataId);
        }

        @ParameterizedTest
        @ValueSource(strings = { "CONNECTOR", "connector", "SCOPE", "BOGUS", "", "CHANNELS", "CHANNE" })
        @DisplayName("anything that is not CHANNEL degrades to per-connector fan-out")
        void unknownRollupDegradesToConnector(String rollup) {
            // Degrading the other way would be the worse bug: fan-out is
            // noisy but never silent, whereas merging alerts an operator
            // expected separately loses information with no signal that it
            // happened. SCOPE is in this list because it is reserved and
            // rejected at save time but must still behave predictably if a
            // direct database edit slips one through.
            seed(0, ConnectionStatusEventType.CONNECTED, 60);
            seed(1, ConnectionStatusEventType.CONNECTED, 60);

            List<ConnectorEvaluation> evaluations = evaluate("{'rollup':'" + rollup + "'}");
            assertEquals(2, evaluations.size());
            assertEquals(Set.of(0, 1), metadataIds(evaluations));
        }

        @Test
        @DisplayName("a non-string rollup degrades to per-connector fan-out")
        void nonStringRollupDegradesToConnector() {
            seed(0, ConnectionStatusEventType.CONNECTED, 60);
            seed(1, ConnectionStatusEventType.CONNECTED, 60);
            assertEquals(2, evaluate("{'rollup':42}").size());
            assertEquals(2, evaluate("{'rollup':null}").size());
            assertEquals(2, evaluate("{'rollup':['CHANNEL']}").size());
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "", "   ", "not json", "[]", "{", "\"CHANNEL\"" })
        @DisplayName("an unusable config falls back to every default without aborting the tick")
        void unusableConfigUsesDefaults(String configJson) {
            // MonitorService validates configs at save time, so this path is
            // defensive — but a single hand-edited row must not take the whole
            // evaluator tick down with it. Defaults are: alert on
            // DISCONNECTED, no minimum duration, per-connector rollup.
            seed(0, ConnectionStatusEventType.DISCONNECTED, 1);
            seed(1, ConnectionStatusEventType.CONNECTED, 1);

            List<ConnectorEvaluation> evaluations =
                    ConnectionStatusEvaluator.evaluate(monitor(configJson), CHANNEL_ID, NOW);
            assertEquals(2, evaluations.size());
            assertEquals(EvaluationOutcome.Result.BREACH, result(byMetadataId(evaluations, 0)));
            assertEquals(EvaluationOutcome.Result.OK, result(byMetadataId(evaluations, 1)));
        }

        @Test
        @DisplayName("a negative minDurationSeconds is floored at zero")
        void negativeMinimumIsFloored() {
            seed(0, ConnectionStatusEventType.DISCONNECTED, 0);
            assertEquals(EvaluationOutcome.Result.BREACH,
                    result(byMetadataId(evaluate("{'minDurationSeconds':-5}"), 0)));
        }
    }

    @Nested
    @DisplayName("the aggregation rule as a total function")
    class AggregationTruthTable {

        /**
         * The aggregation itself, reached reflectively.
         *
         * <p>Two of the four rows of the truth table cannot be produced
         * through {@code evaluate}: today's per-connector loop only ever emits
         * BREACH or OK, and the one INSUFFICIENT_DATA path short-circuits
         * before aggregation is reached. The rule is nonetheless written out
         * in full in the evaluator, on the stated grounds that it must stay
         * total if a connector-level "cannot judge" verdict is ever
         * introduced — and a rule nothing exercises is a rule that quietly
         * rots. Reflection is used rather than widening the method's
         * visibility because the seam here is a test concern, not an API the
         * plugin wants; the reachable rows are covered through the public
         * entry point in {@link ChannelRollup} regardless.</p>
         */
        private ConnectorEvaluation rollUp(List<ConnectorEvaluation> evaluations) {
            try {
                Method method = ConnectionStatusEvaluator.class
                        .getDeclaredMethod("rollUp", List.class, ArrayNode.class);
                method.setAccessible(true);
                return (ConnectorEvaluation) method.invoke(null, evaluations,
                        Json.mapper().createArrayNode());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("ConnectionStatusEvaluator.rollUp is no longer reachable "
                        + "with this signature; the aggregation rule is now untested", e);
            }
        }

        private ConnectorEvaluation evaluation(EvaluationOutcome outcome) {
            return new ConnectorEvaluation(7, outcome);
        }

        @Test
        @DisplayName("every connector unjudgeable stays unjudgeable")
        void allInsufficientStaysInsufficient() {
            // "We cannot know" must never aggregate up into "we are fine": an
            // OK here would resolve an open channel-level problem on no
            // evidence at all, which is precisely what the three-way verdict
            // exists to prevent.
            ConnectorEvaluation rolled = rollUp(List.of(
                    evaluation(EvaluationOutcome.insufficientData("{}")),
                    evaluation(EvaluationOutcome.insufficientData("{}"))));
            assertNull(rolled.metadataId);
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(rolled));
        }

        @Test
        @DisplayName("one judgeable healthy connector makes the channel OK")
        void mixedInsufficientAndOkIsOk() {
            // "OK otherwise" means every connector that could be judged is
            // healthy. The unjudgeable ones do not hold the verdict hostage,
            // because a channel that never regained a full set of readings
            // would otherwise never clear.
            ConnectorEvaluation rolled = rollUp(List.of(
                    evaluation(EvaluationOutcome.insufficientData("{}")),
                    evaluation(EvaluationOutcome.ok("{}"))));
            assertEquals(EvaluationOutcome.Result.OK, result(rolled));
        }

        @Test
        @DisplayName("a breach outranks an unjudgeable connector")
        void breachWinsOverInsufficient() {
            ConnectorEvaluation rolled = rollUp(List.of(
                    evaluation(EvaluationOutcome.insufficientData("{}")),
                    evaluation(EvaluationOutcome.breach("{}", "down"))));
            assertEquals(EvaluationOutcome.Result.BREACH, result(rolled));
            assertTrue(rolled.outcome.getMessage().startsWith("1 of 2 connectors in alerting state"),
                    rolled.outcome.getMessage());
        }

        @Test
        @DisplayName("the aggregate always carries a null metadata id")
        void aggregateNeverNamesAConnector() {
            // TriggerEvaluatorJob.applyOutcome keys the trigger state row on
            // the metadata id, so a rolled-up outcome that leaked a connector
            // id would silently open a per-connector problem instead of the
            // channel-level one — the exact fan-out rollup exists to remove.
            assertNull(rollUp(List.of(evaluation(EvaluationOutcome.ok("{}")))).metadataId);
            assertNull(rollUp(List.of(evaluation(EvaluationOutcome.breach("{}", "down")))).metadataId);
            assertNull(rollUp(List.of(evaluation(EvaluationOutcome.insufficientData("{}")))).metadataId);
        }
    }
}
