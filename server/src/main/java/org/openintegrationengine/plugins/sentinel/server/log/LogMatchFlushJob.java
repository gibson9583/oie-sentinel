/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.MatchSample;
import org.openintegrationengine.plugins.sentinel.server.db.MatchSampleRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;

/**
 * Writes this node's Log monitor counts to {@code sentinel_match_sample}, one
 * row per enabled monitor per run, at the collector interval, and this node's
 * each-occurrence notifications to {@code sentinel_alert_event}. It runs on
 * every node with no leadership check: each node counts its own log, the
 * leader's evaluator sums the nodes, and the leader's outbox pass delivers
 * the notifications.
 *
 * <p>A row, zero counts included, says "this node watched". It is marked
 * incomplete when lines may have gone uncounted in its interval (see
 * {@link LogPipeline#takeComplete()}, plus timed-out pattern checks and a
 * detached appender or stuck matcher). After a failed write, everything
 * drained waits in memory for the next run. Notifications are written first:
 * if they fail nothing is written, and if the counts fail only the counts
 * wait. A graceful stop writes once more, through {@link #flushOnStop}, and
 * nothing waits after that.</p>
 *
 * <p>Matched lines are stored as a JSON array, oldest first, of
 * {@code {"time", "node", "level", "logger", "message"}}, {@code time} being
 * ISO-8601 UTC with milliseconds. Each row also stores its count per second
 * of the lines' own time, a JSON object of epoch second to count, so the
 * evaluator can tell which lines fall inside a window even when a row was
 * written late.</p>
 */
