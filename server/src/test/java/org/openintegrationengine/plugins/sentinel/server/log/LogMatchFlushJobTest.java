/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.server.logging.MirthLog4jFilter;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MatchSample;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.LogEventsEvaluator;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/** The per-node flush against the real Derby schema and mapper: count rows, their completeness, and notifications. */
class LogMatchFlushJobTest {

    private static final String EACH_NOTIFICATION = "{\"condition\": \"EACH\", \"outcome\": \"NOTIFICATION\"}";

    private LogMatchTestDatabase database;
    private LoggerContext context;
    private LogPipeline pipeline;

    @BeforeEach
    void setup() throws Exception {
        LogEventAppender.detach();
        database = new LogMatchTestDatabase();
        context = LogEventAppenderTest.engineLikeContext();
        pipeline = new LogPipeline(new MirthLog4jFilter(), System::nanoTime);
        // A stop's final flush runs on this thread, where the database mocks apply.
        pipeline.runFinalFlushWith(Runnable::run);
    }

    @AfterEach
    void cleanup() throws Exception {
        pipeline.stop();
        context.stop();
        database.close();
    }

    private void counting() {
        LogEventAppender.attach(context, pipeline.queue());
        pipeline.matcher().start();
    }

    /** The real start, with the engine's log4j context replaced by the test's. */
    private void start() {
        try (MockedStatic<LogEventAppender> appender = mockStatic(LogEventAppender.class, CALLS_REAL_METHODS)) {
            appender.when(LogEventAppender::engineContext).thenReturn(context);
            pipeline.start(1_000);
        }
    }

    private void flush() {
        LogMatchFlushJob.flush(pipeline, "node-a");
    }

    private void matched(Level level, String logger, String message, long timeMillis) {
        pipeline.counter().record(new LogQueue.Line(timeMillis, level, logger, message, null));
    }

