/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.mirth.connect.server.controllers.ConfigurationController;

/** Shared real-database migration/uninstall assertions for the five-vendor rig. */
final class DatabaseMatrixSupport {

    @FunctionalInterface
    interface ConnectionOpener {
        Connection open() throws Exception;
    }

    record MatrixDatabase(String vendor, ConnectionOpener opener) {
        Connection open() throws Exception {
            return opener.open();
        }
    }

    @FunctionalInterface
    private interface VersionedWork {
        void run(VersionState schemaVersion) throws Exception;
    }

    private record VersionState(AtomicReference<String> value, AtomicInteger writes) {
        String get() {
            return value.get();
        }

        void clear() {
            value.set(null);
        }

        void resetWrites() {
            writes.set(0);
        }
    }

    private static final String[] LIVE_TABLES = {
            "sentinel_action_dispatch_log",
            "sentinel_trigger_state",
            "sentinel_alert_event",
            "sentinel_channel_activity_trend",
            "sentinel_channel_activity_sample",
            "sentinel_connector_status_event",
            "sentinel_channel_presence",
            "sentinel_maintenance_window",
            "sentinel_action",
            "sentinel_monitor",
            "sentinel_node_lease"};

    private DatabaseMatrixSupport() {
    }

    /** Builds one historical state, restarts, upgrades, and restarts again. */
    static void verifyUpgradeFrom(MatrixDatabase database, int sourceVersion) throws Exception {
        withVersionProperty((version) -> {
            try (Connection connection = database.open()) {
                resetDatabase(connection, database.vendor());
                if (sourceVersion > 0) {
                    migrator(connection, database.vendor()).migrateTo(sourceVersion);
                    assertEquals(String.valueOf(sourceVersion), version.get(),
                            database.vendor() + " did not materialize schema v" + sourceVersion);
                } else {
                    assertFalse(tableExists(connection, "sentinel_monitor"),
                            database.vendor() + " empty fixture was not empty");
                }

                if (sourceVersion == 11) {
                    execute(connection, "INSERT INTO sentinel_connector_status_event "
                            + "(channel_id, metadata_id, node_id, previous_state, new_state, changed_time) VALUES "
                            + "('matrix-legacy-channel', 0, 'matrix-legacy-node', 'STOPPED', 'STARTED', CURRENT_TIMESTAMP)");
                }

                // A new instance models the next engine start. Detection, not
                // the mocked property, is authoritative in production.
                migrator(connection, database.vendor()).migrate();
                assertLatestSchema(connection, database.vendor());
                assertEquals(String.valueOf(SentinelMigrator.LATEST_VERSION), version.get());

                if (sourceVersion == 11) {
                    try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                            "SELECT deployment_time FROM sentinel_connector_status_event WHERE node_id = 'matrix-legacy-node'")) {
                        assertTrue(rows.next(), "Deployment identity migration dropped legacy observation");
                        org.junit.jupiter.api.Assertions.assertNull(rows.getObject(1), "Legacy observation was assigned an invented deployment identity");
                    }
                }

                // The following restart must be a no-op, not a second attempt
                // to add columns/indexes whose DDL may auto-commit.
                version.resetWrites();
                migrator(connection, database.vendor()).migrate();
                assertLatestSchema(connection, database.vendor());
                assertEquals(1, version.writes().get(), database.vendor()
                        + " restart realigned the version before writing its final value");
                MatrixEvidenceSupport.database(connection, database.vendor(), "upgrade-from-" + sourceVersion);
            }
        });
    }

    /** Runs the real non-destructive uninstall scripts, restart and reinstall. */
    static void verifyUninstallRestartReinstall(MatrixDatabase database) throws Exception {
        withVersionProperty((version) -> {
            try (Connection connection = database.open()) {
                resetDatabase(connection, database.vendor());
                SentinelMigrator installed = migrator(connection, database.vendor());
                installed.migrate();
                insertUninstallMarker(connection, database.vendor());

                SentinelMigrator uninstall = new SentinelMigrator();
                uninstall.setDatabaseType(database.vendor());
                List<String> statements = uninstall.getUninstallStatements();
                executeIgnoringFailures(connection, statements);

                if ("derby".equals(database.vendor())) {
                    assertTrue(tableExists(connection, "sentinel_monitor"),
                            "Derby uninstall must preserve the live schema");
                    assertEquals(1, markerCount(connection, "sentinel_monitor"));
                } else {
                    assertFalse(tableExists(connection, "sentinel_monitor"),
                            database.vendor() + " uninstall left the live schema in place");
                    String archivedMonitor = archivedMonitorTable(connection, 1);
                    assertNotNull(archivedMonitor,
                            database.vendor() + " uninstall created no monitor archive");
                    assertEquals(1, markerCount(connection, archivedMonitor),
                            database.vendor() + " uninstall did not preserve archived data");
                }

                // The engine removes the extension's CONFIGURATION group on
                // uninstall. A fresh migrator must therefore detect tables (or
                // their absence) without relying on the old property.
                version.clear();
                migrator(connection, database.vendor()).migrate();
                assertLatestSchema(connection, database.vendor());
                assertEquals(String.valueOf(SentinelMigrator.LATEST_VERSION), version.get());

                if (!"derby".equals(database.vendor())) {
                    for (int cycle = 2; cycle <= 3; cycle++) {
                        insertUninstallMarker(connection, database.vendor());
                        executeIgnoringFailures(connection, uninstall.getUninstallStatements());
                        assertFalse(tableExists(connection, "sentinel_monitor"), database.vendor() + " archive cycle " + cycle);
                        String archive = archivedMonitorTable(connection, cycle);
                        assertNotNull(archive);
                        assertEquals(1, markerCount(connection, archive));
                        version.clear();
                        migrator(connection, database.vendor()).migrate();
                        assertLatestSchema(connection, database.vendor());
                    }
                    insertUninstallMarker(connection, database.vendor());
                    List<String> liveIndexes = indexNames(connection, "sentinel_monitor");
                    executeIgnoringFailures(connection, uninstall.getUninstallStatements());
                    assertTrue(tableExists(connection, "sentinel_monitor"), "Exhausted archive targets moved live schema");
                    assertEquals(1, markerCount(connection, "sentinel_monitor"));
                    assertEquals(liveIndexes, indexNames(connection, "sentinel_monitor"),
                            "Exhausted archive targets renamed live indexes");
                    version.clear();
                    migrator(connection, database.vendor()).migrate();
                    assertLatestSchema(connection, database.vendor());
                    for (int cycle = 1; cycle <= 3; cycle++) {
                        assertEquals(1, markerCount(connection, archivedMonitorTable(connection, cycle)));
                    }
                    System.out.println(database.vendor() + " archive cycles=3 retained markers=4 exhausted-target live indexes preserved");
                }
                MatrixEvidenceSupport.database(connection, database.vendor(), "final-reinstall-state");
            }
        });
    }

    /** Simulates auto-committed ALTER followed by failure before its completion index. */
    static void verifyInterruptedMigration(MatrixDatabase database, int target) throws Exception {
        withVersionProperty((version) -> {
            try (Connection connection = database.open()) {
                resetDatabase(connection, database.vendor());
                migrator(connection, database.vendor()).migrateTo(target - 1);
                String column = target == 8 ? "node_id" : target == 9 ? "resolution_pending" : "problem_pending";
                String table = target == 8 ? "sentinel_connector_status_event" : "sentinel_alert_event";
                if (target == 8) {
                    try (java.io.InputStream resource = DatabaseMatrixSupport.class.getResourceAsStream(
                            "/" + database.vendor() + "-sentinel-v8.sql")) {
                        assertNotNull(resource);
                        execute(connection, new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                } else {
                    String type = "oracle".equals(database.vendor()) ? "NUMBER(1)"
                            : "sqlserver".equals(database.vendor()) ? "BIT" : "BOOLEAN";
                    String add = "oracle".equals(database.vendor()) || "sqlserver".equals(database.vendor())
                            ? " ADD " : " ADD COLUMN ";
                    execute(connection, "ALTER TABLE " + table + add + column + " " + type);
                }
                if (target == 10) {
                    insertUninstallMarker(connection, database.vendor());
                    execute(connection, "INSERT INTO sentinel_alert_event (monitor_id, channel_id, severity, status, "
                            + "message, opened_time) SELECT id, 'matrix-channel', 'HIGH', 'PROBLEM', "
                            + "'legacy open problem', CURRENT_TIMESTAMP FROM sentinel_monitor");
                    execute(connection, "INSERT INTO sentinel_alert_event (monitor_id, channel_id, severity, status, "
                            + "message, opened_time) SELECT id, 'matrix-channel', 'HIGH', 'RESOLVED', "
                            + "'legacy resolved problem', CURRENT_TIMESTAMP FROM sentinel_monitor");
                }
                migrator(connection, database.vendor()).migrate();
                assertLatestSchema(connection, database.vendor());
                if (target == 10) {
                    try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                            "SELECT problem_pending FROM sentinel_alert_event WHERE message = 'legacy open problem'")) {
                        assertTrue(rows.next());
                        assertTrue(rows.getBoolean(1), "Interrupted outbox upgrade missed legacy open problem");
                    }
                    try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                            "SELECT problem_pending FROM sentinel_alert_event WHERE message = 'legacy resolved problem'")) {
                        assertTrue(rows.next());
                        org.junit.jupiter.api.Assertions.assertNull(rows.getObject(1), "Backfill enqueued a resolved event");
                    }
                    String falseLiteral = "oracle".equals(database.vendor()) || "sqlserver".equals(database.vendor())
                            ? "0" : "FALSE";
                    execute(connection, "UPDATE sentinel_alert_event SET problem_pending = " + falseLiteral
                            + " WHERE message = 'legacy open problem'");
                    String drop = "mysql".equals(database.vendor()) || "sqlserver".equals(database.vendor())
                            ? "DROP INDEX idx_sentinel_alert_problem ON sentinel_alert_event"
                            : "DROP INDEX idx_sentinel_alert_problem";
                    execute(connection, drop);
                    // A retry after backfill but before its completion index must preserve
                    // work already acknowledged by the dispatcher.
                    migrator(connection, database.vendor()).migrate();
                    try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                            "SELECT problem_pending FROM sentinel_alert_event WHERE message = 'legacy open problem'")) {
                        assertTrue(rows.next());
                        assertFalse(rows.getBoolean(1), "Migration retry re-enqueued acknowledged outbox work");
                    }
                }
                migrator(connection, database.vendor()).migrate();
                assertEquals(String.valueOf(SentinelMigrator.LATEST_VERSION), version.get());
                MatrixEvidenceSupport.database(connection, database.vendor(), "interrupted-v" + target);
            }
        });
    }

    static SentinelMigrator migrator(Connection connection, String vendor) {
        SentinelMigrator migrator = new SentinelMigrator();
        migrator.setConnection(connection);
        migrator.setDatabaseType(vendor);
        return migrator;
    }

    static void resetDatabase(Connection connection, String vendor) throws Exception {
        connection.setAutoCommit(true);
        List<String> tables = sentinelTables(connection);
        if (tables.isEmpty() && !"postgres".equals(vendor)) {
            return;
        }

        switch (vendor) {
            case "postgres":
                for (String table : tables) {
                    execute(connection, "DROP TABLE IF EXISTS " + table + " CASCADE");
                }
                dropPostgresSequences(connection);
                break;
            case "oracle":
                for (String table : tables) {
                    execute(connection, "DROP TABLE " + table + " CASCADE CONSTRAINTS PURGE");
                }
                break;
            case "mysql":
                execute(connection, "SET FOREIGN_KEY_CHECKS = 0");
                try {
                    for (String table : tables) {
                        execute(connection, "DROP TABLE IF EXISTS `" + table + "`");
                    }
                } finally {
                    execute(connection, "SET FOREIGN_KEY_CHECKS = 1");
                }
                break;
            case "sqlserver":
                dropSqlServerForeignKeys(connection, tables);
                for (String table : tables) {
                    execute(connection, "DROP TABLE " + table);
                }
                break;
            case "derby":
                // Derby cases use a unique in-memory database. Dropping its
                // system-named inline foreign keys would weaken this rig and
                // is unnecessary.
                throw new IllegalStateException("Derby matrix databases must be fresh per case");
            default:
                throw new IllegalArgumentException("Unsupported matrix vendor " + vendor);
        }
    }

    static void assertLatestSchema(Connection connection, String vendor) throws Exception {
        for (String table : LIVE_TABLES) {
            assertTrue(tableExists(connection, table), vendor + " missing latest table " + table);
        }
        assertTrue(columnExists(connection, "sentinel_alert_event", "problem_pending"),
                vendor + " missing problem dispatch outbox");
        assertTrue(columnExists(connection, "sentinel_alert_event", "resolution_pending"),
                vendor + " missing resolution dispatch outbox");
        assertTrue(columnExists(connection, "sentinel_node_lease", "lease_epoch"), vendor + " missing fencing epoch");
        assertTrue(columnExists(connection, "sentinel_connector_status_event", "node_id"), vendor + " missing connector identity");
        assertTrue(columnExists(connection, "sentinel_connector_status_event", "deployment_time"), vendor + " missing observation deployment identity");
        assertTrue(indexExists(connection, "sentinel_connector_status_event", "idx_sentinel_conn_node_id"), vendor + " missing connector index");
        assertTrue(indexExists(connection, "sentinel_alert_event", "idx_sentinel_alert_problem"), vendor + " missing problem outbox index");
        assertTrue(indexExists(connection, "sentinel_alert_event", "idx_sentinel_alert_resolution"), vendor + " missing resolution outbox index");
    }

    private static void withVersionProperty(VersionedWork work) throws Exception {
        VersionState version = new VersionState(new AtomicReference<>(), new AtomicInteger());
        ConfigurationController controller = mock(ConfigurationController.class);
        when(controller.getProperty(anyString(), anyString()))
                .thenAnswer((ignored) -> version.value().get());
        doAnswer((invocation) -> {
            version.value().set(invocation.getArgument(2));
            version.writes().incrementAndGet();
            return null;
        }).when(controller).saveProperty(anyString(), anyString(), anyString());

        try (MockedStatic<ConfigurationController> singleton = Mockito.mockStatic(ConfigurationController.class)) {
            singleton.when(ConfigurationController::getInstance).thenReturn(controller);
            work.run(version);
        }
    }

    private static void insertUninstallMarker(Connection connection, String vendor) throws Exception {
        String booleanTrue = vendor.equals("oracle") || vendor.equals("sqlserver") ? "1" : "TRUE";
        String sql = "INSERT INTO sentinel_monitor (name, description, monitor_type, scope_type, "
                + "scope_id, enabled, severity, config_json, min_consecutive_breaches, "
                + "suppressed_by_monitor_id, runbook_url, created_by, created_time, updated_by, updated_time) "
                + "VALUES (?, NULL, 'INACTIVITY', 'ALL', NULL, " + booleanTrue
                + ", 'HIGH', '{}', 1, NULL, NULL, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "matrix-uninstall-marker");
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static int markerCount(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE name = 'matrix-uninstall-marker'")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static String archivedMonitorTable(Connection connection, int ordinal) throws Exception {
        String suffix = "_u" + LocalDate.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.BASIC_ISO_DATE)
                + (ordinal == 1 ? "" : "_" + ordinal);
        return sentinelTables(connection).stream()
                .filter((name) -> name.equalsIgnoreCase("sentinel_monitor" + suffix))
                .findFirst().orElse(null);
    }

    private static void executeIgnoringFailures(Connection connection, List<String> statements)
            throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String sql : statements) {
                try {
                    execute(connection, sql);
                    connection.commit();
                } catch (SQLException expectedAlternativeFailure) {
                    connection.rollback();
                }
            }
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static List<String> sentinelTables(Connection connection) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> tables = new ArrayList<>();
        try (ResultSet rows = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("sentinel_") || lower.equals("d_m1") || lower.equals("d_mm1")) {
                    tables.add(name);
                }
            }
        }
        // Child-ish objects first for vendors where DROP TABLE has no CASCADE.
        tables.sort(Comparator.comparingInt(DatabaseMatrixSupport::dropPriority));
        return tables;
    }

    private static int dropPriority(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("action_dispatch_log")) return 0;
        if (value.contains("trigger_state")) return 1;
        if (value.contains("alert_event")) return 2;
        if (value.equals("d_mm1")) return 3;
        if (value.equals("d_m1")) return 4;
        if (value.contains("monitor")) return 9;
        return 5;
    }

    private static void dropSqlServerForeignKeys(Connection connection, List<String> tables)
            throws Exception {
        List<String> drops = new ArrayList<>();
        String query = "SELECT OBJECT_SCHEMA_NAME(parent_object_id), "
                + "OBJECT_NAME(parent_object_id), name FROM sys.foreign_keys";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                String table = rows.getString(2);
                if (tables.stream().anyMatch((candidate) -> candidate.equalsIgnoreCase(table))) {
                    drops.add("ALTER TABLE [" + rows.getString(1) + "].[" + table
                            + "] DROP CONSTRAINT [" + rows.getString(3) + "]");
                }
            }
        }
        for (String drop : drops) execute(connection, drop);
    }

    private static void dropPostgresSequences(Connection connection) throws Exception {
        List<String> sequences = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT sequencename FROM pg_sequences WHERE schemaname = current_schema() "
                                + "AND sequencename LIKE 'sentinel_%'")) {
            while (rows.next()) sequences.add(rows.getString(1));
        }
        for (String sequence : sequences) {
            execute(connection, "DROP SEQUENCE IF EXISTS " + sequence + " CASCADE");
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : candidates(table)) {
            try (ResultSet rows = metadata.getTables(connection.getCatalog(), null, candidate,
                    new String[]{"TABLE"})) {
                if (rows.next()) return true;
            }
        }
        return false;
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String tableName : candidates(table)) {
            for (String columnName : candidates(column)) {
                try (ResultSet rows = metadata.getColumns(
                        connection.getCatalog(), null, tableName, columnName)) {
                    if (rows.next()) return true;
                }
            }
        }
        return false;
    }

    private static boolean indexExists(Connection connection, String table, String index)
            throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String tableName : candidates(table)) {
            try (ResultSet rows = metadata.getIndexInfo(
                    connection.getCatalog(), null, tableName, false, true)) {
                while (rows.next()) {
                    String found = rows.getString("INDEX_NAME");
                    if (found != null && found.equalsIgnoreCase(index)) return true;
                }
            } catch (SQLException ignoredCaseMismatch) {
                // Try the next identifier folding convention.
            }
        }
        return false;
    }

    private static List<String> indexNames(Connection connection, String table) throws Exception {
        List<String> names = new ArrayList<>();
        for (String tableName : candidates(table)) {
            try (ResultSet rows = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), null, tableName, false, true)) {
                while (rows.next()) {
                    String found = rows.getString("INDEX_NAME");
                    if (found != null && !names.contains(found)) names.add(found);
                }
            }
        }
        return names;
    }

    private static List<String> candidates(String name) {
        return List.of(name, name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
