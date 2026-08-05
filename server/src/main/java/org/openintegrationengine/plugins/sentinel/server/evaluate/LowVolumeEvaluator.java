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
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;

/**
 * Evaluates LOW_VOLUME monitors: breach when received-message volume over a
 * sliding window falls below a threshold.
 *
 * <p>Config JSON: {@code {windowSeconds, compareTo: FIXED|BASELINE_RELATIVE,
 * minCount, baselinePercent, baselineLookbackDays}}. Two threshold modes:</p>
 *
 * <ul>
 *   <li><b>FIXED</b> — breach when the window's received sum is below a
 *       hand-picked {@code minCount}. Simple, predictable, right for feeds
 *       with a known contractual floor ("at least 10 ADTs an hour").</li>
 *   <li><b>BASELINE_RELATIVE</b> — breach when the window's received sum is
 *       below {@code baselinePercent}% of what {@link BaselineResolver} says
 *       is normal for this hour of day. The baseline mean is per-hour, so it
 *       is scaled by {@code windowSeconds / 3600} to compare like with like
 *       when the window is not exactly an hour. A null baseline (young
 *       channel, sparse history) yields INSUFFICIENT_DATA — never a breach
 *       against an invented threshold of zero.</li>
 * </ul>
 *
 * <p>Unknown {@code compareTo} values degrade to FIXED with a warning: FIXED
 * depends on nothing but the config itself, making it the only mode that can
 * never surprise an operator with baseline-driven behavior they did not ask
 * for.</p>
 *
 * <p>Value JSON shape: {@code {"current", "threshold", "mean", "sampleCount",
 * "tier"}} — the baseline fields are JSON {@code null} in FIXED mode and in
 * the INSUFFICIENT_DATA case, keeping one stable shape for the UI.</p>
 */
