/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Evaluates ANOMALY monitors: breach when a metric's volume for the last
 * completed hour deviates from its historical hour-of-day baseline beyond a
 * z-score threshold.
 *
 * <p>Config JSON: {@code {metric: RECEIVED|SENT|ERROR, zScoreThreshold,
 * direction: LOW_ONLY|HIGH_ONLY|BOTH, baselineWindowDays, useWeekendBucket}}.</p>
 *
 * <p>Evaluation always targets the <b>last completed hour</b> (now truncated
 * to the hour, minus one hour) rather than a sliding window ending at
 * {@code now}: a partial in-progress hour would systematically read low
 * against a baseline of full hours and produce false LOW anomalies every
 * hour, worst at minute one. The hour's current value is summed from raw
 * samples (still comfortably present — sample retention is days, the bucket
 * is one hour old at most), so evaluation does not have to wait for the
 * hourly rollup job to materialize the bucket. The upper bound is pulled one
 * millisecond inside the next hour because the repository range is inclusive
 * on both ends — a sample landing exactly on the boundary belongs to the next
 * bucket, not both.</p>
 *
 * <p>Deviation logic, shared intuition with {@link BaselineResolver}'s
 * tiering: normally {@code z = (current - mean) / stddev} compared against
 * the threshold per {@code direction}. When the historical baseline is flat
 * (stddev &asymp; 0 — e.g. a channel that received exactly 100 messages every
 * hour for weeks), any deviation at all would otherwise produce an infinite
 * z-score and instant alerts, so a tolerance band is used instead: breach
 * only when |current − mean| exceeds max(1, 10% of mean), with the sign of
 * the deviation still respecting {@code direction}.</p>
 *
 * <p>Value JSON shape: {@code {"current", "mean", "stddev", "z", "tier",
 * "sampleCount", "hourBucket"}} — baseline fields are JSON null when no
 * baseline resolved; {@code z} is JSON null on the flat-baseline path where
 * a z-score is undefined.</p>
 */
