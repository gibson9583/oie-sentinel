/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityTrend;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;

/**
 * LOW_VOLUME evaluation: the FIXED floor, the BASELINE_RELATIVE threshold
 * arithmetic, and the "watched while running" gate the two modes share.
 *
 * <p>The gate is where the expensive false positive lives. The collector
 * keeps writing zero-delta rows for deployed-but-stopped channels, so a
 * channel restarted after maintenance presents a perfectly plausible window
 * of zeros — and without the {@link CollectorState} started-throughout check
 * it would breach on its very first tick back. The carve-out for triggers
 * already in PROBLEM points the other way: a plugin restart blanks the
 * in-memory started stamp, and an open problem that flapped to
 * INSUFFICIENT_DATA and back on every restart would double-notify the
 * operator for nothing. Both directions are pinned here.</p>
 *
 * <p>Repositories are static-mocked; {@link CollectorState} is the real
 * process-wide singleton (it is the thing whose semantics the gate depends
 * on), so every case clears the test channel. BASELINE_RELATIVE drives the
 * real {@link BaselineResolver} through mocked trend rows rather than mocking
 * it, so the threshold these cases pin is the one production computes —
 * bucket times are spaced in whole days from the reference hour, which keeps
 * their hour-of-day classification stable in whatever zone the build machine
 * runs in (see {@link BaselineResolverTest} for the tiering itself).</p>
 */
@DisplayName("LowVolumeEvaluator")
class LowVolumeEvaluatorTest {

    private static final String CHANNEL_ID = "5b7d2f91-3c44-4e8a-b7a1-9f0c6d2e8b3c";

    /**
     * Mid-January on purpose: no timezone on Earth changes its UTC offset in
     * the weeks around this instant, so day-spaced trend buckets keep the
     * same local hour under any system default zone.
     */
    private static final Instant NOW = Instant.parse("2026-01-15T12:30:00Z");