@DisallowConcurrentExecution
public class LogMatchFlushJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(LogMatchFlushJob.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /** Why the previous run wrote nothing, so the warning comes once per change. */
    private static volatile String lastNotCounting;

    /** Never throws: Quartz would only log it, and the schedule must keep firing. */
    @Override
    public void execute(JobExecutionContext context) {
        try {
            flush(LogPipeline.get(), SentinelLeadership.nodeId());
        } catch (Throwable t) {
            log.error("Log events flush failed", t);
        }
    }

    static void flush(LogPipeline pipeline, String nodeId) {
        try {
            if (LogEventAppender.ensureAttached()) {
                pipeline.markIncomplete();
                log.warn("Sentinel's log appender was missing from the engine's log4j configuration, probably after "
                        + "a reconfiguration, and has been put back. Lines logged in between were not counted.");
            }
        } catch (Exception e) {
            log.warn("Could not check Sentinel's log appender", e);
        }

        List<Monitor> monitors;
        try {
            monitors = MonitorRepository.listMonitors(null, MonitorType.LOG_EVENTS, Boolean.TRUE, null);
        } catch (Exception e) {
            log.warn("Could not read Log monitors; counting goes on with the previous ones and this flush writes "
                    + "nothing", e);
            reportProblems(pipeline, nodeId);
            return;
        }
        pipeline.refresh(monitors);
        // A save resizes only the node that served it; every node applies the stored capacity here.
        int capacity = SettingsService.get().getLogQueueCapacity();
        if (capacity != pipeline.queue().capacity()) {
            pipeline.resize(capacity);
        }
        reportProblems(pipeline, nodeId);

        String notCounting = pipeline.notCountingReason();
        if (!Objects.equals(notCounting, lastNotCounting)) {
            lastNotCounting = notCounting;
            if (notCounting != null) {
                log.warn("Node {} marks its Log monitor counts incomplete until this clears: {}", nodeId, notCounting);
            }
        }
        boolean complete = pipeline.takeComplete() && notCounting == null;
        write(pipeline.counter(), monitors, nodeId, complete, false);
    }

    /**
     * The last flush, from a graceful stop once the appender is detached and
     * the matcher stopped: what was matched since the previous flush goes
     * through the same write as every run, and its notifications stay pending
     * for the leader's evaluator. Nothing here puts the appender back. The
     * rows are incomplete, because lines logged between the detach and the
     * row's time were not counted.
     */
    static void flushOnStop(LogPipeline pipeline, String nodeId) {
        List<Monitor> monitors;
        try {
            monitors = MonitorRepository.listMonitors(null, MonitorType.LOG_EVENTS, Boolean.TRUE, null);
        } catch (Exception e) {
            log.warn("Could not read Log monitors at the stop; the lines matched since the last flush are not written",
                    e);
            return;
        }
        pipeline.refresh(monitors);
        reportProblems(pipeline, nodeId);
        write(pipeline.counter(), monitors, nodeId, false, true);
    }

    private static void write(LogMatchCounter counter, List<Monitor> monitors, String nodeId, boolean complete,
            boolean stopping) {
        Map<Integer, LogMatchCounter.Drained> drained = counter.drain();
        if (drained.isEmpty()) {
            return;
        }
        // After the drain, so no counted line is later than its row.
        Instant now = Instant.now();
        List<MatchSample> samples = new ArrayList<>(drained.size());
        List<AlertEvent> notifications = new ArrayList<>();
        Map<Integer, Monitor> byId = new HashMap<>();
        monitors.forEach(monitor -> byId.put(monitor.getId(), monitor));
        for (Map.Entry<Integer, LogMatchCounter.Drained> entry : drained.entrySet()) {
            LogMatchCounter.Drained value = entry.getValue();
            samples.add(new MatchSample(null, nodeId, entry.getKey(), null, now, value.count(),
                    value.samples().isEmpty() ? null : samplesJson(value.samples(), nodeId),
                    complete && value.complete(), secondCountsJson(value.seconds())));
            Monitor monitor = byId.get(entry.getKey());
            LogEventsConfig config = monitor == null ? null
                    : LogEventsConfig.lenient(monitor.getId(), monitor.getConfigJson());
            if (config != null && config.notifiesEachOccurrence()) {
                notifications.addAll(LogNotifications.forOccurrences(monitor, config, value, nodeId, now));
            }
        }
        // The drain consumed the pipeline's completeness, so what a failed write
        // puts back carries each row's own: the retried row is no more complete.
        Map<Integer, LogMatchCounter.Drained> back = new HashMap<>();
        drained.forEach((id, value) -> back.put(id, value.withComplete(complete && value.complete())));
        try {
            LogNotifications.insert(notifications);
        } catch (Exception e) {
            if (stopping) {
                log.error("Failed to write {} Log notification(s) at the stop; they and this flush's counts are lost",
                        notifications.size(), e);
                return;
            }
            counter.recredit(back);
            log.error("Failed to write {} Log notification(s); they and this flush's counts are kept for the next "
                    + "flush", notifications.size(), e);
            return;
        }
        try {
            MatchSampleRepository.insertSamples(samples);
        } catch (Exception e) {
            if (stopping) {
                log.error("Failed to write {} log match sample(s) at the stop; their counts are lost", samples.size(),
                        e);
                return;
            }
            Map<Integer, LogMatchCounter.Drained> counts = new HashMap<>();
            back.forEach((id, value) -> counts.put(id, value.countsOnly()));
            counter.recredit(counts);
            log.error("Failed to write {} log match sample(s); their counts are kept for the next flush",
                    samples.size(), e);
        }
    }

    /** @return {@code {"epochSecond": count, ...}} in second order, or {@code null} when nothing matched */
    static String secondCountsJson(SortedMap<Long, Long> seconds) {
        if (seconds.isEmpty()) {
            return null;
        }
        ObjectNode counts = Json.mapper().createObjectNode();
        seconds.forEach((second, count) -> counts.put(String.valueOf(second), count));
        return Json.write(counts);
    }

    static String samplesJson(List<LogMatchCounter.MatchedLine> lines, String nodeId) {
        return Json.write(toJson(lines, nodeId));
    }

    static ArrayNode toJson(List<LogMatchCounter.MatchedLine> lines, String nodeId) {
        ArrayNode array = Json.mapper().createArrayNode();
        for (LogMatchCounter.MatchedLine line : lines) {
            ObjectNode node = array.addObject();
            node.put("time", TIME.format(Instant.ofEpochMilli(line.timeMillis())));
            node.put("node", nodeId);
            node.put("level", line.level());
            node.put("logger", line.logger());
            node.put("message", line.message());
        }
        return array;
    }

    private static void reportProblems(LogPipeline pipeline, String nodeId) {
        Map<Integer, Long> timedOut = pipeline.counter().drainTimedOut();
        long internalErrors = pipeline.counter().drainInternalErrors() + pipeline.queue().drainInternalErrors();
        if (internalErrors > 0) {
            pipeline.markIncomplete();
        }
        if (timedOut.isEmpty() && internalErrors == 0) {
            return;
        }
        log.warn("Log events on node {} since the last flush: pattern checks stopped at the {} ms limit, by monitor "
                        + "id: {}; {} log line(s) not checked because of an internal error", nodeId,
                LogMatcher.TIME_LIMIT_NANOS / 1_000_000, timedOut, internalErrors);
    }
}
