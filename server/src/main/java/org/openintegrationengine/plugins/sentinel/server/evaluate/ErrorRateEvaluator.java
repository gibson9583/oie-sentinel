/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Evaluates ERROR_RATE monitors: breach when the proportion of errored
 * messages over a sliding window reaches a configured percentage.
 *
 * <p>Config JSON: {@code {thresholdPercent, windowSeconds, minMessages}}. The
 * rate is {@code errorSum / receivedSum × 100} over the window, both sums
 * taken from the collector's per-tick deltas — the same rows LOW_VOLUME reads,
 * so no new query and no new column are involved.</p>
 *
 * <h2>Why {@code minMessages} produces INSUFFICIENT_DATA, not OK</h2>
 *
 * <p>A ratio computed over a handful of messages is not a rate, it is a
 * coincidence. One error on a channel that received one message in the window
 * is 100%, and on a quiet interface feed that single reading would page
 * someone every time a stray message failed. {@code minMessages} is the floor
 * of statistical meaning: below it the window is not judged.</p>
 *
 * <p>The verdict for a below-floor window is deliberately
 * {@code INSUFFICIENT_DATA} rather than {@code OK}, and the distinction is
 * load-bearing rather than cosmetic. {@code OK} is an assertion that the
 * condition was checked and holds — it resolves an open problem and clears the
 * hysteresis counter. An unmeasurable channel is not a healthy one: if a feed
 * degrades until it is passing almost nothing, its error rate stops being
 * computable at precisely the moment its errors matter most, and reporting
 * {@code OK} there would resolve a live problem on the strength of having
 * stopped looking. {@code INSUFFICIENT_DATA} freezes the trigger instead,
 * leaving any open alert open until a window with real traffic can either
 * confirm or clear it (see {@link EvaluationOutcome}).</p>
 *
 * <p>A zero {@code receivedSum} is handled explicitly for the same reason and
 * one further one: the ratio is undefined, not zero. It is checked ahead of
 * the {@code minMessages} comparison so that a config of
 * {@code minMessages = 0} — which an operator may legitimately set to mean
 * "no floor" — still cannot reach a division by zero.</p>
 *
 * <p>Errors are not clamped to the received count. A destination can error on
 * a message received in an earlier window, and one message can fail on several
 * destinations, so the computed percentage may legitimately exceed 100. That
 * is reported verbatim rather than capped: an operator reading "180% error
 * rate" learns something real about the backlog draining through a broken
 * destination, whereas a capped "100%" hides it.</p>
 *
 * <p>Unlike {@link LowVolumeEvaluator} and {@link InactivityEvaluator}, this
 * evaluator does not require the collector to have watched the channel for the
 * whole window. Those two infer a breach from the <em>absence</em> of traffic,
 * which an uncovered window fakes perfectly; a ratio is self-normalizing, so a
 * half-covered window simply yields the rate over the half that was observed,
 * and {@code minMessages} already rejects the case where too little was seen
 * to matter.</p>
 *
 * <p>Value JSON shape: {@code {"errorCount", "receivedCount", "errorPercent",
 * "thresholdPercent", "windowSeconds", "minMessages"}}, one stable shape for
 * the UI. {@code errorPercent} is JSON {@code null} only when the window
 * received nothing and no rate exists at all; a below-floor window still
 * reports the ratio it saw (alongside the counts that explain why it was not
 * judged), because "3 of 4 messages errored, floor is 20" is exactly the
 * context an operator needs to decide whether the floor is set right.</p>
 */
