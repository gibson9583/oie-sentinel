/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.FastResultSetHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;

/** Executes the shipped MyBatis materializer without opening a database or engine. */
class MysqlTimestampResultMappingTest {
    private record Shape(String columns, String timestamps, String nullable, boolean bigId) { }
    private record Query(String statement, Shape shape) {
        @Override public String toString() { return statement; }
    }

    private static final Shape MONITOR = new Shape(
            "id name description monitor_type scope_type scope_id enabled severity config_json min_consecutive_breaches suppressed_by_monitor_id runbook_url created_by created_time updated_by updated_time",
            "created_time updated_time", "updated_time", false);
    private static final Shape ALERT = new Shape(
            "id monitor_id channel_id metadata_id severity status message opened_time resolved_time acknowledged_by acknowledged_time ack_comment details_json suppressed problem_pending resolution_pending",
            "opened_time resolved_time acknowledged_time", "resolved_time acknowledged_time", true);
    private static final Shape TRIGGER = new Shape(
            "id monitor_id channel_id metadata_id state consecutive_breach_count last_value_json open_alert_event_id last_change_time last_evaluated_time",
            "last_change_time last_evaluated_time", "last_change_time last_evaluated_time", false);
    private static final Shape SAMPLE = new Shape(
            "id channel_id sample_time received_delta sent_delta error_delta filtered_delta queued_snapshot",
            "sample_time", "", true);
    private static final Shape TREND = new Shape(
            "id channel_id hour_bucket received_sum sent_sum error_sum avg_queued min_queued max_queued",
            "hour_bucket", "", false);
    private static final Shape ACTION = new Shape(
            "id name description enabled action_type condition_json operation_mode repeat_interval_seconds max_repeats max_notifications_per_window rollup_window_seconds escalate_after_seconds escalate_to_action_id config_json created_by created_time updated_by updated_time",
            "created_time updated_time", "updated_time", false);
    private static final Shape DISPATCH = new Shape(
            "id alert_event_id action_id dispatch_time success error_message", "dispatch_time", "", true);
    private static final Shape WINDOW = new Shape(
            "id name scope_type scope_id window_mode repeat_type days_of_week days_of_month start_time end_time timezone active_from active_until enabled created_by created_time",
            "active_from active_until created_time", "active_from active_until", false);
    private static final Shape LEASE = new Shape("lease_name node_id acquired_time expires_time lease_epoch",
            "acquired_time expires_time", "", false);
    private static final Shape CONNECTOR = new Shape(
            "id channel_id metadata_id node_id previous_state new_state changed_time deployment_time",
            "changed_time deployment_time", "deployment_time", true);
    private static final Shape PRESENCE = new Shape("metadata_id node_id deployed_time", "deployed_time", "", false);

    private static List<Query> queries() {
        List<Query> queries = new ArrayList<>();
        add(queries, MONITOR, "getMonitor", "listMonitors");
        add(queries, ALERT, "getAlertEvent", "listAlertEvents", "listRecentAlertEvents",
                "listPendingProblemAlertEvents", "listPendingResolvedAlertEvents");
        add(queries, TRIGGER, "getTriggerState", "listTriggerStatesByMonitor");
        add(queries, SAMPLE, "listActivitySamples");
        add(queries, TREND, "listActivityTrendSince");
        add(queries, ACTION, "getAction", "listActions");
        add(queries, DISPATCH, "listActionDispatchLogsForEvent", "listActionDispatchLogsInRange");
        add(queries, WINDOW, "getMaintenanceWindow", "listMaintenanceWindows", "listEnabledMaintenanceWindows");
        add(queries, LEASE, "getNodeLease");
        add(queries, CONNECTOR, "getLatestConnectorStatusEvent", "listLatestConnectorStatusEvents", "listConnectorStatusEvents");
        add(queries, PRESENCE, "listActiveConnectorNodes");
        return queries;
    }

    private static void add(List<Query> queries, Shape shape, String... statements) {
        for (String statement : statements) queries.add(new Query(statement, shape));
    }

