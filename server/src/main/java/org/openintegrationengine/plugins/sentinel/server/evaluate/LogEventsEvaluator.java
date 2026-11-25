/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.MatchSample;
import org.openintegrationengine.plugins.sentinel.server.db.MatchSampleRepository;
import org.openintegrationengine.plugins.sentinel.server.log.LogEventsConfig;
import org.openintegrationengine.plugins.sentinel.server.log.LogMatchCounter;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/**
 * Evaluates a Log monitor's condition over its window, summed over every
 * node's rows in {@code sentinel_match_sample}. BREACH means the condition is
 * true. A row's count covers the interval since that node's previous row.
 *
 * <p>The window starts at the start of the second {@code now - window} falls
 * in, and only lines whose own second is at or after it count, including
 * lines in a row written late (see {@link LogWindowCount}). It is
 * <b>complete</b> when {@link LogWindowCount} says so: every node that was
 * counting has rows from the window's start up to now with no long gap, every
 * row in it is complete (no dropped lines, no detached appender, no timed-out
 * check, no restart), and every row's lines are placed. A count is positive
 * evidence however incomplete the rows: "at least N" breaches on it, and
 * "at most N" is false once the count exceeds N. Declaring "at least N" false
 * (which clears a problem) or "at most N" true needs a complete window;
 * otherwise the verdict is INSUFFICIENT_DATA, which neither opens nor clears.
 * Each occurrence, as a problem, is "at least 1".</p>
 *
 * <p>Rows written before the monitor was last saved are left out: saving
 * starts the count afresh, so lines the old definition matched never count
 * under the new one. Each node also drops its unwritten tally at the save.
 * A window is complete only once a node's rows cover it from its start, so
 * after a save a window must pass before a low count is evidence.</p>
 *
 * <p>The window is clamped at run time to between the evaluator interval and
 * the sample retention, so settings changed after the monitor was saved
 * neither skip lines between evaluations nor ask for rows already pruned.
 * The value JSON shows the window used.</p>
 *
 * <p>Value JSON: {@code {"condition", "count", "threshold", "windowSeconds",
 * "perNode": {nodeId: count}}}, plus {@code "lastBreach"} on a breach and
 * {@code "matches"} (the newest {@link LogMatchCounter#MAX_SAMPLES} lines,
 * oldest first) when the monitor includes them.</p>
 */
