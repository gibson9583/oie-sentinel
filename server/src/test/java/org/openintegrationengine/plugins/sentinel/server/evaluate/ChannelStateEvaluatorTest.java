/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

/**
 * CHANNEL_STATE evaluation — the monitor that watches a channel's own
 * deployed state, and the only type whose subject is the thing every other
 * evaluator is protected from by the started-channel gate.
 *
 * <p>Two rules carry the type and are pinned hardest here. <b>The clock lives
 * in the trigger row, not in memory</b>: each evaluation carries the previous
 * tick's {@code stateSinceIso} forward while the state is unchanged and
 * restarts it the instant the state differs, which is what makes "stopped
 * since before the last restart" — the likeliest shape of the incident this
 * monitor exists to catch — survive a restart at all. <b>Every unusable prior
 * stamp restarts the clock</b> rather than assuming age: no row, unparsable
 * JSON, a different state, a malformed or future timestamp all resolve to
 * {@code now}, so the failure mode is alerting late, never alerting on a
 * routine redeploy.</p>
 *
 * <p>The third rule is negative and just as load-bearing: this evaluator
 * never returns INSUFFICIENT_DATA. A channel's state is always knowable, so
 * "not in the alert set" and "not held long enough" are both genuine OKs —
 * which is precisely what lets an open problem resolve the moment somebody
 * starts the channel again.</p>
 */
@DisplayName("ChannelStateEvaluator")
class ChannelStateEvaluatorTest {

    private static final String CHANNEL_ID = "3f9a1c07-52be-4d18-9a6e-71c0d4b8e235";

    private static final Instant NOW = Instant.parse("2026-03-04T09:30:00Z");

    /** The config used by most cases: stopped or paused, held for 300s. */
    private static final String CONFIG = "{'alertOnStates':['STOPPED','PAUSED'],'minDurationSeconds':300}";

    private MockedStatic<TriggerStateRepository> triggerStates;

    @BeforeEach
    void setUp() {
        triggerStates = Mockito.mockStatic(TriggerStateRepository.class);
    }

    @AfterEach
    void tearDown() {
        triggerStates.close();
    }

    // ---------------------------------------------------------------- helpers

    private static Monitor monitor(String configJson) {
        Monitor m = new Monitor();
        m.setId(77);
        m.setName("Production channels must run");
        m.setMonitorType(MonitorType.CHANNEL_STATE);
        m.setSeverity(Severity.HIGH);
        m.setEnabled(true);
        m.setConfigJson(configJson != null ? configJson.replace('\'', '"') : null);
        return m;
    }

    /** Stubs the prior trigger row this monitor/channel pair would have written. */
    private void priorValue(String valueJson) {
        TriggerState state = new TriggerState();
        state.setMonitorId(77);
        state.setChannelId(CHANNEL_ID);
        state.setLastValueJson(valueJson != null ? valueJson.replace('\'', '"') : null);
        triggerStates.when(() -> TriggerStateRepository.getTriggerState(77, CHANNEL_ID, null))
                .thenReturn(state);
    }

    /** Stubs "this monitor has never evaluated this channel". */
    private void noPriorRow() {
        triggerStates.when(() -> TriggerStateRepository.getTriggerState(77, CHANNEL_ID, null))
                .thenReturn(null);
    }

    private static EvaluationOutcome evaluate(String configJson, String state) {
        return ChannelStateEvaluator.evaluate(monitor(configJson), CHANNEL_ID, state, NOW);
    }

    private static JsonNode value(EvaluationOutcome outcome) {
        assertNotNull(outcome.getValueJson(), "every outcome carries a value snapshot");
        return Json.read(outcome.getValueJson(), JsonNode.class);
    }

    /** A prior stamp for {@code state}, {@code secondsAgo} before NOW. */
    private static String held(String state, long secondsAgo) {
        return "{'state':'" + state + "','stateSinceIso':'" + NOW.minusSeconds(secondsAgo) + "'}";
    }

    // ------------------------------------------------------------------ cases

    @Nested
    @DisplayName("state matching")
    class StateMatching {

        @Test
        @DisplayName("a state outside alertOnStates is OK however long it has held")
        void startedIsOk() {
            priorValue(held("STARTED", 86400));
            EvaluationOutcome outcome = evaluate(CONFIG, "STARTED");
            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
            assertEquals("STARTED", value(outcome).path("state").asText());
        }

