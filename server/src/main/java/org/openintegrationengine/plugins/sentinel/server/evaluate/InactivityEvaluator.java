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
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Evaluates INACTIVITY monitors: breach when a channel has received no
 * messages for a configured duration.
 *
 * <p>Config JSON: {@code {noDataForSeconds}}. A missing/unparsable value
 * falls back to one hour — a defensible "something is probably wrong" window
 * for a typical HL7 feed — rather than failing the tick; the effective window
 * is always echoed in the value JSON so the operator can see what was
 * actually applied.</p>
 *
 * <p>A zero total proves inactivity only when durable samples cover the
 * entire window and the channel was continuously STARTED. Missing samples,
 * an internal collection gap, or a stale tail yield INSUFFICIENT_DATA. The
 * trigger state machine preserves existing incidents while measurement is
 * unavailable; it does not invent a new breach or resolve an open incident.</p>
 *
 * <p>Value JSON shape: {@code {"receivedInWindow": n, "windowSeconds": x}}.</p>
 */
public final class InactivityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(InactivityEvaluator.class);

    /** Default alerting window when the config omits {@code noDataForSeconds}. */
    private static final int DEFAULT_NO_DATA_FOR_SECONDS = 3600;

    private InactivityEvaluator() {
    }

    /**
     * Evaluates one channel against an INACTIVITY monitor.
     *
     * @param monitor   the monitor whose {@code configJson} supplies
     *                  {@code noDataForSeconds}
     * @param channelId the OIE channel id (a UUID string) to evaluate
     * @param now       the evaluation instant (passed in, not read from the
     *                  clock, so every trigger in a tick judges the same
     *                  window)
     * @return BREACH when the window is provably message-free, OK when
     *         anything was received, INSUFFICIENT_DATA when the collector has
     *         not covered the whole window yet
     */
    public static EvaluationOutcome evaluate(Monitor monitor, String channelId, Instant now) {
        JsonNode config = parseConfig(monitor.getConfigJson());
        int windowSeconds = Math.max(1, config.path("noDataForSeconds").asInt(DEFAULT_NO_DATA_FOR_SECONDS));
        Instant windowStart = now.minusSeconds(windowSeconds);

        ActivityAggregate aggregate = ActivityRepository.sumActivitySamplesForRange(channelId, windowStart, now);
        // Verified never null (the query COALESCEs every aggregate), but the
        // guard costs nothing and a null here must mean "no samples", not NPE.
        long received = aggregate != null ? aggregate.getReceivedSum() : 0L;

        String valueJson = buildValueJson(received, windowSeconds);
        if (received > 0) {
            return EvaluationOutcome.ok(valueJson);
        }

        if (!CollectorState.getInstance().isChannelStartedThroughout(channelId, windowStart)
                || !ActivityCoverage.covers(channelId, windowStart, now)) {
            return EvaluationOutcome.insufficientData(valueJson);
        }

        return EvaluationOutcome.breach(valueJson,
                String.format("No messages received in %ds", windowSeconds));
    }

    /** Value JSON per the contract shape: {@code {receivedInWindow, windowSeconds}}. */
    private static String buildValueJson(long received, int windowSeconds) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put("receivedInWindow", received);
        node.put("windowSeconds", windowSeconds);
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
            log.warn("Unparsable INACTIVITY config '{}'; using defaults", configJson, e);
            return Json.mapper().createObjectNode();
        }
    }
}
