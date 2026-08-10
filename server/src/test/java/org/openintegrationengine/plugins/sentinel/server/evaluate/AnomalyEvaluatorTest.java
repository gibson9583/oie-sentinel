/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
 * ANOMALY evaluation: the z-score comparison, its direction gating, the
 * flat-baseline tolerance band, and the config parsing that feeds them.
 *
 * <p>{@link BaselineResolver} is static-mocked so mean and stddev are exact
 * inputs rather than outputs of the tiering algorithm — the tiering has its
 * own suite ({@link BaselineResolverTest}); what belongs here is what the
 * evaluator does with a baseline once it has one. With mean 100 and stddev 10
 * every z in these cases is a small exact decimal, so the strict-inequality
 * boundaries are decidable: z of exactly the threshold does NOT breach, which
 * matters because a flat-lining feed often sits exactly at a round z when its
 * history is highly regular, and "3.0 sigma" reading as a breach would page
 * on the most regular channels first.</p>
 *
 * <p>The flat-baseline path exists because a z-score against stddev 0 is
 * infinite: a channel that received exactly 100 messages every hour for weeks
 * would page on 101 without the tolerance band. The band — max(1, 10% of
 * mean) — and its direction gating are pinned separately from the z path
 * because they are separate code, and a regression in one would otherwise
 * hide behind the other's passing cases.</p>
 *
 * <p>The current-hour sum is stubbed with exact bucket bounds
 * ({@code [hourStart, hourStart + 1h - 1ms]}), so the "last completed hour,
 * inclusive range pulled one millisecond inside the boundary" arithmetic is
 * pinned too: a sample landing exactly on the next hour must belong to the
 * next bucket, not both.</p>
 */
@DisplayName("AnomalyEvaluator")
class AnomalyEvaluatorTest {

    private static final String CHANNEL_ID = "e3a7f512-9c08-4b6d-b1e4-7f2a85c90d13";

    private static final Instant NOW = Instant.parse("2026-01-15T12:34:56Z");