        @Test
        @DisplayName("a matching state held past the minimum breaches")
        void stoppedLongEnoughBreaches() {
            priorValue(held("STOPPED", 600));
            EvaluationOutcome outcome = evaluate(CONFIG, "STOPPED");
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals(600L, value(outcome).path("sustainedSeconds").asLong());
            assertTrue(outcome.getMessage().contains("STOPPED"),
                    "the message names the state so it reads alone in an email subject");
        }

        @Test
        @DisplayName("every state in the set is watched, not just the first")
        void pausedAlsoBreaches() {
            priorValue(held("PAUSED", 600));
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate(CONFIG, "PAUSED").getResult());
        }

        @Test
        @DisplayName("state names are matched case-insensitively on both sides")
        void caseInsensitive() {
            priorValue(held("STOPPED", 600));
            EvaluationOutcome outcome = ChannelStateEvaluator.evaluate(
                    monitor("{'alertOnStates':['stopped'],'minDurationSeconds':300}"),
                    CHANNEL_ID, "stopped", NOW);
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
        }

        @Test
        @DisplayName("a null state reads as UNDEPLOYED rather than NPEing mid-tick")
        void nullStateIsUndeployed() {
            priorValue(held("UNDEPLOYED", 600));
            EvaluationOutcome outcome = ChannelStateEvaluator.evaluate(
                    monitor("{'alertOnStates':['UNDEPLOYED'],'minDurationSeconds':300}"),
                    CHANNEL_ID, null, NOW);
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals("UNDEPLOYED", value(outcome).path("state").asText());
        }
    }

    @Nested
    @DisplayName("duration")
    class DurationRule {

        @Test
        @DisplayName("a matching state that has not held long enough is OK, not INSUFFICIENT_DATA")
        void tooBriefIsOk() {
            priorValue(held("STOPPED", 120));
            EvaluationOutcome outcome = evaluate(CONFIG, "STOPPED");
            // OK rather than INSUFFICIENT_DATA is what lets an open problem
            // from an earlier outage resolve on this tick.
            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
            assertEquals(120L, value(outcome).path("sustainedSeconds").asLong());
        }

        @Test
        @DisplayName("exactly the minimum breaches — the threshold is inclusive")
        void atThresholdBreaches() {
            priorValue(held("STOPPED", 300));
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate(CONFIG, "STOPPED").getResult());
        }

        @Test
        @DisplayName("a zero minimum breaches on the first matching tick")
        void zeroMinimumBreachesImmediately() {
            noPriorRow();
            EvaluationOutcome outcome = evaluate(
                    "{'alertOnStates':['STOPPED'],'minDurationSeconds':0}", "STOPPED");
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals(0L, value(outcome).path("sustainedSeconds").asLong());
        }
    }

    @Nested
    @DisplayName("the since-stamp carried in the trigger row")
    class SinceStamp {

        @Test
        @DisplayName("an unchanged state carries the previous stamp forward")
        void carriesForward() {
            priorValue(held("STOPPED", 900));
            assertEquals(NOW.minusSeconds(900).toString(),
                    value(evaluate(CONFIG, "STOPPED")).path("stateSinceIso").asText());
        }

        @Test
        @DisplayName("a changed state restarts the clock at now")
        void changedStateRestarts() {
            priorValue(held("STARTED", 86400));
            EvaluationOutcome outcome = evaluate(CONFIG, "STOPPED");
            // The channel stopped somewhere between the last tick and this one;
            // the only defensible stamp is this tick.
            assertEquals(NOW.toString(), value(outcome).path("stateSinceIso").asText());
            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
        }

        @Test
        @DisplayName("a first evaluation stamps now, so a long-stopped channel breaches late not never")
        void noPriorRowStampsNow() {
            noPriorRow();
            EvaluationOutcome outcome = evaluate(CONFIG, "STOPPED");
            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
            assertEquals(0L, value(outcome).path("sustainedSeconds").asLong());
        }

        @Test
        @DisplayName("an unparsable prior value restarts the clock rather than aborting")
        void unparsablePriorValue() {
            priorValue("not json at all");
            assertEquals(EvaluationOutcome.Result.OK, evaluate(CONFIG, "STOPPED").getResult());
        }

        @Test
        @DisplayName("a prior value with no stamp restarts the clock")
        void priorValueMissingStamp() {
            priorValue("{'state':'STOPPED'}");
            assertEquals(0L, value(evaluate(CONFIG, "STOPPED")).path("sustainedSeconds").asLong());
        }

        @Test
        @DisplayName("a malformed stamp restarts the clock")
        void malformedStamp() {
            priorValue("{'state':'STOPPED','stateSinceIso':'yesterday-ish'}");
            assertEquals(0L, value(evaluate(CONFIG, "STOPPED")).path("sustainedSeconds").asLong());
        }

        @Test
        @DisplayName("a stamp in the future is clamped to now, not read as a long hold")
        void futureStampClamped() {
            priorValue("{'state':'STOPPED','stateSinceIso':'" + NOW.plusSeconds(3600) + "'}");
            EvaluationOutcome outcome = evaluate(CONFIG, "STOPPED");
            // A restored database or a clock adjustment must not manufacture a
            // negative duration, nor a breach.
            assertEquals(0L, value(outcome).path("sustainedSeconds").asLong());
            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
        }

        @Test
        @DisplayName("a repository failure restarts the clock instead of failing the tick")
        void repositoryFailure() {
            triggerStates.when(() -> TriggerStateRepository.getTriggerState(77, CHANNEL_ID, null))
                    .thenThrow(new RuntimeException("connection reset"));
            assertEquals(EvaluationOutcome.Result.OK, evaluate(CONFIG, "STOPPED").getResult());
        }
    }

    @Nested
    @DisplayName("config handling")
    class ConfigHandling {

        @Test
        @DisplayName("the default set is the resting states, so a redeploy's transitions do not fire")
        void defaultsToRestingStates() {
            priorValue(held("STARTING", 3600));
            // STARTING is transitional: every ordinary redeploy passes through
            // it, so the default set must leave it alone.
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{}", "STARTING").getResult());

            priorValue(held("STOPPED", 3600));
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}", "STOPPED").getResult());
        }

        @Test
        @DisplayName("UNDEPLOYED is in the default set")
        void undeployedIsDefault() {
            priorValue(held("UNDEPLOYED", 3600));
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}", "UNDEPLOYED").getResult());
        }

        @Test
        @DisplayName("a transitional state can be selected explicitly for stuck-in-STARTING")
        void transitionalStateSelectable() {
            priorValue(held("STARTING", 3600));
            EvaluationOutcome outcome = evaluate(
                    "{'alertOnStates':['STARTING'],'minDurationSeconds':600}", "STARTING");
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
        }

        @Test
        @DisplayName("a null, blank or malformed config falls back to the defaults")
        void malformedConfigDefaults() {
            priorValue(held("STOPPED", 3600));
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate(null, "STOPPED").getResult());
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("   ", "STOPPED").getResult());
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{oops", "STOPPED").getResult());
        }

        @Test
        @DisplayName("an empty alertOnStates array falls back to the defaults at runtime")
        void emptyStatesDefaults() {
            // MonitorService rejects this at save time; the evaluator must
            // still not abort a tick over a hand-edited row.
            priorValue(held("STOPPED", 3600));
            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'alertOnStates':[]}", "STOPPED").getResult());
        }

        @Test
        @DisplayName("a negative minimum is clamped to zero")
        void negativeMinimumClamped() {
            noPriorRow();
            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'alertOnStates':['STOPPED'],'minDurationSeconds':-60}", "STOPPED").getResult());
        }

        @Test
        @DisplayName("the value snapshot echoes what the monitor was watching for")
        void valueEchoesConfiguredStates() {
            priorValue(held("STOPPED", 600));
            JsonNode value = value(evaluate(CONFIG, "STOPPED"));
            // The config may be edited after the alert opens, so the problem
            // detail pane reads the set from the snapshot rather than the row.
            assertEquals(2, value.path("alertOnStates").size());
            assertEquals("STOPPED", value.path("alertOnStates").get(0).asText());
            assertEquals(300L, value.path("minDurationSeconds").asLong());
        }
    }
}
