/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * ERROR_RATE evaluation — the one evaluator whose core operation is a
 * division, and therefore the one whose edge cases are arithmetic rather than
 * temporal.
 *
 * <p>The two verdicts that matter most here are the ones that refuse to
 * judge. A window that received <em>nothing</em> has no error rate at all —
 * the ratio is undefined, not zero — and that check must hold even when the
 * operator has configured {@code minMessages = 0} to mean "no floor", because
 * that configuration is precisely the one that would otherwise walk straight
 * into a division by zero. And a window below the floor is
 * INSUFFICIENT_DATA rather than OK, because a feed that has degraded until it
 * passes almost nothing stops being measurable at exactly the moment its
 * errors matter most; an OK there would resolve a live problem on the
 * strength of having stopped looking.</p>
 *
 * <p>The repository is replaced with a static mock and stubbed with
 * <em>exact</em> range arguments — {@code [now - windowSeconds, now]} — so
 * the window arithmetic is pinned along with the verdict: an evaluator that
 * silently queried a different window would read the stub's default
 * ({@code null}, the "no data" guard) and fail these cases on the wrong
 * verdict rather than passing on the right one by accident.</p>
 */
@DisplayName("ErrorRateEvaluator")
class ErrorRateEvaluatorTest {

    private static final String CHANNEL_ID = "1c8f0a7e-5b3d-4d21-9a6f-2e7c4b91d0aa";

    /** The evaluation instant; every window is expressed relative to it. */
    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    private MockedStatic<ActivityRepository> activityRepository;

    @BeforeEach
    void setUp() {
        activityRepository = Mockito.mockStatic(ActivityRepository.class);
    }

    @AfterEach
    void tearDown() {
        activityRepository.close();
    }

    // ---------------------------------------------------------------- helpers

    private static Monitor monitor(String configJson) {
        Monitor m = new Monitor();
        m.setId(21);
        m.setName("Error rate");
        m.setMonitorType(MonitorType.ERROR_RATE);
        m.setSeverity(Severity.HIGH);
        m.setEnabled(true);
        m.setConfigJson(configJson != null ? configJson.replace('\'', '"') : null);
        return m;
    }

    private static ActivityAggregate aggregate(long received, long errors) {
        ActivityAggregate a = new ActivityAggregate();
        a.setReceivedSum(received);
        a.setErrorSum(errors);
        return a;
    }

    /** Stubs the window sum for an exact {@code [NOW - windowSeconds, NOW]} range. */
    private void givenWindow(int windowSeconds, ActivityAggregate aggregate) {
        activityRepository.when(() -> ActivityRepository.sumActivitySamplesForRange(
                CHANNEL_ID, NOW.minusSeconds(windowSeconds), NOW)).thenReturn(aggregate);
    }

    private static EvaluationOutcome evaluate(String configJson) {
        return ErrorRateEvaluator.evaluate(monitor(configJson), CHANNEL_ID, NOW);
    }

    private static JsonNode value(EvaluationOutcome outcome) {
        try {
            return Json.mapper().readTree(outcome.getValueJson());
        } catch (Exception e) {
            throw new AssertionError("value JSON is not parseable: " + outcome.getValueJson(), e);
        }
    }

    @Nested
    @DisplayName("an empty window has no rate")
    class UndefinedRate {

        @Test
        @DisplayName("received = 0 with minMessages = 0 is INSUFFICIENT_DATA, not a division by zero")
        void zeroReceivedWithNoFloorCannotDivide() {
            // "minMessages: 0" is a legitimate operator statement meaning "no
            // floor" — and it is exactly the configuration that would reach
            // errors/received with received = 0 if the empty-window check were
            // ordered after the floor check instead of before it.
            givenWindow(3600, aggregate(0, 5));
            EvaluationOutcome outcome = evaluate("{'minMessages':0}");

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult());
            // No rate exists, so errorPercent must be JSON null — the UI has
            // to tell "no errors" apart from "no rate".
            assertTrue(value(outcome).path("errorPercent").isNull());
            assertEquals(5, value(outcome).path("errorCount").asLong());
        }

