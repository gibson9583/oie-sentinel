/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.parameter.DefaultParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NullableParameterBindingTest {
    private record Binding(String statement, String field, JdbcType type, boolean conditional) { }

    static Stream<Arguments> bindings() {
        List<Binding> cases = new ArrayList<>();
        for (String statement : List.of("insertMonitor", "updateMonitor", "insertAction", "updateAction")) {
            add(cases, statement, JdbcType.INTEGER, false, "created_by", "updated_by");
            add(cases, statement, JdbcType.TIMESTAMP, false, "updated_time");
        }
        add(cases, "insertAlertEvent", JdbcType.INTEGER, false, "metadata_id", "acknowledged_by");
        add(cases, "insertAlertEvent", JdbcType.TIMESTAMP, false, "resolved_time", "acknowledged_time");
        add(cases, "updateAlertEvent", JdbcType.INTEGER, true, "acknowledged_by");
        add(cases, "updateAlertEvent", JdbcType.TIMESTAMP, true, "resolved_time", "acknowledged_time");
        add(cases, "insertTriggerState", JdbcType.INTEGER, false, "metadata_id");
        for (String statement : List.of("insertTriggerState", "updateTriggerState")) {
            boolean conditional = statement.startsWith("update");
            add(cases, statement, JdbcType.BIGINT, conditional, "open_alert_event_id");
            add(cases, statement, JdbcType.TIMESTAMP, conditional, "last_change_time", "last_evaluated_time");
        }
        for (String statement : List.of("insertAction", "updateAction")) {
            add(cases, statement, JdbcType.INTEGER, false, "repeat_interval_seconds", "max_repeats",
                    "max_notifications_per_window", "rollup_window_seconds", "escalate_after_seconds",
                    "escalate_to_action_id");
        }
        for (String statement : List.of("insertMaintenanceWindow", "updateMaintenanceWindow")) {
            add(cases, statement, JdbcType.TIMESTAMP, false, "active_from", "active_until");
            add(cases, statement, JdbcType.INTEGER, false, "created_by");
        }
        add(cases, "insertActionDispatchLog", JdbcType.INTEGER, false, "action_id");
        return Stream.of("postgres", "mysql", "sqlserver", "oracle", "derby")
                .flatMap(vendor -> cases.stream().map(binding -> Arguments.of(vendor, binding)));
    }

    private static void add(List<Binding> cases, String statement, JdbcType type,
            boolean conditional, String... fields) {
        for (String field : fields) cases.add(new Binding(statement, field, type, conditional));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("bindings")
    void bindsNullableValuesWithEngineConfiguration(String vendor, Binding binding) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setJdbcTypeForNull(JdbcType.VARCHAR); // Engine SqlMapConfig.xml.
        String resource = "/mapper/" + vendor + "-sqlmap.xml";
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        MappedStatement mapped = configuration.getMappedStatement("Sentinel." + binding.statement());
        Object assigned = switch (binding.type()) {
            case INTEGER -> Integer.valueOf(42);
            case BIGINT -> Long.valueOf(3_000_000_000L); // Catch accidental integer narrowing.
            case TIMESTAMP -> Timestamp.valueOf("2026-09-17 12:34:56.123");
            default -> throw new AssertionError(binding.type());
        };
        Map<String, Object> parameters = new HashMap<>();
        // Exercise absent, assigned, cleared and repeated null bindings on the same statement.
        for (Object value : new Object[] { null, assigned, null, null }) {
            parameters.put(binding.field(), value);
            BoundSql sql = mapped.getBoundSql(parameters);
            int index = -1;
            for (int i = 0; i < sql.getParameterMappings().size(); i++) {
                if (binding.field().equals(sql.getParameterMappings().get(i).getProperty())) index = i + 1;
            }
            if (binding.conditional() && value == null) {
                assertTrue(index == -1, "Partial updates must keep omitting absent fields");
                continue;
            }
            assertTrue(index > 0, "Missing parameter " + binding);
            PreparedStatement jdbc = mock(PreparedStatement.class);
            new DefaultParameterHandler(mapped, parameters, sql).setParameters(jdbc);
            if (value == null) {
                verify(jdbc).setNull(index, binding.type().TYPE_CODE);
            } else {
                switch (binding.type()) {
                    case INTEGER -> verify(jdbc).setInt(index, (Integer) value);
                    case BIGINT -> verify(jdbc).setLong(index, (Long) value);
                    case TIMESTAMP -> verify(jdbc).setTimestamp(index, (Timestamp) value);
                    default -> throw new AssertionError(binding.type());
                }
            }
            verify(jdbc, never()).setNull(index, Types.VARCHAR);
        }
    }
}
