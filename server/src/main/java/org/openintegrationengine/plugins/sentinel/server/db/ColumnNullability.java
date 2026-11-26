/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mirth.connect.model.util.MigrationException;

/**
 * Relaxing NOT NULL columns for {@link SentinelMigrator}: a column's declared
 * nullability, the per-vendor statement that drops it, and the SQL Server
 * steps around the constraint and index that use {@code channel_id}. Runs on
 * the migrator's connection and through its statement and index helpers.
 */
final class ColumnNullability {

    private final SentinelMigrator migrator;

    ColumnNullability(SentinelMigrator migrator) {
        this.migrator = migrator;
    }

    /**
     * A log problem has no channel. The base DDL stays v1-shaped (NOT NULL),
     * so fresh installs and upgrades both relax the column here.
     */
    void makeSubjectChannelNullable(String[] tables) throws MigrationException {
        try {
            if ("sqlserver".equalsIgnoreCase(migrator.getDatabaseType())) {
                makeSqlServerChannelNullable();
                return;
            }
            for (String table : tables) {
                makeNullable(table, "channel_id", "CHAR(36)");
            }
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Failed to make Sentinel channel_id nullable", e);
        }
    }

    /**
     * SQL Server refuses ALTER COLUMN on a column that a constraint or index
     * uses, so each dependent object is dropped, the column relaxed, and the
     * object recreated. A failure can stop between any two of those
     * statements; the recreate steps therefore run whenever the object is
     * missing, not only right after a drop. v1 declared the trigger-state
     * unique constraint inline, so SQL Server named it itself (UQ__...); it is
     * found through the catalog and comes back named.
     */
    private void makeSqlServerChannelNullable() throws Exception {
        if (!isNullable("sentinel_trigger_state", "channel_id")) {
            for (Map.Entry<String, List<String>> constraint : sqlServerUniqueConstraints().entrySet()) {
                if (constraint.getValue().contains("channel_id")) {
                    migrator.execute("ALTER TABLE sentinel_trigger_state DROP CONSTRAINT ["
                            + constraint.getKey().replace("]", "]]") + "]");
                }
            }
            makeNullable("sentinel_trigger_state", "channel_id", "CHAR(36)");
        }
        if (!sqlServerUniqueConstraints().containsValue(List.of("monitor_id", "channel_id", "metadata_id"))) {
            migrator.execute("ALTER TABLE sentinel_trigger_state ADD CONSTRAINT uq_sentinel_trigger_state "
                    + "UNIQUE (monitor_id, channel_id, metadata_id)");
        }

        if (!isNullable("sentinel_alert_event", "channel_id")) {
            if (migrator.indexExists("sentinel_alert_event", "idx_sentinel_alert_event_monitor_channel")) {
                migrator.execute("DROP INDEX idx_sentinel_alert_event_monitor_channel ON sentinel_alert_event");
            }
            makeNullable("sentinel_alert_event", "channel_id", "CHAR(36)");
        }
        migrator.createIndexIfAbsent("sentinel_alert_event", "idx_sentinel_alert_event_monitor_channel",
                "monitor_id, channel_id");
    }

    /**
     * @return every unique constraint on {@code sentinel_trigger_state}, by
     *         name, with its key columns lower-cased in key order
     */
    private Map<String, List<String>> sqlServerUniqueConstraints() throws Exception {
        Map<String, List<String>> constraints = new HashMap<>();
        try (PreparedStatement statement = migrator.getConnection().prepareStatement(
                "SELECT kc.name, c.name FROM sys.key_constraints kc "
                        + "JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id "
                        + "AND ic.index_id = kc.unique_index_id "
                        + "JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id "
                        + "WHERE kc.parent_object_id = OBJECT_ID(N'sentinel_trigger_state') AND kc.type = 'UQ' "
                        + "ORDER BY kc.name, ic.key_ordinal");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                constraints.computeIfAbsent(rows.getString(1), (ignored) -> new ArrayList<>())
                        .add(rows.getString(2).toLowerCase(Locale.ROOT));
            }
        }
        return constraints;
    }

    /**
     * Drops a column's NOT NULL once. MySQL and SQL Server can only do that by
     * restating the column, so they need its type.
     */
    void makeNullable(String table, String column, String type) throws Exception {
        if (isNullable(table, column)) {
            return;
        }
        String change = switch (migrator.getDatabaseType().toLowerCase(Locale.ROOT)) {
            case "derby" -> "ALTER COLUMN " + column + " NULL";
            case "postgres" -> "ALTER COLUMN " + column + " DROP NOT NULL";
            case "mysql" -> "MODIFY " + column + " " + type + " NULL";
            case "sqlserver" -> "ALTER COLUMN " + column + " " + type + " NULL";
            case "oracle" -> "MODIFY (" + column + " NULL)";
            default -> throw new MigrationException("Unsupported Sentinel database " + migrator.getDatabaseType());
        };
        migrator.execute("ALTER TABLE " + table + " " + change);
    }

    /**
     * Reads a column's declared nullability, trying the identifier cases the
     * drivers fold to.
     *
     * @return {@code true} when the column accepts NULL
     * @throws MigrationException when the column is missing or the driver
     *                            cannot say
     */
    private boolean isNullable(String table, String column) throws Exception {
        for (String tableName : SentinelMigrator.namingCandidates(table)) {
            for (String columnName : SentinelMigrator.namingCandidates(column)) {
                try (ResultSet columns = migrator.getConnection().getMetaData().getColumns(
                        migrator.getConnection().getCatalog(), null, tableName, columnName)) {
                    if (columns.next()) {
                        int nullable = columns.getInt("NULLABLE");
                        if (nullable == DatabaseMetaData.columnNullable) return true;
                        if (nullable == DatabaseMetaData.columnNoNulls) return false;
                        throw new MigrationException("Unknown nullability for Sentinel column " + table + "." + column);
                    }
                }
            }
        }
        throw new MigrationException("Missing Sentinel column " + table + "." + column);
    }
}
