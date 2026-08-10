/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityTrend;

/**
 * The three-tier baseline fallback: which buckets each tier admits, the
 * minimum sample counts that gate each tier, and the mean/stddev arithmetic
 * of the winning tier.
 *
 * <p>The tiering classifies buckets by hour-of-day and weekend-ness in the
 * <em>system default zone</em>, which a unit test does not control. These
 * cases are built to be zone-proof rather than zone-assuming: buckets are
 * spaced in whole days from the reference hour (same local hour under any
 * fixed offset, and mid-January is transition-free in every real zone), the
 * reference instant falls on a local weekday in every zone on Earth (11:00Z
 * on a Thursday reaches at most Wednesday 23:00 westward and Friday 01:00
 * eastward), and counts lean on a calendar invariant — any run of whole
 * weeks contains a fixed number of weekend days whichever weekday it starts
 * on (14 consecutive days always hold exactly 4), which is why 14 is the
 * bucket count used wherever weekend exclusion is asserted. Where hour-of-day
 * <em>mismatch</em> is needed, 25-hour spacing walks the local hour through
 * the whole clock in every zone alike.</p>
 *
 * <p>Why the boundaries deserve tests at all: the tier minimums are the line
 * between "young channel gets a usable baseline quickly" and "sparse history
 * produces a garbage mean that pages someone". A tier admitted one sample
 * early is a monitor that alerts off statistical noise; a tier demanding one
 * sample extra is a channel unmonitorable for a day longer than documented.</p>
 */
@DisplayName("BaselineResolver")
class BaselineResolverTest {

    private static final String CHANNEL_ID = "c2d94e07-1a58-4f36-9b82-e65d03a7c841";

    /**
     * The reference hour: 11:00Z on Thursday 2026-01-15 — a local weekday
     * hour in every timezone (see class Javadoc), in a season with no DST
     * transitions anywhere.
     */
    private static final Instant REFERENCE = Instant.parse("2026-01-15T11:00:00Z");

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

    private static ActivityTrend trend(Instant bucket, long received, long sent, long error) {
        ActivityTrend trend = new ActivityTrend();
        trend.setChannelId(CHANNEL_ID);
        trend.setHourBucket(bucket);
        trend.setReceivedSum(received);
        trend.setSentSum(sent);
        trend.setErrorSum(error);
        return trend;
    }

    /** {@code count} buckets, one per day back from the reference, all worth {@code received}. */
    private static List<ActivityTrend> dailyBuckets(int count, long received) {
        List<ActivityTrend> trends = new ArrayList<>();
        for (int day = 1; day <= count; day++) {
            trends.add(trend(REFERENCE.minus(day, ChronoUnit.DAYS), received, 0, 0));
        }
        return trends;
    }

    private void givenTrends(List<ActivityTrend> trends) {
        activityRepository.when(() -> ActivityRepository.listActivityTrendSince(eq(CHANNEL_ID), any()))
                .thenReturn(trends);
    }

    private static BaselineResolver.Baseline resolve(int lookbackDays, boolean useWeekendBucket) {
        return BaselineResolver.resolve(CHANNEL_ID, REFERENCE, "received", lookbackDays, useWeekendBucket);
    }

    @Nested
    @DisplayName("tier selection")
    class TierSelection {

        @Test
        @DisplayName("tier 1 wins on same-hour same-weekend-class buckets, excluding the weekends")
        void tier1WinsAndExcludesWeekends() {
            // 14 daily buckets: all share the reference's local hour; exactly
            // 4 of any 14 consecutive days are weekend days, so tier 1 keeps
            // exactly 10 — in every timezone. The exclusion is the point:
            // folding Saturday's trickle into a weekday-morning baseline is
            // how a Monday 09:00 lab feed gets an artificially low mean and a
            // LOW alert that never fires.
            givenTrends(dailyBuckets(14, 100));

            BaselineResolver.Baseline baseline = resolve(28, true);
            assertNotNull(baseline);
            assertEquals(1, baseline.tier);
            assertEquals(10, baseline.sampleCount);
            assertEquals(100.0, baseline.mean);
            assertEquals(0.0, baseline.stddev);
        }

        @Test
        @DisplayName("with the weekend split disabled the same history resolves nothing")
        void weekendBucketOffSkipsTier1() {
            // Same 14 buckets, useWeekendBucket = false: tier 1 is not
            // attempted, tier 2 has 14 < 16 and tier 3 has 14 < 30 — so the
            // toggle genuinely changes the verdict, not just a label. A
            // resolver that silently still used tier 1 would hand
            // no-weekly-shape channels a baseline built on a split the
            // operator explicitly said does not exist.
            givenTrends(dailyBuckets(14, 100));
            assertNull(resolve(28, false));
        }

        @Test
        @DisplayName("tier 2 needs exactly 16 same-hour buckets: 16 resolves, 15 does not")
        void tier2Boundary() {
            givenTrends(dailyBuckets(16, 100));
            BaselineResolver.Baseline baseline = resolve(28, false);
            assertNotNull(baseline);
            assertEquals(2, baseline.tier);
            assertEquals(16, baseline.sampleCount);

            givenTrends(dailyBuckets(15, 100));
            assertNull(resolve(28, false));
        }