    static Stream<Arguments> rows() {
        return queries().stream().flatMap(query -> Stream.of(false, true)
                .map(nulls -> Arguments.of(query, nulls)));
    }

    @ParameterizedTest(name = "{0}, nullable timestamps cleared={1}")
    @MethodSource("rows")
    void mapsEveryTimestampAndPreservesOtherColumns(Query query, boolean nulls) throws Exception {
        Configuration configuration = mapper();
        MappedStatement statement = configuration.getMappedStatement("Sentinel." + query.statement());
        Map<String, Object> expected = values(query.shape(), nulls);
        // Independent projection inventory prevents a result map or test fixture from silently losing a column.
        String projection = statement.getBoundSql(new HashMap<>()).getSql().trim()
                .split("(?i)\\s+FROM\\s+", 2)[0].replaceFirst("(?i)^SELECT\\s+", "");
        Set<String> selected = Arrays.stream(projection.split(",")).map(String::trim)
                .map(column -> column.substring(column.lastIndexOf('.') + 1)).collect(java.util.stream.Collectors.toSet());
        assertEquals(expected.keySet(), selected, query.statement());
        JdbcRows jdbc = new JdbcRows(expected, words(query.shape().timestamps()));
        Map<?, ?> actual = materialize(statement, jdbc);
        Map<String, Object> nonnull = new LinkedHashMap<>(expected);
        nonnull.values().removeIf(java.util.Objects::isNull);
        assertEquals(nonnull, actual, "MyBatis 3.1.1 omits null values but must retain every other column");
        for (String column : words(query.shape().timestamps())) {
            verify(jdbc.rows).getTimestamp(column);
            verify(jdbc.rows, never()).getObject(column);
        }
    }

    @Test
    void fixtureReproducesTheUntypedDatetimeFailure() throws Exception {
        Configuration configuration = new Configuration();
        MappedStatement untyped = new MappedStatement.Builder(configuration, "untyped",
                new StaticSqlSource(configuration, "SELECT sample_time, received_delta FROM sample"), SqlCommandType.SELECT)
                .resultMaps(List.of(new ResultMap.Builder(configuration, "untyped-map", Map.class, List.of()).build())).build();
        Timestamp time = Timestamp.valueOf("2026-10-15 12:34:56.123456");
        JdbcRows jdbc = new JdbcRows(Map.of("sample_time", time, "received_delta", 3_000_000_001L), Set.of("sample_time"));
        Map<?, ?> row = materialize(untyped, jdbc);
        assertEquals(time.toLocalDateTime(), row.get("sample_time"));
        assertThrows(ClassCastException.class, () -> { Timestamp ignored = (Timestamp) row.get("sample_time"); });
        assertEquals(3_000_000_001L, row.get("received_delta"));
    }

    @Test
    void historyDateAndAggregateValuesKeepTheirExistingTypes() throws Exception {
        MappedStatement statement = mapper().getMappedStatement("Sentinel.listMonitorHistory");
        Map<String, Object> expected = Map.of("bucket", java.sql.Date.valueOf("2026-10-15"),
                "alert_count", 3_000_000_001L, "avg_resolve_seconds", new java.math.BigDecimal("12.50"));
        assertEquals(expected, materialize(statement, new JdbcRows(expected, Set.of())));
    }

