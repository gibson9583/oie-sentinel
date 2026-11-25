/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;

/** Which monitors count, what a drain returns, the kept samples and occurrences, and a recredit. */
class LogMatchCounterTest {

    private static final String EACH_NOTIFICATION = "{\"condition\": \"EACH\", \"outcome\": \"NOTIFICATION\"}";

    private final LogMatchCounter counter = new LogMatchCounter();

    static Monitor monitor(int id, String configJson) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setName("log monitor " + id);
        monitor.setMonitorType(MonitorType.LOG_EVENTS);
        monitor.setEnabled(true);
        monitor.setConfigJson(configJson);
        return monitor;
    }

    private static LogQueue.Line line(long timeMillis, Level level, String message) {
        return new LogQueue.Line(timeMillis, level, "transformer", message, null);
    }

    private static List<String> messages(List<LogMatchCounter.MatchedLine> lines) {
        return lines.stream().map(LogMatchCounter.MatchedLine::message).toList();
    }

    @Test
    void anUnchangedMonitorKeepsItsTallyAcrossARefreshAndASaveStartsItAfresh() {
        Monitor saved = monitor(1, "{}");
        saved.setUpdatedTime(Instant.parse("2026-11-25T12:00:00Z"));
        counter.refresh(List.of(saved));
        counter.record(line(1L, Level.ERROR, "counted"));
        Monitor reread = monitor(1, "{}");
        reread.setUpdatedTime(saved.getUpdatedTime());
        counter.refresh(List.of(reread));
        assertEquals(1, counter.drain().get(1).count(), "the same saved definition keeps its tally");

        counter.record(line(2L, Level.ERROR, "matched before the save"));
        Monitor resaved = monitor(1, "{}");
        resaved.setUpdatedTime(Instant.parse("2026-11-25T12:05:00Z"));
        counter.refresh(List.of(resaved));
        counter.record(line(3L, Level.ERROR, "matched after the save"));
        assertEquals(1, counter.drain().get(1).count(), "a save with the same config starts afresh too");
    }

    @Test
    void aDrainCountsEachSecondOfTheLinesOwnTimeAndARecreditMergesThem() {
        counter.refresh(List.of(monitor(1, "{}")));
        counter.record(line(1_500L, Level.ERROR, "first second"));
        counter.record(line(1_999L, Level.ERROR, "first second again"));
        counter.record(line(3_000L, Level.ERROR, "third second"));
        LogMatchCounter.Drained drained = counter.drain().get(1);
        assertEquals(Map.of(1L, 2L, 3L, 1L), drained.seconds());
        assertEquals(List.of(1L, 3L), List.copyOf(drained.seconds().keySet()), "in second order");

        counter.record(line(3_200L, Level.ERROR, "after the failed write"));
        counter.recredit(Map.of(1, drained));
        LogMatchCounter.Drained retried = counter.drain().get(1);
        assertEquals(4, retried.count());
        assertEquals(Map.of(1L, 2L, 3L, 2L), retried.seconds());
        assertEquals("{\"1\":2,\"3\":2}", LogMatchFlushJob.secondCountsJson(retried.seconds()));
        assertNull(LogMatchFlushJob.secondCountsJson(counter.drain().get(1).seconds()), "nothing matched");
    }

    @Test
    void aTallyKeepsPerSecondCountsUpTo3600SecondsAndOnlyItsTotalBeyond() {
        counter.refresh(List.of(monitor(1, "{}")));
        for (int second = 0; second < 3_600; second++) {
            counter.record(line(second * 1_000L, Level.ERROR, "at the cap"));
        }
        LogMatchCounter.Drained atCap = counter.drain().get(1);
        assertEquals(3_600, atCap.count());
        assertEquals(3_600, atCap.seconds().size());
        assertEquals(3_600, LogMatchFlushJob.secondCountsJson(atCap.seconds()).split(",").length);

        for (int second = 0; second <= 3_600; second++) {
            counter.record(line(second * 1_000L, Level.ERROR, "over the cap"));
        }
        LogMatchCounter.Drained over = counter.drain().get(1);
        assertEquals(3_601, over.count(), "the total is still counted");
        assertNull(LogMatchFlushJob.secondCountsJson(over.seconds()), "written without per-second counts");

        counter.record(line(5_000L, Level.ERROR, "the next interval"));
        assertEquals(Map.of(5L, 1L), counter.drain().get(1).seconds(), "a drain starts keeping them again");
    }

    @Test
    void aRecreditThatTakesATallyOverTheCapKeepsOnlyItsTotal() {
        counter.refresh(List.of(monitor(1, "{}")));
        for (int second = 0; second < 2_000; second++) {
            counter.record(line(second * 1_000L, Level.ERROR, "failed write"));
        }
        LogMatchCounter.Drained failed = counter.drain().get(1);
        for (int second = 2_000; second <= 3_600; second++) {
            counter.record(line(second * 1_000L, Level.ERROR, "after the failed write"));
        }
        counter.recredit(Map.of(1, failed));
        LogMatchCounter.Drained retried = counter.drain().get(1);
        assertEquals(3_601, retried.count());
        assertNull(LogMatchFlushJob.secondCountsJson(retried.seconds()));

        counter.record(line(1L, Level.ERROR, "one more"));
        counter.recredit(Map.of(1, retried));
        assertNull(LogMatchFlushJob.secondCountsJson(counter.drain().get(1).seconds()),
                "counts put back without per-second detail keep none");
    }

    @Test
    void refreshDecidesWhatCountsAndAConfigChangeStartsTheTallyAfresh() {
        Monitor disabled = monitor(2, "{}");
        disabled.setEnabled(false);
        Monitor channelType = monitor(3, "{}");
        channelType.setMonitorType(MonitorType.ERROR_RATE);
        Monitor noId = monitor(5, "{}");
        noId.setId(null);
        List<Monitor> monitors = new ArrayList<>(List.of(monitor(1, "{}"), disabled, channelType,
                monitor(4, "{\"threshold\": 0}"), noId, monitor(6, "{\"minLevel\": \"WARN\"}"),
                monitor(1, "{\"minLevel\": \"DEBUG\"}")));
        monitors.add(null);
        assertEquals(2, counter.refresh(monitors).size(), "monitors 1 and 6; the first entry for an id wins");

        counter.record(line(1L, Level.ERROR, "boom"));
        counter.record(line(2L, Level.WARN, "careful"));
        Map<Integer, LogMatchCounter.Drained> drained = counter.drain();
        assertEquals(List.of(1, 6), List.copyOf(drained.keySet()));
        assertEquals(1, drained.get(1).count());
        assertEquals(2, drained.get(6).count());
        assertEquals(List.of(), drained.get(1).samples(), "samples only when the monitor asks");
        assertEquals(List.of(), drained.get(1).occurrences(), "occurrences only for each-occurrence notifications");
        assertTrue(drained.get(1).complete());
        assertEquals(0, counter.drain().get(1).count(), "a drain starts the next interval at zero, still reported");

        counter.record(line(3L, Level.ERROR, "before the change"));
        counter.refresh(List.of(monitor(1, "{\"minLevel\": \"WARN\"}")));
        counter.record(line(4L, Level.WARN, "after the change"));
        assertEquals(1, counter.drain().get(1).count(), "a line counted under the old config is dropped");
        counter.record(line(5L, Level.ERROR, "lost with its monitor"));
        counter.refresh(List.of());
        assertEquals(Map.of(), counter.drain());
    }

    @Test
    void samplesAreTheNewestCutSafelyAndARecreditKeepsTheNewest() {
        counter.refresh(List.of(monitor(1, "{\"includeMatches\": true}")));
        for (int i = 0; i < 25; i++) {
            counter.record(line(1_000L + i, Level.ERROR, "failure " + i));
        }
        LogMatchCounter.Drained drained = counter.drain().get(1);
        assertEquals(25, drained.count());
        assertEquals(LogMatchCounter.MAX_SAMPLES, drained.samples().size());
        assertEquals(new LogMatchCounter.MatchedLine(1_015L, "ERROR", "transformer", "failure 15"),
                drained.samples().get(0));

        // A character outside the Basic Multilingual Plane straddling the cut is not split.
        String straddling = "s".repeat(LogMatchCounter.MAX_SAMPLE_CHARS - 1) + "\uD83D\uDE00tail";
        counter.record(new LogQueue.Line(1L, Level.ERROR, "l".repeat(3_000), straddling, null));
        LogMatchCounter.MatchedLine cut = counter.drain().get(1).samples().get(0);
        assertEquals("s".repeat(LogMatchCounter.MAX_SAMPLE_CHARS - 1) + "...", cut.message());
        assertEquals("l".repeat(LogMatchCounter.MAX_SAMPLE_CHARS) + "...", cut.logger());

        for (int i = 0; i < 6; i++) {
            counter.record(line(i, Level.ERROR, "old " + i));
        }
        Map<Integer, LogMatchCounter.Drained> failed = counter.drain();
        for (int i = 0; i < 6; i++) {
            counter.record(line(100 + i, Level.ERROR, "new " + i));
        }
        counter.recredit(failed);
        LogMatchCounter.Drained next = counter.drain().get(1);
        assertEquals(12, next.count());
        assertEquals(List.of("old 2", "old 3", "old 4", "old 5", "new 0", "new 1", "new 2", "new 3", "new 4", "new 5"),
                messages(next.samples()));
    }

    @Test
    void eachOccurrenceNotificationsKeepTheFirstOccurrencesUpToTheCapAndCountTheRest() {
        counter.refresh(List.of(monitor(1, EACH_NOTIFICATION),
                monitor(2, "{\"condition\": \"EACH\", \"outcome\": \"NOTIFICATION\", \"includeMatches\": true}")));
        for (int i = 0; i < LogMatchCounter.MAX_OCCURRENCES + 5; i++) {
            counter.record(line(i, Level.ERROR, "occurrence " + i));
        }
        Map<Integer, LogMatchCounter.Drained> drained = counter.drain();
        LogMatchCounter.Drained withoutText = drained.get(1);
        assertEquals(LogMatchCounter.MAX_OCCURRENCES + 5, withoutText.count());
        assertEquals(LogMatchCounter.MAX_OCCURRENCES, withoutText.occurrences().size());
        assertEquals(5, withoutText.overflow());
        assertEquals(new LogMatchCounter.MatchedLine(0L, "ERROR", "transformer", ""), withoutText.occurrences().get(0),
                "without includeMatches an occurrence keeps its time, level and logger, not its text");
        assertEquals("occurrence 0", drained.get(2).occurrences().get(0).message());
        assertEquals("occurrence 99", drained.get(2).occurrences().get(LogMatchCounter.MAX_OCCURRENCES - 1).message());

        LogMatchCounter.Drained next = counter.drain().get(1);
        assertEquals(List.of(), next.occurrences());
        assertEquals(0, next.overflow());
    }

    @Test
    void aRecreditKeepsTheOldestOccurrencesAndAddsTheOverflow() {
        counter.refresh(List.of(monitor(1, "{\"condition\": \"EACH\", \"outcome\": \"NOTIFICATION\", \"includeMatches\": true}")));
        for (int i = 0; i < LogMatchCounter.MAX_OCCURRENCES - 2; i++) {
            counter.record(line(i, Level.ERROR, "failed " + i));
        }
        Map<Integer, LogMatchCounter.Drained> failed = counter.drain();
        for (int i = 0; i < 5; i++) {
            counter.record(line(1_000 + i, Level.ERROR, "later " + i));
        }
        counter.recredit(failed);

        LogMatchCounter.Drained next = counter.drain().get(1);
        assertEquals(LogMatchCounter.MAX_OCCURRENCES + 3, next.count());
        assertEquals(LogMatchCounter.MAX_OCCURRENCES, next.occurrences().size());
        assertEquals("failed 0", next.occurrences().get(0).message());
        assertEquals(List.of("later 0", "later 1"), messages(next.occurrences().subList(
                LogMatchCounter.MAX_OCCURRENCES - 2, LogMatchCounter.MAX_OCCURRENCES)));
        assertEquals(3, next.overflow(), "the later occurrences past the cap");

        counter.recredit(Map.of(1, new LogMatchCounter.Drained(0, List.of(), true, List.of(), 7)));
        assertEquals(7, counter.drain().get(1).overflow(), "the recredited interval's own overflow");
    }

    @Test
    void aTimedOutPatternCheckMakesOnlyThatMonitorsDrainIncompleteAndARecreditKeepsIt() {
        counter.refresh(List.of(monitor(1, "{\"pattern\": \"((x+)+)+y\"}"), monitor(2, "{}")));
        counter.record(new LogQueue.Line(1L, Level.ERROR, "x", "x".repeat(40), null));
        Map<Integer, LogMatchCounter.Drained> drained = counter.drain();
        assertFalse(drained.get(1).complete());
        assertTrue(drained.get(2).complete());
        assertEquals(1, drained.get(2).count());

        counter.recredit(drained);
        Map<Integer, LogMatchCounter.Drained> next = counter.drain();
        assertFalse(next.get(1).complete(), "a recredited incomplete interval stays incomplete");
        assertTrue(next.get(2).complete());
        assertTrue(counter.drain().get(1).complete(), "the next interval starts complete");
    }
}
