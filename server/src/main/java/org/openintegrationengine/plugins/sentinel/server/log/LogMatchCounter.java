/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;

/**
 * This node's matches per {@code LOG_EVENTS} monitor since the last flush,
 * with the newest matched lines of monitors that keep them.
 *
 * <p>{@link #record} runs on the matcher thread, the rest on the flush job's
 * thread. The monitors live in an array the flush replaces whole, and a
 * monitor's tally carries over from one array to the next, so a line matched
 * against the old array still lands in the right count. A saved monitor
 * starts a new tally instead: what its old definition matched is dropped, as
 * the evaluator leaves out rows written before the save.</p>
 */
public final class LogMatchCounter {

    /** Matched lines kept per monitor per flush, the newest. */
    public static final int MAX_SAMPLES = 10;

    /**
     * Occurrences kept per monitor per flush for each-occurrence
     * notifications. At the 1,000-character cuts an occurrence is at most
     * about 4 KB, so this bounds one monitor at about 400 KB between flushes;
     * the rest are counted and sent as one summary.
     */
    public static final int MAX_OCCURRENCES = 100;

    /** Longest message, and longest logger name, kept in a matched line. */
    public static final int MAX_SAMPLE_CHARS = 1000;

    /**
     * What one monitor matched since the last drain. {@code complete} is false
     * when a pattern check timed out; {@code occurrences} and {@code overflow}
     * are filled only for each-occurrence notifications.
     */
    public record Drained(long count, List<MatchedLine> samples, boolean complete, List<MatchedLine> occurrences,
            long overflow) {

        /** @return this without its occurrences, for putting back counts whose notifications were written */
        public Drained countsOnly() {
            return new Drained(count, samples, complete, List.of(), 0);
        }

        /** @return this, marked complete only when {@code rowComplete} is true, for putting back a row's counts */
        public Drained withComplete(boolean rowComplete) {
            return new Drained(count, samples, rowComplete, occurrences, overflow);
        }
    }

    /**
     * One matched line. {@code message} is the text the pattern saw (message
     * plus exception summary), cut to {@link #MAX_SAMPLE_CHARS}.
     */
    public record MatchedLine(long timeMillis, String level, String logger, String message) {

        static MatchedLine of(LogQueue.Line line, boolean withText) {
            return new MatchedLine(line.timeMillis(), line.level().name(), sample(line.logger()),
                    withText ? sample(line.text()) : "");
        }

        private static String sample(String value) {
            return value.length() <= MAX_SAMPLE_CHARS ? value : LogQueue.cut(value, MAX_SAMPLE_CHARS) + "...";
        }
    }

    private final LongAdder internalErrors = new LongAdder();
    private volatile Entry[] entries = new Entry[0];

    /** How many matched lines a monitor keeps per flush. */
    static int samplesToKeep(LogEventsConfig config) {
        return config.includeMatches() ? MAX_SAMPLES : 0;
    }

    // ========== Matcher thread ==========

    /** Counts one line against every monitor it matches. */
    void record(LogQueue.Line line) {
        String text = null;
        long deadline = 0L;
        for (Entry entry : entries) {
            try {
                if (!entry.matcher.accepts(line.level(), line.logger())) {
                    continue;
                }
                if (entry.matcher.hasPattern()) {
                    if (text == null) {
                        text = line.text();
                        deadline = System.nanoTime() + LogMatcher.TIME_LIMIT_NANOS;
                    }
                    LogMatcher.Result result = entry.matcher.find(text, System::nanoTime, deadline);
                    if (result == LogMatcher.Result.TIMED_OUT) {
                        entry.tally.timedOut();
                    }
                    if (result != LogMatcher.Result.MATCH) {
                        continue;
                    }
                }
                matched(entry, line);
            } catch (Throwable t) {
                internalErrors.increment();
            }
        }
    }

    /** The one place a match is recorded. */
    private static void matched(Entry entry, LogQueue.Line line) {
        MatchedLine sample = entry.samples > 0 ? MatchedLine.of(line, true) : null;
        MatchedLine occurrence = !entry.config.notifiesEachOccurrence() ? null
                : sample != null ? sample : MatchedLine.of(line, entry.config.includeMatches());
        entry.tally.add(sample, entry.samples, occurrence);
    }

    void internalError() {
        internalErrors.increment();
    }

    // ========== Flush job ==========

