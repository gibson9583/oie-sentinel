/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityTrend;

/**
 * Computes a historical mean/stddev baseline for one channel metric from the
 * hourly rollup table ({@code sentinel_channel_activity_trend}), using a
 * three-tier fallback so a brand-new channel gets coverage almost immediately
 * and full hour-of-day seasonality within ~2 weeks:
 *
 * <pre>
 * tier 1: buckets with the same hour-of-day AND the same weekend/weekday
 *         classification as the reference hour   — need &ge; 8 samples
 * tier 2: same hour-of-day only                  — need &ge; 16 samples
 * tier 3: every bucket in the lookback window    — need &ge; 30 samples
 * below the tier-3 threshold → null (caller reports INSUFFICIENT_DATA)
 * </pre>
 *
 * <p>Tier 1 captures true seasonality (a lab interface is quiet on Sunday
 * 03:00 but busy on Monday 09:00); tiers 2 and 3 progressively trade
 * seasonality for sample count so young channels are not unmonitorable. The
 * chosen tier is surfaced on the returned {@link Baseline} so evaluators can
 * persist it into their value JSON and operators can judge how much to trust
 * a given alert.</p>
 *
 * <p>The hour-of-day/weekend bucketing happens here in Java rather than SQL
 * because date-part extraction is one of the least portable corners of the
 * five supported database vendors — the mapper just returns every trend row
 * in the lookback window and this class does the tiering. The cost is bounded:
 * the lookback window caps the scan at {@code baselineWindowDays * 24} rows
 * per call (~672 for the default 28 days). Shared by {@code AnomalyEvaluator}
 * and {@code LowVolumeEvaluator}'s BASELINE_RELATIVE mode so both agree on
 * what "normal" means.</p>
 *
 * <p>Stateless static utility, matching the repository/evaluator style used
 * throughout the plugin.</p>
 */
public final class BaselineResolver {

    private static final Logger log = LoggerFactory.getLogger(BaselineResolver.class);

    /** Minimum sample counts per tier — see the class Javadoc for rationale. */
    private static final int TIER1_MIN_SAMPLES = 8;
    private static final int TIER2_MIN_SAMPLES = 16;
    private static final int TIER3_MIN_SAMPLES = 30;

    private BaselineResolver() {
    }

    /**
     * An immutable resolved baseline: the mean and sample standard deviation
     * of the chosen tier's metric values, plus how many buckets contributed
     * and which tier won. Plain public-final fields (no getters) — this is a
     * short-lived in-process value object that never crosses the REST
     * boundary.
     */
    public static final class Baseline {

        /** Mean of the metric over the resolved tier's buckets. */
        public final double mean;
        /**
         * Sample standard deviation (n-1 denominator — these buckets are a
         * sample of the channel's behavior, not the whole population).
         */
        public final double stddev;
        /** Number of hourly buckets that contributed to this baseline. */
        public final int sampleCount;
        /** Which fallback tier produced this baseline (1, 2, or 3). */
        public final int tier;

        public Baseline(double mean, double stddev, int sampleCount, int tier) {
            this.mean = mean;
            this.stddev = stddev;
            this.sampleCount = sampleCount;
            this.tier = tier;
        }
    }