        @Test
        @DisplayName("received = 0 under the default floor is likewise unjudged")
        void zeroReceivedDefaultConfig() {
            givenWindow(3600, aggregate(0, 0));
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate("{}").getResult());
        }

        @Test
        @DisplayName("a null aggregate is treated as an empty window, never an NPE")
        void nullAggregateIsGuarded() {
            // The query COALESCEs every column so this "cannot happen" — but a
            // mid-tick NPE would take every later monitor's evaluation with it,
            // so the guard is part of the contract.
            givenWindow(3600, null);
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate("{}").getResult());
        }
    }

    @Nested
    @DisplayName("the minMessages floor")
    class MessageFloor {

        @Test
        @DisplayName("below the floor the window is not judged, but the rate it saw is still reported")
        void belowFloorIsInsufficientButInformative() {
            // 3 of 4 errored is 75% — on a quiet feed that single reading must
            // not page anyone, but hiding it entirely would strip the operator
            // of exactly the context needed to decide if the floor is right.
            givenWindow(3600, aggregate(4, 3));
            EvaluationOutcome outcome = evaluate("{'minMessages':20,'thresholdPercent':10}");

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult());
            assertEquals(75.0, value(outcome).path("errorPercent").asDouble());
        }

        @Test
        @DisplayName("the floor is exclusive: exactly minMessages messages are enough to judge")
        void atFloorIsJudged() {
            // The boundary is where "20 messages minimum" either means 20 or
            // quietly means 21. received == minMessages must be judged, or the
            // documented floor is off by one forever.
            givenWindow(3600, aggregate(20, 0));
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{'minMessages':20}").getResult());

            givenWindow(3600, aggregate(19, 0));
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA,
                    evaluate("{'minMessages':20}").getResult());
        }

        @Test
        @DisplayName("a negative floor is clamped to zero rather than making every window unjudgeable")
        void negativeFloorIsClamped() {
            // received < minMessages with a negative floor would be false for
            // every window, which happens to work — but the clamp makes the
            // intent explicit and keeps the value JSON from echoing a
            // nonsensical negative floor to the UI.
            givenWindow(3600, aggregate(1, 1));
            EvaluationOutcome outcome = evaluate("{'minMessages':-5}");
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals(0, value(outcome).path("minMessages").asLong());
        }
    }

    @Nested
    @DisplayName("the threshold comparison")
    class Threshold {

        @Test
        @DisplayName("the threshold is inclusive: exactly thresholdPercent breaches")
        void thresholdIsInclusive() {
            // An operator who writes "alert at 10%" means 10% is bad. 10 of
            // 100 is exactly 10.0% (both sides exact in binary), so this
            // boundary is decidable and must not drift to "strictly above".
            givenWindow(3600, aggregate(100, 10));
            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'thresholdPercent':10,'minMessages':1}").getResult());

            givenWindow(3600, aggregate(100, 9));
            assertEquals(EvaluationOutcome.Result.OK,
                    evaluate("{'thresholdPercent':10,'minMessages':1}").getResult());
        }

        @Test
        @DisplayName("a rate above 100% is reported verbatim, not capped")
        void over100PercentIsReportedVerbatim() {
            // A backlog draining through a broken destination can error more
            // messages than the window received. "180%" tells the operator
            // that; a capped "100%" would hide it.
            givenWindow(3600, aggregate(50, 90));
            EvaluationOutcome outcome = evaluate("{'thresholdPercent':10,'minMessages':1}");

            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals(180.0, value(outcome).path("errorPercent").asDouble());
            assertTrue(outcome.getMessage().contains("180.0%"), outcome.getMessage());
        }

        @Test
        @DisplayName("zero errors over real traffic is a clean OK")
        void zeroErrorsIsOk() {
            givenWindow(3600, aggregate(500, 0));
            EvaluationOutcome outcome = evaluate("{}");
            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
            assertEquals(0.0, value(outcome).path("errorPercent").asDouble());
        }
    }

    @Nested
    @DisplayName("config parsing")
    class ConfigParsing {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "", "   ", "not json", "{" })
        @DisplayName("an unusable config falls back to every default without aborting the tick")
        void unusableConfigUsesDefaults(String configJson) {
            // Defaults: window 3600s, threshold 10%, floor 20. The window
            // default is proven by the exact-range stub: had the evaluator
            // queried any other range it would have read null and produced
            // INSUFFICIENT_DATA instead of this breach.
            givenWindow(3600, aggregate(100, 25));
            EvaluationOutcome outcome =
                    ErrorRateEvaluator.evaluate(monitor(configJson), CHANNEL_ID, NOW);

            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals(10.0, value(outcome).path("thresholdPercent").asDouble());
            assertEquals(20, value(outcome).path("minMessages").asLong());
            assertEquals(3600, value(outcome).path("windowSeconds").asInt());
        }

        @Test
        @DisplayName("a zero window is floored at one second, not a zero-width query")
        void zeroWindowIsFloored() {
            // A zero-width range would return an empty aggregate on every
            // vendor and pin the monitor at INSUFFICIENT_DATA forever with no
            // hint why. The floor keeps a hand-broken config observable.
            givenWindow(1, aggregate(100, 50));
            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'windowSeconds':0,'minMessages':1}").getResult());
        }

        @Test
        @DisplayName("the breach message names counts, window and both percentages")
        void breachMessageIsSelfContained() {
            // The message becomes the alert event's subject line; it has to be
            // actionable without opening the detail pane.
            givenWindow(3600, aggregate(200, 50));
            assertEquals("50 of 200 messages errored in the last 3600s (25.0%, threshold 10.0%)",
                    evaluate("{}").getMessage());
        }
    }
}
