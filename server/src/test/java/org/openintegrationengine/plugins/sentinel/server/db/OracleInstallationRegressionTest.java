/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Issue #18: Oracle requires DEFAULT before the inline NOT NULL constraint. */
class OracleInstallationRegressionTest {
    private static final Pattern INVALID_DEFAULT_ORDER =
            Pattern.compile("\\bNOT\\s+NULL\\s+DEFAULT\\b", Pattern.CASE_INSENSITIVE);

    @TestFactory
    Stream<DynamicTest> packagedOracleScriptsKeepDefaultsBeforeConstraints() {
        // v9/v10 use guarded Java statements rather than SQL resources.
        return Stream.of("tables", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v11", "v12")
                .map(suffix -> dynamicTest("Oracle " + suffix + " default ordering", () -> {
                    List<String> statements = new ScriptReader().read("/oracle-sentinel-" + suffix + ".sql");
                    assertFalse(statements.isEmpty(), "Missing migration statements: " + suffix);
                    for (String statement : statements) {
                        assertFalse(INVALID_DEFAULT_ORDER.matcher(statement).find(),
                                "Oracle rejects NOT NULL DEFAULT in " + suffix + ": " + statement);
                    }
                }));
    }

    /** Called only with the matrix's owned, isolated Oracle database. */
    static void verifyOracleInstallation(DatabaseMatrixSupport.MatrixDatabase database) throws Exception {
        DatabaseMatrixSupport.verifyUpgradeFrom(database, 0);
        try (Connection connection = database.open(); Statement sql = connection.createStatement()) {
            SQLException originalFailure = assertThrows(SQLException.class, () -> sql.execute(
                    "CREATE TABLE sentinel_invalid_default (severity VARCHAR2(16) NOT NULL DEFAULT 'WARNING')"));
            MatrixEvidenceSupport.observation("oracle", "issue-18-negative-control", originalFailure);
            // Oracle 19c reports ORA-00907; Oracle Free 23 reports ORA-03076.
            assertTrue(originalFailure.getErrorCode() == 907 || originalFailure.getErrorCode() == 3076,
                    "Old ordering must fail with an Oracle column syntax error: " + originalFailure);

            // Exercise the corrected packaged DDL, including generated IDs and omitted defaults.
            sql.executeUpdate("INSERT INTO sentinel_monitor "
                    + "(name, monitor_type, scope_type, config_json, created_time) VALUES "
                    + "('oracle-install-regression', 'INACTIVITY', 'ALL', '{}', CURRENT_TIMESTAMP)");
            try (ResultSet rows = sql.executeQuery("SELECT id, enabled, severity, min_consecutive_breaches "
                    + "FROM sentinel_monitor WHERE name = 'oracle-install-regression'")) {
                assertTrue(rows.next());
                assertTrue(rows.getLong("id") > 0);
                assertEquals(1, rows.getInt("enabled"));
                assertEquals("WARNING", rows.getString("severity"));
                assertEquals(1, rows.getInt("min_consecutive_breaches"));
                assertFalse(rows.next());
            }
            SQLException nullFailure = assertThrows(SQLException.class, () -> sql.executeUpdate(
                    "UPDATE sentinel_monitor SET severity = NULL WHERE name = 'oracle-install-regression'"));
            assertEquals(1407, nullFailure.getErrorCode(), "Defaults must retain their NOT NULL constraints");
            try (ResultSet rows = sql.executeQuery("SELECT severity FROM sentinel_monitor "
                    + "WHERE name = 'oracle-install-regression'")) {
                assertTrue(rows.next());
                assertEquals("WARNING", rows.getString(1), "Failed update must preserve the row");
            }
            MatrixEvidenceSupport.database(connection, "oracle", "issue-18-installation");
        }
    }

    private static class ScriptReader extends SentinelMigrator {
        List<String> read(String resource) throws IOException {
            return readStatements(resource);
        }
    }
}