    /** A monitor's rows, oldest first. */
    private List<MatchSample> rows(Monitor monitor) {
        List<MatchSample> rows = new ArrayList<>();
        try (Connection connection = database.connection(); Statement sql = connection.createStatement();
                ResultSet result = sql.executeQuery("SELECT id, node_id, channel_id, sample_time, match_count, "
                        + "samples_json, complete FROM sentinel_match_sample WHERE monitor_id = " + monitor.getId()
                        + " ORDER BY sample_time, id")) {
            while (result.next()) {
                rows.add(new MatchSample(result.getLong(1), result.getString(2), monitor.getId(), result.getString(3),
                        result.getTimestamp(4).toInstant(), result.getLong(5), result.getString(6), result.getBoolean(7)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    private static List<Long> counts(List<MatchSample> rows) {
        return rows.stream().map(MatchSample::matchCount).toList();
    }

    private static List<Boolean> completes(List<MatchSample> rows) {
        return rows.stream().map(MatchSample::complete).toList();
    }

    /** A monitor's notifications, newest first. */
    private static List<AlertEvent> notifications(Monitor monitor) {
        return AlertEventRepository.listRecentAlertEvents(1_000, AlertKind.NOTIFICATION).stream()
                .filter(event -> monitor.getId().equals(event.getMonitorId())).toList();
    }

    private void sql(String statement) throws SQLException {
        try (Connection connection = database.connection(); Statement sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    @Test
    void everyEnabledLogMonitorGetsOneRowPerFlushWithItsCountAndSamples() throws Exception {
        counting();
        Monitor errors = database.logMonitor("errors", "{}");
        Monitor scripts = database.logMonitor("script lines",
                "{\"minLevel\": \"DEBUG\", \"loggerPrefix\": \"transformer\", \"includeMatches\": true}");
        Monitor disabled = database.logMonitor("disabled", "{}");
        MonitorRepository.setMonitorEnabled(disabled.getId(), false);
        Monitor broken = database.logMonitor("broken", "{\"threshold\": 0}");

        flush();
        assertEquals(List.of(0L), counts(rows(errors)), "a zero count is written, so no row means no data");
        assertEquals(List.of(0L), counts(rows(scripts)));
        assertEquals(List.of(), rows(disabled));
        assertEquals(List.of(), rows(broken), "an invalid config is not counted, so it has no data");

        matched(Level.ERROR, "com.example.Engine", "boom", 1L);
        matched(Level.DEBUG, "transformer", "MSH|^~\\&|A", Instant.parse("2026-11-21T12:00:00.007Z").toEpochMilli());
        Instant before = Instant.now();
        flush();

        MatchSample errorRow = rows(errors).get(1);
        assertEquals(1L, errorRow.matchCount());
        assertEquals("node-a", errorRow.nodeId());
        assertNull(errorRow.channelId());
        assertNull(errorRow.samplesJson());
        assertFalse(errorRow.sampleTime().isBefore(before.truncatedTo(ChronoUnit.MILLIS)));

        JsonNode samples = Json.mapper().readTree(rows(scripts).get(1).samplesJson());
        assertEquals(1, samples.size());
        assertEquals("{\"time\":\"2026-11-21T12:00:00.007Z\",\"node\":\"node-a\",\"level\":\"DEBUG\","
                + "\"logger\":\"transformer\",\"message\":\"MSH|^~\\\\&|A\"}", samples.get(0).toString());
    }

    @Test
    void rowsAreIncompleteAfterAStartAfterDroppedLinesAndAfterAnInternalError() {
        Monitor monitor = database.logMonitor("errors", "{}");
        start();
        flush();
        flush();

        pipeline.matcher().stop(2_000);
        pipeline.resize(100);
        for (int i = 0; i < 105; i++) {
            pipeline.queue().offer(LogQueueTest.event(Level.ERROR, "x", "line " + i));
        }
        pipeline.matcher().start();
        flush();
        flush();

        pipeline.queue().internalError();
        flush();
        flush();

        pipeline.stop();
        start();
        flush();
        assertEquals(List.of(false, true, false, true, false, true, false, false), completes(rows(monitor)),
                "the stop's own row is incomplete too");
    }

    @Test
    void rowsAreWrittenButIncompleteWhileDetachedOrWhileTheMatcherIsNotAlive() {
        Monitor monitor = database.logMonitor("errors", "{}");
        pipeline.matcher().start();
        flush();
        assertTrue(pipeline.notCountingReason().contains("appender"), pipeline.notCountingReason());

        pipeline.matcher().stop(2_000);
        LogEventAppender.attach(context, pipeline.queue());
        matched(Level.ERROR, "x", "counted before the matcher stopped", 1L);
        flush();
        assertTrue(pipeline.notCountingReason().contains("not running"), pipeline.notCountingReason());

        pipeline.matcher().start();
        flush();
        assertNull(pipeline.notCountingReason());
        assertEquals(List.of(0L, 1L, 0L), counts(rows(monitor)));
        assertEquals(List.of(false, false, true), completes(rows(monitor)));
    }

    @Test
    void aTimedOutPatternCheckMarksOnlyThatMonitorsRowIncomplete() {
        counting();
        Monitor slow = database.logMonitor("slow", "{\"pattern\": \"((x+)+)+y\"}");
        Monitor plain = database.logMonitor("plain", "{}");
        flush();
        pipeline.counter().record(new LogQueue.Line(1L, Level.ERROR, "x", "x".repeat(40), null));
        flush();
        assertEquals(List.of(false, false), completes(rows(slow)));
        assertEquals(List.of(false, true), completes(rows(plain)));
    }

    @Test
    void eachOccurrenceBecomesAClosedPendingNotificationAtTheLinesTime() throws Exception {
        counting();
        Monitor bare = database.logMonitor("bare", EACH_NOTIFICATION);
        Monitor withMatches = database.logMonitor("with matches",
                "{\"condition\": \"EACH\", \"outcome\": \"NOTIFICATION\", \"includeMatches\": true}");
        flush();
        Instant at = Instant.parse("2026-11-21T12:00:00.007Z");
        matched(Level.ERROR, "com.example.Engine", "boom", at.toEpochMilli());
        flush();

        List<AlertEvent> written = notifications(bare);
        assertEquals(1, written.size());
        AlertEvent notification = written.get(0);
        assertEquals(AlertKind.NOTIFICATION, notification.getKind());
        assertEquals(SubjectType.SERVER, notification.getSubjectType());
        assertNull(notification.getChannelId());
        assertEquals(Severity.HIGH, notification.getSeverity());
        assertEquals(AlertStatus.RESOLVED, notification.getStatus());
        assertTrue(notification.isProblemPending());
        assertFalse(notification.isResolutionPending());
        assertEquals(at, notification.getOpenedTime());
        assertEquals(at, notification.getResolvedTime());
        assertEquals("Matching ERROR log line from com.example.Engine", notification.getMessage());
        assertEquals("{\"condition\":\"EACH\",\"count\":1,\"node\":\"node-a\"}", notification.getDetailsJson());

        JsonNode details = Json.mapper().readTree(notifications(withMatches).get(0).getDetailsJson());
        assertEquals("{\"time\":\"2026-11-21T12:00:00.007Z\",\"node\":\"node-a\",\"level\":\"ERROR\","
                + "\"logger\":\"com.example.Engine\",\"message\":\"boom\"}", details.path("matches").get(0).toString());
        assertEquals(List.of(0L, 1L), counts(rows(bare)), "the counts are written as for any Log monitor");
    }

    @Test
    void occurrencesPastTheCapAreSentAsOneSummary() throws Exception {
        counting();
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        flush();
        for (int i = 0; i < LogMatchCounter.MAX_OCCURRENCES + 3; i++) {
            matched(Level.ERROR, "x", "line " + i, 1_000L + i);
        }
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        flush();

        List<AlertEvent> written = notifications(monitor);
        assertEquals(LogMatchCounter.MAX_OCCURRENCES + 1, written.size());
        AlertEvent summary = written.get(0);
        assertEquals("3 more matching log lines on node-a were not sent one by one (limit 100 per collector run)",
                summary.getMessage());
        assertEquals(3, Json.mapper().readTree(summary.getDetailsJson()).path("notSentIndividually").asLong());
        assertFalse(summary.getOpenedTime().isBefore(before), "the summary is opened at the flush, not at a line");
    }

    @Test
    void aFailedNotificationWriteWritesNothingAndKeepsEverythingForTheNextFlush() {
        counting();
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        flush();
        matched(Level.ERROR, "x", "first", 1L);
        matched(Level.ERROR, "x", "second", 2L);
        try (MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class, CALLS_REAL_METHODS)) {
            alerts.when(() -> AlertEventRepository.insertAlertEvent(any()))
                    .thenCallRealMethod().thenThrow(new IllegalStateException("database unavailable"));
            flush();
        }
        assertEquals(List.of(), notifications(monitor), "the first insert rolled back with the second");
        assertEquals(List.of(0L), counts(rows(monitor)), "no counts without their notifications");

        flush();
        assertEquals(2, notifications(monitor).size());
        assertEquals(List.of(0L, 2L), counts(rows(monitor)));
    }

    @Test
    void aFailedCountWriteIsLoggedOnceAndKeepsTheCountsButNotTheNotifications() throws Exception {
        counting();
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        flush();
        matched(Level.ERROR, "x", "one", 1L);
        matched(Level.ERROR, "x", "two", 2L);

        sql("RENAME TABLE sentinel_match_sample TO sentinel_match_sample_away");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            flush();
        } finally {
            System.setErr(original);
            sql("RENAME TABLE sentinel_match_sample_away TO sentinel_match_sample");
        }
        String output = err.toString(StandardCharsets.UTF_8);
        assertEquals(1, output.lines().filter(line -> line.contains(" ERROR ") && line.contains("match sample")).count(),
                output);
        assertEquals(2, notifications(monitor).size());

        matched(Level.ERROR, "x", "three", 3L);
        flush();
        assertEquals(List.of(0L, 3L), counts(rows(monitor)));
        assertEquals(3, notifications(monitor).size(), "the first two are not written again");
    }

    @Test
    void aCountRetriedAfterAFailedCountWriteStaysIncompleteWhenALineWasDropped() throws Exception {
        Monitor monitor = database.logMonitor("errors", "{}");
        start();
        flush();
        flush();
        droppedOneOf101();

        sql("RENAME TABLE sentinel_match_sample TO sentinel_match_sample_away");
        try {
            flush();
        } finally {
            sql("RENAME TABLE sentinel_match_sample_away TO sentinel_match_sample");
        }
        flush();
        assertEquals(List.of(0L, 0L, 100L), counts(rows(monitor)), "101 lines, one dropped");
        assertEquals(List.of(false, true, false), completes(rows(monitor)));
    }

    @Test
    void aCountRetriedAfterAFailedNotificationWriteStaysIncompleteWhenALineWasDropped() {
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        start();
        flush();
        flush();
        droppedOneOf101();

        try (MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class, CALLS_REAL_METHODS)) {
            alerts.when(() -> AlertEventRepository.insertAlertEvent(any()))
                    .thenThrow(new IllegalStateException("database unavailable"));
            flush();
        }
        flush();
        assertEquals(List.of(0L, 0L, 100L), counts(rows(monitor)), "101 lines, one dropped");
        assertEquals(List.of(false, true, false), completes(rows(monitor)));
        assertEquals(100, notifications(monitor).size());
    }

    /**
     * Offers 101 ERROR lines to a queue with room for 100, so one is dropped,
     * and counts the 100 on this thread. The matcher runs again afterwards, so
     * only the drop can make the next row incomplete.
     */
    private void droppedOneOf101() {
        pipeline.matcher().stop(2_000);
        pipeline.resize(100);
        for (int i = 0; i < 101; i++) {
            pipeline.queue().offer(LogQueueTest.event(Level.ERROR, "x", "line " + i));
        }
        for (LogQueue.Line line = pipeline.queue().poll(); line != null; line = pipeline.queue().poll()) {
            pipeline.counter().record(line);
        }
        pipeline.matcher().start();
        assertNull(pipeline.notCountingReason());
    }

    @Test
    void savingAMonitorStartsItsCountAfreshSoLinesMatchedByTheOldRuleNeverCount() throws Exception {
        counting();
        Monitor monitor = database.logMonitor("alpha", "{\"pattern\": \"ALPHA\", \"includeMatches\": true}");
        flush();
        matched(Level.ERROR, "x", "ALPHA only", System.currentTimeMillis());
        flush();
        matched(Level.ERROR, "x", "ALPHA only, not written yet", System.currentTimeMillis());
        assertEquals(EvaluationOutcome.Result.BREACH, LogEventsEvaluator.evaluate(monitor, Instant.now()).getResult(),
                "the old rule breaches");

        monitor.setConfigJson("{\"pattern\": \"BETA\", \"includeMatches\": true}");
        monitor.setUpdatedTime(Instant.now());
        MonitorRepository.updateMonitor(monitor);
        flush();

        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, Instant.now());
        assertNotEquals(EvaluationOutcome.Result.BREACH, outcome.getResult(), outcome.getValueJson());
        assertEquals(0, Json.mapper().readTree(outcome.getValueJson()).path("count").asLong());
        assertFalse(outcome.getValueJson().contains("ALPHA"), outcome.getValueJson());
        assertEquals(List.of(0L, 1L, 0L), counts(rows(monitor)), "the line matched before the save is not written");
    }

    @Test
    void aQueueCapacitySavedOnAnotherNodeIsAppliedHereAtTheNextFlushAndKeepsWhatIsQueued() {
        database.logMonitor("errors", "{}");
        start();
        flush();
        pipeline.matcher().stop(2_000);
        for (int i = 0; i < 3; i++) {
            pipeline.queue().offer(LogQueueTest.event(Level.ERROR, "x", "line " + i));
        }
        assertEquals(1_000, pipeline.queue().capacity());

        // Another node's save writes the stored property; nothing on this node is called.
        database.setting("log.queue.capacity", 2_500);
        flush();
        assertEquals(2_500, pipeline.queue().capacity());
        assertEquals(3, pipeline.queue().depth(), "lines already queued stay");
    }

    @Test
    void anUnchangedQueueCapacitySettingCausesNoResize() {
        start();
        LogPipeline watched = spy(pipeline);
        database.setting("log.queue.capacity", 1_000);
        LogMatchFlushJob.flush(watched, "node-a");
        verify(watched, never()).resize(anyInt());
        assertEquals(1_000, pipeline.queue().capacity());
    }

    @Test
    void aStopWritesTheLinesMatchedSinceTheLastFlushAsARowAndPendingNotifications() {
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        start();
        flush();
        Instant first = Instant.parse("2026-11-23T12:00:00.001Z");
        Instant second = Instant.parse("2026-11-23T12:00:04.002Z");
        matched(Level.ERROR, "com.example.Engine", "first after the last flush", first.toEpochMilli());
        matched(Level.ERROR, "com.example.Engine", "second after the last flush", second.toEpochMilli());

        pipeline.stop();

        assertEquals(List.of(0L, 2L), counts(rows(monitor)));
        assertEquals(List.of(false, false), completes(rows(monitor)),
                "lines logged between the detach and the stop's row were not counted");
        List<AlertEvent> written = notifications(monitor);
        assertEquals(List.of(first, second), written.stream().map(AlertEvent::getOpenedTime).sorted().toList());
        assertTrue(written.stream().allMatch(AlertEvent::isProblemPending), "left for the leader's evaluator");
        assertTrue(pipeline.counter().drain().isEmpty(), "the stop still forgets the counts");
    }

    @Test
    void aStopWithNothingMatchedWritesOnlyTheZeroCountRow() {
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        start();
        flush();

        pipeline.stop();

        assertEquals(List.of(0L, 0L), counts(rows(monitor)), "the row every flush writes: this node watched");
        assertEquals(List.of(), notifications(monitor));
    }

    @Test
    void aStopBeforeAnyFlushLoadedTheMonitorsWritesNothing() {
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        start();
        matched(Level.ERROR, "x", "not counted: no monitor loaded yet", 1L);

        pipeline.stop();

        assertEquals(List.of(), rows(monitor));
        assertEquals(List.of(), notifications(monitor));
    }

    @Test
    void aLineStillQueuedWhenTheMatcherStopsIsMatchedAndWrittenByTheStop() {
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        start();
        flush();
        pipeline.matcher().stop(2_000);
        pipeline.queue().offer(LogQueueTest.event(Level.ERROR, "x", "queued, not yet matched"));

        pipeline.stop();

        assertEquals(List.of(0L, 1L), counts(rows(monitor)));
        assertEquals(1, notifications(monitor).size());
        assertEquals(0, pipeline.queue().depth());
    }

    @Test
    void aFailedNotificationWriteAtTheStopStillStopsAndForgetsTheCounts() {
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        start();
        flush();
        matched(Level.ERROR, "x", "matched before the stop", 1L);
        try (MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class, CALLS_REAL_METHODS)) {
            alerts.when(() -> AlertEventRepository.insertAlertEvent(any()))
                    .thenThrow(new IllegalStateException("database unavailable"));
            assertDoesNotThrow(pipeline::stop);
        }
        assertEquals(List.of(0L), counts(rows(monitor)), "no counts without their notifications");
        assertEquals(List.of(), notifications(monitor));
        assertTrue(pipeline.counter().drain().isEmpty());
        assertFalse(pipeline.matcher().isAlive());
    }

    @Test
    void aFailedMonitorReadAtTheStopStillStopsAndForgetsTheCounts() {
        Monitor monitor = database.logMonitor("each", EACH_NOTIFICATION);
        start();
        flush();
        matched(Level.ERROR, "x", "matched before the stop", 1L);
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class)) {
            monitors.when(() -> MonitorRepository.listMonitors(any(), any(), any(), any()))
                    .thenThrow(new LinkageError("database driver gone"));
            assertDoesNotThrow(pipeline::stop);
        }
        assertEquals(List.of(0L), counts(rows(monitor)));
        assertEquals(List.of(), notifications(monitor));
        assertTrue(pipeline.counter().drain().isEmpty());
    }

    @Test
    void aFinalFlushThatDoesNotFinishIsLeftBehindAfterItsTimeLimitAndInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        long started = System.nanoTime();

        LogPipeline.runWithin(() -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        }, 300);

        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(waitedMillis >= 300 && waitedMillis < 3_000, "waited " + waitedMillis + " ms");
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the abandoned flush is interrupted");
    }
}