public final class AnomalyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AnomalyEvaluator.class);

    /** Default z-score threshold — the classic three-sigma rule. */
    private static final double DEFAULT_Z_SCORE_THRESHOLD = 3.0;
    /** Default baseline lookback — 4 weeks captures a monthly work rhythm. */
    private static final int DEFAULT_BASELINE_WINDOW_DAYS = 28;
    /** Below this, the baseline is considered flat and z is undefined. */
    private static final double FLAT_STDDEV_EPSILON = 1e-9;

    private AnomalyEvaluator() {
    }

    /**
     * Evaluates one channel against an ANOMALY monitor.
     *
     * @param monitor   the monitor whose {@code configJson} supplies metric,
     *                  threshold, direction, and baseline parameters
     * @param channelId the OIE channel id (a UUID string) to evaluate
     * @param now       the evaluation instant (passed in so every trigger in
     *                  a tick judges the same hour bucket)
     * @return BREACH when the last completed hour deviates beyond the
     *         threshold in an alertable direction, OK when within bounds,
     *         INSUFFICIENT_DATA when no baseline tier has enough history yet
     */
    public static EvaluationOutcome evaluate(Monitor monitor, String channelId, Instant now) {
        JsonNode config = parseConfig(monitor.getConfigJson());
        String metric = config.path("metric").asText("RECEIVED");
        double zScoreThreshold = config.path("zScoreThreshold").asDouble(DEFAULT_Z_SCORE_THRESHOLD);
        String direction = normalizeDirection(config.path("direction").asText("BOTH"), monitor);
        int baselineWindowDays = Math.max(1, config.path("baselineWindowDays").asInt(DEFAULT_BASELINE_WINDOW_DAYS));
        boolean useWeekendBucket = config.path("useWeekendBucket").asBoolean(true);

        Instant hourStart = now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        Instant hourEnd = hourStart.plus(1, ChronoUnit.HOURS);

        ActivityAggregate aggregate = ActivityRepository.sumActivitySamplesForRange(
                channelId, hourStart, hourEnd.minusMillis(1));
        long current = metricSum(aggregate, metric);

        BaselineResolver.Baseline baseline = BaselineResolver.resolve(
                channelId, hourStart, metric, baselineWindowDays, useWeekendBucket);
        if (baseline == null) {
            return EvaluationOutcome.insufficientData(
                    buildValueJson(current, null, null, null, null, null, hourStart));
        }

        double deviation = current - baseline.mean;

        if (baseline.stddev < FLAT_STDDEV_EPSILON) {
            // Flat baseline: z is undefined; use the tolerance band instead.
            double band = Math.max(1.0, baseline.mean * 0.10);
            boolean breach = Math.abs(deviation) > band && directionAllows(direction, deviation);
            String valueJson = buildValueJson(current, baseline.mean, baseline.stddev, null,
                    baseline.tier, baseline.sampleCount, hourStart);
            if (breach) {
                return EvaluationOutcome.breach(valueJson, String.format(
                        "%s volume %d for hour %s deviates from flat baseline mean %.1f by more than %.1f",
                        metricLabel(metric), current, hourStart, baseline.mean, band));
            }
            return EvaluationOutcome.ok(valueJson);
        }

        double z = deviation / baseline.stddev;
        boolean breach;
        switch (direction) {
            case "LOW_ONLY":
                breach = z < -zScoreThreshold;
                break;
            case "HIGH_ONLY":
                breach = z > zScoreThreshold;
                break;
            default: // BOTH
                breach = Math.abs(z) > zScoreThreshold;
                break;
        }

        String valueJson = buildValueJson(current, baseline.mean, baseline.stddev, z,
                baseline.tier, baseline.sampleCount, hourStart);
        if (breach) {
            return EvaluationOutcome.breach(valueJson, String.format(
                    "%s volume %d for hour %s is %.2f standard deviations from baseline mean %.1f (threshold %.2f)",
                    metricLabel(metric), current, hourStart, z, baseline.mean, zScoreThreshold));
        }
        return EvaluationOutcome.ok(valueJson);
    }

    /**
     * Whether a deviation's sign is alertable under the configured direction.
     * Used only on the flat-baseline path — the z-score path folds direction
     * into the comparison itself.
     */
    private static boolean directionAllows(String direction, double deviation) {
        switch (direction) {
            case "LOW_ONLY":
                return deviation < 0;
            case "HIGH_ONLY":
                return deviation > 0;
            default: // BOTH
                return true;
        }
    }

    /**
     * Normalizes the configured direction, degrading unknown values to BOTH
     * (the only choice that can never silently ignore a real deviation) with
     * a warning.
     */
    private static String normalizeDirection(String direction, Monitor monitor) {
        String normalized = direction != null ? direction.trim().toUpperCase() : "BOTH";
        switch (normalized) {
            case "LOW_ONLY":
            case "HIGH_ONLY":
            case "BOTH":
                return normalized;
            default:
                log.warn("Unknown ANOMALY direction '{}' on monitor {}; treating as BOTH",
                        direction, monitor.getId());
                return "BOTH";
        }
    }

    /**
     * Selects the configured metric's sum from the hour aggregate.
     * Unrecognized metric names fall back to received, mirroring
     * {@link BaselineResolver}'s fallback so current value and baseline are
     * always drawn from the same metric.
     */
    private static long metricSum(ActivityAggregate aggregate, String metric) {
        if (aggregate == null) {
            // Verified never null (the query COALESCEs every aggregate); defensive only.
            return 0L;
        }
        String normalized = metric != null ? metric.trim().toLowerCase() : "received";
        switch (normalized) {
            case "sent":
                return aggregate.getSentSum();
            case "error":
                return aggregate.getErrorSum();
            default:
                return aggregate.getReceivedSum();
        }
    }

    /** Human label for messages, e.g. "Received"/"Sent"/"Error". */
    private static String metricLabel(String metric) {
        String normalized = metric != null ? metric.trim().toLowerCase() : "received";
        switch (normalized) {
            case "sent":
                return "Sent";
            case "error":
                return "Error";
            default:
                return "Received";
        }
    }

    /**
     * Value JSON per the contract shape: {@code {current, mean, stddev, z,
     * tier, sampleCount, hourBucket}}. Boxed parameters so absent fields
     * render as JSON null; {@code hourBucket} is the ISO-8601 instant of the
     * evaluated bucket's start so the UI can label exactly which hour fired.
     */
    private static String buildValueJson(long current, Double mean, Double stddev, Double z,
            Integer tier, Integer sampleCount, Instant hourBucket) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("current", current);
        if (mean != null) {
            node.put("mean", mean);
        } else {
            node.putNull("mean");
        }
        if (stddev != null) {
            node.put("stddev", stddev);
        } else {
            node.putNull("stddev");
        }
        if (z != null) {
            node.put("z", z);
        } else {
            node.putNull("z");
        }
        if (tier != null) {
            node.put("tier", tier);
        } else {
            node.putNull("tier");
        }
        if (sampleCount != null) {
            node.put("sampleCount", sampleCount);
        } else {
            node.putNull("sampleCount");
        }
        node.put("hourBucket", hourBucket.toString());
        return Json.write(node);
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
            log.warn("Unparsable ANOMALY config '{}'; using defaults", configJson, e);
            return Json.mapper().createObjectNode();
        }
    }
}