public final class ErrorRateEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ErrorRateEvaluator.class);

    /** Default sliding window when the config omits {@code windowSeconds}. */
    private static final int DEFAULT_WINDOW_SECONDS = 3600;

    /**
     * Default breach percentage. 10% is high enough that a healthy feed with
     * occasional bad payloads does not page, and low enough that a genuinely
     * failing destination is caught well before it becomes a total outage.
     */
    private static final double DEFAULT_THRESHOLD_PERCENT = 10.0;

    /**
     * Default message floor for the window. Twenty messages makes the
     * finest-grained rate the monitor can report 5%, which is below every
     * sensible threshold — so the floor never suppresses a breach it should
     * have caught, while still refusing to read a single failure as 100%.
     */
    private static final long DEFAULT_MIN_MESSAGES = 20L;

    private ErrorRateEvaluator() {
    }

    /**
     * Evaluates one channel against an ERROR_RATE monitor.
     *
     * @param monitor   the monitor whose {@code configJson} supplies the
     *                  window, threshold percentage and message floor
     * @param channelId the OIE channel id (a UUID string) to evaluate
     * @param now       the evaluation instant (passed in so every trigger in
     *                  a tick judges the same window)
     * @return BREACH when the error percentage is at or above
     *         {@code thresholdPercent}, OK when it is below,
     *         INSUFFICIENT_DATA when the window received nothing (the rate is
     *         undefined) or fewer than {@code minMessages} messages (the rate
     *         is not yet meaningful)
     */
    public static EvaluationOutcome evaluate(Monitor monitor, String channelId, Instant now) {
        JsonNode config = parseConfig(monitor.getConfigJson());
        int windowSeconds = Math.max(1, config.path("windowSeconds").asInt(DEFAULT_WINDOW_SECONDS));
        double thresholdPercent = config.path("thresholdPercent").asDouble(DEFAULT_THRESHOLD_PERCENT);
        long minMessages = Math.max(0L, config.path("minMessages").asLong(DEFAULT_MIN_MESSAGES));

        ActivityAggregate aggregate = ActivityRepository.sumActivitySamplesForRange(
                channelId, now.minusSeconds(windowSeconds), now);
        // Verified never null (the query COALESCEs every aggregate); guard anyway.
        long received = aggregate != null ? aggregate.getReceivedSum() : 0L;
        long errors = aggregate != null ? aggregate.getErrorSum() : 0L;

        // Checked before minMessages so a "no floor" config (minMessages = 0)
        // still cannot divide by zero. Nothing received means the rate is
        // undefined, not 0% — see the class Javadoc.
        if (received <= 0L) {
            return EvaluationOutcome.insufficientData(buildValueJson(
                    errors, received, null, thresholdPercent, windowSeconds, minMessages));
        }
        if (received < minMessages) {
            return EvaluationOutcome.insufficientData(buildValueJson(
                    errors, received, 100.0 * errors / received, thresholdPercent,
                    windowSeconds, minMessages));
        }

        double percent = 100.0 * errors / received;
        String valueJson = buildValueJson(errors, received, percent, thresholdPercent,
                windowSeconds, minMessages);

        if (percent >= thresholdPercent) {
            return EvaluationOutcome.breach(valueJson, String.format(
                    "%d of %d messages errored in the last %ds (%.1f%%, threshold %.1f%%)",
                    errors, received, windowSeconds, percent, thresholdPercent));
        }
        return EvaluationOutcome.ok(valueJson);
    }

    /**
     * Value JSON per the contract shape: {@code {errorCount, receivedCount,
     * errorPercent, thresholdPercent, windowSeconds, minMessages}}.
     * {@code errorPercent} is boxed so the undefined case renders as JSON null
     * instead of a misleading 0.0 — the UI must be able to tell "no errors"
     * from "no rate".
     */
    private static String buildValueJson(long errors, long received, Double percent,
            double thresholdPercent, int windowSeconds, long minMessages) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("errorCount", errors);
        node.put("receivedCount", received);
        if (percent != null) {
            node.put("errorPercent", percent);
        } else {
            node.putNull("errorPercent");
        }
        node.put("thresholdPercent", thresholdPercent);
        node.put("windowSeconds", windowSeconds);
        node.put("minMessages", minMessages);
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
            log.warn("Unparsable ERROR_RATE config '{}'; using defaults", configJson, e);
            return Json.mapper().createObjectNode();
        }
    }
}