    /** The baseline anchor: the last completed hour before {@link #NOW}. */
    private static final Instant LAST_COMPLETED_HOUR =
            NOW.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);

    private MockedStatic<ActivityRepository> activityRepository;
    private MockedStatic<TriggerStateRepository> triggerStateRepository;

    @BeforeEach
    void setUp() {
        activityRepository = Mockito.mockStatic(ActivityRepository.class);
        triggerStateRepository = Mockito.mockStatic(TriggerStateRepository.class);
        CollectorState.getInstance().forgetChannel(CHANNEL_ID);
    }

    @AfterEach
    void tearDown() {
        CollectorState.getInstance().forgetChannel(CHANNEL_ID);
        triggerStateRepository.close();
        activityRepository.close();
    }

    // ---------------------------------------------------------------- helpers

    private static Monitor monitor(String configJson) {
        Monitor m = new Monitor();
        m.setId(31);
        m.setName("Volume floor");
        m.setMonitorType(MonitorType.LOW_VOLUME);
        m.setSeverity(Severity.AVERAGE);
        m.setEnabled(true);
        m.setConfigJson(configJson != null ? configJson.replace('\'', '"') : null);
        return m;
    }

    /** Marks the channel continuously started since long before any window used here. */
    private static void channelStartedLongAgo() {
        CollectorState.getInstance().markChannelStarted(CHANNEL_ID, NOW.minusSeconds(86_400));
    }

    /** Stubs the received sum for an exact {@code [NOW - windowSeconds, NOW]} range. */
    private void givenWindowSum(int windowSeconds, long received) {
        ActivityAggregate aggregate = new ActivityAggregate();
        aggregate.setReceivedSum(received);
        activityRepository.when(() -> ActivityRepository.sumActivitySamplesForRange(
                CHANNEL_ID, NOW.minusSeconds(windowSeconds), NOW)).thenReturn(aggregate);
    }

    /**
     * Stubs {@code count} hourly trend buckets, one per day walking back from
     * the last completed hour, every one carrying the same received sum. Day
     * spacing keeps the hour-of-day classification identical to the reference
     * in any zone, and identical values make the resolved mean exact no
     * matter which buckets the winning tier keeps.
     */
    private void givenDailyTrendBuckets(int count, long receivedSum) {
        List<ActivityTrend> trends = new ArrayList<>();
        for (int day = 1; day <= count; day++) {
            ActivityTrend trend = new ActivityTrend();
            trend.setChannelId(CHANNEL_ID);
            trend.setHourBucket(LAST_COMPLETED_HOUR.minus(day, ChronoUnit.DAYS));
            trend.setReceivedSum(receivedSum);
            trends.add(trend);
        }
        activityRepository.when(() -> ActivityRepository.listActivityTrendSince(eq(CHANNEL_ID), any()))
                .thenReturn(trends);
    }

    private static EvaluationOutcome evaluate(String configJson) {
        return LowVolumeEvaluator.evaluate(monitor(configJson), CHANNEL_ID, NOW);
    }

    private static JsonNode value(EvaluationOutcome outcome) {
        try {
            return Json.mapper().readTree(outcome.getValueJson());
        } catch (Exception e) {
            throw new AssertionError("value JSON is not parseable: " + outcome.getValueJson(), e);
        }
    }

    @Nested
    @DisplayName("FIXED mode")
    class Fixed {

        @Test
        @DisplayName("the floor is inclusive: exactly minCount is OK, one less breaches")
        void floorIsInclusive() {
            // "At least 10 an hour" has to mean 10 suffices. If the comparison
            // drifted to <=, every contractual floor would page on the tick it
            // was exactly met.
            channelStartedLongAgo();

            givenWindowSum(3600, 10);
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{'minCount':10}").getResult());

            givenWindowSum(3600, 9);
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{'minCount':10}").getResult());
        }

        @Test
        @DisplayName("the default floor is one message: silence breaches, anything at all is OK")
        void defaultFloorIsOne() {
            channelStartedLongAgo();

            givenWindowSum(3600, 0);
            EvaluationOutcome outcome = evaluate("{}");
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals("Received 0 messages in the last 3600s (minimum 1)", outcome.getMessage());

            givenWindowSum(3600, 1);
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{}").getResult());
        }

        @Test
        @DisplayName("FIXED mode reports no baseline fields, but keeps the JSON shape stable")
        void fixedValueJsonShape() {
            // One shape for the UI whatever the mode: the baseline keys exist
            // and are JSON null, not absent and not zero.
            channelStartedLongAgo();
            givenWindowSum(3600, 5);

            JsonNode node = value(evaluate("{'minCount':10}"));
            assertEquals(5, node.path("current").asLong());
            assertEquals(10.0, node.path("threshold").asDouble());
            assertTrue(node.path("mean").isNull());
            assertTrue(node.path("sampleCount").isNull());
            assertTrue(node.path("tier").isNull());
        }

        @Test
        @DisplayName("an unknown compareTo degrades to FIXED, the only mode with no hidden inputs")
        void unknownCompareToDegradesToFixed() {
            // Degrading to BASELINE_RELATIVE instead would hand a typo'd
            // monitor a threshold derived from history the operator never
            // asked to depend on. FIXED can only do what its own config says.
            channelStartedLongAgo();
            givenWindowSum(3600, 3);
            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'compareTo':'BASELINE','minCount':10}").getResult());
        }
    }

    @Nested
    @DisplayName("the started-throughout gate")
    class StartedGate {

        @Test
        @DisplayName("a channel started mid-window is not judged")
        void startedMidWindowIsInsufficient() {
            // The collector writes zero-delta rows for stopped channels, so a
            // post-maintenance restart shows a plausible window of zeros.
            // Breaching here would page on every planned restart.
            CollectorState.getInstance().markChannelStarted(CHANNEL_ID, NOW.minusSeconds(1800));
            givenWindowSum(3600, 0);

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate("{}").getResult());
        }

        @Test
        @DisplayName("a channel never observed started is not judged")
        void neverStartedIsInsufficient() {
            givenWindowSum(3600, 0);
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate("{}").getResult());
        }

        @Test
        @DisplayName("started exactly at the window start counts as throughout")
        void startedExactlyAtWindowStartIsJudged() {
            // The boundary of "continuously started since at or before the
            // window start" — an off-by-one here would defer every monitor by
            // one collector tick after each restart for no reason.
            CollectorState.getInstance().markChannelStarted(CHANNEL_ID, NOW.minusSeconds(3600));
            givenWindowSum(3600, 0);

            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}").getResult());
        }

        @Test
        @DisplayName("an open problem keeps evaluating through a blanked started stamp")
        void openProblemSurvivesRestart() {
            // A plugin restart blanks the in-memory stamp. Without this
            // carve-out every open LOW_VOLUME problem would flap to
            // INSUFFICIENT_DATA and back across a restart, double-notifying
            // when it re-crossed the hysteresis threshold.
            TriggerState problem = new TriggerState();
            problem.setState(TriggerStatus.PROBLEM);
            triggerStateRepository.when(() -> TriggerStateRepository.getTriggerState(31, CHANNEL_ID, null))
                    .thenReturn(problem);
            givenWindowSum(3600, 0);

            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}").getResult());
        }
    }

    @Nested
    @DisplayName("BASELINE_RELATIVE mode")
    class BaselineRelative {

        @Test
        @DisplayName("the threshold is baselinePercent of the mean, and current below it breaches")
        void thresholdIsPercentOfMean() {
            // 14 day-spaced buckets of 120 resolve to a mean of exactly 120
            // (every bucket agrees, so the winning tier's subset cannot change
            // the mean). 50% of 120 over a full hour window is a threshold of
            // exactly 60 — both values binary-exact, so the boundary is
            // decidable: 59 breaches, 60 does not (the comparison is strict).
            channelStartedLongAgo();
            givenDailyTrendBuckets(14, 120);

            givenWindowSum(3600, 59);
            EvaluationOutcome breach = evaluate(
                    "{'compareTo':'BASELINE_RELATIVE','baselinePercent':50}");
            assertEquals(EvaluationOutcome.Result.BREACH, breach.getResult());
            assertEquals(60.0, value(breach).path("threshold").asDouble());
            assertEquals(120.0, value(breach).path("mean").asDouble());

            givenWindowSum(3600, 60);
            assertEquals(EvaluationOutcome.Result.OK,
                    evaluate("{'compareTo':'BASELINE_RELATIVE','baselinePercent':50}").getResult());
        }

        @Test
        @DisplayName("a sub-hour window scales the hourly baseline down to compare like with like")
        void windowScalesTheHourlyMean() {
            // The baseline mean is per-hour. A 30-minute window compared
            // against a full hour's mean would breach every healthy channel
            // half the time; the windowSeconds/3600 factor is what stops that.
            // 50% of 120 over 1800s = 30.
            channelStartedLongAgo();
            givenDailyTrendBuckets(14, 120);

            givenWindowSum(1800, 29);
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate(
                    "{'compareTo':'BASELINE_RELATIVE','baselinePercent':50,'windowSeconds':1800}")
                    .getResult());

            givenWindowSum(1800, 30);
            assertEquals(EvaluationOutcome.Result.OK, evaluate(
                    "{'compareTo':'BASELINE_RELATIVE','baselinePercent':50,'windowSeconds':1800}")
                    .getResult());
        }

        @Test
        @DisplayName("no baseline yet means INSUFFICIENT_DATA, never a breach against zero")
        void noBaselineIsInsufficient() {
            // A young channel has no history. Inventing a threshold of zero
            // would make the monitor silently inert; inventing any other
            // number would page on guesswork. Refusing to judge is the only
            // verdict with evidence behind it.
            channelStartedLongAgo();
            activityRepository.when(() -> ActivityRepository.listActivityTrendSince(eq(CHANNEL_ID), any()))
                    .thenReturn(List.of());
            givenWindowSum(3600, 500);

            EvaluationOutcome outcome = evaluate("{'compareTo':'BASELINE_RELATIVE'}");
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult());
            assertTrue(value(outcome).path("threshold").isNull());
            assertEquals(500, value(outcome).path("current").asLong());
        }

        @Test
        @DisplayName("the mode name is case-insensitive")
        void modeNameIsCaseInsensitive() {
            // Written as "baseline_relative" by an older UI build; silently
            // reading it as FIXED would swap the monitor's entire semantics
            // without a word in the log.
            channelStartedLongAgo();
            givenDailyTrendBuckets(14, 120);
            givenWindowSum(3600, 59);

            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'compareTo':'baseline_relative','baselinePercent':50}").getResult());
        }

        @Test
        @DisplayName("a baseline breach reports mean, tier and sample count for operator trust")
        void baselineValueJsonCarriesProvenance() {
            channelStartedLongAgo();
            givenDailyTrendBuckets(14, 120);
            givenWindowSum(3600, 10);

            JsonNode node = value(evaluate("{'compareTo':'BASELINE_RELATIVE'}"));
            assertEquals(120.0, node.path("mean").asDouble());
            assertTrue(node.path("tier").isInt(), "the winning tier must be reported");
            assertTrue(node.path("sampleCount").asInt() > 0,
                    "the contributing bucket count must be reported");
        }
    }
}