    /** The bucket under judgment: the last completed hour before {@link #NOW}. */
    private static final Instant HOUR_START =
            NOW.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);

    private MockedStatic<ActivityRepository> activityRepository;
    private MockedStatic<BaselineResolver> baselineResolver;

    @BeforeEach
    void setUp() {
        activityRepository = Mockito.mockStatic(ActivityRepository.class);
        baselineResolver = Mockito.mockStatic(BaselineResolver.class);
    }

    @AfterEach
    void tearDown() {
        baselineResolver.close();
        activityRepository.close();
    }

    // ---------------------------------------------------------------- helpers

    private static Monitor monitor(String configJson) {
        Monitor m = new Monitor();
        m.setId(61);
        m.setName("Volume anomaly");
        m.setMonitorType(MonitorType.ANOMALY);
        m.setSeverity(Severity.AVERAGE);
        m.setEnabled(true);
        m.setConfigJson(configJson != null ? configJson.replace('\'', '"') : null);
        return m;
    }

    /**
     * Stubs the current hour's sums with exact bucket bounds. All three
     * metric sums are set so the metric-selection cases can tell them apart.
     */
    private void givenHourSums(long received, long sent, long error) {
        ActivityAggregate aggregate = new ActivityAggregate();
        aggregate.setReceivedSum(received);
        aggregate.setSentSum(sent);
        aggregate.setErrorSum(error);
        activityRepository.when(() -> ActivityRepository.sumActivitySamplesForRange(
                CHANNEL_ID, HOUR_START, HOUR_START.plus(1, ChronoUnit.HOURS).minusMillis(1)))
                .thenReturn(aggregate);
    }

    private void givenBaseline(double mean, double stddev) {
        baselineResolver.when(() -> BaselineResolver.resolve(
                eq(CHANNEL_ID), eq(HOUR_START), anyString(), anyInt(), anyBoolean()))
                .thenReturn(new BaselineResolver.Baseline(mean, stddev, 20, 1));
    }

    private static EvaluationOutcome evaluate(String configJson) {
        return AnomalyEvaluator.evaluate(monitor(configJson), CHANNEL_ID, NOW);
    }

    private static JsonNode value(EvaluationOutcome outcome) {
        try {
            return Json.mapper().readTree(outcome.getValueJson());
        } catch (Exception e) {
            throw new AssertionError("value JSON is not parseable: " + outcome.getValueJson(), e);
        }
    }

    @Nested
    @DisplayName("no baseline")
    class NoBaseline {

        @Test
        @DisplayName("a null baseline is INSUFFICIENT_DATA carrying the current value and hour")
        void noBaselineIsInsufficient() {
            // A young channel must not breach against an invented mean of
            // zero — but the UI still gets the current reading and the exact
            // bucket that went unjudged.
            givenHourSums(500, 0, 0);
            baselineResolver.when(() -> BaselineResolver.resolve(
                    eq(CHANNEL_ID), eq(HOUR_START), anyString(), anyInt(), anyBoolean()))
                    .thenReturn(null);

            EvaluationOutcome outcome = evaluate("{}");
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult());
            assertEquals(500, value(outcome).path("current").asLong());
            assertTrue(value(outcome).path("mean").isNull());
            assertEquals("2026-01-15T11:00:00Z", value(outcome).path("hourBucket").asText());
        }
    }

    @Nested
    @DisplayName("the z-score comparison (mean 100, stddev 10)")
    class ZScore {

        @Test
        @DisplayName("the threshold is strict: exactly 3.0 sigma does not breach, 3.1 does")
        void thresholdIsStrict() {
            // The most regular channels produce the roundest z values;
            // an inclusive comparison would page on them first.
            givenBaseline(100, 10);

            givenHourSums(130, 0, 0); // z = 3.0
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{}").getResult());

            givenHourSums(131, 0, 0); // z = 3.1
            EvaluationOutcome breach = evaluate("{}");
            assertEquals(EvaluationOutcome.Result.BREACH, breach.getResult());
            assertEquals(3.1, value(breach).path("z").asDouble(), 1e-9);
        }

        @Test
        @DisplayName("BOTH is the default and catches deviations on either side")
        void defaultDirectionIsBoth() {
            givenBaseline(100, 10);

            givenHourSums(69, 0, 0); // z = -3.1
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}").getResult());

            givenHourSums(131, 0, 0); // z = +3.1
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}").getResult());
        }

        @Test
        @DisplayName("LOW_ONLY ignores a spike, however large")
        void lowOnlyIgnoresSpikes() {
            // A results feed whose volume doubles during flu season is not an
            // incident; the same feed going quiet is. Direction gating is how
            // the operator says so — a HIGH breach leaking through would
            // teach them to disable the monitor.
            givenBaseline(100, 10);

            givenHourSums(300, 0, 0); // z = +20
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{'direction':'LOW_ONLY'}").getResult());

            givenHourSums(69, 0, 0); // z = -3.1
            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'direction':'LOW_ONLY'}").getResult());
        }

        @Test
        @DisplayName("HIGH_ONLY ignores a collapse, however deep")
        void highOnlyIgnoresCollapses() {
            givenBaseline(100, 10);

            givenHourSums(0, 0, 0); // z = -10
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{'direction':'HIGH_ONLY'}").getResult());

            givenHourSums(131, 0, 0); // z = +3.1
            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'direction':'HIGH_ONLY'}").getResult());
        }

        @Test
        @DisplayName("a configured threshold replaces the three-sigma default")
        void configuredThresholdApplies() {
            givenBaseline(100, 10);
            givenHourSums(121, 0, 0); // z = 2.1

            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'zScoreThreshold':2}").getResult());
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{}").getResult());
        }
    }

    @Nested
    @DisplayName("the flat-baseline tolerance band")
    class FlatBaseline {

        @Test
        @DisplayName("with stddev 0 the band is 10% of the mean, exceeded strictly")
        void flatBandIsTenPercentOfMean() {
            // stddev 0 makes every z infinite: a channel that received
            // exactly 100 every hour for weeks would page on 101 without the
            // band. Mean 100 -> band 10: deviation 10 holds, 11 breaches.
            givenBaseline(100, 0);

            givenHourSums(110, 0, 0);
            EvaluationOutcome ok = evaluate("{}");
            assertEquals(EvaluationOutcome.Result.OK, ok.getResult());
            // No z-score exists on this path; JSON null, not zero or infinity.
            assertTrue(value(ok).path("z").isNull());

            givenHourSums(111, 0, 0);
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}").getResult());

            givenHourSums(89, 0, 0);
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}").getResult());
        }

        @Test
        @DisplayName("a flat zero baseline uses the absolute floor of 1")
        void flatZeroBaselineUsesAbsoluteFloor() {
            // The pathological newcomer: weeks of exactly zero. 10% of 0 is
            // 0, and without the max(1, ...) floor a single message would be
            // an "anomaly". One message of slack is the difference between a
            // usable monitor and a hair trigger.
            givenBaseline(0, 0);

            givenHourSums(1, 0, 0);
            assertEquals(EvaluationOutcome.Result.OK, evaluate("{}").getResult());

            givenHourSums(2, 0, 0);
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate("{}").getResult());
        }

        @Test
        @DisplayName("the band respects direction gating")
        void flatBandRespectsDirection() {
            // The band and the z path gate direction in different code; a
            // regression in this one would let a LOW_ONLY monitor page on a
            // spike exactly when the channel's history is most regular.
            givenBaseline(100, 0);
            givenHourSums(150, 0, 0); // deviation +50, well past the band

            assertEquals(EvaluationOutcome.Result.OK, evaluate("{'direction':'LOW_ONLY'}").getResult());
            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'direction':'HIGH_ONLY'}").getResult());
        }
    }

    @Nested
    @DisplayName("config parsing")
    class ConfigParsing {

        @Test
        @DisplayName("direction names are trimmed and case-insensitive")
        void directionIsNormalized() {
            givenBaseline(100, 10);
            givenHourSums(300, 0, 0); // z = +20

            assertEquals(EvaluationOutcome.Result.OK,
                    evaluate("{'direction':' low_only '}").getResult());
        }

        @ParameterizedTest
        @ValueSource(strings = { "DOWN", "LOW", "low-only", "", "42" })
        @DisplayName("junk directions degrade to BOTH, the only value that cannot miss a deviation")
        void junkDirectionDegradesToBoth(String direction) {
            // Degrading to either single-sided value would silently ignore
            // half of everything the monitor exists to catch; BOTH can only
            // over-report, which the operator will notice and fix.
            givenBaseline(100, 10);
            givenHourSums(69, 0, 0); // z = -3.1, only alertable if LOW is live

            assertEquals(EvaluationOutcome.Result.BREACH,
                    evaluate("{'direction':'" + direction + "'}").getResult());
        }

        @Test
        @DisplayName("the configured metric picks its sum, and the baseline is asked for the same one")
        void metricSelectsItsSum() {
            // Distinct sums per metric make a wrong pick visible in "current".
            // The same raw string must reach BaselineResolver, or current and
            // baseline would silently describe different metrics.
            givenBaseline(100, 10);
            givenHourSums(10, 20, 30);

            assertEquals(20, value(evaluate("{'metric':'SENT'}")).path("current").asLong());
            baselineResolver.verify(() -> BaselineResolver.resolve(
                    eq(CHANNEL_ID), eq(HOUR_START), eq("SENT"), anyInt(), anyBoolean()));

            assertEquals(30, value(evaluate("{'metric':'error'}")).path("current").asLong());
        }

        @Test
        @DisplayName("an unknown metric falls back to received rather than failing the tick")
        void junkMetricFallsBackToReceived() {
            givenBaseline(100, 10);
            givenHourSums(10, 20, 30);
            assertEquals(10, value(evaluate("{'metric':'throughput'}")).path("current").asLong());
        }

        @Test
        @DisplayName("a malformed config evaluates with every default instead of aborting")
        void malformedConfigUsesDefaults() {
            // Defaults: metric received, direction BOTH, threshold 3. The
            // exact-bounds stub pins the bucket arithmetic at the same time.
            givenBaseline(100, 10);
            givenHourSums(131, 0, 0); // z = +3.1 on received

            EvaluationOutcome outcome = AnomalyEvaluator.evaluate(
                    monitor("not json"), CHANNEL_ID, NOW);
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
        }
    }
}
