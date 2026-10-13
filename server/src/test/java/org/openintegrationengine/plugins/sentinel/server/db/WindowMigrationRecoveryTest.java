package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.mirth.connect.server.controllers.ConfigurationController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WindowMigrationRecoveryTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    void resumesAfterEveryCommittedV2Statement(int completed) throws Exception {
        verifyRecovery(completed, false);
    }

    @Test
    void repairsIncompleteV2EvenWhenNewerSchemaWasReportedComplete() throws Exception {
        verifyRecovery(1, true);
    }

    private void verifyRecovery(int completed, boolean newerSchema) throws Exception {
        String url = "jdbc:derby:memory:windowRecovery" + UUID.randomUUID().toString().replace("-", "");
        AtomicReference<String> version = new AtomicReference<>();
        ConfigurationController controller = mock(ConfigurationController.class);
        when(controller.getProperty(SentinelMigrator.PLUGIN_NAME, SentinelMigrator.VERSION_PROPERTY))
                .thenAnswer(invocation -> version.get());
        doAnswer(invocation -> { version.set(invocation.getArgument(2)); return null; })
                .when(controller).saveProperty(eq(SentinelMigrator.PLUGIN_NAME),
                        eq(SentinelMigrator.VERSION_PROPERTY), anyString());
        try (var config = mockStatic(ConfigurationController.class);
                Connection db = DriverManager.getConnection(url + ";create=true")) {
            config.when(ConfigurationController::getInstance).thenReturn(controller);
            SentinelMigrator migrator = DatabaseMatrixSupport.migrator(db, "derby");
            migrator.migrateTo(1);
            String script;
            try (var resource = getClass().getResourceAsStream("/derby-sentinel-v2.sql")) {
                assertNotNull(resource);
                script = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            String[] statements = script.trim().split("\\r?\\n\\s*\\r?\\n");
            assertEquals(8, statements.length);
            db.setAutoCommit(true);
            for (int i = 0; i < completed; i++) {
                try (Statement sql = db.createStatement()) { sql.executeUpdate(statements[i]); }
            }
            if (newerSchema) {
                // Recreate the older release's false-positive detection: later
                // migrations ran while v2 still lacked seven operations.
                for (int i = 3; i <= SentinelMigrator.LATEST_VERSION; i++) {
                    var step = SentinelMigrator.class.getDeclaredMethod("applyV" + i);
                    step.setAccessible(true);
                    step.invoke(migrator);
                }
                version.set(String.valueOf(SentinelMigrator.LATEST_VERSION));
            }
            DatabaseMatrixSupport.migrator(db, "derby").migrate();
            assertEquals(String.valueOf(SentinelMigrator.LATEST_VERSION), version.get());
            try (Statement sql = db.createStatement(); ResultSet rows = sql.executeQuery(
                    "SELECT window_mode, repeat_type, days_of_week, days_of_month, start_time, end_time "
                            + "FROM sentinel_maintenance_window")) {
                assertFalse(rows.next());
            }
            for (String column : new String[]{"ACTIVE_FROM", "ACTIVE_UNTIL"}) {
                try (ResultSet metadata = db.getMetaData().getColumns(null, null,
                        "SENTINEL_MAINTENANCE_WINDOW", column)) {
                    assertTrue(metadata.next());
                    assertEquals(DatabaseMetaData.columnNullable, metadata.getInt("NULLABLE"));
                }
            }
            // A complete second startup must not replay duplicate ALTERs.
            DatabaseMatrixSupport.migrator(db, "derby").migrate();
        } finally {
            SQLException dropped = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection(url + ";drop=true"));
            assertTrue("08006".equals(dropped.getSQLState()) || "XJ004".equals(dropped.getSQLState()));
        }
    }
}