public final class LowVolumeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LowVolumeEvaluator.class);

    /** Default sliding window when the config omits {@code windowSeconds}. */
    private static final int DEFAULT_WINDOW_SECONDS = 3600;
    /** Default FIXED-mode floor: "at least one message in the window". */
    private static final long DEFAULT_MIN_COUNT = 1L;
    /** Default BASELINE_RELATIVE percentage of the baseline mean. */
    private static final double DEFAULT_BASELINE_PERCENT = 50.0;
    /** Default baseline lookback — 4 weeks captures a monthly work rhythm. */
    private static final int DEFAULT_BASELINE_LOOKBACK_DAYS = 28;

    private LowVolumeEvaluator() {
    }

    /**
     * Evaluates one channel against a LOW_VOLUME monitor.
     *
     * @param monitor   the monitor whose {@code configJson} supplies window
     *                  and threshold parameters
     * @param channelId the OIE channel id (a UUID string) to evaluate
     * @param now       the evaluation instant (passed in so every trigger in
     *                  a tick judges the same window)
     * @return BREACH when volume is below the effective threshold, OK when at
     *         or above it, INSUFFICIENT_DATA when the channel has not been
     *         continuously started for the whole window or when
     *         BASELINE_RELATIVE mode has no baseline to compare against yet
     */
    public static EvaluationOutcome evaluate(Monitor monitor, String channelId, Instant now) {
        JsonNode config = parseConfig(monitor.getConfigJson());
        int windowSeconds = Math.max(1, config.path("windowSeconds").asInt(DEFAULT_WINDOW_SECONDS));
        String compareTo = config.path("compareTo").asText("FIXED");

        ActivityAggregate aggregate = ActivityRepository.sumActivitySamplesForRange(
                channelId, now.minusSeconds(windowSeconds), now);
        // Verified never null (the query COALESCEs every aggregate); guard anyway.
        long current = aggregate != null ? aggregate.getReceivedSum() : 0L;

        // A window the channel was not running for all of cannot prove low
        // volume: the collector keeps writing zero-delta samples for
        // deployed-but-stopped channels, so a channel restarted after
        // maintenance would otherwise breach on its very first tick (same
        // "watched while running" rule as InactivityEvaluator). An
        // already-PROBLEM trigger keeps evaluating so a plugin restart —
        // which blanks the in-memory started stamp — cannot flap an open
        // problem into INSUFFICIENT_DATA and back.
        if (!CollectorState.getInstance().isChannelStartedThroughout(channelId, now.minusSeconds(windowSeconds))) {
            TriggerState state = TriggerStateRepository.getTriggerState(monitor.getId(), channelId, null);
            boolean alreadyProblem = state != null && state.getState() == TriggerStatus.PROBLEM;
            if (!alreadyProblem) {
                return EvaluationOutcome.insufficientData(buildValueJson(current, null, null, null, null));
            }
        }

        if ("BASELINE_RELATIVE".equalsIgnoreCase(compareTo)) {
            return evaluateBaselineRelative(config, channelId, now, windowSeconds, current);
        }
        if (!"FIXED".equalsIgnoreCase(compareTo)) {
            log.warn("Unknown LOW_VOLUME compareTo '{}' on monitor {}; treating as FIXED",
                    compareTo, monitor.getId());
        }
        return evaluateFixed(config, windowSeconds, current);
    }

    /** FIXED mode: compare the window sum against a hand-configured floor. */
    private static EvaluationOutcome evaluateFixed(JsonNode config, int windowSeconds, long current) {
        long minCount = config.path("minCount").asLong(DEFAULT_MIN_COUNT);
        String valueJson = buildValueJson(current, (double) minCount, null, null, null);

        if (current < minCount) {
            return EvaluationOutcome.breach(valueJson, String.format(
                    "Received %d messages in the last %ds (minimum %d)", current, windowSeconds, minCount));
        }
        return EvaluationOutcome.ok(valueJson);
    }

    /**
     * BASELINE_RELATIVE mode: threshold = {@code baselinePercent}% of the
     * baseline mean for the last completed hour, scaled to the window length.
     * The last completed hour (not the in-progress one) anchors the baseline
     * because it is the most recent bucket whose seasonality classification
     * (hour-of-day, weekend) is fully determined.
     */
    private static EvaluationOutcome evaluateBaselineRelative(JsonNode config, String channelId,
            Instant now, int windowSeconds, long current) {
        double baselinePercent = config.path("baselinePercent").asDouble(DEFAULT_BASELINE_PERCENT);
        int lookbackDays = Math.max(1, config.path("baselineLookbackDays").asInt(DEFAULT_BASELINE_LOOKBACK_DAYS));

        Instant lastCompletedHourStart = now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        BaselineResolver.Baseline baseline = BaselineResolver.resolve(
                channelId, lastCompletedHourStart, "received", lookbackDays, true);

        if (baseline == null) {
            return EvaluationOutcome.insufficientData(buildValueJson(current, null, null, null, null));
        }

        double threshold = baseline.mean * baselinePercent / 100.0 * (windowSeconds / 3600.0);
        String valueJson = buildValueJson(current, threshold, baseline.mean,
                baseline.sampleCount, baseline.tier);

        if (current < threshold) {
            return EvaluationOutcome.breach(valueJson, String.format(
                    "Received %d messages in the last %ds, below %.0f%% of baseline (threshold %.1f)",
                    current, windowSeconds, baselinePercent, threshold));
        }
        return EvaluationOutcome.ok(valueJson);
    }

    /**
     * Value JSON per the contract shape: {@code {current, threshold, mean,
     * sampleCount, tier}}. Boxed parameters so absent baseline fields render
     * as JSON null instead of a misleading zero.
     */
    private static String buildValueJson(long current, Double threshold, Double mean,
            Integer sampleCount, Integer tier) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("current", current);
        if (threshold != null) {
            node.put("threshold", threshold);
        } else {
            node.putNull("threshold");
        }
        if (mean != null) {
            node.put("mean", mean);
        } else {
            node.putNull("mean");
        }
        if (sampleCount != null) {
            node.put("sampleCount", sampleCount);
        } else {
            node.putNull("sampleCount");
        }
        if (tier != null) {
            node.put("tier", tier);
        } else {
            node.putNull("tier");
        }
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
            log.warn("Unparsable LOW_VOLUME config '{}'; using defaults", configJson, e);
            return Json.mapper().createObjectNode();
        }
    }
}
