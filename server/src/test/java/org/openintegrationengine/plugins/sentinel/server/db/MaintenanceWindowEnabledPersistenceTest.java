/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MaintenanceWindowEnabledPersistenceTest {
    private Configuration mapper(String vendor) throws Exception {
        Configuration configuration = new Configuration();
        String resource = "/mapper/" + vendor + "-sqlmap.xml";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "postgres", "oracle", "sqlserver"})
    void everyVendorBindsOnlyTheRequestedFlagAndIdentity(String vendor) throws Exception {
        var sql = mapper(vendor).getMappedStatement("Sentinel.setMaintenanceWindowEnabled")
                .getBoundSql(Map.of("id", 7, "enabled", true));
        assertEquals("UPDATE sentinel_maintenance_window SET enabled = ? WHERE id = ?",
                sql.getSql().trim().replaceAll("\\s+", " "));
        assertEquals(java.util.List.of("enabled", "id"), sql.getParameterMappings().stream()
                .map(p -> p.getProperty()).toList());
    }

    @Test
    void realDerbyTogglePreservesConcurrentEditsAndRetries() throws Exception {
        String url = "jdbc:derby:memory:sentinelToggle" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url + ";create=true")) {
            try (Statement sql = connection.createStatement()) {
                sql.executeUpdate("CREATE TABLE sentinel_maintenance_window "
                        + "(id INT PRIMARY KEY, enabled BOOLEAN, window_mode VARCHAR(16), scope_id VARCHAR(128), name VARCHAR(128))");
                sql.executeUpdate("INSERT INTO sentinel_maintenance_window VALUES (7, FALSE, 'SUPPRESS', 'old-channel', 'old')");
                // Another operator edits after a client rendered its old row.
                sql.executeUpdate("UPDATE sentinel_maintenance_window SET window_mode='ACTIVE', scope_id='new-channel', name='new' WHERE id=7");
            }
            SqlSession session = new SqlSessionFactoryBuilder().build(mapper("derby")).openSession(connection);
            try {
                for (boolean requested : new boolean[] {true, true, false, false}) {
                    session.update("Sentinel.setMaintenanceWindowEnabled", Map.of("id", 7, "enabled", requested));
                    session.commit();
                    try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                            "SELECT enabled, window_mode, scope_id, name FROM sentinel_maintenance_window WHERE id=7")) {
                        assertTrue(rows.next());
                        assertEquals(requested, rows.getBoolean(1));
                        assertEquals("ACTIVE", rows.getString(2));
                        assertEquals("new-channel", rows.getString(3));
                        assertEquals("new", rows.getString(4));
                    }
                }
                assertEquals(0, session.update("Sentinel.setMaintenanceWindowEnabled", Map.of("id", 99, "enabled", true)));
            } finally {
                session.close();
            }
        } finally {
            try { DriverManager.getConnection(url + ";drop=true"); }
            catch (SQLException e) { if (!"08006".equals(e.getSQLState()) && !"XJ004".equals(e.getSQLState())) throw e; }
        }
    }
}