    /**
     * Counts the enabled {@code LOG_EVENTS} monitors in this list from now on.
     * An unchanged monitor keeps its compiled entry and its tally. A monitor
     * saved since the last refresh (a new updated time or config) starts with
     * an empty tally, and an invalid one is left out.
     *
     * @return the matchers now counted, for the queue's gate
     */
    public synchronized List<LogMatcher> refresh(List<Monitor> monitors) {
        Map<Integer, Entry> previous = new HashMap<>();
        for (Entry entry : entries) {
            previous.put(entry.monitorId, entry);
        }
        Map<Integer, Entry> next = new LinkedHashMap<>();
        for (Monitor monitor : monitors) {
            if (monitor == null || monitor.getId() == null || !monitor.isEnabled()
                    || monitor.getMonitorType() != MonitorType.LOG_EVENTS || next.containsKey(monitor.getId())) {
                continue;
            }
            Entry entry = previous.get(monitor.getId());
            if (entry == null || !Objects.equals(entry.configJson, monitor.getConfigJson())
                    || !Objects.equals(entry.savedTime, monitor.getUpdatedTime())) {
                LogEventsConfig config = LogEventsConfig.lenient(monitor.getId(), monitor.getConfigJson());
                if (config == null) {
                    continue;
                }
                entry = new Entry(monitor.getId(), monitor.getConfigJson(), monitor.getUpdatedTime(), config,
                        LogMatcher.of(config), samplesToKeep(config), new Tally());
            }
            next.put(entry.monitorId, entry);
        }
        entries = next.values().toArray(new Entry[0]);
        List<LogMatcher> matchers = new ArrayList<>(next.size());
        next.values().forEach(entry -> matchers.add(entry.matcher));
        return matchers;
    }

    /**
     * Takes each counted monitor's matches since the previous drain, zero
     * counts included.
     *
     * @return by monitor id, in the order {@link #refresh} received them
     */
    public synchronized Map<Integer, Drained> drain() {
        Map<Integer, Drained> drained = new LinkedHashMap<>();
        for (Entry entry : entries) {
            drained.put(entry.monitorId, entry.tally.drain());
        }
        return drained;
    }

    /** Puts back what a failed write drained; older samples go first, so the newest are kept. */
    public synchronized void recredit(Map<Integer, Drained> drained) {
        for (Entry entry : entries) {
            Drained back = drained.get(entry.monitorId);
            if (back != null) {
                entry.tally.recredit(back, entry.samples);
            }
        }
    }

    /** @return per monitor id, pattern checks stopped at the time limit since the previous call; zeros left out */
    public synchronized Map<Integer, Long> drainTimedOut() {
        Map<Integer, Long> timedOut = new LinkedHashMap<>();
        for (Entry entry : entries) {
            long count = entry.tally.takeTimedOut();
            if (count > 0) {
                timedOut.put(entry.monitorId, count);
            }
        }
        return timedOut;
    }

    /** @return lines that could not be checked because of an internal error, since the previous call */
    public long drainInternalErrors() {
        return internalErrors.sumThenReset();
    }

    /** @return whether a flush has loaded at least one monitor to count */
    boolean isCounting() {
        return entries.length > 0;
    }

    /** Stops counting and forgets every tally, for a plugin stop. */
    public synchronized void clear() {
        entries = new Entry[0];
        internalErrors.reset();
    }

    private record Entry(int monitorId, String configJson, Instant savedTime, LogEventsConfig config,
            LogMatcher matcher, int samples, Tally tally) {
    }

    private static final class Tally {
        private long count;
        private long timedOut;
        private boolean missed;
        private long overflow;
        /** Oldest first. */
        private final ArrayDeque<MatchedLine> samples = new ArrayDeque<>(MAX_SAMPLES);
        /** Oldest first; the first {@link #MAX_OCCURRENCES} since the last drain. */
        private final List<MatchedLine> occurrences = new ArrayList<>();

        synchronized void add(MatchedLine sample, int keep, MatchedLine occurrence) {
            count++;
            if (sample != null && keep > 0) {
                while (samples.size() >= keep) {
                    samples.removeFirst();
                }
                samples.addLast(sample);
            }
            if (occurrence != null) {
                keepOccurrence(occurrence);
            }
        }

        private void keepOccurrence(MatchedLine occurrence) {
            if (occurrences.size() < MAX_OCCURRENCES) {
                occurrences.add(occurrence);
            } else {
                overflow++;
            }
        }

        synchronized void timedOut() {
            timedOut++;
            missed = true;
        }

        synchronized long takeTimedOut() {
            long taken = timedOut;
            timedOut = 0;
            return taken;
        }

        synchronized Drained drain() {
            Drained drained = new Drained(count, List.copyOf(samples), !missed, List.copyOf(occurrences), overflow);
            count = 0;
            missed = false;
            overflow = 0;
            samples.clear();
            occurrences.clear();
            return drained;
        }

        /** Older first, so the newest samples and the oldest occurrences are what the caps keep. */
        synchronized void recredit(Drained back, int keep) {
            count += back.count();
            missed |= !back.complete();
            List<MatchedLine> merged = new ArrayList<>(back.samples());
            merged.addAll(samples);
            samples.clear();
            samples.addAll(merged.subList(Math.max(0, merged.size() - keep), merged.size()));
            List<MatchedLine> later = new ArrayList<>(occurrences);
            long laterOverflow = overflow;
            occurrences.clear();
            overflow = back.overflow();
            back.occurrences().forEach(this::keepOccurrence);
            later.forEach(this::keepOccurrence);
            overflow += laterOverflow;
        }
    }
}
