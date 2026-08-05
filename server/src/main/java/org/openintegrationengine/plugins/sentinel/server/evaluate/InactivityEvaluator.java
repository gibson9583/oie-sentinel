/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;

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
 * <p>The subtle part is telling "no messages arrived" apart from "nobody was
 * watching". Activity samples only exist while the collector job is running
 * and the channel is deployed, so a zero received-sum over the window proves
 * nothing if the collector only started watching mid-window (plugin just
 * installed, server restarted, channel just deployed). In that case the
 * verdict is {@code INSUFFICIENT_DATA}: coverage of the window is checked by
 * requiring at least one sample at or before the window start (queried with
 * one collector-interval of slack, sized to the maximum configurable
 * collector interval, so a slow collector cannot make a covered window look
 * uncovered).</p>
 *
 * <p>Sample coverage alone is still not enough: the collector deliberately
 * keeps sampling deployed-but-stopped channels (zero-delta rows, for
 * queue-depth continuity), so a channel stopped for maintenance and then
 * restarted has a fully "covered" window of zeros — breaching on the first
 * post-restart tick would be exactly the guaranteed false positive the
 * STARTED-only evaluation scope exists to prevent. The window must therefore
 * also fall entirely inside the channel's current continuous STARTED stretch
 * ({@link CollectorState#isChannelStartedThroughout}); until it does, the
 * verdict stays {@code INSUFFICIENT_DATA}. The stamp is in-memory, so after
 * a server restart detection of NEW problems is deferred by up to one window
 * — the already-PROBLEM carve-out below keeps existing problems breaching
 * through that gap.</p>
 *
 * <p>Exception: once the trigger is already in PROBLEM, a zero-sum window
 * keeps breaching even without proven coverage. Otherwise a server restart
 * would flip a genuinely dead channel's trigger from PROBLEM to
 * INSUFFICIENT_DATA and back on a cycle, and the operator would see flapping
 * instead of one continuous problem. The evaluator reads the trigger state
 * itself (rather than taking it as a parameter) to keep the shared evaluator
 * signature uniform across monitor types.</p>
 *
 * <p>Value JSON shape: {@code {"receivedInWindow": n, "windowSeconds": x}}.</p>
 */
public final class InactivityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(InactivityEvaluator.class);

    /** Default alerting window when the config omits {@code noDataForSeconds}. */
    private static final int DEFAULT_NO_DATA_FOR_SECONDS = 3600;

    /**
     * Slack added before the window start when probing for coverage. Sized to
     * the maximum collector interval SettingsService accepts (600s): with any
     * legal collector configuration, a collector that was truly watching
     * before the window began must have left at least one sample within this
     * many seconds before the window start.
     */
    private static final int COLLECTOR_SLACK_SECONDS = 600;

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

        // Zero received: only breach if we can prove the collector actually
        // covered the window AND the channel was running for all of it —
        // unless the trigger is already in PROBLEM, in which case we keep
        // breaching (see class Javadoc for why).
        TriggerState state = TriggerStateRepository.getTriggerState(monitor.getId(), channelId, null);
        boolean alreadyProblem = state != null && state.getState() == TriggerStatus.PROBLEM;

        if (!alreadyProblem
                && (!CollectorState.getInstance().isChannelStartedThroughout(channelId, windowStart)
                        || !windowIsCovered(channelId, windowStart, now))) {
            return EvaluationOutcome.insufficientData(valueJson);
        }

        return EvaluationOutcome.breach(valueJson,
                String.format("No messages received in %ds", windowSeconds));
    }

    /**
     * Determines whether the collector was watching this channel for the
     * whole window: true iff at least one sample exists at or before the
     * window start (probed with {@link #COLLECTOR_SLACK_SECONDS} of slack).
     * An empty result, or an oldest sample that is newer than the window
     * start, means collection began mid-window and a zero sum is not
     * evidence of inactivity.
     */
    private static boolean windowIsCovered(String channelId, Instant windowStart, Instant now) {
        List<ActivitySample> samples = ActivityRepository.listActivitySamples(
                channelId, windowStart.minusSeconds(COLLECTOR_SLACK_SECONDS), now);
        if (samples.isEmpty()) {
            return false;
        }
        Instant oldest = samples.get(0).getSampleTime();
        return oldest != null && !oldest.isAfter(windowStart);
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
