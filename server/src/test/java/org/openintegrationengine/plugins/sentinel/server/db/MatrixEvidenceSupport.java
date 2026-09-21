/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Preserves final observations before isolated validation databases are removed. */
final class MatrixEvidenceSupport {
    private MatrixEvidenceSupport() { }

    static Path directory(String vendor) throws Exception {
        Path path = Path.of("target", "db-matrix-evidence", vendor);
        Files.createDirectories(path);
        return path;
    }

    static void observation(String vendor, String label, Object value) throws Exception {
        Files.writeString(directory(vendor).resolve(label + ".txt"), String.valueOf(value) + "\n");
    }

    static void database(Connection connection, String vendor, String label) throws Exception {
        List<String> tables = new ArrayList<>();
        try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, "%",
                new String[]{"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith("sentinel_")) tables.add(name);
            }
        }
        tables.sort(String::compareTo);
        StringBuilder evidence = new StringBuilder("Database: " + connection.getMetaData().getDatabaseProductName()
                + " " + connection.getMetaData().getDatabaseProductVersion() + "\n");
        for (String table : tables) {
            // All names came from this isolated database's metadata and use Sentinel's
            // strict generated identifier alphabet; never interpolate external input.
            if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Unexpected fixture table: " + table);
            try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next();
                evidence.append(table).append(" rows=").append(rows.getLong(1)).append('\n');
            }
            try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, "%")) {
                while (columns.next()) {
                    evidence.append("  ").append(columns.getString("COLUMN_NAME")).append(' ')
                            .append(columns.getString("TYPE_NAME")).append(" nullable=")
                            .append(columns.getInt("NULLABLE")).append('\n');
                }
            }
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, true)) {
                while (indexes.next()) {
                    String index = indexes.getString("INDEX_NAME");
                    if (index != null) evidence.append("  index=").append(index).append(" column=")
                            .append(indexes.getString("COLUMN_NAME")).append(" position=")
                            .append(indexes.getInt("ORDINAL_POSITION")).append('\n');
                }
            }
            if (table.toLowerCase(Locale.ROOT).startsWith("sentinel_monitor")) {
                try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                        "SELECT id, name FROM " + table + " ORDER BY id")) {
                    while (rows.next()) evidence.append("  monitor id=").append(rows.getLong(1))
                            .append(" name=").append(rows.getString(2)).append('\n');
                }
            }
            if (table.equalsIgnoreCase("sentinel_alert_event")) {
                try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(
                        "SELECT id, status, problem_pending, resolution_pending FROM " + table + " ORDER BY id")) {
                    while (rows.next()) evidence.append("  alert id=").append(rows.getLong(1))
                            .append(" status=").append(rows.getString(2)).append(" problem_pending=")
                            .append(rows.getObject(3)).append(" resolution_pending=").append(rows.getObject(4)).append('\n');
                }
            }
        }
        observation(vendor, label, evidence);
    }
}
