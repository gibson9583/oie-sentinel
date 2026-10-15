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

                if (sourceVersion > 0) {
                    insertLifecycleMarkers(connection, database.vendor(), sourceVersion);
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
                if (sourceVersion > 0) {
                    assertLifecycleMarkers(connection, sourceVersion);
                }
                MatrixEvidenceSupport.database(connection, database.vendor(), "upgrade-from-" + sourceVersion);
            }
        });
    }

    /** Runs the queued uninstall SQL with the engine's per-statement commit/rollback behavior. */
    static void verifyUninstallRestartReinstall(MatrixDatabase database) throws Exception {
        withVersionProperty((version) -> {
            try (Connection connection = database.open()) {
                resetDatabase(connection, database.vendor());
                migrator(connection, database.vendor()).migrate();
                createUnrelatedTable(connection);
                try {
                    for (int cycle = 1; cycle <= 3; cycle++) {
                        insertLifecycleMarkers(connection, database.vendor(), SentinelMigrator.LATEST_VERSION);
                        assertLifecycleMarkers(connection, SentinelMigrator.LATEST_VERSION);
                        MatrixEvidenceSupport.database(connection, database.vendor(), "before-uninstall-" + cycle);
                        assertEquals(List.of(), executeIgnoringFailures(connection, uninstallStatements(database.vendor())),
                                "Complete schema uninstall failed; check foreign-key dependency order");
                        assertRemovedAndReinstall(connection, database.vendor(), version, "uninstall-cycle-" + cycle);
                    }
                } finally {
                    dropUnrelatedTable(connection, database.vendor());
                }
            }
        });
    }

    /** Missing tables in an old or never-installed schema must not prevent later drops. */
    static void verifyUninstallFrom(MatrixDatabase database, int sourceVersion) throws Exception {
        withVersionProperty((version) -> {
            try (Connection connection = database.open()) {
                resetDatabase(connection, database.vendor());
                if (sourceVersion > 0) {
                    migrator(connection, database.vendor()).migrateTo(sourceVersion);
                    insertLifecycleMarkers(connection, database.vendor(), sourceVersion);
                }
                createUnrelatedTable(connection);
                try {
                    executeIgnoringFailures(connection, uninstallStatements(database.vendor()));
                    assertRemovedAndReinstall(connection, database.vendor(), version, "uninstall-from-" + sourceVersion);
                } finally {
                    dropUnrelatedTable(connection, database.vendor());
                }
            }
        });
    }

    /** A restart can find any committed prefix of the queued uninstall already applied. */
    static void verifyInterruptedUninstall(MatrixDatabase database, int completedStatements) throws Exception {
        withVersionProperty((version) -> {
            try (Connection connection = database.open()) {
                resetDatabase(connection, database.vendor());
                migrator(connection, database.vendor()).migrate();
                insertLifecycleMarkers(connection, database.vendor(), SentinelMigrator.LATEST_VERSION);
                createUnrelatedTable(connection);
                try {
                    List<String> statements = uninstallStatements(database.vendor());
                    assertEquals(List.of(), executeIgnoringFailures(connection,
                            statements.subList(0, completedStatements)));
                    executeIgnoringFailures(connection, statements);
                    assertRemovedAndReinstall(connection, database.vendor(), version,
                            "uninstall-interrupted-after-" + completedStatements);
                } finally {
                    dropUnrelatedTable(connection, database.vendor());
                }
            }
        });
    }

    private static List<String> uninstallStatements(String vendor) {
        // DefaultExtensionController does not give this callback a connection.
        SentinelMigrator migrator = new SentinelMigrator();
        migrator.setDatabaseType(vendor);
        return migrator.getUninstallStatements();
    }

    private static void assertRemovedAndReinstall(Connection connection, String vendor,
            VersionState version, String label) throws Exception {
        assertNoSentinelTables(connection, vendor);
        assertEquals(1, rowCount(connection, "matrix_unrelated"), "Uninstall affected another extension's data");
        MatrixEvidenceSupport.database(connection, vendor, label + "-removed");
        MatrixEvidenceSupport.observation(vendor, label + "-unrelated", "matrix_unrelated rows=1 marker=keep");

        // Replay uses the same ignore-errors policy as the engine; already
        // absent tables are harmless and must not prevent later statements.
        executeIgnoringFailures(connection, uninstallStatements(vendor));
        assertNoSentinelTables(connection, vendor);
        assertEquals(1, rowCount(connection, "matrix_unrelated"));

        // Explicit uninstall clears the plugin's version property. Reinstall
        // must create an empty schema, with no archived or retained user data.
        version.clear();
        migrator(connection, vendor).migrate();
        migrator(connection, vendor).migrate();
        assertLatestSchema(connection, vendor);
        assertEquals(String.valueOf(SentinelMigrator.LATEST_VERSION), version.get());
        for (String table : LIVE_TABLES) {
            assertEquals(0, rowCount(connection, table), "Reinstall retained rows in " + table);
        }
        assertEquals(LIVE_TABLES.length + 2, sentinelTables(connection).stream()
                .filter((table) -> table.toLowerCase(Locale.ROOT).startsWith("sentinel_")).count());
        MatrixEvidenceSupport.database(connection, vendor, label + "-reinstalled");
    }

    private static void assertNoSentinelTables(Connection connection, String vendor) throws Exception {
        assertEquals(List.of(), sentinelTables(connection).stream()
                .filter((table) -> table.toLowerCase(Locale.ROOT).startsWith("sentinel_"))
                .filter((table) -> !table.equalsIgnoreCase("sentinel_unrelated_extension")
                        && !table.equalsIgnoreCase("sentinel_monitor_u20250101")).toList(),
                vendor + " uninstall left live or newly archived Sentinel tables");
        assertEquals(1, rowCount(connection, "sentinel_unrelated_extension"),
                "Uninstall swept up another extension's table sharing the prefix");
        assertEquals(1, rowCount(connection, "sentinel_monitor_u20250101"),
                "Uninstall swept up a pre-existing legacy archive");
        if ("oracle".equals(vendor)) {
            try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                    "SELECT COUNT(*) FROM user_recyclebin WHERE original_name LIKE 'SENTINEL_%'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "Oracle retained uninstalled tables in its recycle bin");
            }
        }
    }

    private static void createUnrelatedTable(Connection connection) throws Exception {
        execute(connection, "CREATE TABLE matrix_unrelated (marker VARCHAR(16) NOT NULL)");
        execute(connection, "INSERT INTO matrix_unrelated (marker) VALUES ('keep')");
        execute(connection, "CREATE TABLE sentinel_unrelated_extension (marker VARCHAR(16) NOT NULL)");
        execute(connection, "INSERT INTO sentinel_unrelated_extension (marker) VALUES ('keep')");
        execute(connection, "CREATE TABLE sentinel_monitor_u20250101 (id INTEGER NOT NULL, name VARCHAR(255))");
        execute(connection, "INSERT INTO sentinel_monitor_u20250101 (id, name) VALUES (1, 'existing-archive')");
    }

    private static void dropUnrelatedTable(Connection connection, String vendor) throws Exception {
        String suffix = "oracle".equals(vendor) ? " PURGE" : "";
        execute(connection, "DROP TABLE matrix_unrelated" + suffix);
        execute(connection, "DROP TABLE sentinel_unrelated_extension" + suffix);
        execute(connection, "DROP TABLE sentinel_monitor_u20250101" + suffix);
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
                + "suppressed_by_monitor_id, created_by, created_time, updated_by, updated_time) "
                + "VALUES (?, NULL, 'INACTIVITY', 'ALL', NULL, " + booleanTrue
                + ", 'HIGH', '{}', 1, NULL, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)";
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

    /** Data common to v1 onward, plus later tables only when that schema includes them. */
    private static void insertLifecycleMarkers(Connection connection, String vendor, int schemaVersion)
            throws Exception {
        insertUninstallMarker(connection, vendor);
        String trueLiteral = "oracle".equals(vendor) || "sqlserver".equals(vendor) ? "1" : "TRUE";
        execute(connection, "INSERT INTO sentinel_monitor (name, monitor_type, scope_type, config_json, "
                + "suppressed_by_monitor_id, created_time) SELECT 'matrix-dependent-monitor', 'INACTIVITY', "
                + "'ALL', '{}', id, CURRENT_TIMESTAMP FROM sentinel_monitor WHERE name = 'matrix-uninstall-marker'");
        execute(connection, "INSERT INTO sentinel_alert_event (monitor_id, channel_id, severity, status, "
                + "message, opened_time) SELECT id, 'matrix-channel', 'HIGH', 'PROBLEM', "
                + "'matrix-history-marker', CURRENT_TIMESTAMP FROM sentinel_monitor WHERE name = 'matrix-uninstall-marker'");
        execute(connection, "INSERT INTO sentinel_trigger_state (monitor_id, channel_id, open_alert_event_id) "
                + "SELECT monitor_id, channel_id, id FROM sentinel_alert_event");
        execute(connection, "INSERT INTO sentinel_action (name, action_type, condition_json, config_json, created_time) "
                + "VALUES ('matrix-action', 'EMAIL', '{}', '{}', CURRENT_TIMESTAMP)");
        execute(connection, "INSERT INTO sentinel_action_dispatch_log (alert_event_id, action_id, dispatch_time, success) "
                + "SELECT e.id, a.id, CURRENT_TIMESTAMP, " + trueLiteral
                + " FROM sentinel_alert_event e CROSS JOIN sentinel_action a");
        execute(connection, "INSERT INTO sentinel_channel_activity_sample (channel_id, sample_time, received_delta) "
                + "VALUES ('matrix-channel', CURRENT_TIMESTAMP, 42)");
        execute(connection, "INSERT INTO sentinel_channel_activity_trend (channel_id, hour_bucket, received_sum) "
                + "VALUES ('matrix-channel', CURRENT_TIMESTAMP, 84)");
        String nodeColumn = schemaVersion >= 8 ? ", node_id" : "";
        String nodeValue = schemaVersion >= 8 ? ", 'matrix-node'" : "";
        execute(connection, "INSERT INTO sentinel_connector_status_event "
                + "(channel_id, metadata_id, new_state, changed_time" + nodeColumn + ") "
                + "VALUES ('matrix-channel', 0, 'STARTED', CURRENT_TIMESTAMP" + nodeValue + ")");
        execute(connection, "INSERT INTO sentinel_maintenance_window "
                + "(name, scope_type, active_from, active_until, created_time) "
                + "VALUES ('matrix-window', 'ALL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        if (schemaVersion >= 4) {
            execute(connection, "INSERT INTO sentinel_node_lease (lease_name, node_id, acquired_time, expires_time) "
                    + "VALUES ('matrix-lease', 'matrix-node', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        if (schemaVersion >= 11) {
            execute(connection, "INSERT INTO sentinel_channel_presence (channel_id, node_id, metadata_id, deployed_time) "
                    + "VALUES ('matrix-channel', 'matrix-node', 0, CURRENT_TIMESTAMP)");
        }
    }

    private static void assertLifecycleMarkers(Connection connection, int sourceVersion) throws Exception {
        assertEquals(1, markerCount(connection, "sentinel_monitor"), "Upgrade lost monitor configuration");
        assertEquals(2, rowCount(connection, "sentinel_monitor"));
        for (String table : LIVE_TABLES) {
            if ("sentinel_monitor".equals(table)) continue;
            int expected = "sentinel_node_lease".equals(table) && sourceVersion < 4
                    || "sentinel_channel_presence".equals(table) && sourceVersion < 11 ? 0
                    : "sentinel_connector_status_event".equals(table) && sourceVersion == 11 ? 2 : 1;
            assertEquals(expected, rowCount(connection, table), "Upgrade lost existing data in " + table);
        }
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                "SELECT COUNT(*) FROM sentinel_monitor child JOIN sentinel_monitor parent "
                        + "ON child.suppressed_by_monitor_id = parent.id WHERE child.name = 'matrix-dependent-monitor' "
                        + "AND parent.name = 'matrix-uninstall-marker'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "Upgrade changed monitor suppression configuration");
        }
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                "SELECT COUNT(*) FROM sentinel_action_dispatch_log dispatch "
                        + "JOIN sentinel_alert_event event ON dispatch.alert_event_id = event.id "
                        + "JOIN sentinel_action action ON dispatch.action_id = action.id "
                        + "WHERE event.message = 'matrix-history-marker' AND action.name = 'matrix-action'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "Upgrade changed linked notification history");
        }
    }

    private static long rowCount(Connection connection, String table) throws Exception {
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static List<String> executeIgnoringFailures(Connection connection, List<String> statements)
            throws Exception {
        List<String> failures = new ArrayList<>();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String sql : statements) {
                try {
                    execute(connection, sql);
                    connection.commit();
                } catch (SQLException failure) {
                    connection.rollback();
                    failures.add(sql + " SQLState=" + failure.getSQLState());
                }
            }
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        return failures;
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

    private static List<String> candidates(String name) {
        return List.of(name, name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
