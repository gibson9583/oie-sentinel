/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Only engine plumbing and audit delivery are replaced; services, repositories and Derby SQL are real. */
abstract class MonitorIdentityDatabaseTestSupport {
    private String url;
    protected SqlSessionManager sessions;
    protected SqlConfig engine;
    private MockedStatic<SqlConfig> config;
    protected MockedStatic<SentinelAuditLog> audit;
    private MockedStatic<MaintenanceWindowService> windows;

    @BeforeEach
    void setupDatabase() throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        url = "jdbc:derby:memory:monitorIdentity" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url + ";create=true");
                Statement sql = connection.createStatement()) {
            for (String suffix : List.of("-sentinel-tables.sql", "-sentinel-v4.sql")) {
                try (InputStream in = getClass().getResourceAsStream("/derby" + suffix)) {
                    assertNotNull(in);
                    for (String statement : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .split("\\r?\\n\\s*\\r?\\n")) {
                        if (!statement.isBlank()) sql.execute(statement.trim());
                    }
                }
            }
        }
        Configuration mapping = new Configuration(new Environment("monitor-identity", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver", url, null, null)));
        mapping.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream in = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            new XMLMapperBuilder(in, mapping, "derby", mapping.getSqlFragments()).parse();
        }
        sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(mapping));
        engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(sessions);
        config = mockStatic(SqlConfig.class);
        config.when(SqlConfig::getInstance).thenReturn(engine);
        audit = mockStatic(SentinelAuditLog.class);
        windows = mockStatic(MaintenanceWindowService.class);
    }

    @AfterEach
    void cleanupDatabase() throws Exception {
        try {
            if (config != null) {
                System.out.println("Final isolated monitor-identity rows " + url + ": " + snapshot());
            }
        } finally {
            try {
                if (windows != null) windows.close();
                if (audit != null) audit.close();
                if (config != null) config.close();
                if (sessions != null && sessions.isManagedSessionStarted()) sessions.close();
            } finally {
                if (url != null) {
                    SQLException dropped = assertThrows(SQLException.class,
                            () -> DriverManager.getConnection(url + ";drop=true"));
                    assertEquals("08006", dropped.getSQLState());
                    System.out.println("Dropped isolated monitor-identity database " + url
                            + " SQLState=" + dropped.getSQLState());
                }
            }
        }
    }

    static Monitor monitor(String name, String config, Integer parent) {
        Monitor monitor = new Monitor();
        monitor.setName(name);
        monitor.setMonitorType(MonitorType.INACTIVITY);
        monitor.setScopeType(ScopeType.ALL);
        monitor.setSeverity(Severity.HIGH);
        monitor.setEnabled(true);
        monitor.setMinConsecutiveBreaches(1);
        monitor.setConfigJson(config);
        monitor.setSuppressedByMonitorId(parent);
        return monitor;
    }

    /** Legacy records must bypass the corrected CRUD name validation. */
    static Monitor legacyMonitor(String name, String config, Integer parent) {
        Monitor monitor = monitor(name, config, parent);
        monitor.setCreatedBy(99);
        monitor.setCreatedTime(Instant.parse("2026-10-14T00:00:00Z"));
        monitor.setUpdatedBy(99);
        monitor.setUpdatedTime(monitor.getCreatedTime());
        return MonitorRepository.insertMonitor(monitor);
    }

    static Action action(String name, String condition) {
        Action action = new Action();
        action.setName(name);
        action.setEnabled(true);
        action.setActionType(ActionType.EMAIL);
        action.setOperationMode(OperationMode.BOTH);
        action.setConditionJson(condition);
        action.setConfigJson("{\"to\":\"intended@example.test\"}");
        return action;
    }

    static String condition(String operator, String... values) {
        ObjectNode row = Json.mapper().createObjectNode().put("field", "MONITOR").put("operator", operator);
        if ("IN".equals(operator)) {
            ArrayNode array = row.putArray("value");
            for (String value : values) array.add(value);
        } else {
            row.put("value", values[0]);
        }
        return Json.write(Json.mapper().createArrayNode().add(row));
    }

    static ObjectNode monitorEntry(String name, String config, String parentName) {
        ObjectNode node = Json.mapper().valueToTree(monitor(name, config, null));
        node.remove("id");
        node.remove("suppressedByMonitorId");
        if (parentName == null) node.putNull("suppressedByMonitorName");
        else node.put("suppressedByMonitorName", parentName);
        return node;
    }

    static ObjectNode actionEntry(String name, String condition) {
        ObjectNode node = Json.mapper().valueToTree(action(name, condition));
        node.remove("id");
        return node;
    }

    static ObjectNode document() {
        return Json.mapper().createObjectNode().put("schemaVersion", 2);
    }

    static void reverse(ObjectNode document) {
        for (String field : List.of("monitors", "actions")) {
            if (!document.path(field).isArray()) continue;
            List<JsonNode> nodes = new ArrayList<>();
            document.path(field).forEach(nodes::add);
            Collections.reverse(nodes);
            ArrayNode reversed = document.putArray(field);
            nodes.forEach(reversed::add);
        }
    }

    static Monitor namedMonitor(String name) {
        return MonitorRepository.listMonitors(null, null, null, null).stream()
                .filter(monitor -> name.equals(monitor.getName())).findFirst().orElse(null);
    }

    static Action namedAction(String name) {
        return ActionRepository.listActions(null).stream()
                .filter(action -> name.equals(action.getName())).findFirst().orElse(null);
    }

    static String snapshot() {
        return Json.write(Map.of("monitors", MonitorRepository.listMonitors(null, null, null, null),
                "actions", ActionRepository.listActions(null)));
    }

    Connection connection() throws SQLException { return DriverManager.getConnection(url); }

    void clearRows() throws Exception {
        try (Connection db = DriverManager.getConnection(url); Statement sql = db.createStatement()) {
            sql.executeUpdate("DELETE FROM sentinel_action");
            sql.executeUpdate("DELETE FROM sentinel_monitor");
        }
    }

    static void assertCollisionSkip(ExportImportService.ImportResult result, String name) {
        var entry = result.getEntries().stream().filter(e -> name.equals(e.getName())).findFirst().orElseThrow();
        assertEquals("SKIPPED", entry.getOutcome());
        assertNotEquals("Already matches this server", entry.getReason(),
                "An ambiguous reference must not pass the unchanged fast path");
        assertNotNull(entry.getReason());
        assertFalse(entry.getReason().isBlank());
    }
}
