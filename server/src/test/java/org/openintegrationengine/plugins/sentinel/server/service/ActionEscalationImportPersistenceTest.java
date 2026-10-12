/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;

/** Real service/repository/mapper verification for legacy canonical-name collisions. */
class ActionEscalationImportPersistenceTest {
    private String url;
    private SqlSessionManager sessions;
    private MockedStatic<SqlConfig> config;

    @BeforeEach
    void setup() throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        url = "jdbc:derby:memory:actionImport" + UUID.randomUUID().toString().replace("-", "");
        DriverManager.getConnection(url + ";create=true").close();
        try (Connection connection = DriverManager.getConnection(url); Statement sql = connection.createStatement()) {
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
        Configuration mapping = new Configuration(new Environment("action-import", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver", url, null, null)));
        mapping.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream in = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            new XMLMapperBuilder(in, mapping, "derby", mapping.getSqlFragments()).parse();
        }
        sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(mapping));
        SqlConfig engineConfig = mock(SqlConfig.class);
        when(engineConfig.getSqlSessionManager()).thenReturn(sessions);
        config = mockStatic(SqlConfig.class);
        config.when(SqlConfig::getInstance).thenReturn(engineConfig);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (config != null) config.close();
        if (sessions != null && sessions.isManagedSessionStarted()) sessions.close();
        if (url != null) {
            SQLException dropped = assertThrows(SQLException.class, () -> DriverManager.getConnection(url + ";drop=true"));
            assertEquals("08006", dropped.getSQLState());
            System.out.println("Dropped isolated action-import database " + url + " SQLState=" + dropped.getSQLState());
        }
    }

    @Test
    void exportKeepsDistinctPersistedRowsAndImportNeverGuessesBetweenTheirRecipients() {
        try (MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class);
                MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
                MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class)) {
            // Both names are accepted by the existing CRUD contract and by the
            // actual UNIQUE constraint. Import must handle stored rows safely.
            Action intended = ActionService.create(action(" Pager", "intended@example.test", null), 99);
            Action other = ActionService.create(action("Pager", "other@example.test", null), 99);
            Action primary = ActionService.create(action("Primary", "primary@example.test", intended.getId()), 99);
            List<Action> before = ActionRepository.listActions(null);
            assertEquals(3, before.size());
            ObjectNode exported = ExportImportService.export();
            ArrayNode exportedActions = (ArrayNode) exported.get("actions");
            assertEquals(3, exportedActions.size());
            assertEquals(Set.of(" Pager", "Pager", "Primary"),
                    java.util.stream.StreamSupport.stream(exportedActions.spliterator(), false)
                            .map(a -> a.path("name").asText()).collect(Collectors.toSet()));
            assertEquals(" Pager", exportedActions.get(2).path("escalateToActionName").asText());

            for (String targetName : List.of(" Pager", "Pager", "PAGER")) {
                ObjectNode incoming = Json.mapper().valueToTree(primary);
                incoming.remove("id");
                incoming.remove("escalateToActionId");
                incoming.put("escalateToActionName", targetName);
                incoming.put("description", "must not change the existing source");
                ObjectNode document = Json.mapper().createObjectNode().put("schemaVersion", 2);
                document.putArray("actions").add(incoming);
                var result = ExportImportService.importDocument(document, 99);
                assertEquals(1, result.getSkipped());
                assertTrue(result.getEntries().get(0).getReason().contains("multiple actions"));
                assertEquals(intended.getId(), ActionRepository.getAction(primary.getId()).getEscalateToActionId());
                assertNotEquals(other.getId(), ActionRepository.getAction(primary.getId()).getEscalateToActionId());
            }
            // Re-importing the complete export rejects duplicate input names
            // and their dependents instead of overwriting either recipient.
            assertEquals(3, ExportImportService.importDocument(exported, 99).getSkipped());
            List<Action> after = ActionRepository.listActions(null);
            assertEquals(Json.write(before), Json.write(after));
            System.out.println("Action import final rows: " + Json.write(after));
        }
    }

    private static Action action(String name, String recipient, Integer target) {
        Action action = new Action();
        action.setName(name);
        action.setEnabled(true);
        action.setActionType(ActionType.EMAIL);
        action.setOperationMode(OperationMode.BOTH);
        action.setConditionJson("[]");
        action.setConfigJson("{\"to\":\"" + recipient + "\"}");
        action.setMaxNotificationsPerWindow(5);
        action.setRollupWindowSeconds(60);
        action.setEscalateAfterSeconds(300);
        action.setEscalateToActionId(target);
        return action;
    }
}