public final class LogEventsEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LogEventsEvaluator.class);

    /**
     * How many collector intervals a node's rows may be apart while it counts
     * as watching: before the window's start, between two rows, and before now.
     */
    static final int COVERAGE_INTERVALS = 2;

    private LogEventsEvaluator() {
    }

    public static EvaluationOutcome evaluate(Monitor monitor, Instant now) {
        LogEventsConfig config = LogEventsConfig.lenient(monitor.getId(), monitor.getConfigJson());
        if (config == null) {
            ObjectNode invalid = Json.mapper().createObjectNode();
            invalid.put("invalidConfig", true);
            return EvaluationOutcome.insufficientData(Json.write(invalid));
        }
        SentinelSettings settings = SettingsService.get();
        long windowSeconds = windowSeconds(config, settings);
        Instant start = now.minusSeconds(windowSeconds).truncatedTo(ChronoUnit.SECONDS);
        int collectorSeconds = settings.getCollectorIntervalSeconds();
        // Rows from before the window too: a row's interval starts at its node's previous row.
        Instant read = countedFrom(monitor, start.minusSeconds(COVERAGE_INTERVALS * (long) collectorSeconds));

        LogWindowCount window = LogWindowCount.of(
                MatchSampleRepository.listCountRows(monitor.getId(), read, now), start, now, collectorSeconds);
        long count = window.count();
        boolean complete = window.complete();
        ObjectNode perNode = Json.mapper().createObjectNode();
        window.perNode().forEach(perNode::put);

        LogEventsConfig.Condition condition = config.condition();
        int threshold = condition == LogEventsConfig.Condition.EACH ? 1 : config.threshold();
        boolean atMost = condition == LogEventsConfig.Condition.AT_MOST;
        Boolean breached = atMost
                ? (count > threshold ? Boolean.FALSE : complete ? Boolean.TRUE : null)
                : (count >= threshold ? Boolean.TRUE : complete ? Boolean.FALSE : null);

        ObjectNode value = Json.mapper().createObjectNode();
        value.put("condition", condition.name());
        value.put("count", count);
        value.put("threshold", threshold);
        value.put("windowSeconds", windowSeconds);
        value.set("perNode", perNode);
        if (Boolean.TRUE.equals(breached)) {
            value.put("lastBreach", now.toString());
        }
        if (config.includeMatches()) {
            value.set("matches", recentMatches(monitor.getId(), window.perNode(), start, countedFrom(monitor, start),
                    now));
        }
        String valueJson = Json.write(value);

        if (breached == null) {
            return EvaluationOutcome.insufficientData(valueJson);
        }
        if (!breached) {
            return EvaluationOutcome.ok(valueJson);
        }
        String bound = condition == LogEventsConfig.Condition.EACH ? ""
                : String.format(" (%s %d)", atMost ? "at most" : "at least", threshold);
        return EvaluationOutcome.breach(valueJson, String.format("%d matching log %s in the last %ds%s",
                count, count == 1 ? "line" : "lines", windowSeconds, bound));
    }

    /**
     * Where the counted rows start: the window's start, or the monitor's last
     * save (its updated time, which a create sets too) when that is later.
     * Assumes the nodes' clocks agree with the one that served the save to
     * within a collector interval.
     */
    static Instant countedFrom(Monitor monitor, Instant from) {
        Instant saved = monitor.getUpdatedTime();
        return saved != null && saved.isAfter(from) ? saved : from;
    }

    /** The configured window, clamped to the current evaluator interval and sample retention. */
    static long windowSeconds(LogEventsConfig config, SentinelSettings settings) {
        long retention = settings.getSampleRetentionDays() * 86_400L;
        return Math.min(retention, Math.max(config.windowSeconds(), settings.getEvaluatorIntervalSeconds()));
    }

    /**
     * The newest lines in the window across all nodes, oldest first. Each
     * node's newest rows with lines hold its newest lines, so reading that
     * many rows per node with matches is enough. A line older than the
     * window's start, from a row that straddles it, is left out.
     */
    private static ArrayNode recentMatches(int monitorId, Map<String, Long> perNode, Instant start, Instant from,
            Instant now) {
        List<JsonNode> lines = new ArrayList<>();
        for (Map.Entry<String, Long> node : perNode.entrySet()) {
            if (node.getValue() <= 0) {
                continue;
            }
            for (MatchSample sample : MatchSampleRepository.listRecentSamples(
                    monitorId, node.getKey(), from, now, LogMatchCounter.MAX_SAMPLES)) {
                try {
                    JsonNode stored = Json.mapper().readTree(sample.samplesJson());
                    if (stored != null && stored.isArray()) {
                        for (JsonNode line : stored) {
                            if (!lineTime(line).isBefore(start)) {
                                lines.add(line);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Skipping unreadable matched lines in match sample {}", sample.id(), e);
                }
            }
        }
        // Stable, so lines with the same millisecond keep their stored order.
        lines.sort(Comparator.comparing(LogEventsEvaluator::lineTime));
        ArrayNode newest = Json.mapper().createArrayNode();
        lines.subList(Math.max(0, lines.size() - LogMatchCounter.MAX_SAMPLES), lines.size()).forEach(newest::add);
        return newest;
    }

    /** An unreadable time sorts first, so it is the first dropped. */
    private static Instant lineTime(JsonNode line) {
        try {
            return Instant.parse(line.path("time").asText());
        } catch (Exception e) {
            return Instant.MIN;
        }
    }
}
