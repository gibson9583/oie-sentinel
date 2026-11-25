/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.db.MatchSample;
import org.openintegrationengine.plugins.sentinel.server.util.Json;

/**
 * What a Log monitor's count rows say about one window: how many matched
 * lines fall inside it, per node, and whether that count is complete.
 *
 * <p>The window ends where the rows end: at the oldest of the reporting
 * nodes' newest rows (see {@link #end}), at most about one collector interval
 * before now. A line is written in a row up to a collector interval after it
 * is logged, so a window that ended at now would miss the lines logged just
 * before each evaluation; ending at the rows, every line falls in some window.</p>
 *
 * <p>A row counts what its node matched since that node's previous row, and
 * every line in it is older than the row. Rows written since schema v15 carry
 * their count per second of the lines' own time, so only lines whose second
 * is from the window's first second to its last are counted, however late
 * the row was written. A row without per-second counts (written before v15,
 * or one whose lines fall in more than {@code LogMatchCounter.MAX_SECONDS}
 * seconds after long failed writes) is counted only when its whole interval,
 * from the node's previous row to the row, is inside the window; one wholly
 * after the window counts nothing, and any other is left out and makes the
 * window incomplete. A row without matches needs neither.</p>
 *
 * <p>A low count is evidence only when every node that was counting watched
 * the whole window. The nodes expected are those with a row in the window or
 * in the {@link LogEventsEvaluator#COVERAGE_INTERVALS} collector intervals
 * before it. Each must have a row at or before the window's start, within
 * that allowance before it; no two consecutive rows in the window further
 * apart than the allowance; and a newest row within the allowance of now.
 * The window is then <b>covered</b>, and it is <b>complete</b> when it is
 * covered, every row in it is complete, and every row's lines could be placed.
 * A node that stops writing therefore leaves windows incomplete until it has
 * been gone for a whole window, and a new monitor needs a full window of rows
 * before a zero count means anything.</p>
 */
final class LogWindowCount {

    private final long count;
    private final Map<String, Long> perNode;
    private final boolean complete;

    private LogWindowCount(long count, Map<String, Long> perNode, boolean complete) {
        this.count = count;
        this.perNode = Collections.unmodifiableMap(perNode);
        this.complete = complete;
    }

    /**
     * Where a window ends: the oldest newest-row time among the nodes still
     * reporting (newest row within the coverage allowance of now), or now when
     * none is. A node that has stopped doesn't hold the window back; the
     * coverage check makes the window incomplete instead.
     *
     * @param rows a monitor's count rows up to now, ordered by node and then time
     */
    static Instant end(List<MatchSample> rows, Instant now, int collectorSeconds) {
        Instant reporting = now.minusSeconds(LogEventsEvaluator.COVERAGE_INTERVALS * (long) collectorSeconds);
        Instant end = null;
        for (int i = 0; i < rows.size(); i++) {
            MatchSample row = rows.get(i);
            boolean newestOfNode = i + 1 == rows.size() || !rows.get(i + 1).nodeId().equals(row.nodeId());
            if (newestOfNode && !row.sampleTime().isBefore(reporting)
                    && (end == null || row.sampleTime().isBefore(end))) {
                end = row.sampleTime();
            }
        }
        return end != null && end.isBefore(now) ? end : now;
    }

