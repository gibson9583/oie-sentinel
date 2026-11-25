/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.openintegrationengine.plugins.sentinel.server.log.LogMatchTestDatabase;
import org.openintegrationengine.plugins.sentinel.server.log.LogNotifications;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;

/** Match samples, Log subjects and alert kinds through the repositories and the shipped Derby mapper, on a migrated schema. */
class LogPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00.123Z");
    /** channel_id is CHAR(36), so a shorter id would come back padded. */
    private static final String CHANNEL_A = "00000000-0000-0000-0000-00000000000a";
    private static final String CHANNEL_B = "00000000-0000-0000-0000-00000000000b";

    private LogMatchTestDatabase database;
    private Monitor monitor;

    @BeforeEach
    void setup() throws Exception {
        database = new LogMatchTestDatabase();
        monitor = database.logMonitor("counts", "{}");
    }

    @AfterEach
    void cleanup() throws Exception {
        database.close();
    }

    @Test
    void countRowsAreOneMonitorsHeartbeatRowsOverAnInclusiveRangeByNodeThenTime() {
        int other = database.logMonitor("other", "{}").getId();
        String seconds = "{\"" + NOW.getEpochSecond() + "\":3}";
        MatchSampleRepository.insertSamples(List.of(
                sample("node-a", monitor.getId(), null, NOW.minusSeconds(30), 1, null, false),
                new MatchSample(null, "node-b", monitor.getId(), null, NOW.plusSeconds(30), 0, null, true, null),
                new MatchSample(null, "node-a", monitor.getId(), null, NOW, 3, "[\"kept\"]", false, seconds),
                sample("node-a", monitor.getId(), null, NOW.plusSeconds(10), 0, null, true),
                sample("node-a", monitor.getId(), CHANNEL_A, NOW, 5, null, false),
                sample("node-a", other, null, NOW, 9, null, false)));
        MatchSampleRepository.insertSamples(List.of());
        MatchSampleRepository.insertSamples(null);

        assertEquals(List.of(new MatchSample(null, "node-a", monitor.getId(), null, NOW, 3, null, false, seconds),
                        new MatchSample(null, "node-a", monitor.getId(), null, NOW.plusSeconds(10), 0, null, true, null),
                        new MatchSample(null, "node-b", monitor.getId(), null, NOW.plusSeconds(30), 0, null, true, null)),
                MatchSampleRepository.listCountRows(monitor.getId(), NOW, NOW.plusSeconds(30)),
                "per-second counts round-trip; the matched lines are not read");
    }

    @Test
    void channelRowsSumPerChannelAcrossNodes() {
        int id = monitor.getId();
        MatchSampleRepository.insertSamples(List.of(
                sample("node-a", id, CHANNEL_B, NOW, 1, null, true),
                sample("node-a", id, CHANNEL_A, NOW, 2, null, true),
                sample("node-b", id, CHANNEL_A, NOW.plusSeconds(30), 3, null, true),
                sample("node-b", id, CHANNEL_A, NOW.minusSeconds(60), 7, null, true),
                sample("node-a", id, null, NOW, 4, null, true)));

        assertEquals(List.of(new MatchSampleRepository.ChannelSum(CHANNEL_A, 5),
                        new MatchSampleRepository.ChannelSum(CHANNEL_B, 1)),
                MatchSampleRepository.sumSamplesByChannel(id, NOW, NOW.plusSeconds(30)));
    }

    @Test
    void recentSamplesAreOneNodesNewestRowsThatKeepSamplesWithEveryColumn() {
        int id = monitor.getId();
        String large = "[{\"message\":\"" + "x".repeat(5_000) + "\"}]";
        MatchSampleRepository.insertSamples(List.of(
                sample("node-a", id, null, NOW.minusSeconds(60), 1, "[\"oldest\"]", true),
                sample("node-a", id, CHANNEL_A, NOW.minusSeconds(30), 1, large, false),
                sample("node-a", id, null, NOW, 0, null, true),
                sample("node-b", id, null, NOW, 1, "[\"other node\"]", true)));

        List<MatchSample> recent = MatchSampleRepository.listRecentSamples(id, "node-a", NOW.minusSeconds(300), NOW, 2);
        assertEquals(2, recent.size());
        MatchSample newest = recent.get(0);
        assertNotNull(newest.id());
        assertEquals(new MatchSample(newest.id(), "node-a", id, CHANNEL_A, NOW.minusSeconds(30), 1, large, false),
                newest, "every column round-trips, the CLOB and complete=false included");
        assertEquals("[\"oldest\"]", recent.get(1).samplesJson());
        assertTrue(recent.get(1).complete());
        assertEquals(1, MatchSampleRepository.listRecentSamples(id, "node-a", NOW.minusSeconds(300), NOW, 1).size());
    }

    @Test
    void thePruneRunsInChunksAndAMonitorDeleteCascades() throws Exception {
        List<MatchSample> samples = new ArrayList<>();
        for (int i = 0; i < ChunkedDelete.CHUNK_SIZE + 3; i++) {
            samples.add(sample("node-a", monitor.getId(), null, NOW.minusSeconds(3_600).plusMillis(i), 1, null, true));
        }
        samples.add(sample("node-a", monitor.getId(), null, NOW, 1, null, true));
        MatchSampleRepository.insertSamples(samples);

        assertEquals(ChunkedDelete.CHUNK_SIZE + 3,
                MatchSampleRepository.deleteSamplesOlderThan(NOW.minusSeconds(60), LeaseFence.unmanaged()));
        assertEquals(1, count("sentinel_match_sample"));
        MonitorRepository.deleteMonitor(monitor.getId());
        assertEquals(0, count("sentinel_match_sample"));
    }

    @Test
    void aServerTriggerStateRoundTripsAndIsNeverConfusedWithAChannelRow() {
        TriggerState server = state(SubjectType.SERVER, null);
        TriggerStateRepository.insertTriggerState(server);
        TriggerState channel = state(SubjectType.CHANNEL, CHANNEL_A);
        TriggerStateRepository.insertTriggerState(channel);

        TriggerState found = TriggerStateRepository.getTriggerState(monitor.getId(), SubjectType.SERVER, null, null);
        assertEquals(server.getId(), found.getId());
        assertEquals(SubjectType.SERVER, found.getSubjectType());
        assertNull(found.getChannelId());
        assertNull(found.getMetadataId());
        assertEquals(TriggerStatus.PROBLEM, found.getState());
        assertEquals(NOW, found.getLastEvaluatedTime());

        assertEquals(channel.getId(), TriggerStateRepository.getTriggerState(monitor.getId(), CHANNEL_A, null).getId());
        assertNull(TriggerStateRepository.getTriggerState(monitor.getId(), SubjectType.CHANNEL, null, null),
                "a channel lookup without a channel must not return the server row");
        assertNull(TriggerStateRepository.getTriggerState(monitor.getId(), SubjectType.SERVER, CHANNEL_A, null),
                "a server lookup must not return a channel row");

        found.setConsecutiveBreachCount(4);
        TriggerStateRepository.updateTriggerState(found);
        assertEquals(4, TriggerStateRepository.getTriggerState(monitor.getId(), SubjectType.SERVER, null, null)
                .getConsecutiveBreachCount());

        Map<Integer, SubjectType> listed = new HashMap<>();
        for (TriggerState state : TriggerStateRepository.listTriggerStatesByMonitor(monitor.getId())) {
            listed.put(state.getId(), state.getSubjectType());
        }
        assertEquals(Map.of(server.getId(), SubjectType.SERVER, channel.getId(), SubjectType.CHANNEL), listed);
    }

    @Test
    void aServerAlertEventRoundTripsWithANullChannelAndIsHiddenFromAChannelRestrictedRead() {
        AlertEvent event = problem("3 matching log lines in the last 300s (at least 1)", AlertStatus.PROBLEM);
        event.setProblemPending(true);
        AlertEventRepository.insertAlertEvent(event);

        AlertEvent read = AlertEventRepository.getAlertEvent(event.getId());
        assertEquals(SubjectType.SERVER, read.getSubjectType());
        assertNull(read.getChannelId());
        assertNull(read.getMetadataId());
        assertEquals("{\"count\":3}", read.getDetailsJson());

        assertServerRow(event.getId(), AlertEventRepository.listPendingProblemAlertEvents());
        assertServerRow(event.getId(), AlertEventRepository.listRecentAlertEvents(10));
        AlertEventFilter filter = new AlertEventFilter();
        filter.setMonitorId(monitor.getId());
        filter.setPageSize(25);
        assertServerRow(event.getId(), AlertEventRepository.listAlertEvents(filter).items());

        AlertEventFilter restricted = new AlertEventFilter();
        restricted.setChannelIdIn(List.of(CHANNEL_A));
        restricted.setPageSize(25);
        assertEquals(0L, AlertEventRepository.listAlertEvents(restricted).total());
    }

    @Test
    void rowsWithoutASubjectOrKindReadAsChannelProblems() throws Exception {
        try (Connection connection = database.connection(); Statement sql = connection.createStatement()) {
            // Only an unsupported downgrade writes these into a v14 schema.
            sql.executeUpdate("INSERT INTO sentinel_trigger_state (monitor_id, channel_id) VALUES ("
                    + monitor.getId() + ", '" + CHANNEL_A + "')");
            sql.executeUpdate("INSERT INTO sentinel_alert_event (monitor_id, channel_id, severity, status, message, "
                    + "opened_time) VALUES (" + monitor.getId() + ", '" + CHANNEL_A + "', 'HIGH', 'PROBLEM', 'legacy', "
                    + "CURRENT_TIMESTAMP)");
        }
        assertEquals(SubjectType.CHANNEL,
                TriggerStateRepository.listTriggerStatesByMonitor(monitor.getId()).get(0).getSubjectType());
        AlertEvent event = AlertEventRepository.listRecentAlertEvents(1).get(0);
        assertEquals("legacy", event.getMessage());
        assertEquals(SubjectType.CHANNEL, event.getSubjectType());
        assertEquals(AlertKind.PROBLEM, event.getKind());
    }

    @Test
    void aNotificationRoundTripsAndTheKindKeepsItOutOfProblemReads() {
        AlertEvent problem = AlertEventRepository.insertAlertEvent(problem("a problem", AlertStatus.PROBLEM));
        AlertEvent notification = AlertEventRepository.insertAlertEvent(
                LogNotifications.notification(monitor, "a notification", "{\"count\":1}", NOW.plusSeconds(1)));

        AlertEvent read = AlertEventRepository.getAlertEvent(notification.getId());
        assertEquals(AlertKind.NOTIFICATION, read.getKind());
        assertEquals(AlertStatus.RESOLVED, read.getStatus());
        assertEquals(NOW.plusSeconds(1), read.getResolvedTime());
        assertTrue(read.isProblemPending());
        assertFalse(read.isResolutionPending());
        assertEquals(List.of(notification.getId()),
                AlertEventRepository.listPendingProblemAlertEvents().stream().map(AlertEvent::getId)
                        .filter(notification.getId()::equals).toList(), "the outbox pass sees a pending notification");

        AlertEventFilter filter = new AlertEventFilter();
        filter.setPageSize(25);
        assertEquals(2L, AlertEventRepository.listAlertEvents(filter).total());
        for (AlertEvent expected : List.of(problem, notification)) {
            filter.setKind(expected.getKind());
            PagedResult<AlertEvent> page = AlertEventRepository.listAlertEvents(filter);
            assertEquals(List.of(expected.getId()), page.items().stream().map(AlertEvent::getId).toList());
            assertEquals(1L, page.total(), expected.getMessage());
        }

        assertEquals(List.of(notification.getId(), problem.getId()), ids(AlertEventRepository.listRecentAlertEvents(10)));
        assertEquals(List.of(problem.getId()), ids(AlertEventRepository.listRecentAlertEvents(10, AlertKind.PROBLEM)));
        assertEquals(List.of(notification.getId()),
                ids(AlertEventRepository.listRecentAlertEvents(10, AlertKind.NOTIFICATION)));
        assertEquals(1L, AlertEventRepository.listMonitorHistory(monitor.getId(), NOW.minusSeconds(60),
                NOW.plusSeconds(60)).stream().mapToLong(point -> point.getAlertCount()).sum(), "history counts problems only");
    }

    @Test
    void detailsAreRewrittenOnAnOpenProblemAndNotOnAResolvedOne() {
        AlertEvent open = AlertEventRepository.insertAlertEvent(problem("open", AlertStatus.PROBLEM));
        AlertEvent resolved = problem("resolved", AlertStatus.RESOLVED);
        resolved.setResolvedTime(NOW);
        AlertEventRepository.insertAlertEvent(resolved);

        assertTrue(AlertEventRepository.updateAlertEventDetails(open.getId(), "{\"count\":9}"));
        assertFalse(AlertEventRepository.updateAlertEventDetails(resolved.getId(), "{\"count\":9}"));
        assertEquals("{\"count\":9}", AlertEventRepository.getAlertEvent(open.getId()).getDetailsJson());
        assertEquals("{\"count\":3}", AlertEventRepository.getAlertEvent(resolved.getId()).getDetailsJson());
    }

    private AlertEvent problem(String message, AlertStatus status) {
        AlertEvent event = new AlertEvent();
        event.setMonitorId(monitor.getId());
        event.setSubjectType(SubjectType.SERVER);
        event.setSeverity(Severity.HIGH);
        event.setStatus(status);
        event.setMessage(message);
        event.setOpenedTime(NOW);
        event.setDetailsJson("{\"count\":3}");
        return event;
    }

    private TriggerState state(SubjectType subject, String channel) {
        TriggerState state = new TriggerState();
        state.setMonitorId(monitor.getId());
        state.setSubjectType(subject);
        state.setChannelId(channel);
        state.setState(TriggerStatus.PROBLEM);
        state.setConsecutiveBreachCount(1);
        state.setLastChangeTime(NOW);
        state.setLastEvaluatedTime(NOW);
        return state;
    }

    private int count(String table) throws Exception {
        try (Connection connection = database.connection(); Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static List<Long> ids(List<AlertEvent> events) {
        return events.stream().map(AlertEvent::getId).toList();
    }

    private static void assertServerRow(long id, List<AlertEvent> events) {
        AlertEvent event = events.stream().filter(candidate -> candidate.getId() == id).findFirst()
                .orElseThrow(() -> new AssertionError("server event " + id + " missing"));
        assertEquals(SubjectType.SERVER, event.getSubjectType());
        assertNull(event.getChannelId());
    }

    private static MatchSample sample(String node, int monitor, String channel, Instant time, long count,
            String samplesJson, boolean complete) {
        return new MatchSample(null, node, monitor, channel, time, count, samplesJson, complete);
    }
}