        @Test
        @DisplayName("tier 3 needs exactly 30 buckets of any hour: 30 resolves, 29 does not")
        void tier3Boundary() {
            // 25-hour spacing walks the bucket's local hour around the clock,
            // so at most one of 30 buckets (k = 24) shares the reference hour
            // — tier 2 can never reach 16 and only tier 3 is in play.
            List<ActivityTrend> offHour = new ArrayList<>();
            for (int k = 1; k <= 30; k++) {
                offHour.add(trend(REFERENCE.minus(25L * k, ChronoUnit.HOURS), 100, 0, 0));
            }
            givenTrends(offHour);
            BaselineResolver.Baseline baseline = resolve(40, true);
            assertNotNull(baseline);
            assertEquals(3, baseline.tier);
            assertEquals(30, baseline.sampleCount);

            givenTrends(offHour.subList(0, 29));
            assertNull(resolve(40, true));
        }

        @Test
        @DisplayName("history below every tier's minimum resolves to null, never a thin baseline")
        void tooLittleHistoryIsNull() {
            // 7 daily buckets: at most 5 for tier 1, 7 for tiers 2 and 3 —
            // all short. The caller must see null and report
            // INSUFFICIENT_DATA; a mean over 7 points dressed up as "normal"
            // is exactly the guesswork the minimums exist to prevent.
            givenTrends(dailyBuckets(7, 100));
            assertNull(resolve(28, true));
        }
    }

    @Nested
    @DisplayName("what a bucket must be to count")
    class BucketAdmission {

        @Test
        @DisplayName("the reference hour and anything newer are excluded from their own baseline")
        void referenceHourIsExcluded() {
            // The hour under judgment must not drag the mean toward the very
            // value being tested — with it included, a 10x spike would raise
            // its own baseline and mask itself. The boundary is exclusive at
            // exactly the reference start.
            List<ActivityTrend> trends = dailyBuckets(14, 100);
            trends.add(trend(REFERENCE, 1_000_000, 0, 0));
            trends.add(trend(REFERENCE.plus(1, ChronoUnit.HOURS), 1_000_000, 0, 0));
            givenTrends(trends);

            BaselineResolver.Baseline baseline = resolve(28, true);
            assertNotNull(baseline);
            assertEquals(100.0, baseline.mean, "a polluted mean proves the exclusion failed");
            assertEquals(10, baseline.sampleCount);
        }

        @Test
        @DisplayName("a row with no bucket time is skipped, not counted or crashed on")
        void nullBucketIsSkipped() {
            List<ActivityTrend> trends = dailyBuckets(14, 100);
            trends.add(trend(null, 1_000_000, 0, 0));
            givenTrends(trends);

            BaselineResolver.Baseline baseline = resolve(28, true);
            assertNotNull(baseline);
            assertEquals(100.0, baseline.mean);
        }
    }

    @Nested
    @DisplayName("the statistics of the winning tier")
    class Statistics {

        @Test
        @DisplayName("mean and sample (n-1) standard deviation over the tier's values")
        void meanAndSampleStddev() {
            // 16 same-hour buckets alternating 90/110 (weekend split off, so
            // the tier-2 set is all 16 in any zone): mean 100 exactly;
            // sample stddev = sqrt(16 * 10^2 / 15). The n-1 denominator is
            // load-bearing — these buckets are a sample of the channel's
            // behavior, and the population formula would understate spread
            // and over-fire every z-score built on it.
            List<ActivityTrend> trends = new ArrayList<>();
            for (int day = 1; day <= 16; day++) {
                trends.add(trend(REFERENCE.minus(day, ChronoUnit.DAYS), day % 2 == 0 ? 110 : 90, 0, 0));
            }
            givenTrends(trends);

            BaselineResolver.Baseline baseline = resolve(28, false);
            assertNotNull(baseline);
            assertEquals(100.0, baseline.mean);
            assertEquals(Math.sqrt(16.0 * 100.0 / 15.0), baseline.stddev, 1e-9);
        }
    }

    @Nested
    @DisplayName("metric selection")
    class MetricSelection {

        private void givenDistinctSums() {
            List<ActivityTrend> trends = new ArrayList<>();
            for (int day = 1; day <= 16; day++) {
                trends.add(trend(REFERENCE.minus(day, ChronoUnit.DAYS), 10, 20, 30));
            }
            givenTrends(trends);
        }

        private double meanFor(String metric) {
            BaselineResolver.Baseline baseline = BaselineResolver.resolve(
                    CHANNEL_ID, REFERENCE, metric, 28, false);
            assertNotNull(baseline, "baseline should resolve for metric " + metric);
            return baseline.mean;
        }

        @Test
        @DisplayName("each metric name selects its own sum, case-insensitively")
        void metricNamesSelectTheirSums() {
            givenDistinctSums();
            assertEquals(10.0, meanFor("received"));
            assertEquals(20.0, meanFor("sent"));
            assertEquals(30.0, meanFor("error"));
            assertEquals(20.0, meanFor(" SENT "), "names must be trimmed and case-folded");
        }

        @Test
        @DisplayName("an unknown metric falls back to received instead of failing the evaluation")
        void unknownMetricFallsBack() {
            // A typo in one monitor's config must not abort a whole evaluator
            // tick; received is the fallback because it is the one sum every
            // channel meaningfully has.
            givenDistinctSums();
            assertEquals(10.0, meanFor("throughput"));
            assertEquals(10.0, meanFor(null));
        }
    }
}
