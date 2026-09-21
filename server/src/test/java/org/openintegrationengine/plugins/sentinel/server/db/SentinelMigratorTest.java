/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class SentinelMigratorTest {

    @Test
    void latestSchemaIncludesBothLifecycleOutboxes() {
        assertEquals(12, SentinelMigrator.LATEST_VERSION);
    }

    @Test
    void archiveNamesRespectLegacyOracleLimitAndRemainDistinct() {
        LocalDate date = LocalDate.of(2026, 8, 30);
        String first = SentinelMigrator.archiveName(
                "idx_sentinel_action_dispatch_log_event", date, 1, 30);
        String repeat = SentinelMigrator.archiveName(
                "idx_sentinel_action_dispatch_log_event", date, 2, 30);
        String other = SentinelMigrator.archiveName(
                "idx_sentinel_action_dispatch_log_action", date, 1, 30);

        assertTrue(first.length() <= 30);
        assertTrue(repeat.length() <= 30);
        assertTrue(first.endsWith("_u20260830"));
        assertTrue(repeat.endsWith("_u20260830_2"));
        assertFalse(first.equals(repeat));
        assertFalse(first.equals(other));
        assertEquals(first, SentinelMigrator.archiveName(
                "idx_sentinel_action_dispatch_log_event", date, 1, 30));
    }

    @Test
    void postgresArchiveMovesTablesIndexesConstraintsAndSequences() {
        SentinelMigrator migrator = new SentinelMigrator();
        migrator.setDatabaseType("postgres");
        List<String> statements = migrator.getUninstallStatements();
        LocalDate date = LocalDate.now(ZoneId.systemDefault());
        String archivedTable = SentinelMigrator.archiveName(
                "sentinel_trigger_state", date, 1, 63);

        int tableRename = indexOfContaining(statements,
                "ALTER TABLE sentinel_trigger_state RENAME TO " + archivedTable);
        int indexRename = indexOfContaining(statements,
                "ALTER INDEX idx_sentinel_monitor_scope RENAME TO "
                        + SentinelMigrator.archiveName(
                                "idx_sentinel_monitor_scope", date, 1, 63));
        assertTrue(tableRename >= 0);
        assertEquals(0, tableRename);
        assertTrue(statements.get(0).contains("Sentinel archive target set is occupied"));
        assertTrue(statements.get(0).contains("ALTER TABLE sentinel_monitor RENAME TO "));
        assertTrue(indexRename > tableRename);
        assertTrue(statements.contains("ALTER TABLE " + archivedTable
                + " RENAME CONSTRAINT sentinel_trigger_state_pkey TO "
                + SentinelMigrator.archiveName("sentinel_trigger_state_pkey", date, 1, 63)));
        assertTrue(indexOfContaining(statements, "ALTER SEQUENCE sentinel_trigger_state_id_seq RENAME TO "
                + SentinelMigrator.archiveName("sentinel_trigger_state_id_seq", date, 1, 63)) >= 0);
        assertFalse(statements.stream().anyMatch(statement -> statement.startsWith("DROP TABLE")));
    }

    @Test
    void vendorArchiveScriptsHandleTheirSchemaNamespaces() {
        LocalDate date = LocalDate.now(ZoneId.systemDefault());

        SentinelMigrator oracle = new SentinelMigrator();
        oracle.setDatabaseType("oracle");
        List<String> oracleStatements = oracle.getUninstallStatements();
        String oracleTrigger = SentinelMigrator.archiveName("sentinel_trigger_state", date, 1, 30);
        assertTrue(indexOfContaining(oracleStatements,
                "ALTER TABLE sentinel_trigger_state RENAME TO " + oracleTrigger) >= 0);
        assertTrue(oracleStatements.get(0).contains("SELECT COUNT(*) INTO sentinel_conflicts"));
        assertTrue(oracleStatements.get(0).contains("EXCEPTION WHEN OTHERS THEN"));
        assertTrue(oracleStatements.get(0).contains(
                "ALTER TABLE " + oracleTrigger + " RENAME TO sentinel_trigger_state"));
        assertTrue(oracleStatements.contains("ALTER TABLE " + oracleTrigger
                + " RENAME CONSTRAINT uq_sentinel_trigger_state TO "
                + SentinelMigrator.archiveName("uq_sentinel_trigger_state", date, 1, 30)));
        assertTrue(oracleStatements.contains("ALTER TABLE " + oracleTrigger
                + " RENAME CONSTRAINT uq_sentinel_trigger_state TO "
                + SentinelMigrator.archiveName("uq_sentinel_trigger_state", date, 2, 30)));

        SentinelMigrator sqlServer = new SentinelMigrator();
        sqlServer.setDatabaseType("sqlserver");
        List<String> sqlServerStatements = sqlServer.getUninstallStatements();
        assertTrue(sqlServerStatements.get(0).startsWith("SET XACT_ABORT ON; IF "));
        assertTrue(sqlServerStatements.get(0).contains(
                "EXEC sp_rename 'sentinel_monitor', 'sentinel_monitor_u"));
        assertTrue(indexOfContaining(sqlServerStatements, "DECLARE @sentinel_object NVARCHAR(776) = "
                + "QUOTENAME(SCHEMA_NAME()) + N'.' + QUOTENAME(N'df_sentinel_node_lease_epoch'); "
                + "EXEC sp_rename @sentinel_object, N'"
                + SentinelMigrator.archiveName("df_sentinel_node_lease_epoch", date, 1, 128)
                + "', N'OBJECT'") >= 0);

        SentinelMigrator mysql = new SentinelMigrator();
        mysql.setDatabaseType("mysql");
        List<String> mysqlStatements = mysql.getUninstallStatements();
        String archivedDispatch = SentinelMigrator.archiveName(
                "sentinel_action_dispatch_log", date, 1, 64);
        String archivedAction = SentinelMigrator.archiveName("sentinel_action", date, 1, 64);
        assertTrue(mysqlStatements.get(0).startsWith("RENAME TABLE sentinel_action_dispatch_log TO "
                + archivedDispatch + ", "));
        assertFalse(mysqlStatements.contains("ALTER TABLE sentinel_action_dispatch_log "
                + "DROP FOREIGN KEY sentinel_action_dispatch_log_ibfk_2"));
        assertTrue(mysqlStatements.contains("ALTER TABLE " + archivedDispatch
                + " DROP FOREIGN KEY sentinel_action_dispatch_log_ibfk_2"));
        assertTrue(mysqlStatements.contains("ALTER TABLE " + archivedDispatch
                + " DROP FOREIGN KEY " + archivedDispatch + "_ibfk_2"));
        assertTrue(mysqlStatements.contains("ALTER TABLE " + archivedDispatch
                + " ADD CONSTRAINT "
                + SentinelMigrator.archiveName("fk_sentinel_dispatch_action", date, 1, 64)
                + " FOREIGN KEY (action_id) REFERENCES " + archivedAction + "(id) ON DELETE SET NULL"));

        SentinelMigrator derby = new SentinelMigrator();
        derby.setDatabaseType("derby");
        assertTrue(derby.getUninstallStatements().isEmpty());
    }

    private static int indexOfContaining(List<String> statements, String fragment) {
        for (int i = 0; i < statements.size(); i++) if (statements.get(i).contains(fragment)) return i;
        return -1;
    }
}