    /**
     * Resolves the baseline for one channel metric relative to a reference
     * hour.
     *
     * <p>Buckets at or after {@code referenceHourStart} are excluded: the
     * reference hour is the one being judged, and letting it (or anything
     * newer) into its own baseline would drag the mean toward the very value
     * under test, masking exactly the deviations this exists to catch.</p>
     *
     * <p>Hour-of-day and weekend classification use {@link
     * ZoneId#systemDefault()} — "Sunday" for baseline purposes should mean
     * Sunday where the server (and its message sources) live, not UTC.
     * Weekend = Saturday/Sunday.</p>
     *
     * <p>This stays on the server zone deliberately, and the asymmetry with
     * maintenance windows (which carry their own {@code timezone} as of
     * schema v3) is not an oversight: a window is an operator-facing schedule
     * someone reads off a rota, so it has to run on that rota's clock, while a
     * baseline is a statistical grouping — it only needs a consistent notion
     * of "the same hour last week", and any fixed zone gives it one. A
     * per-monitor baseline zone would add configuration surface for no
     * behavioral gain.</p>
     *
     * @param channelId          the OIE channel id (a UUID string) whose
     *                           trend history is consulted
     * @param referenceHourStart start of the hour bucket being evaluated;
     *                           also the exclusive upper bound of the history
     *                           considered
     * @param metric             which trend sum to baseline: {@code received},
     *                           {@code sent}, or {@code error}
     *                           (case-insensitive; unrecognized values fall
     *                           back to {@code received} with a warning
     *                           rather than failing the whole evaluation)
     * @param baselineWindowDays how far back from the reference hour to look
     * @param useWeekendBucket   whether tier 1 (weekend/weekday split) is
     *                           attempted at all; {@code false} skips
     *                           straight to tier 2, for channels whose
     *                           traffic has no weekly shape
     * @return the resolved baseline, or {@code null} when even tier 3 lacks
     *         the minimum sample count — the caller must treat this as
     *         INSUFFICIENT_DATA, never as "baseline of zero"
     */
    public static Baseline resolve(String channelId, Instant referenceHourStart, String metric,
                                   int baselineWindowDays, boolean useWeekendBucket) {
        Instant lookbackStart = referenceHourStart.minus(baselineWindowDays, ChronoUnit.DAYS);
        List<ActivityTrend> trends = ActivityRepository.listActivityTrendSince(channelId, lookbackStart);

        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime reference = referenceHourStart.atZone(zone);
        int referenceHour = reference.getHour();
        boolean referenceWeekend = isWeekend(reference.getDayOfWeek());

        // Tier lists are strict supersets (tier1 ⊆ tier2 ⊆ tier3), built in
        // one pass over the (already small) trend row set.
        List<Double> tier1 = new ArrayList<>();
        List<Double> tier2 = new ArrayList<>();
        List<Double> tier3 = new ArrayList<>();

        for (ActivityTrend trend : trends) {
            Instant bucket = trend.getHourBucket();
            if (bucket == null || !bucket.isBefore(referenceHourStart)) {
                continue; // exclude the reference hour itself (and anything newer)
            }

            double value = metricValue(trend, metric);
            tier3.add(value);

            ZonedDateTime bucketTime = bucket.atZone(zone);
            if (bucketTime.getHour() == referenceHour) {
                tier2.add(value);
                if (isWeekend(bucketTime.getDayOfWeek()) == referenceWeekend) {
                    tier1.add(value);
                }
            }
        }

        if (useWeekendBucket && tier1.size() >= TIER1_MIN_SAMPLES) {
            return build(tier1, 1);
        }
        if (tier2.size() >= TIER2_MIN_SAMPLES) {
            return build(tier2, 2);
        }
        if (tier3.size() >= TIER3_MIN_SAMPLES) {
            return build(tier3, 3);
        }
        return null;
    }

    /**
     * Computes mean and sample (n-1) standard deviation over the winning
     * tier's values. The n-1 denominator is guaranteed safe: every tier's
     * minimum sample count is well above 1.
     */
    private static Baseline build(List<Double> values, int tier) {
        int n = values.size();
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / n;

        double squaredDeviations = 0.0;
        for (double v : values) {
            double d = v - mean;
            squaredDeviations += d * d;
        }
        double stddev = Math.sqrt(squaredDeviations / (n - 1));

        return new Baseline(mean, stddev, n, tier);
    }

    /**
     * Selects the requested sum from a trend bucket. Unrecognized metric
     * names fall back to {@code received} (the most broadly meaningful
     * metric) with a warning instead of throwing — a typo in one monitor's
     * config must not abort a whole evaluator tick.
     */
    private static double metricValue(ActivityTrend trend, String metric) {
        String normalized = metric != null ? metric.trim().toLowerCase() : "received";
        switch (normalized) {
            case "received":
                return trend.getReceivedSum();
            case "sent":
                return trend.getSentSum();
            case "error":
                return trend.getErrorSum();
            default:
                log.warn("Unknown baseline metric '{}'; falling back to 'received'", metric);
                return trend.getReceivedSum();
        }
    }

    /** Weekend = Saturday or Sunday (in the zone the caller classified with). */
    private static boolean isWeekend(DayOfWeek day) {
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
