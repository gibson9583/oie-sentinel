/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ibatis.session.SqlSessionManager;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.mirth.connect.server.util.SqlConfig;

import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * Live alerting state written by an older schema and read back after the upgrade: an acknowledged open problem, a
 * problem with repeat and escalation dispatch rows (one of them a failed attempt), a pending open edge, a pending
 * resolution edge, and a storm-controlled action's two sends and rollup with one open held back. Each piece is
 * written only by source versions whose columns hold it: acknowledgement, repeats and dispatch rows from v1,
 * escalation and the ceiling from v4, resolution_pending from v9 and problem_pending from v10. After the migration
 * every value must be unchanged in the table and come back through the shipped repositories, which read the packaged
 * vendor mapper.
 */
final class UpgradeLiveStateMatrix {

    /** A full CHAR(36) id: Oracle compares a bound string to the column without padding. */
    private static final String CHANNEL = "00000000-0000-0000-0000-0000000000d4";
    /** Whole seconds: MySQL DATETIME keeps no fraction and SQL Server DATETIME rounds it. */
    private static final Instant OPENED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant ACKED = Instant.parse("2026-11-20T10:01:00Z");
    private static final Instant RESOLVED = Instant.parse("2026-11-20T10:30:00Z");
    private static final Instant STORM = Instant.parse("2026-11-20T12:00:00Z");
    private static final int ACK_USER = 7;
    private static final String ACK_COMMENT = "matrix live acknowledgement";
    /** The dispatcher's row markers (AlertStormControl.ROLLUP_MARKER, ActionDispatcher.RESOLUTION_MARKER). */
    private static final String ROLLUP = "[sentinel:rollup]";
    private static final String FAILURE = "matrix transport failure";

    private static final String ACKNOWLEDGED = "matrix-live-acknowledged";
    private static final String ESCALATING = "matrix-live-escalating";
    private static final String PENDING_OPEN = "matrix-live-pending-open";
    private static final String PENDING_RESOLUTION = "matrix-live-pending-resolution";
    private static final List<String> STORM_ALERTS = List.of(
            "matrix-live-storm-1", "matrix-live-storm-2", "matrix-live-storm-3", "matrix-live-storm-held");

    private UpgradeLiveStateMatrix() {
    }

    /** One dispatch row as written: alert message, action name, dispatch time, success, error text. */
    private record Row(String alert, String action, Instant at, boolean success, String error) {
    }

