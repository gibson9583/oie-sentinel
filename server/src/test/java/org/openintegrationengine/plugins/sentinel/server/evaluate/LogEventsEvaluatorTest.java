/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.db.MatchSample;
import org.openintegrationengine.plugins.sentinel.server.db.MatchSampleRepository;
import org.openintegrationengine.plugins.sentinel.server.log.LogMatchTestDatabase;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Log monitor evaluation against the real Derby schema and mapper. Windows are
 * {@code [NOW - 300s, NOW]} unless a test says otherwise, and the collector
 * interval is the default 30 seconds, so a window is covered when its earliest
 * row is at most 60 seconds after {@code START}.
 */
class LogEventsEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Instant START = NOW.minusSeconds(300);

    private LogMatchTestDatabase database;

    @BeforeEach
    void setup() throws Exception {
        database = new LogMatchTestDatabase();
    }

    @AfterEach
    void cleanup() throws Exception {
        database.close();
    }

    private static void rows(Monitor monitor, MatchSample... rows) {
        List<MatchSample> samples = new ArrayList<>();
        for (MatchSample row : rows) {
            samples.add(new MatchSample(null, row.nodeId(), monitor.getId(), null, row.sampleTime(), row.matchCount(),
                    row.samplesJson(), row.complete(), row.secondCounts()));
        }
        MatchSampleRepository.insertSamples(samples);
    }

    /** A row whose lines were all logged in the second it was written. */
    private static MatchSample row(String node, Instant time, long count) {
        return new MatchSample(null, node, 0, null, time, count, null, true, seconds(time, count));
    }

    private static MatchSample incomplete(String node, Instant time, long count) {
        return new MatchSample(null, node, 0, null, time, count, null, false, seconds(time, count));
    }

    private static MatchSample row(String node, Instant time, long count, String matches) {
        return new MatchSample(null, node, 0, null, time, count, matches, true, seconds(time, count));
    }

    /** A row as written before schema v15, without per-second counts. */
    private static MatchSample legacy(String node, Instant time, long count) {
        return new MatchSample(null, node, 0, null, time, count, null, true, null);
    }

    /** A row written at {@code time} whose lines were logged at the given seconds. */
    private static MatchSample late(String node, Instant time, Instant... lines) {
        TreeMap<Long, Long> seconds = new TreeMap<>();
        for (Instant line : lines) {
            seconds.merge(line.getEpochSecond(), 1L, Long::sum);
        }
        StringBuilder json = new StringBuilder("{");
        seconds.forEach((second, count) -> json.append(json.length() > 1 ? "," : "")
                .append('"').append(second).append("\":").append(count));
        return new MatchSample(null, node, 0, null, time, lines.length, null, true, json.append('}').toString());
    }

    private static String seconds(Instant time, long count) {
        return count > 0 ? "{\"" + time.getEpochSecond() + "\":" + count + "}" : null;
    }

    private static EvaluationOutcome.Result result(Monitor monitor) {
        return LogEventsEvaluator.evaluate(monitor, NOW).getResult();
    }

    private static JsonNode value(EvaluationOutcome outcome) throws Exception {
        return Json.mapper().readTree(outcome.getValueJson());
    }

    /** A stored matches value with one line per given time. */
    private static String matches(String node, Instant... times) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < times.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"time\":\"").append(times[i]).append("\",\"node\":\"").append(node)
                    .append("\",\"level\":\"ERROR\",\"logger\":\"transformer\",\"message\":\"")
                    .append(node).append(" at ").append(times[i]).append("\"}");
        }
        return json.append(']').toString();
    }

    @Test
    void theSumAcrossNodesAtTheThresholdIsABreachAndTheValueSaysHowItWasJudged() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{\"threshold\": 3}");
        rows(monitor, row("node-a", START.plusSeconds(10), 1), row("node-b", START.plusSeconds(20), 1),
                row("node-a", NOW.minusSeconds(10), 1), row("node-b", NOW, 0));

        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
        assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
        assertEquals("3 matching log lines in the last 300s (at least 3)", outcome.getMessage());
        JsonNode value = value(outcome);
        List<String> keys = new ArrayList<>();
        value.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("condition", "count", "threshold", "windowSeconds", "perNode", "lastBreach"), keys,
                "matches appear only when the monitor includes them");
        assertEquals("AT_LEAST", value.get("condition").asText());
        assertEquals(3, value.get("count").asLong());
        assertEquals(3, value.get("threshold").asInt());
        assertEquals(300, value.get("windowSeconds").asInt());
        assertEquals(2, value.get("perNode").get("node-a").asLong());
        assertEquals(1, value.get("perNode").get("node-b").asLong());
        assertEquals(NOW.toString(), value.get("lastBreach").asText());
    }

    @Test
    void atLeastBreachesOnACountFromIncompleteRowsHoweverLittleOfTheWindowTheyCover() {
        Monitor monitor = database.logMonitor("errors", "{\"threshold\": 2}");
        rows(monitor, incomplete("node-a", NOW.minusSeconds(5), 2));
        assertEquals(EvaluationOutcome.Result.BREACH, result(monitor));
    }

    @Test
    void atLeastBelowTheThresholdIsOkOnlyWhenTheWindowIsCoveredAndComplete() throws Exception {
        Monitor covered = database.logMonitor("covered", "{\"threshold\": 5}");
        rows(covered, row("node-a", START.plusSeconds(60), 1), row("node-a", NOW, 3));
        EvaluationOutcome ok = LogEventsEvaluator.evaluate(covered, NOW);
        assertEquals(EvaluationOutcome.Result.OK, ok.getResult(), "the earliest row exactly two intervals in");
        assertNull(ok.getMessage());
        assertEquals(4, value(ok).get("count").asLong());
        assertFalse(value(ok).has("lastBreach"));

        Monitor late = database.logMonitor("late", "{\"threshold\": 5}");
        rows(late, row("node-a", START.plusSeconds(61), 0), row("node-a", NOW, 0));
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(late),
                "the window's first 61 seconds were not watched, so 'nothing matched' cannot be asserted");

        Monitor gap = database.logMonitor("gap", "{\"threshold\": 5}");
        rows(gap, row("node-a", START.plusSeconds(10), 0), incomplete("node-a", NOW.minusSeconds(30), 1), row("node-a", NOW, 0));
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(gap), "one incomplete row in a covered window");
    }

    @Test
    void atMostIsOkOnceTheCountExceedsTheThresholdAndBreachesOnlyOnACompleteWindow() throws Exception {
        String config = "{\"condition\": \"AT_MOST\", \"threshold\": 2}";
        Monitor exceeded = database.logMonitor("exceeded", config);
        rows(exceeded, incomplete("node-a", NOW.minusSeconds(5), 3));
        EvaluationOutcome ok = LogEventsEvaluator.evaluate(exceeded, NOW);
        assertEquals(EvaluationOutcome.Result.OK, ok.getResult(), "a count over the bound is evidence whatever the rows");
        assertFalse(value(ok).has("lastBreach"));

        Monitor quiet = database.logMonitor("quiet", config);
        rows(quiet, row("node-a", START.plusSeconds(10), 1), row("node-a", NOW, 1));
        EvaluationOutcome breach = LogEventsEvaluator.evaluate(quiet, NOW);
        assertEquals(EvaluationOutcome.Result.BREACH, breach.getResult());
        assertEquals("2 matching log lines in the last 300s (at most 2)", breach.getMessage());
        assertEquals("AT_MOST", value(breach).get("condition").asText());
        assertEquals(NOW.toString(), value(breach).get("lastBreach").asText());

        Monitor gap = database.logMonitor("gap", config);
        rows(gap, row("node-a", START.plusSeconds(10), 1), incomplete("node-a", NOW, 1));
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(gap));
        Monitor unwatched = database.logMonitor("unwatched", config);
        rows(unwatched, row("node-a", NOW.minusSeconds(10), 0));
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(unwatched));
    }

    @Test
    void eachOccurrenceIsAtLeastOneAndItsMessageNamesNoBound() throws Exception {
        Monitor monitor = database.logMonitor("each", "{\"condition\": \"EACH\", \"threshold\": 5}");
        rows(monitor, row("node-a", NOW.minusSeconds(5), 1));
        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
        assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
        assertEquals("1 matching log line in the last 300s", outcome.getMessage());
        assertEquals("EACH", value(outcome).get("condition").asText());
        assertEquals(1, value(outcome).get("threshold").asInt());
    }

    @Test
    void noRowsAtAllIsInsufficientDataAndBothWindowBoundsAreInclusive() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{}");
        EvaluationOutcome empty = LogEventsEvaluator.evaluate(monitor, NOW);
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, empty.getResult());
        assertEquals(0, value(empty).get("perNode").size());

        rows(monitor, row("node-a", START.minusMillis(1), 7), row("node-a", NOW.plusMillis(1), 7));
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(monitor));
        rows(monitor, row("node-a", START, 0));
        assertEquals(EvaluationOutcome.Result.OK, result(monitor));
    }

    @Test
    void theCoverageAllowanceFollowsTheCollectorIntervalSetting() {
        Monitor monitor = database.logMonitor("errors", "{\"windowSeconds\": 3600}");
        rows(monitor, row("node-a", NOW.minusSeconds(3600).plusSeconds(1000), 0));
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, result(monitor));
        database.collectorIntervalSeconds(600);
        assertEquals(EvaluationOutcome.Result.OK, result(monitor), "two 600-second intervals allow a first row 1000 seconds in");
    }

    @Test
    void theEarliestRowOfAnyNodeCoversTheWindow() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{}");
        rows(monitor, row("node-a", START.plusSeconds(15), 0), row("node-b", NOW.minusSeconds(30), 0));
        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
        assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
        assertEquals(0, value(outcome).get("perNode").get("node-b").asLong(), "a node with rows and no matches is listed");
    }

    @Test
    void theWindowIsClampedToTheEvaluatorIntervalAndTheSampleRetentionAtRunTime() throws Exception {
        Monitor shortWindow = database.logMonitor("short", "{\"windowSeconds\": 30}");
        rows(shortWindow, row("node-a", NOW.minusSeconds(50), 1));
        EvaluationOutcome widened = LogEventsEvaluator.evaluate(shortWindow, NOW);
        assertEquals("1 matching log line in the last 60s (at least 1)", widened.getMessage(),
                "the default 60-second evaluator interval, so no line falls between evaluations");
        assertEquals(60, value(widened).get("windowSeconds").asInt());

        database.setting("retention.sample.days", 1);
        Monitor longWindow = database.logMonitor("long", "{\"windowSeconds\": 172800}");
        rows(longWindow, row("node-a", NOW.minusSeconds(90_000), 5), row("node-a", NOW.minusSeconds(86_390), 0));
        EvaluationOutcome narrowed = LogEventsEvaluator.evaluate(longWindow, NOW);
        assertEquals(EvaluationOutcome.Result.OK, narrowed.getResult(), "the row older than a day is outside the window");
        assertEquals(86_400, value(narrowed).get("windowSeconds").asInt());
    }

    @Test
    void aRowWithoutPerSecondCountsCountsOnlyWhenItsWholeIntervalIsInsideTheWindow() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{\"threshold\": 5}");
        // Rows as written before schema v15. The second row's interval starts before the window.
        rows(monitor, legacy("node-a", START.minusSeconds(20), 0), legacy("node-a", START.plusSeconds(10), 3),
                legacy("node-a", START.plusSeconds(40), 2), legacy("node-a", NOW.minusSeconds(10), 0));

        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
        assertEquals(2, value(outcome).get("count").asLong(), "the straddling row's 3 lines cannot be placed");
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult(),
                "a row left out makes the window incomplete");
    }

    @Test
    void aLateRowCountsOnlyTheLinesWhoseSecondIsInsideTheWindow() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{\"threshold\": 3, \"includeMatches\": true}");
        MatchSample late = late("node-a", NOW.minusSeconds(5), START.minusSeconds(100), START.minusSeconds(1), START,
                NOW.minusSeconds(10));
        rows(monitor, row("node-a", START.minusSeconds(30), 0), new MatchSample(null, "node-a", 0, null,
                late.sampleTime(), late.matchCount(), matches("node-a", START.minusSeconds(100), START.minusSeconds(1),
                        START, NOW.minusSeconds(10)), true, late.secondCounts()));

        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
        JsonNode value = value(outcome);
        assertEquals(2, value.get("count").asLong(), "the window's first second counts, the one before it does not");
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult(), "2 is under 3 and the window "
                + "has no row near its start");
        List<String> times = new ArrayList<>();
        value.get("matches").forEach(line -> times.add(line.get("time").asText()));
        assertEquals(List.of(START.toString(), NOW.minusSeconds(10).toString()), times,
                "matched lines older than the window are left out too");
    }

    @Test
    void matchesAreTheNewestTenAcrossNodesOldestFirst() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{\"threshold\": 100, \"includeMatches\": true}");
        Instant t = START.plusSeconds(30);
        // node-b's flushes interleave with node-a's, and one of its lines is newer than all of node-a's last row.
        rows(monitor,
                row("node-a", t, 3, matches("node-a", t.minusSeconds(20), t.minusSeconds(10), t.minusSeconds(1))),
                row("node-a", t.plusSeconds(30), 4, matches("node-a", t.plusSeconds(2), t.plusSeconds(4),
                        t.plusSeconds(6), t.plusSeconds(8))),
                row("node-a", t.plusSeconds(60), 0),
                row("node-a", t.plusSeconds(90), 2, matches("node-a", t.plusSeconds(61), t.plusSeconds(62))),
                row("node-b", t.plusSeconds(45), 3, matches("node-b", t.plusSeconds(20), t.plusSeconds(21),
                        t.plusSeconds(22))),
                row("node-b", t.plusSeconds(100), 2, matches("node-b", t.plusSeconds(70), t.plusSeconds(99))),
                row("node-c", t.plusSeconds(100), 0));

        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
        assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
        JsonNode value = value(outcome);
        assertEquals(14, value.get("count").asLong());
        List<String> times = new ArrayList<>();
        value.get("matches").forEach(line -> times.add(line.get("time").asText()));
        List<String> expected = new ArrayList<>();
        for (int offset : new int[] {4, 6, 8, 20, 21, 22, 61, 62, 70, 99}) {
            expected.add(t.plusSeconds(offset).toString());
        }
        assertEquals(expected, times);
        assertEquals("node-b at " + t.plusSeconds(99), value.get("matches").get(9).get("message").asText());
    }

    @Test
    void matchesAreReadFromEachNodesNewestRowsNeverFromTheWholeWindow() {
        Monitor monitor = database.logMonitor("errors",
                "{\"threshold\": 1000000, \"windowSeconds\": 86400, \"includeMatches\": true}");
        MatchSample[] many = new MatchSample[200];
        for (int i = 0; i < many.length; i++) {
            Instant time = NOW.minusSeconds(30L * i);
            many[i] = row("node-a", time, 1, matches("node-a", time.minusSeconds(1)));
        }
        rows(monitor, many);
        rows(monitor, row("node-b", NOW.minusSeconds(86_000), 0));
        try (MockedStatic<MatchSampleRepository> repository = mockStatic(MatchSampleRepository.class, CALLS_REAL_METHODS)) {
            EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
            assertEquals(10, outcome.getValueJson().split("\"time\"").length - 1);
            repository.verify(() -> MatchSampleRepository.listRecentSamples(
                    eq(monitor.getId()), eq("node-a"), any(), any(), eq(10)), times(1));
            repository.verify(() -> MatchSampleRepository.listRecentSamples(
                    anyInt(), eq("node-b"), any(), any(), anyInt()), never());
        }
    }

    @Test
    void includedMatchesWithNoMatchesInTheWindowAreAnEmptyList() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{\"includeMatches\": true}");
        rows(monitor, row("node-a", START.plusSeconds(10), 0));
        JsonNode value = value(LogEventsEvaluator.evaluate(monitor, NOW));
        assertTrue(value.get("matches").isArray());
        assertEquals(0, value.get("matches").size());
    }

    @Test
    void anUnreadableStoredMatchesValueIsSkipped() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{\"includeMatches\": true}");
        rows(monitor, row("node-a", NOW.minusSeconds(40), 1, "{not json"),
                row("node-a", NOW.minusSeconds(10), 1, matches("node-a", NOW.minusSeconds(11))));
        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
        assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
        assertEquals(1, value(outcome).get("matches").size());
    }

    @Test
    void anInvalidStoredConfigIsInsufficientDataNotAnException() throws Exception {
        Monitor monitor = database.logMonitor("broken", "{\"windowSeconds\": -5}");
        rows(monitor, row("node-a", NOW.minusSeconds(10), 9));
        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, NOW);
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult());
        assertTrue(value(outcome).get("invalidConfig").asBoolean());
    }

    @Test
    void theEvaluatorReadsNothingButItsOwnMonitor() {
        Monitor mine = database.logMonitor("mine", "{}");
        Monitor other = database.logMonitor("other", "{}");
        rows(other, row("node-a", NOW.minusSeconds(10), 50));
        rows(mine, row("node-a", START.plusSeconds(5), 0));
        assertEquals(EvaluationOutcome.Result.OK, result(mine), "another monitor's matches are not this monitor's");
        assertEquals(EvaluationOutcome.Result.BREACH, result(other));
    }
}
