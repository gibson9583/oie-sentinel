/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

/** Executes every statement in an exact packaged vendor mapper against its real database. */
final class MapperStatementMatrix {

    private static final int ACCEPTANCE_MINIMUM_STATEMENTS = 49;

    private MapperStatementMatrix() {
    }

    static void verifyAll(DatabaseMatrixSupport.MatrixDatabase database) throws Exception {
        Configuration configuration = loadPackagedMapper(database.vendor());
        List<String> statementIds = configuration.getMappedStatementNames().stream()
                .filter((id) -> id.startsWith("Sentinel."))
                .sorted()
                .toList();
        assertTrue(statementIds.size() >= ACCEPTANCE_MINIMUM_STATEMENTS,
                database.vendor() + " mapper exposed only " + statementIds.size()
                        + " statements; expected at least " + ACCEPTANCE_MINIMUM_STATEMENTS);

        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(configuration);
        Seed seed = seed(database);
        for (String statementId : statementIds) {
            executeAndRollback(database, sessions, statementId, seed);
        }
        verifyPendingOutboxValues(database, sessions, seed);
        verifyActiveDeploymentInventory(database, sessions, seed);
    }

    /** Syntax-only execution misses result-map casing errors on Derby and Oracle. */
    private static void verifyPendingOutboxValues(DatabaseMatrixSupport.MatrixDatabase database,
            SqlSessionFactory sessions, Seed seed) throws Exception {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            SqlSession session = sessions.openSession(connection);
            try {
                String bool = "oracle".equals(database.vendor()) || "sqlserver".equals(database.vendor()) ? "1" : "TRUE";
                try (Statement sql = connection.createStatement()) {
                    sql.executeUpdate("UPDATE sentinel_alert_event SET status = 'RESOLVED', "
                            + "resolved_time = CURRENT_TIMESTAMP, problem_pending = " + bool
                            + ", resolution_pending = " + bool + " WHERE id = " + seed.alertId());
                }
                for (String id : List.of("listPendingProblemAlertEvents", "listPendingResolvedAlertEvents")) {
                    List<Map<String, Object>> rows = session.selectList("Sentinel." + id);
                    assertEquals(1, rows.size(), database.vendor() + " pending scan " + id);
                    Map<String, Object> row = rows.get(0);
                    MatrixEvidenceSupport.observation(database.vendor(), id + "-values", rows);
                    assertNotNull(row.get("id"), database.vendor() + " lower-case id missing in " + id);
                    assertEquals(seed.alertId(), ((Number) row.get("id")).longValue());
                    assertEquals(seed.monitorId(), ((Number) row.get("monitor_id")).longValue());
                    assertEquals("RESOLVED", row.get("status"));
                    for (String flag : List.of("problem_pending", "resolution_pending")) {
                        Object value = row.get(flag);
                        assertNotNull(value, database.vendor() + " missing outbox flag " + flag);
                        assertTrue(Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1,
                                database.vendor() + " false outbox flag " + flag + "=" + value);
                    }
                }
            } finally {
                session.rollback();
                connection.rollback();
                session.close();
            }
        }
    }

    /** One node's expiry removes only its channels; shared deployments remain visible. */
    private static void verifyActiveDeploymentInventory(DatabaseMatrixSupport.MatrixDatabase database,
            SqlSessionFactory sessions, Seed seed) throws Exception {
        String channelA = "00000000-0000-0000-0000-000000000011";
        String channelB = "00000000-0000-0000-0000-000000000012";
        Timestamp deployed = Timestamp.from(Instant.parse("2026-08-30T12:00:00.123Z"));
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            SqlSession session = sessions.openSession(connection);
            try {
                for (String node : List.of("inventory-a", "inventory-b")) {
                    Map<String, Object> lease = parameters(seed);
                    lease.put("lease_name", "sentinel-presence-" + node);
                    lease.put("node_id", node);
                    session.insert("Sentinel.insertNodeLease", lease);
                    session.insert("Sentinel.insertChannelPresence", Map.of("channelId", channelA, "nodeId", node, "metadataId", 0, "deployedTime", deployed));
                }
                session.insert("Sentinel.insertChannelPresence", Map.of("channelId", channelB, "nodeId", "inventory-b", "metadataId", 0, "deployedTime", deployed));
                session.insert("Sentinel.insertChannelPresence", Map.of("channelId", channelA, "nodeId", "inventory-a", "metadataId", 1, "deployedTime", deployed));
                session.insert("Sentinel.insertChannelPresence", Map.of("channelId", channelA, "nodeId", "inventory-b", "metadataId", 2, "deployedTime", deployed));
                assertEquals(java.util.Set.of("0/inventory-a", "1/inventory-a", "0/inventory-b", "2/inventory-b"),
                        connectorNodes(session, channelA), database.vendor() + " connector inventory lost runtime identities");
                MatrixEvidenceSupport.observation(database.vendor(), "active-connector-inventory",
                        session.selectList("Sentinel.listActiveConnectorNodes", Map.of("channelId", channelA)));
                assertEquals(java.util.Set.of(channelA, channelB), new java.util.HashSet<>(
                        session.<String>selectList("Sentinel.listActiveDeployedChannelIds")), database.vendor());
                assertEquals(java.util.Set.of("inventory-a", "inventory-b"), new java.util.HashSet<>(
                        session.<String>selectList("Sentinel.listActiveDeployedNodeIds", Map.of("channelId", channelA))),
                        database.vendor());

                Map<String, Object> observation = parameters(seed);
                observation.put("channel_id", channelA);
                observation.put("node_id", "inventory-a");
                observation.put("metadata_id", 0);
                session.insert("Sentinel.insertConnectorStatusEvent", observation);
                List<Map<String, Object>> events = session.selectList("Sentinel.listLatestConnectorStatusEvents",
                        Map.of("channelId", channelA));
                assertEquals(1, events.size());
                assertEquals(deployed, events.get(0).get("deployment_time"), database.vendor() + " lost observation deployment identity");
                MatrixEvidenceSupport.observation(database.vendor(), "deployed-connector-observation", events);

                session.update("Sentinel.releaseNodeLease", Map.of("leaseName", "sentinel-presence-inventory-b",
                        "nodeId", "inventory-b", "leaseEpoch", 1L));
                assertEquals(List.of(channelA), session.<String>selectList("Sentinel.listActiveDeployedChannelIds"),
                        database.vendor() + " expired node still contributed deployment inventory");
                assertEquals(List.of("inventory-a"), session.<String>selectList("Sentinel.listActiveDeployedNodeIds",
                        Map.of("channelId", channelA)), database.vendor());
                assertEquals(java.util.Set.of("0/inventory-a", "1/inventory-a"), connectorNodes(session, channelA),
                        database.vendor() + " expired node remained in connector inventory");
                session.delete("Sentinel.deleteChannelPresenceByNode", Map.of("nodeId", "inventory-a"));
                List<String> finalChannels = session.selectList("Sentinel.listActiveDeployedChannelIds");
                assertTrue(finalChannels.isEmpty(), database.vendor() + " empty replacement snapshot retained old channels");
                MatrixEvidenceSupport.observation(database.vendor(), "final-active-inventory", finalChannels);
            } finally {
                session.rollback();
                connection.rollback();
                session.close();
            }
        }
    }

    private static java.util.Set<String> connectorNodes(SqlSession session, String channelId) {
        java.util.Set<String> nodes = new java.util.HashSet<>();
        List<Map<String, Object>> rows = session.selectList("Sentinel.listActiveConnectorNodes", Map.of("channelId", channelId));
        for (Map<String, Object> row : rows) {
            assertNotNull(row.get("metadata_id"), "Connector metadata result key is missing");
            assertNotNull(row.get("node_id"), "Connector node result key is missing");
            assertNotNull(row.get("deployed_time"), "Deployment timestamp result key is missing");
            assertEquals(Timestamp.from(Instant.parse("2026-08-30T12:00:00.123Z")), row.get("deployed_time"));
            nodes.add(((Number) row.get("metadata_id")).intValue() + "/" + row.get("node_id"));
        }
        return nodes;
    }

    private static Configuration loadPackagedMapper(String vendor) throws Exception {
        String resource = "/mapper/" + vendor + "-sqlmap.xml";
        InputStream stream = MapperStatementMatrix.class.getResourceAsStream(resource);
        assertNotNull(stream, "Packaged mapper is absent from test classpath: " + resource);
        try (stream) {
            Configuration configuration = new Configuration();
            configuration.setJdbcTypeForNull(org.apache.ibatis.type.JdbcType.VARCHAR);
            XMLMapperBuilder parser = new XMLMapperBuilder(
                    stream, configuration, resource, configuration.getSqlFragments());
            parser.parse();
            return configuration;
        }
    }

    private static Seed seed(DatabaseMatrixSupport.MatrixDatabase database) throws Exception {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                long monitorId = seedMonitor(connection, database.vendor());
                long actionId = seedAction(connection, database.vendor());
                long alertId = seedAlert(connection, database.vendor(), monitorId);
                seedTrigger(connection, monitorId, alertId);
                seedLease(connection);
                connection.commit();
                return new Seed(monitorId, actionId, alertId);
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            }
        }
    }

    private static void executeAndRollback(DatabaseMatrixSupport.MatrixDatabase database,
            SqlSessionFactory sessions, String statementId, Seed seed) throws Exception {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            SqlSession session = sessions.openSession(connection);
            try {
                Map<String, Object> parameters = parameters(seed);
                avoidDestructiveFixtureDeletes(statementId, parameters);
                MappedStatement statement = sessions.getConfiguration().getMappedStatement(statementId);
                try {
                    SqlCommandType command = statement.getSqlCommandType();
                    if (command == SqlCommandType.SELECT) {
                        session.selectList(statementId, parameters);
                    } else if (command == SqlCommandType.INSERT) {
                        session.insert(statementId, parameters);
                    } else if (command == SqlCommandType.UPDATE) {
                        session.update(statementId, parameters);
                    } else if (command == SqlCommandType.DELETE) {
                        session.delete(statementId, parameters);
                    } else {
                        fail(database.vendor() + " mapper statement " + statementId
                                + " has unsupported command " + command);
                    }
                } catch (Throwable failure) {
                    throw new AssertionError(database.vendor() + " mapper statement " + statementId
                            + " failed against the real database", failure);
                } finally {
                    session.rollback();
                }
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                session.close();
            }
        }
    }

    private static void avoidDestructiveFixtureDeletes(String statementId,
            Map<String, Object> parameters) {
        if (statementId.endsWith("deleteMonitor")
                || statementId.endsWith("deleteAction")
                || statementId.endsWith("deleteMaintenanceWindow")) {
            parameters.put("id", -999L);
        }
        if (statementId.endsWith("deleteMessageLatencyProgress")) {
            parameters.put("channelId", "matrix-missing-channel");
        }
    }

    private static Map<String, Object> parameters(Seed seed) {
        long monitorId = seed == null ? 1L : seed.monitorId();
        long actionId = seed == null ? 1L : seed.actionId();
        long alertId = seed == null ? 1L : seed.alertId();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-31T12:00:00Z"));
        Timestamp earlier = Timestamp.from(Instant.parse("2026-08-30T12:00:00.123Z"));

        Map<String, Object> values = new HashMap<>();
        values.put("id", monitorId);
        values.put("name", "matrix-exercise-row");
        values.put("description", "database matrix exercise");
        values.put("monitor_type", "INACTIVITY");
        values.put("monitorType", "INACTIVITY");
        values.put("scope_type", "ALL");
        values.put("scopeType", "ALL");
        values.put("scope_id", "matrix-scope");
        values.put("scopeId", "matrix-scope");
        values.put("enabled", Boolean.TRUE);
        values.put("severity", "HIGH");
        values.put("severityIn", new ArrayList<>(List.of("HIGH")));
        values.put("config_json", "{}");
        values.put("condition_json", "{}");
        values.put("details_json", "{}");
        values.put("min_consecutive_breaches", 1);
        values.put("suppressed_by_monitor_id", monitorId);
        values.put("runbook_url", "https://example.invalid/runbook");
        values.put("created_by", 1);
        values.put("created_time", now);
        values.put("updated_by", 1);
        values.put("updated_time", now);

        values.put("monitor_id", monitorId);
        values.put("monitorId", monitorId);
        values.put("channel_id", "matrix-channel");
        values.put("channelId", "matrix-channel");
        values.put("channelIdIn", new ArrayList<>(List.of("matrix-channel")));
        values.put("metadata_id", 1);
        values.put("metadataId", 1);
        values.put("status", "PROBLEM");
        values.put("message", "matrix exercise alert");
        values.put("opened_time", now);
        values.put("resolved_time", now);
        values.put("acknowledged_by", 1);
        values.put("acknowledged_time", now);
        values.put("ack_comment", "matrix acknowledgement");
        values.put("acknowledged", Boolean.TRUE);
        values.put("suppressed", Boolean.FALSE);
        values.put("problem_pending", Boolean.TRUE);
        values.put("problemPending", Boolean.TRUE);
        values.put("resolution_pending", Boolean.TRUE);
        values.put("resolutionPending", Boolean.TRUE);
        values.put("alertEventId", alertId);
        values.put("alertId", alertId);
        values.put("now", now);
        values.put("alert_event_id", alertId);

        values.put("state", "PROBLEM");
        values.put("consecutive_breach_count", 1);
        values.put("last_value_json", "{}");
        values.put("open_alert_event_id", alertId);
        values.put("last_change_time", now);
        values.put("last_evaluated_time", now);
        values.put("expected_last_evaluated_time", now);
        values.put("lastChangeTime", now);

        values.put("sample_time", now);
        values.put("received_delta", 1L);
        values.put("sent_delta", 1L);
        values.put("error_delta", 0L);
        values.put("filtered_delta", 0L);
        values.put("queued_snapshot", 0L);
        values.put("latency_message_count", 1L);
        values.put("latency_total_ms", 10L);
        values.put("latency_max_ms", 10L);
        values.put("latency_coverage_truncated", Boolean.FALSE);
        values.put("local_channel_id", 1);
        values.put("localChannelId", 1);
        values.put("last_message_id", 1L);
        values.put("pending_ids", "[1]");
        values.put("coverage_poisoned", Boolean.FALSE);
        values.put("backlog_pending", Boolean.FALSE);
        values.put("afterMessageId", 0L);
        values.put("messageIds", new ArrayList<>(List.of(1L)));

        values.put("hour_bucket", now);
        values.put("hourBucket", now);
        values.put("sinceHourBucket", earlier);
        values.put("received_sum", 1L);
        values.put("sent_sum", 1L);
        values.put("error_sum", 0L);
        values.put("avg_queued", 0.0);
        values.put("min_queued", 0L);
        values.put("max_queued", 0L);
        values.put("deployedTime", earlier);
        values.put("deployment_time", earlier);
        values.put("node_id", "matrix-node");
        values.put("nodeId", "matrix-node");
        values.put("previous_state", "STARTED");
        values.put("new_state", "STOPPED");
        values.put("changed_time", now);

        values.put("action_id", actionId);
        values.put("action_type", "EMAIL");
        values.put("operation_mode", "ONCE_PER_ALERT");
        values.put("repeat_interval_seconds", 60);
        values.put("max_repeats", 1);
        values.put("max_notifications_per_window", 1);
        values.put("rollup_window_seconds", 60);
        values.put("escalate_after_seconds", 60);
        values.put("escalate_to_action_id", actionId);
        values.put("dispatch_time", now);
        values.put("success", Boolean.TRUE);
        values.put("error_message", "");

        values.put("window_mode", "RECURRING");
        values.put("repeat_type", "DAILY");
        values.put("days_of_week", "1");
        values.put("days_of_month", "1");
        values.put("start_time", "08:00");
        values.put("end_time", "17:00");
        values.put("timezone", "UTC");
        values.put("active_from", earlier);
        values.put("active_until", now);

        values.put("lease_name", "matrix-new-lease");
        values.put("leaseName", "matrix-existing-lease");
        values.put("leaseSeconds", 60);
        values.put("lease_epoch", 1L);
        values.put("leaseEpoch", 1L);
        values.put("expectedLeaseEpoch", 1L);

        values.put("from", earlier);
        values.put("to", now);
        values.put("cutoff", now);
        values.put("q", "matrix");
        values.put("offset", 0);
        values.put("limit", 10);
        values.put("chunkSize", 10);
        values.put("sortColumn", "opened_time");
        values.put("sortDir", "ASC");
        return values;
    }

    private static long seedMonitor(Connection connection, String vendor) throws Exception {
        String booleanTrue = vendor.equals("oracle") || vendor.equals("sqlserver") ? "1" : "TRUE";
        String insert = "INSERT INTO sentinel_monitor (name, description, monitor_type, scope_type, "
                + "scope_id, enabled, severity, config_json, min_consecutive_breaches, "
                + "suppressed_by_monitor_id, runbook_url, created_by, created_time, updated_by, updated_time) "
                + "VALUES ('matrix-seed-monitor', 'database matrix seed', 'INACTIVITY', 'ALL', "
                + "'matrix-seed-scope', " + booleanTrue
                + ", 'HIGH', '{}', 1, NULL, 'https://example.invalid/runbook', "
                + "1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)";
        return insertAndReadId(connection, insert,
                "SELECT id FROM sentinel_monitor WHERE name = 'matrix-seed-monitor'", "monitor");
    }

    private static long seedAction(Connection connection, String vendor) throws Exception {
        String booleanTrue = vendor.equals("oracle") || vendor.equals("sqlserver") ? "1" : "TRUE";
        String insert = "INSERT INTO sentinel_action (name, description, enabled, action_type, "
                + "condition_json, operation_mode, config_json, created_by, created_time, updated_by, updated_time) "
                + "VALUES ('matrix-seed-action', 'database matrix seed', " + booleanTrue
                + ", 'EMAIL', '{}', 'ONCE_PER_ALERT', '{}', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)";
        return insertAndReadId(connection, insert,
                "SELECT id FROM sentinel_action WHERE name = 'matrix-seed-action'", "action");
    }

    private static long seedAlert(Connection connection, String vendor, long monitorId)
            throws Exception {
        String booleanFalse = vendor.equals("oracle") || vendor.equals("sqlserver") ? "0" : "FALSE";
        String insert = "INSERT INTO sentinel_alert_event (monitor_id, channel_id, metadata_id, "
                + "severity, status, message, opened_time, details_json, suppressed) VALUES ("
                + monitorId + ", '00000000-0000-0000-0000-000000000001', 1, 'HIGH', 'PROBLEM', "
                + "'matrix seed alert', CURRENT_TIMESTAMP, '{}', " + booleanFalse + ")";
        return insertAndReadId(connection, insert,
                "SELECT id FROM sentinel_alert_event WHERE message = 'matrix seed alert'", "alert event");
    }

    private static void seedTrigger(Connection connection, long monitorId, long alertId)
            throws Exception {
        String insert = "INSERT INTO sentinel_trigger_state (monitor_id, channel_id, metadata_id, "
                + "state, consecutive_breach_count, last_value_json, open_alert_event_id, "
                + "last_change_time, last_evaluated_time) VALUES (" + monitorId
                + ", 'matrix-seed-channel', 1, 'PROBLEM', 1, '{}', " + alertId
                + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(insert);
        }
    }

    private static void seedLease(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sentinel_node_lease "
                    + "(lease_name, node_id, acquired_time, expires_time, lease_epoch) VALUES "
                    + "('matrix-existing-lease', 'matrix-node', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)");
        }
    }

    private static long insertAndReadId(
            Connection connection, String insert, String select, String label) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(insert);
            try (ResultSet row = statement.executeQuery(select)) {
                assertTrue(row.next(), "Matrix seed " + label + " was not persisted");
                return row.getLong(1);
            }
        }
    }

    private record Seed(long monitorId, long actionId, long alertId) {
    }
}