    /** Writes the state with the columns {@code schemaVersion} has. */
    static void insert(Connection connection, String vendor, int schemaVersion) throws Exception {
        execute(connection, "INSERT INTO sentinel_monitor (name, description, monitor_type, scope_type, scope_id, "
                + "enabled, severity, config_json, min_consecutive_breaches, suppressed_by_monitor_id, created_by, "
                + "created_time, updated_by, updated_time) VALUES ('matrix-live-monitor', NULL, 'INACTIVITY', 'ALL', "
                + "NULL, " + literal(vendor, true) + ", 'HIGH', '{}', 1, NULL, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)");
        long monitorId = id(connection, "SELECT id FROM sentinel_monitor WHERE name = 'matrix-live-monitor'");
        boolean v4 = schemaVersion >= 4;
        insertAction(connection, vendor, "matrix-live-target", "ON_PROBLEM", null, null, null, null, null, v4);
        Long target = id(connection, "SELECT id FROM sentinel_action WHERE name = 'matrix-live-target'");
        insertAction(connection, vendor, "matrix-live-repeat", "ON_PROBLEM", 60, 4, v4 ? 300 : null,
                v4 ? target : null, null, v4);
        if (v4) {
            insertAction(connection, vendor, "matrix-live-storm", "BOTH", null, null, null, null, 2, true);
        }

        insertAlert(connection, schemaVersion, monitorId, ACKNOWLEDGED, "PROBLEM", true, false, false);
        insertAlert(connection, schemaVersion, monitorId, ESCALATING, "PROBLEM", false, false, false);
        insertAlert(connection, schemaVersion, monitorId, PENDING_OPEN, "PROBLEM", false, true, false);
        insertAlert(connection, schemaVersion, monitorId, PENDING_RESOLUTION, "RESOLVED", false, false, true);
        if (v4) {
            for (String alert : STORM_ALERTS) {
                insertAlert(connection, schemaVersion, monitorId, alert, "PROBLEM", false,
                        alert.endsWith("held"), false);
            }
        }
        for (Row row : rows(schemaVersion)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sentinel_action_dispatch_log (alert_event_id, action_id, dispatch_time, success, "
                            + "error_message) VALUES (?, ?, ?, ?, ?)")) {
                statement.setLong(1, id(connection, "SELECT id FROM sentinel_alert_event WHERE message = '"
                        + row.alert() + "'"));
                statement.setLong(2, id(connection, "SELECT id FROM sentinel_action WHERE name = '" + row.action() + "'"));
                statement.setTimestamp(3, Timestamp.from(row.at()));
                statement.setBoolean(4, row.success());
                statement.setString(5, row.error());
                assertEquals(1, statement.executeUpdate());
            }
        }
    }

    /**
     * Reads the state back after the migration, first from the tables, then through the repositories. Then removes
     * it (the monitor's delete cascades to its alerts and their dispatch rows), so the rig's row-count assertions on
     * its own markers still hold.
     */
    static void verifyAndRemove(DatabaseMatrixSupport.MatrixDatabase database, Connection connection, int schemaVersion)
            throws Exception {
        String vendor = database.vendor();
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String alert : alerts(schemaVersion)) {
            ids.put(alert, id(connection, "SELECT id FROM sentinel_alert_event WHERE message = '" + alert + "'"));
        }
        assertTables(connection, vendor, schemaVersion, ids);
        assertRepositories(database, schemaVersion, ids);
        execute(connection, "DELETE FROM sentinel_monitor WHERE name = 'matrix-live-monitor'");
        execute(connection, "DELETE FROM sentinel_action WHERE name LIKE 'matrix-live-%'");
        assertEquals(0, count(connection, "SELECT COUNT(*) FROM sentinel_alert_event WHERE message LIKE 'matrix-live-%'"),
                vendor + " the live monitor's delete did not cascade to its alerts");
    }

    private static void assertTables(Connection connection, String vendor, int schemaVersion, Map<String, Long> ids)
            throws Exception {
        List<Object> observed = new ArrayList<>();
        for (Map.Entry<String, Long> alert : ids.entrySet()) {
            try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery(
                    "SELECT status, channel_id, opened_time, resolved_time, acknowledged_by, acknowledged_time, "
                            + "ack_comment, subject_type, event_kind, problem_pending, resolution_pending "
                            + "FROM sentinel_alert_event WHERE id = " + alert.getValue())) {
                assertTrue(row.next(), alert.getKey());
                String label = vendor + " v" + schemaVersion + " " + alert.getKey();
                boolean resolved = alert.getKey().equals(PENDING_RESOLUTION);
                assertEquals(resolved ? "RESOLVED" : "PROBLEM", row.getString(1), label + " status");
                assertEquals(CHANNEL, row.getString(2).trim(), label + " channel_id");
                assertEquals(OPENED, row.getTimestamp(3).toInstant(), label + " opened_time");
                assertEquals(resolved ? RESOLVED : null, instant(row.getTimestamp(4)), label + " resolved_time");
                boolean acked = alert.getKey().equals(ACKNOWLEDGED);
                assertEquals(acked ? (Object) ACK_USER : null, integer(row, 5), label + " acknowledged_by");
                assertEquals(acked ? ACKED : null, instant(row.getTimestamp(6)), label + " acknowledged_time");
                assertEquals(acked ? ACK_COMMENT : null, row.getString(7), label + " ack_comment");
                assertEquals("CHANNEL", row.getString(8), label + " subject_type");
                assertEquals("PROBLEM", row.getString(9), label + " event_kind");
                assertEquals(problemPending(schemaVersion, alert.getKey()), flag(row.getObject(10)),
                        label + " problem_pending");
                assertEquals(resolutionPending(schemaVersion, alert.getKey()), flag(row.getObject(11)),
                        label + " resolution_pending");
                observed.add(alert.getKey() + " problem_pending=" + flag(row.getObject(10))
                        + " resolution_pending=" + flag(row.getObject(11)));
            }
        }
        List<String> stored = new ArrayList<>();
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery(
                "SELECT e.message, a.name, d.dispatch_time, d.success, d.error_message "
                        + "FROM sentinel_action_dispatch_log d JOIN sentinel_alert_event e ON d.alert_event_id = e.id "
                        + "JOIN sentinel_action a ON d.action_id = a.id WHERE e.message LIKE 'matrix-live-%'")) {
            while (row.next()) {
                stored.add(describe(row.getString(1), row.getString(2), row.getTimestamp(3).toInstant(),
                        Boolean.TRUE.equals(flag(row.getObject(4))), row.getString(5)));
            }
        }
        List<String> written = new ArrayList<>();
        for (Row row : rows(schemaVersion)) {
            written.add(describe(row.alert(), row.action(), row.at(), row.success(), row.error()));
        }
        assertEquals(new TreeSet<>(written), new TreeSet<>(stored), vendor + " v" + schemaVersion + " dispatch rows");
        assertEquals(written.size(), stored.size(), vendor + " v" + schemaVersion + " dispatch row count");
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery(
                "SELECT name, repeat_interval_seconds, max_repeats FROM sentinel_action WHERE name = 'matrix-live-repeat'")) {
            assertTrue(row.next());
            assertEquals(60, row.getInt(2), vendor + " repeat interval");
            assertEquals(4, row.getInt(3), vendor + " max repeats");
        }
        observed.add(stored);
        MatrixEvidenceSupport.observation(vendor, "live-state-from-" + schemaVersion, observed);
    }

    private static void assertRepositories(DatabaseMatrixSupport.MatrixDatabase database, int schemaVersion,
            Map<String, Long> ids) throws Exception {
        String label = database.vendor() + " v" + schemaVersion;
        SqlSessionManager sessions = MatrixSqlSessions.open(database);
        SqlConfig config = mock(SqlConfig.class);
        when(config.getSqlSessionManager()).thenReturn(sessions);
        try (MockedStatic<SqlConfig> engine = Mockito.mockStatic(SqlConfig.class)) {
            engine.when(SqlConfig::getInstance).thenReturn(config);

            for (Map.Entry<String, Long> alert : ids.entrySet()) {
                AlertEvent event = AlertEventRepository.getAlertEvent(alert.getValue());
                String which = label + " " + alert.getKey();
                assertNotNull(event, which);
                boolean acked = alert.getKey().equals(ACKNOWLEDGED);
                boolean resolved = alert.getKey().equals(PENDING_RESOLUTION);
                assertEquals(resolved ? AlertStatus.RESOLVED : AlertStatus.PROBLEM, event.getStatus(), which);
                assertEquals(AlertKind.PROBLEM, event.getKind(), which + " kind");
                assertEquals(SubjectType.CHANNEL, event.getSubjectType(), which + " subject");
                assertEquals(CHANNEL, event.getChannelId().trim(), which + " channel");
                assertEquals(OPENED, event.getOpenedTime(), which + " opened");
                assertEquals(resolved ? RESOLVED : null, event.getResolvedTime(), which + " resolved");
                assertEquals(acked ? (Object) ACK_USER : null, event.getAcknowledgedBy(), which + " acknowledged by");
                assertEquals(acked ? ACKED : null, event.getAcknowledgedTime(), which + " acknowledged at");
                assertEquals(acked ? ACK_COMMENT : null, event.getAckComment(), which + " comment");
                assertEquals(Boolean.TRUE.equals(problemPending(schemaVersion, alert.getKey())), event.isProblemPending(),
                        which + " problemPending");
                assertEquals(Boolean.TRUE.equals(resolutionPending(schemaVersion, alert.getKey())),
                        event.isResolutionPending(), which + " resolutionPending");
            }

            Set<String> pendingOpen = new TreeSet<>();
            Set<String> pendingResolution = new TreeSet<>();
            for (String alert : ids.keySet()) {
                if (Boolean.TRUE.equals(problemPending(schemaVersion, alert))) pendingOpen.add(alert);
                if (Boolean.TRUE.equals(resolutionPending(schemaVersion, alert))) pendingResolution.add(alert);
            }
            assertEquals(pendingOpen, messages(AlertEventRepository.listPendingProblemAlertEvents()),
                    label + " pending open edges");
            assertEquals(pendingResolution, messages(AlertEventRepository.listPendingResolvedAlertEvents()),
                    label + " pending resolution edges");
            assertTrue(AlertEventRepository.listAcknowledgingUserIds(List.of(CHANNEL)).contains(ACK_USER),
                    label + " acknowledging user lookup");

            List<String> escalating = new ArrayList<>();
            for (ActionDispatchLog row : ActionDispatchLogRepository.listActionDispatchLogsForEvent(ids.get(ESCALATING))) {
                escalating.add(describe(ESCALATING, actionName(row.getActionId()), row.getDispatchTime(), row.isSuccess(),
                        row.getErrorMessage()));
            }
            assertEquals(new TreeSet<>(written(schemaVersion, ESCALATING)), new TreeSet<>(escalating),
                    label + " repeat and escalation rows through the repository");

            Action repeat = ActionRepository.getAction(actionId("matrix-live-repeat"));
            assertEquals(60, repeat.getRepeatIntervalSeconds(), label);
            assertEquals(4, repeat.getMaxRepeats(), label);
            if (schemaVersion >= 4) {
                assertEquals(300, repeat.getEscalateAfterSeconds(), label);
                assertEquals(actionId("matrix-live-target"), repeat.getEscalateToActionId(), label);
                Action storm = ActionRepository.getAction(actionId("matrix-live-storm"));
                assertEquals(2, storm.getMaxNotificationsPerWindow(), label);
                assertEquals(300, storm.getRollupWindowSeconds(), label);
                // The storm-control window read: the dispatch rows by time, the rollup marked.
                List<String> window = new ArrayList<>();
                for (ActionDispatchLog row : ActionDispatchLogRepository.listActionDispatchLogsInRange(
                        STORM.minusSeconds(1), STORM.plusSeconds(60))) {
                    window.add(describe(alertMessage(row.getAlertEventId(), ids), actionName(row.getActionId()),
                            row.getDispatchTime(), row.isSuccess(), row.getErrorMessage()));
                }
                List<String> expected = new ArrayList<>();
                for (String alert : STORM_ALERTS) expected.addAll(written(schemaVersion, alert));
                assertEquals(new TreeSet<>(expected), new TreeSet<>(window), label + " storm window rows");
                assertEquals(1, window.stream().filter(row -> row.contains(ROLLUP)).count(), label + " one rollup");
            } else {
                assertNull(repeat.getEscalateAfterSeconds(), label);
            }
        } finally {
            if (sessions.isManagedSessionStarted()) sessions.close();
        }
    }

    /** problem_pending after the migration: as written from v10; v10's backfill sets it for every open problem. */
    private static Boolean problemPending(int schemaVersion, String alert) {
        boolean open = !alert.equals(PENDING_RESOLUTION);
        if (schemaVersion < 10) return open ? Boolean.TRUE : null;
        return alert.equals(PENDING_OPEN) || alert.endsWith("held");
    }

    /** resolution_pending after the migration: as written from v9; v9 adds the column empty. */
    private static Boolean resolutionPending(int schemaVersion, String alert) {
        if (schemaVersion < 9) return null;
        return alert.equals(PENDING_RESOLUTION);
    }

    private static List<String> alerts(int schemaVersion) {
        List<String> alerts = new ArrayList<>(List.of(ACKNOWLEDGED, ESCALATING, PENDING_OPEN, PENDING_RESOLUTION));
        if (schemaVersion >= 4) alerts.addAll(STORM_ALERTS);
        return alerts;
    }

    /** Every dispatch row the source version writes. */
    private static List<Row> rows(int schemaVersion) {
        List<Row> rows = new ArrayList<>(List.of(
                new Row(ACKNOWLEDGED, "matrix-live-repeat", OPENED, true, null),
                new Row(ESCALATING, "matrix-live-repeat", OPENED, true, null),
                new Row(ESCALATING, "matrix-live-repeat", OPENED.plusSeconds(60), true, null),
                new Row(ESCALATING, "matrix-live-repeat", OPENED.plusSeconds(120), false, FAILURE),
                new Row(ESCALATING, "matrix-live-repeat", OPENED.plusSeconds(180), true, null),
                new Row(PENDING_RESOLUTION, "matrix-live-repeat", OPENED, true, null)));
        if (schemaVersion >= 4) {
            rows.add(new Row(ESCALATING, "matrix-live-target", OPENED.plusSeconds(300), true, null));
            rows.add(new Row(STORM_ALERTS.get(0), "matrix-live-storm", STORM, true, null));
            rows.add(new Row(STORM_ALERTS.get(1), "matrix-live-storm", STORM.plusSeconds(1), true, null));
            rows.add(new Row(STORM_ALERTS.get(2), "matrix-live-storm", STORM.plusSeconds(2), true, ROLLUP));
        }
        return rows;
    }

    private static List<String> written(int schemaVersion, String alert) {
        List<String> found = new ArrayList<>();
        for (Row row : rows(schemaVersion)) {
            if (row.alert().equals(alert)) {
                found.add(describe(row.alert(), row.action(), row.at(), row.success(), row.error()));
            }
        }
        return found;
    }

    private static String describe(String alert, String action, Instant at, boolean success, String error) {
        return alert + " | " + action + " | " + at + " | " + (success ? "success" : "failure") + " | " + error;
    }

    private static void insertAction(Connection connection, String vendor, String name, String mode, Integer repeat,
            Integer maxRepeats, Integer escalateAfter, Long escalateTo, Integer ceiling, boolean v4) throws Exception {
        String columns = "name, description, enabled, action_type, condition_json, operation_mode, "
                + "repeat_interval_seconds, max_repeats, config_json, created_by, created_time, updated_by, updated_time"
                + (v4 ? ", max_notifications_per_window, rollup_window_seconds, escalate_after_seconds, "
                        + "escalate_to_action_id" : "");
        String values = "?, NULL, " + literal(vendor, true) + ", 'EMAIL', '[]', ?, ?, ?, '{}', 1, CURRENT_TIMESTAMP, 1, "
                + "CURRENT_TIMESTAMP" + (v4 ? ", ?, ?, ?, ?" : "");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sentinel_action (" + columns + ") VALUES (" + values + ")")) {
            statement.setString(1, name);
            statement.setString(2, mode);
            setInteger(statement, 3, repeat);
            setInteger(statement, 4, maxRepeats);
            if (v4) {
                setInteger(statement, 5, ceiling);
                setInteger(statement, 6, ceiling != null ? 300 : null);
                setInteger(statement, 7, escalateAfter);
                setInteger(statement, 8, escalateTo != null ? escalateTo.intValue() : null);
            }
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertAlert(Connection connection, int schemaVersion, long monitorId, String message,
            String status, boolean acknowledged, boolean problemPending, boolean resolutionPending) throws Exception {
        boolean resolved = status.equals("RESOLVED");
        StringBuilder columns = new StringBuilder("monitor_id, channel_id, metadata_id, severity, status, message, "
                + "opened_time, resolved_time, acknowledged_by, acknowledged_time, ack_comment, suppressed");
        StringBuilder values = new StringBuilder("?, ?, NULL, 'HIGH', ?, ?, ?, ?, ?, ?, ?, ?");
        if (schemaVersion >= 9) {
            columns.append(", resolution_pending");
            values.append(", ?");
        }
        if (schemaVersion >= 10) {
            columns.append(", problem_pending");
            values.append(", ?");
        }
        if (schemaVersion >= 14) {
            columns.append(", subject_type, event_kind");
            values.append(", 'CHANNEL', 'PROBLEM'");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sentinel_alert_event (" + columns + ") VALUES (" + values + ")")) {
            int i = 1;
            statement.setLong(i++, monitorId);
            statement.setString(i++, CHANNEL);
            statement.setString(i++, status);
            statement.setString(i++, message);
            statement.setTimestamp(i++, Timestamp.from(OPENED));
            statement.setTimestamp(i++, resolved ? Timestamp.from(RESOLVED) : null);
            setInteger(statement, i++, acknowledged ? ACK_USER : null);
            statement.setTimestamp(i++, acknowledged ? Timestamp.from(ACKED) : null);
            statement.setString(i++, acknowledged ? ACK_COMMENT : null);
            statement.setBoolean(i++, false);
            if (schemaVersion >= 9) statement.setBoolean(i++, resolutionPending);
            if (schemaVersion >= 10) statement.setBoolean(i++, problemPending);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static Set<String> messages(List<AlertEvent> events) {
        Set<String> found = new TreeSet<>();
        for (AlertEvent event : events) {
            if (event.getMessage() != null && event.getMessage().startsWith("matrix-live-")) found.add(event.getMessage());
        }
        return found;
    }

    private static String alertMessage(long alertId, Map<String, Long> ids) {
        return ids.entrySet().stream().filter(e -> e.getValue() == alertId).map(Map.Entry::getKey)
                .findFirst().orElse("alert " + alertId);
    }

    private static String actionName(Integer id) {
        Action action = id == null ? null : ActionRepository.getAction(id);
        return action == null ? "action " + id : action.getName();
    }

    private static int actionId(String name) {
        return ActionRepository.listActions(null).stream().filter(a -> name.equals(a.getName()))
                .map(Action::getId).findFirst().orElseThrow(() -> new AssertionError("no action " + name));
    }

    private static Boolean flag(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(value.toString().trim()) || "1".equals(value.toString().trim());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet row, int column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.INTEGER);
        else statement.setInt(index, value);
    }

    private static String literal(String vendor, boolean value) {
        boolean numeric = "oracle".equals(vendor) || "sqlserver".equals(vendor);
        return numeric ? (value ? "1" : "0") : (value ? "TRUE" : "FALSE");
    }

    private static long id(Connection connection, String select) throws Exception {
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery(select)) {
            assertTrue(row.next(), select);
            long id = row.getLong(1);
            assertFalse(row.next(), "more than one row: " + select);
            return id;
        }
    }

    private static long count(Connection connection, String select) throws Exception {
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery(select)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
