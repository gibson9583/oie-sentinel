/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

/** Executes every statement in an exact packaged vendor mapper against its real database. */
final class MapperStatementMatrix {

    private static final int ACCEPTANCE_MINIMUM_STATEMENTS = 49;
    /** channel_id is CHAR(36), and Oracle compares it to a bound string without padding, so fixtures use full ids. */
    private static final String SUBJECT_CHANNEL = "00000000-0000-0000-0000-0000000000a1";
    private static final String OTHER_CHANNEL = "00000000-0000-0000-0000-0000000000b2";
    private static final String COVERAGE_CHANNEL = "00000000-0000-0000-0000-0000000000c3";

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
        verifyActivityTimestampPages(database, sessions, seed);
        verifyServerSubjects(database, sessions, seed);
        verifyAlertKinds(database, sessions, seed);
        verifyMatchSamples(database, sessions, seed);
        if (database.vendor().equals("mysql")) verifyMysqlTemporalResultValues(database, sessions, seed);
    }

    /**
     * Server rows through the shipped statements: a NULL channel binds on every
     * dialect, the subject comes back under its lower-case key (Derby and Oracle
     * fold labels), the trigger lookup matches NULLs without confusing the two
     * kinds, and a channel-restricted read never returns a server row.
     */
    private static void verifyServerSubjects(DatabaseMatrixSupport.MatrixDatabase database,
            SqlSessionFactory sessions, Seed seed) throws Exception {
        String vendor = database.vendor();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            SqlSession session = sessions.openSession(connection);
            try {
                Map<String, Object> event = parameters(seed);
                event.put("subject_type", "SERVER");
                event.put("channel_id", null);
                event.put("metadata_id", null);
                event.put("message", "matrix server problem");
                session.insert("Sentinel.insertAlertEvent", event);
                long alertId = ((Number) event.get("id")).longValue();
                Map<String, Object> unrestricted = new HashMap<>();
                unrestricted.put("monitorId", seed.monitorId());
                unrestricted.put("sortColumn", "opened_time");
                unrestricted.put("sortDir", "DESC");
                unrestricted.put("offset", 0);
                unrestricted.put("limit", 50);
                unrestricted.put("id", alertId);
                for (String query : List.of("getAlertEvent", "listAlertEvents", "listRecentAlertEvents",
                        "listPendingProblemAlertEvents")) {
                    Map<String, Object> row = rowWithId(session, query, unrestricted, alertId);
                    assertEquals("SERVER", row.get("subject_type"), vendor + " " + query + " lost subject_type");
                    assertNull(row.get("channel_id"), vendor + " " + query);
                }
                Map<String, Object> restricted = new HashMap<>(unrestricted);
                restricted.put("channelIdIn", new ArrayList<>(List.of("00000000-0000-0000-0000-000000000001")));
                List<Map<String, Object>> visible = session.selectList("Sentinel.listAlertEvents", restricted);
                assertTrue(visible.stream().noneMatch(row -> ((Number) row.get("id")).longValue() == alertId),
                        vendor + " channel-restricted read returned a server-wide problem");
                assertEquals(1, ((Number) session.selectOne("Sentinel.countAlertEvents", restricted)).intValue(),
                        vendor + " channel-restricted count included a server-wide problem");

                Map<String, Object> server = parameters(seed);
                server.put("subject_type", "SERVER");
                server.put("channel_id", null);
                server.put("metadata_id", null);
                server.put("open_alert_event_id", alertId);
                session.insert("Sentinel.insertTriggerState", server);
                long serverId = ((Number) server.get("id")).longValue();
                Map<String, Object> channel = parameters(seed);
                channel.put("channel_id", SUBJECT_CHANNEL);
                channel.put("metadata_id", null);
                channel.put("open_alert_event_id", null);
                session.insert("Sentinel.insertTriggerState", channel);
                long channelId = ((Number) channel.get("id")).longValue();

                Map<String, Object> lookup = new HashMap<>();
                lookup.put("monitorId", seed.monitorId());
                lookup.put("subjectType", "SERVER");
                lookup.put("channelId", null);
                lookup.put("metadataId", null);
                List<Map<String, Object>> found = session.selectList("Sentinel.getTriggerState", lookup);
                assertEquals(1, found.size(), vendor + " server trigger lookup");
                assertEquals(serverId, ((Number) found.get(0).get("id")).longValue());
                assertEquals("SERVER", found.get(0).get("subject_type"), vendor + " trigger result lost subject_type");
                assertEquals(alertId, ((Number) found.get(0).get("open_alert_event_id")).longValue());
                lookup.put("subjectType", "CHANNEL");
                assertEquals(List.of(), session.selectList("Sentinel.getTriggerState", lookup),
                        vendor + " a channel lookup without a channel matched the server row");
                lookup.put("channelId", SUBJECT_CHANNEL);
                found = session.selectList("Sentinel.getTriggerState", lookup);
                assertEquals(1, found.size(), vendor + " channel trigger lookup");
                assertEquals(channelId, ((Number) found.get(0).get("id")).longValue());
                assertEquals("CHANNEL", found.get(0).get("subject_type"));
                lookup.put("subjectType", "SERVER");
                assertEquals(List.of(), session.selectList("Sentinel.getTriggerState", lookup),
                        vendor + " a channel row matched a server lookup");

                Map<String, Object> listed = rowWithId(session, "listTriggerStatesByMonitor",
                        Map.of("monitorId", seed.monitorId()), serverId);
                assertEquals("SERVER", listed.get("subject_type"), vendor);
                assertNull(listed.get("channel_id"), vendor);
                MatrixEvidenceSupport.observation(vendor, "server-subject-rows",
                        session.selectList("Sentinel.listTriggerStatesByMonitor", Map.of("monitorId", seed.monitorId())));
            } finally {
                session.rollback();
                connection.rollback();
                session.close();
            }
        }
    }

    /** event_kind comes back under its lower-case key, and the lists and count select by it. */
    private static void verifyAlertKinds(DatabaseMatrixSupport.MatrixDatabase database,
            SqlSessionFactory sessions, Seed seed) throws Exception {
        String vendor = database.vendor();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            SqlSession session = sessions.openSession(connection);
            try {
                Map<String, Long> ids = new HashMap<>();
                for (String kind : List.of("PROBLEM", "NOTIFICATION")) {
                    Map<String, Object> event = parameters(seed);
                    event.put("event_kind", kind);
                    session.insert("Sentinel.insertAlertEvent", event);
                    ids.put(kind, ((Number) event.get("id")).longValue());
                }
                for (Map.Entry<String, Long> expected : ids.entrySet()) {
                    Map<String, Object> filter = new HashMap<>();
                    filter.put("id", expected.getValue());
                    filter.put("monitorId", seed.monitorId());
                    filter.put("kind", expected.getKey());
                    filter.put("sortColumn", "opened_time");
                    filter.put("sortDir", "DESC");
                    filter.put("offset", 0);
                    filter.put("limit", 50);
                    Map<String, Object> read = session.selectOne("Sentinel.getAlertEvent", filter);
                    assertEquals(expected.getKey(), read.get("event_kind"), vendor + " lost event_kind");
                    List<Map<String, Object>> listed = session.selectList("Sentinel.listAlertEvents", filter);
                    assertEquals(List.of(expected.getValue()), listed.stream()
                            .map(row -> ((Number) row.get("id")).longValue()).toList(), vendor + " " + expected.getKey());
                    assertEquals(1, ((Number) session.selectOne("Sentinel.countAlertEvents", filter)).intValue(),
                            vendor + " " + expected.getKey());
                    List<Long> recent = session.<Map<String, Object>>selectList("Sentinel.listRecentAlertEvents", filter)
                            .stream().map(row -> ((Number) row.get("id")).longValue()).toList();
                    assertTrue(recent.contains(expected.getValue()), vendor + " recent " + expected.getKey());
                    assertTrue(recent.stream().noneMatch(id -> ids.containsValue(id) && !id.equals(expected.getValue())),
                            vendor + " recent " + expected.getKey() + " returned the other kind");
                }
            } finally {
                session.rollback();
                connection.rollback();
                session.close();
            }
        }
    }

    /**
     * Match samples through the shipped statements, inserted under a BATCH
     * executor as MatchSampleRepository does. Fractional seconds must survive,
     * which is why MySQL and SQL Server store DATETIME(3)/DATETIME2(3). The
     * 40,000-character samples value and the longer per-second counts value
     * are past the 32,767-byte bind limit of a plain Oracle string, so the
     * CLOB path is exercised for both.
     */
    private static void verifyMatchSamples(DatabaseMatrixSupport.MatrixDatabase database,
            SqlSessionFactory sessions, Seed seed) throws Exception {
        String vendor = database.vendor();
        Timestamp base = Timestamp.from(Instant.parse("2026-08-30T12:00:00.123Z"));
        String large = "[\"" + "x".repeat(39_996) + "\"]";
        StringBuilder manySeconds = new StringBuilder("{");
        for (int second = 0; second < 5_000; second++) {
            manySeconds.append(second == 0 ? "" : ",").append('"').append(1_788_000_000L + second).append("\":1");
        }
        String largeSeconds = manySeconds.append('}').toString();
        String oneSecond = "{\"" + base.toInstant().getEpochSecond() + "\":3}";
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            SqlSession session = sessions.openSession(connection);
            SqlSession batch = sessions.openSession(ExecutorType.BATCH, connection);
            try {
                Object[][] rows = {
                        {"node-a", -1, null, 1L, null, false, null},
                        {"node-a", 0, null, 3L, large, true, oneSecond},
                        {"node-b", 1, null, 0L, null, true, null},
                        {"node-a", 2, null, 5_000L, "[]", false, largeSeconds},
                        {"node-a", 1, SUBJECT_CHANNEL, 2L, "[\"a\"]", true, null},
                        {"node-b", 2, SUBJECT_CHANNEL, 4L, null, true, null},
                        {"node-b", 2, OTHER_CHANNEL, 1L, null, true, null}};
                for (Object[] row : rows) {
                    Map<String, Object> sample = new HashMap<>();
                    sample.put("node_id", row[0]);
                    sample.put("monitor_id", seed.monitorId());
                    sample.put("channel_id", row[2]);
                    sample.put("sample_time", Timestamp.from(base.toInstant().plusSeconds((Integer) row[1])));
                    sample.put("match_count", row[3]);
                    sample.put("samples_json", row[4]);
                    sample.put("complete", row[5]);
                    sample.put("second_counts", row[6]);
                    batch.insert("Sentinel.insertMatchSample", sample);
                }
                batch.flushStatements();

                Map<String, Object> range = new HashMap<>();
                range.put("monitorId", seed.monitorId());
                range.put("from", base);
                range.put("to", Timestamp.from(base.toInstant().plusSeconds(2)));
                List<Map<String, Object>> nodes = session.selectList("Sentinel.listMatchSampleCounts", range);
                assertEquals(List.of("node-a=3@" + base, "node-a=5000@" + Timestamp.from(base.toInstant().plusSeconds(2)),
                        "node-b=0@" + Timestamp.from(base.toInstant().plusSeconds(1))), counts(nodes),
                        vendor + " heartbeat rows by node then time, inclusive range");
                assertTrue(nodes.get(0).get("sample_time") instanceof Timestamp, vendor + " sample_time type");
                assertEquals(Arrays.asList(oneSecond, largeSeconds, null),
                        nodes.stream().map(row -> row.get("second_counts")).toList(),
                        vendor + " per-second counts round-trip, the long value intact");
                assertEquals(List.of(true, false, true), nodes.stream().map(row -> isTrue(row.get("complete"))).toList(),
                        vendor + " complete per row");
                List<Map<String, Object>> channels = session.selectList("Sentinel.sumMatchSamplesByChannel", range);
                assertEquals(List.of(SUBJECT_CHANNEL + "=6", OTHER_CHANNEL + "=1"), sums(channels, "channel_id", null),
                        vendor + " channel sums across nodes");

                Map<String, Object> recent = new HashMap<>(range);
                recent.put("nodeId", "node-a");
                recent.put("limit", 10);
                List<Map<String, Object>> newest = session.selectList("Sentinel.listRecentMatchSamples", recent);
                assertEquals(List.of("[]", "[\"a\"]", large), newest.stream().map(row -> row.get("samples_json")).toList(),
                        vendor + " newest first, rows with samples only, the long value intact");
                assertEquals(List.of(false, true, true), newest.stream().map(row -> isTrue(row.get("complete"))).toList(),
                        vendor + " complete round-trips");
                Map<String, Object> first = newest.get(1);
                assertNotNull(first.get("id"), vendor + " lower-case id missing from match sample result");
                assertEquals(seed.monitorId(), ((Number) first.get("monitor_id")).longValue(), vendor);
                assertEquals(SUBJECT_CHANNEL, first.get("channel_id"), vendor);
                assertEquals(2L, ((Number) first.get("match_count")).longValue(), vendor);
                assertEquals(Timestamp.from(base.toInstant().plusSeconds(1)),
                        org.junit.jupiter.api.Assertions.assertInstanceOf(Timestamp.class, first.get("sample_time")),
                        vendor + " sample_time type or precision");
                recent.put("limit", 1);
                assertEquals(1, session.selectList("Sentinel.listRecentMatchSamples", recent).size(), vendor + " limit");
                recent.put("nodeId", "node-b");
                assertEquals(List.of(), session.selectList("Sentinel.listRecentMatchSamples", recent), vendor);

                Map<String, Object> prune = new HashMap<>();
                prune.put("cutoff", Timestamp.from(base.toInstant().plusSeconds(2)));
                prune.put("chunkSize", 2);
                assertEquals(2, session.delete("Sentinel.deleteMatchSamplesOlderThan", prune), vendor + " chunk bound");
                assertEquals(2, session.delete("Sentinel.deleteMatchSamplesOlderThan", prune), vendor);
                assertEquals(0, session.delete("Sentinel.deleteMatchSamplesOlderThan", prune), vendor);
                range.put("from", Timestamp.from(base.toInstant().minusSeconds(60)));
                List<String> remaining = sums(session.selectList("Sentinel.sumMatchSamplesByChannel", range),
                        "channel_id", null);
                remaining.addAll(counts(session.selectList("Sentinel.listMatchSampleCounts", range)).stream()
                        .map(row -> row.substring(0, row.indexOf('@'))).toList());
                assertEquals(List.of(SUBJECT_CHANNEL + "=4", OTHER_CHANNEL + "=1", "node-a=5000"), remaining,
                        vendor + " prune removed a sample at the cutoff");
                MatrixEvidenceSupport.observation(vendor, "match-samples", "nodes=" + counts(nodes)
                        + " channels=" + channels + " samplesLength=" + large.length()
                        + " secondCountsLength=" + largeSeconds.length() + " remainingAfterPrune=" + remaining);
            } finally {
                connection.rollback();
                batch.close();
                session.close();
            }
        }
    }

    /** A boolean column can come back as a Boolean or as a number, depending on the vendor's driver. */
    private static boolean isTrue(Object value) {
        return value instanceof Boolean flag ? flag : value instanceof Number number && number.intValue() != 0;
    }

    /** Count rows as {@code node=count@time}, in the order read. */
    private static List<String> counts(List<Map<String, Object>> rows) {
        List<String> counts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            counts.add(row.get("node_id") + "=" + ((Number) row.get("match_count")).longValue() + "@"
                    + row.get("sample_time"));
        }
        return counts;
    }

    private static List<String> sums(List<Map<String, Object>> rows, String key, String time) {
        List<String> sums = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String at = time == null ? "" : "@" + row.get(time);
            sums.add(row.get(key) + "=" + ((Number) row.get("match_sum")).longValue() + at);
        }
        return sums;
    }

    /** Default Connector/J returns DATETIME as LocalDateTime unless the mapper asks for Timestamp. */
    private static void verifyMysqlTemporalResultValues(DatabaseMatrixSupport.MatrixDatabase database,
            SqlSessionFactory sessions, Seed seed) throws Exception {
        Timestamp time = Timestamp.from(Instant.parse("2026-08-31T12:34:56Z"));
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            SqlSession session = sessions.openSession(connection);
            try {
                Map<String, Object> values = parameters(seed);
                values.put("channel_id", "matrix-temporal");
                values.put("channelId", "matrix-temporal");
                // MyBatis 3.1.1 OGNL reflects isEmpty(); Java 21 hides List.of's implementation class.
                values.put("channelIdIn", new ArrayList<>(List.of("matrix-temporal")));
                values.put("from", Timestamp.from(time.toInstant().minusSeconds(3600)));
                values.put("to", Timestamp.from(time.toInstant().plusSeconds(3600)));
                for (String column : List.of("created_time", "updated_time", "opened_time", "resolved_time",
                        "acknowledged_time", "last_change_time", "last_evaluated_time", "sample_time",
                        "hour_bucket", "dispatch_time", "active_from", "active_until")) values.put(column, time);

                session.update("Sentinel.updateMonitor", values);
                // This fails if a URL compatibility flag masks the original production trigger.
                try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery(
                        "SELECT created_time FROM sentinel_monitor WHERE id = " + seed.monitorId())) {
                    assertTrue(row.next());
                    org.junit.jupiter.api.Assertions.assertInstanceOf(java.time.LocalDateTime.class, row.getObject(1),
                            "MySQL matrix must exercise default DATETIME getObject behavior without a compatibility flag");
                    assertEquals(time, row.getTimestamp(1));
                }
                for (String query : List.of("getMonitor", "listMonitors")) {
                    Map<String, Object> row = rowWithId(session, query, values, seed.monitorId());
                    assertTimes(row, time, "created_time", "updated_time");
                    assertEquals("matrix-exercise-row", row.get("name"));
                }
                values.put("updated_time", null);
                session.update("Sentinel.updateMonitor", values);
                assertNullTime(rowWithId(session, "getMonitor", values, seed.monitorId()), "updated_time");

                values.put("updated_time", time);
                values.put("id", seed.actionId());
                session.update("Sentinel.updateAction", values);
                for (String query : List.of("getAction", "listActions")) {
                    Map<String, Object> row = rowWithId(session, query, values, seed.actionId());
                    assertTimes(row, time, "created_time", "updated_time");
                    assertEquals("EMAIL", row.get("action_type"));
                }
                values.put("updated_time", null);
                session.update("Sentinel.updateAction", values);
                assertNullTime(rowWithId(session, "getAction", values, seed.actionId()), "updated_time");

                values.put("status", "RESOLVED");
                session.insert("Sentinel.insertAlertEvent", values);
                long alertId = ((Number) values.get("id")).longValue();
                for (String query : List.of("getAlertEvent", "listAlertEvents", "listRecentAlertEvents",
                        "listPendingProblemAlertEvents", "listPendingResolvedAlertEvents")) {
                    Map<String, Object> row = rowWithId(session, query, values, alertId);
                    assertTimes(row, time, "opened_time", "resolved_time", "acknowledged_time");
                    assertEquals("matrix exercise alert", row.get("message"));
                }
                try (Statement sql = connection.createStatement()) {
                    sql.executeUpdate("UPDATE sentinel_alert_event SET resolved_time=NULL, acknowledged_time=NULL WHERE id=" + alertId);
                }
                session.clearCache();
                Map<String, Object> alert = rowWithId(session, "getAlertEvent", values, alertId);
                assertTimes(alert, time, "opened_time");
                assertNullTime(alert, "resolved_time", "acknowledged_time");

                values.put("open_alert_event_id", alertId);
                session.insert("Sentinel.insertTriggerState", values);
                long triggerId = ((Number) values.get("id")).longValue();
                for (String query : List.of("getTriggerState", "listTriggerStatesByMonitor")) {
                    Map<String, Object> row = rowWithId(session, query, values, triggerId);
                    assertTimes(row, time, "last_change_time", "last_evaluated_time");
                    assertEquals(alertId, ((Number) row.get("open_alert_event_id")).longValue());
                }
                try (Statement sql = connection.createStatement()) {
                    sql.executeUpdate("UPDATE sentinel_trigger_state SET last_change_time=NULL, last_evaluated_time=NULL WHERE id=" + triggerId);
                }
                session.clearCache();
                assertNullTime(rowWithId(session, "getTriggerState", values, triggerId), "last_change_time", "last_evaluated_time");

                values.put("received_delta", 3_000_000_001L);
                session.insert("Sentinel.insertActivitySample", values);
                Map<String, Object> sample = session.selectOne("Sentinel.listActivitySamples", values);
                assertTimes(sample, time, "sample_time");
                assertEquals(3_000_000_001L, ((Number) sample.get("received_delta")).longValue());
                values.put("received_sum", 3_000_000_002L);
                values.put("avg_queued", 1.25d);
                session.insert("Sentinel.insertActivityTrend", values);
                Map<String, Object> trend = session.selectOne("Sentinel.listActivityTrendSince", values);
                assertTimes(trend, time, "hour_bucket");
                assertEquals(3_000_000_002L, ((Number) trend.get("received_sum")).longValue());
                assertEquals(1.25d, ((Number) trend.get("avg_queued")).doubleValue());

                values.put("alert_event_id", alertId);
                values.put("alertEventId", alertId);
                session.insert("Sentinel.insertActionDispatchLog", values);
                for (String query : List.of("listActionDispatchLogsForEvent", "listActionDispatchLogsInRange")) {
                    Map<String, Object> row = session.selectOne("Sentinel." + query, values);
                    assertTimes(row, time, "dispatch_time");
                    assertEquals(alertId, ((Number) row.get("alert_event_id")).longValue());
                }

                session.insert("Sentinel.insertMaintenanceWindow", values);
                long windowId = ((Number) values.get("id")).longValue();
                for (String query : List.of("getMaintenanceWindow", "listMaintenanceWindows", "listEnabledMaintenanceWindows")) {
                    Map<String, Object> row = rowWithId(session, query, values, windowId);
                    assertTimes(row, time, "active_from", "active_until", "created_time");
                    assertEquals("08:00", row.get("start_time"));
                }
                values.put("active_from", null);
                values.put("active_until", null);
                session.update("Sentinel.updateMaintenanceWindow", values);
                Map<String, Object> window = rowWithId(session, "getMaintenanceWindow", values, windowId);
                assertNullTime(window, "active_from", "active_until");
                assertTimes(window, time, "created_time");

                Timestamp before = session.selectOne("Sentinel.getDatabaseTime");
                session.insert("Sentinel.insertNodeLease", values);
                values.put("leaseName", values.get("lease_name"));
                Map<String, Object> lease = session.selectOne("Sentinel.getNodeLease", values);
                Timestamp acquired = org.junit.jupiter.api.Assertions.assertInstanceOf(Timestamp.class, lease.get("acquired_time"));
                Timestamp expires = org.junit.jupiter.api.Assertions.assertInstanceOf(Timestamp.class, lease.get("expires_time"));
                assertTrue(!acquired.before(before));
                assertEquals(60, java.time.Duration.between(acquired.toInstant(), expires.toInstant()).getSeconds());
                assertEquals("matrix-node", lease.get("node_id"));
                MatrixEvidenceSupport.observation(database.vendor(), "mysql-default-datetime-result-maps",
                        Map.of("sample", sample, "trend", trend, "alertWithNulls", alert, "windowWithNulls", window, "lease", lease));
            } finally {
                session.rollback();
                connection.rollback();
                session.close();
            }
        }
    }

    private static Map<String, Object> rowWithId(SqlSession session, String statement,
            Map<String, Object> parameters, long id) {
        List<Map<String, Object>> rows = session.selectList("Sentinel." + statement, parameters);
        return rows.stream().filter(row -> ((Number) row.get("id")).longValue() == id).findFirst()
                .orElseThrow(() -> new AssertionError(statement + " lost row " + id));
    }

    private static void assertTimes(Map<String, Object> row, Timestamp expected, String... columns) {
        assertNotNull(row);
        for (String column : columns) {
            assertEquals(expected, org.junit.jupiter.api.Assertions.assertInstanceOf(Timestamp.class, row.get(column)), column);
        }
    }

    private static void assertNullTime(Map<String, Object> row, String... columns) {
        for (String column : columns) org.junit.jupiter.api.Assertions.assertNull(row.get(column), column);
    }

    /** Bounded keyset reads preserve endpoints and collapse duplicate observations. */
    private static void verifyActivityTimestampPages(DatabaseMatrixSupport.MatrixDatabase database,
            SqlSessionFactory sessions, Seed seed) throws Exception {
        // The shipped MySQL sample_time is DATETIME(0). A .123 query bound
        // excludes the fixture's first row after the server rounds it to .000.
        // Keep fractional endpoint coverage for schemas that preserve it.
        Timestamp start = Timestamp.from(Instant.parse(database.vendor().equals("mysql")
                ? "2026-08-30T12:00:00Z" : "2026-08-30T12:00:00.123Z"));
        Timestamp end = Timestamp.from(start.toInstant().plusSeconds(4));
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            SqlSession session = sessions.openSession(connection);
            try {
                try {
                    Map<String, Object> sample = parameters(seed);
                    sample.put("channel_id", COVERAGE_CHANNEL);
                    for (int second : new int[] {-1, 0, 0, 1, 2, 2, 3, 4, 5}) {
                        sample.put("sample_time", Timestamp.from(start.toInstant().plusSeconds(second)));
                        session.insert("Sentinel.insertActivitySample", sample);
                    }
                    sample.put("channel_id", "different-channel");
                    sample.put("sample_time", Timestamp.from(start.toInstant().plusMillis(500)));
                    session.insert("Sentinel.insertActivitySample", sample);
                    List<Timestamp> stored = new ArrayList<>();
                    try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                            "SELECT DISTINCT sample_time FROM sentinel_channel_activity_sample "
                                    + "WHERE channel_id = '" + COVERAGE_CHANNEL + "' ORDER BY sample_time")) {
                        while (rows.next()) stored.add(rows.getTimestamp(1));
                    }
                    assertEquals(java.util.stream.IntStream.rangeClosed(-1, 5)
                                    .mapToObj(second -> Timestamp.from(start.toInstant().plusSeconds(second))).toList(), stored,
                            database.vendor() + " stored fixture precision must match the paging bounds");
                    MatrixEvidenceSupport.observation(database.vendor(), "activity-timestamp-page-fixture", stored);
                    Map<String, Object> query = new HashMap<>();
                    query.put("channelId", COVERAGE_CHANNEL);
                    query.put("from", start);
                    query.put("to", end);
                    query.put("limit", 2);
                    List<Timestamp> all = new ArrayList<>();
                    for (int pageSize : new int[] {2, 2, 1, 0}) {
                        List<Timestamp> page = session.selectList("Sentinel.listActivitySampleTimes", query);
                        assertEquals(pageSize, page.size(), database.vendor() + " timestamp page bound");
                        all.addAll(page);
                        if (!page.isEmpty()) query.put("after", page.get(page.size() - 1));
                    }
                    assertEquals(java.util.stream.IntStream.rangeClosed(0, 4)
                            .mapToObj(second -> Timestamp.from(start.toInstant().plusSeconds(second))).toList(), all,
                            database.vendor() + " inclusive bounds, strict cursor and duplicate elimination");
                    MatrixEvidenceSupport.observation(database.vendor(), "activity-timestamp-pages", all);
                } finally {
                    session.rollback();
                    connection.rollback();
                }
            } finally {
                session.close();
            }
        }
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
        values.put("subject_type", "CHANNEL");
        values.put("subjectType", "CHANNEL");
        values.put("event_kind", "PROBLEM");
        values.put("kind", "PROBLEM");

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
        values.put("match_count", 2L);
        values.put("samples_json", "[]");
        values.put("complete", Boolean.TRUE);
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