    /**
     * @param rows             a monitor's count rows up to now, ordered by node
     *                         and then time, read from the coverage allowance
     *                         before the window's start (or the monitor's last
     *                         save)
     * @param start            the window's first second
     * @param end              the window's end, from {@link #end}
     * @param now              the evaluation time
     * @param collectorSeconds the collector interval now
     */
    static LogWindowCount of(List<MatchSample> rows, Instant start, Instant end, Instant now, int collectorSeconds) {
        long allowance = LogEventsEvaluator.COVERAGE_INTERVALS * (long) collectorSeconds;
        Map<String, Long> perNode = new TreeMap<>();
        long count = 0L;
        boolean rowsComplete = true;
        boolean covered = !rows.isEmpty();
        MatchSample previous = null;
        Coverage coverage = null;
        for (MatchSample row : rows) {
            MatchSample before = previous != null && previous.nodeId().equals(row.nodeId()) ? previous : null;
            previous = row;
            if (before == null) {
                covered &= coverage == null || coverage.covers(now, allowance);
                coverage = new Coverage();
            }
            coverage.add(row.sampleTime(), start, allowance);
            if (row.sampleTime().isBefore(start)) {
                continue; // every line in it is older than the window
            }
            rowsComplete &= row.complete();
            Long inside = linesInside(row, before, start, end);
            if (inside == null) {
                rowsComplete = false;
                inside = 0L;
            }
            perNode.merge(row.nodeId(), inside, Long::sum);
            count += inside;
        }
        covered &= coverage == null || coverage.covers(now, allowance);
        return new LogWindowCount(count, perNode, covered && rowsComplete);
    }

    /** One node's rows, oldest first: did they watch the window from its start up to now? */
    private static final class Coverage {
        private boolean anchored;
        private boolean gapless = true;
        private Instant newest;

        void add(Instant time, Instant start, long allowance) {
            if (!time.isAfter(start) && !time.isBefore(start.minusSeconds(allowance))) {
                anchored = true;
            }
            if (newest != null && time.isAfter(start) && time.isAfter(newest.plusSeconds(allowance))) {
                gapless = false;
            }
            newest = time;
        }

        boolean covers(Instant now, long allowance) {
            return anchored && gapless && !newest.isBefore(now.minusSeconds(allowance));
        }
    }

    /** @return matched lines in the window, summed over every node */
    long count() {
        return count;
    }

    /** @return lines in the window by node id, for every node with a row in the window */
    Map<String, Long> perNode() {
        return perNode;
    }

    /** @return whether the count can be trusted as complete, so a zero or low count is evidence */
    boolean complete() {
        return complete;
    }

    /**
     * @param before the same node's previous row, or {@code null} when it was
     *               not read
     * @return the row's lines whose second is in the window, or {@code null}
     *         when they cannot be told apart from lines outside it
     */
    private static Long linesInside(MatchSample row, MatchSample before, Instant start, Instant end) {
        if (row.matchCount() <= 0) {
            return 0L;
        }
        Map<Long, Long> seconds = seconds(row);
        if (seconds != null) {
            long inside = 0L;
            for (Map.Entry<Long, Long> second : seconds.entrySet()) {
                if (second.getKey() >= start.getEpochSecond() && second.getKey() <= end.getEpochSecond()) {
                    inside += second.getValue();
                }
            }
            return inside;
        }
        // No usable per-second counts: the whole interval, from the previous row to this one.
        if (before != null && !before.sampleTime().isBefore(end)) {
            return 0L; // wholly after the window
        }
        boolean inside = before != null && !before.sampleTime().isBefore(start) && !row.sampleTime().isAfter(end);
        return inside ? row.matchCount() : null;
    }

    /** @return the row's per-second counts, or {@code null} when absent, unreadable or not adding up to its count */
    private static Map<Long, Long> seconds(MatchSample row) {
        if (row.secondCounts() == null || row.secondCounts().isBlank()) {
            return null;
        }
        try {
            JsonNode stored = Json.mapper().readTree(row.secondCounts());
            if (stored == null || !stored.isObject()) {
                return null;
            }
            Map<Long, Long> seconds = new TreeMap<>();
            long total = 0L;
            for (Iterator<Map.Entry<String, JsonNode>> fields = stored.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                long matches = field.getValue().asLong();
                seconds.put(Long.parseLong(field.getKey()), matches);
                total += matches;
            }
            return total == row.matchCount() ? seconds : null;
        } catch (Exception e) {
            return null;
        }
    }
}