    private static Configuration mapper() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "/mapper/mysql-sqlmap.xml";
        try (InputStream input = MysqlTimestampResultMappingTest.class.getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static Map<?, ?> materialize(MappedStatement mapped, JdbcRows jdbc) throws Exception {
        Statement statement = mock(Statement.class);
        when(statement.getResultSet()).thenReturn(jdbc.rows);
        when(statement.getUpdateCount()).thenReturn(-1);
        FastResultSetHandler handler = new FastResultSetHandler(mock(Executor.class), mapped,
                mock(ParameterHandler.class), null, mapped.getBoundSql(new HashMap<>()), RowBounds.DEFAULT);
        List<Object> rows = handler.handleResultSets(statement);
        assertEquals(1, rows.size());
        return assertInstanceOf(Map.class, rows.get(0));
    }

    private static Set<String> words(String text) {
        return text.isBlank() ? Set.of() : Set.of(text.split(" "));
    }

    private static Map<String, Object> values(Shape shape, boolean nulls) {
        Map<String, Object> row = new LinkedHashMap<>();
        Set<String> big = words("open_alert_event_id alert_event_id received_delta sent_delta error_delta filtered_delta queued_snapshot received_sum sent_sum error_sum min_queued max_queued lease_epoch");
        Set<String> integers = words("monitor_id metadata_id created_by updated_by min_consecutive_breaches suppressed_by_monitor_id consecutive_breach_count action_id repeat_interval_seconds max_repeats max_notifications_per_window rollup_window_seconds escalate_after_seconds escalate_to_action_id");
        Set<String> flags = words("enabled suppressed problem_pending resolution_pending success");
        for (String column : shape.columns().split(" ")) {
            Object value;
            if (words(shape.timestamps()).contains(column)) {
                value = nulls && words(shape.nullable()).contains(column) ? null
                        : Timestamp.valueOf("2026-10-15 12:34:56.123456");
            } else if (big.contains(column) || column.equals("id") && shape.bigId()) value = 3_000_000_001L;
            else if (integers.contains(column) || column.equals("id")) value = 17;
            else if (flags.contains(column)) value = Boolean.FALSE;
            else if (column.equals("avg_queued")) value = 1.25d;
            else value = "value-" + column;
            row.put(column, value);
        }
        return row;
    }

    /** Connector/J 8.4 default DATETIME metadata/getObject differs from its getTimestamp contract. */
    private static final class JdbcRows {
        final ResultSet rows;

        JdbcRows(Map<String, Object> data, Set<String> timestamps) throws Exception {
            List<String> columns = new ArrayList<>(data.keySet());
            ResultSetMetaData metadata = mock(ResultSetMetaData.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("getColumnCount")) return columns.size();
                if (invocation.getArguments().length == 1 && invocation.getArgument(0) instanceof Integer index) {
                    String column = columns.get(index - 1);
                    Object value = data.get(column);
                    return switch (method) {
                        case "getColumnLabel", "getColumnName" -> column;
                        case "getColumnClassName" -> timestamps.contains(column) ? LocalDateTime.class.getName() : value.getClass().getName();
                        case "getColumnType" -> timestamps.contains(column) ? Types.TIMESTAMP
                                : value instanceof Integer ? Types.INTEGER : value instanceof Long ? Types.BIGINT
                                : value instanceof Boolean ? Types.BIT : value instanceof Double ? Types.DOUBLE
                                : value instanceof java.sql.Date ? Types.DATE : value instanceof java.math.BigDecimal ? Types.DECIMAL : Types.VARCHAR;
                        default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                    };
                }
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
            AtomicInteger cursor = new AtomicInteger();
            AtomicBoolean wasNull = new AtomicBoolean();
            rows = mock(ResultSet.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("getMetaData")) return metadata;
                if (method.equals("next")) return cursor.getAndIncrement() == 0;
                if (method.equals("wasNull")) return wasNull.get();
                if (method.startsWith("get") && invocation.getArguments().length == 1) {
                    Object key = invocation.getArgument(0);
                    String column = key instanceof Integer index ? columns.get(index - 1) : key.toString();
                    if (!data.containsKey(column)) throw new AssertionError("Unexpected JDBC column: " + column);
                    Object value = data.get(column);
                    wasNull.set(value == null);
                    return switch (method) {
                        case "getObject" -> value instanceof Timestamp time ? time.toLocalDateTime() : value;
                        case "getTimestamp", "getDate", "getBigDecimal", "getString" -> value;
                        case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                        case "getDouble" -> value == null ? 0d : ((Number) value).doubleValue();
                        case "getBoolean" -> value != null && (Boolean) value;
                        default -> Answers.RETURNS_DEFAULTS.answer(invocation);
                    };
                }
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
        }
    }
}
