/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SentinelMigratorTest {

    @Test
    void latestSchemaIncludesBothLifecycleOutboxes() {
        assertEquals(13, SentinelMigrator.LATEST_VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "postgres", "mysql", "sqlserver", "oracle"})
    void uninstallWithoutConnectionDropsExactlyTheTablesCreatedByPackagedMigrations(String vendor)
            throws Exception {
        // The engine constructs the migrator without a JDBC connection when
        // requesting uninstall statements, then executes the result on restart.
        SentinelMigrator migrator = new SentinelMigrator();
        migrator.setDatabaseType(vendor);
        List<String> statements = migrator.getUninstallStatements();
        Set<String> dropped = new HashSet<>();
        String suffix = "oracle".equals(vendor) ? " PURGE" : "";
        Pattern drop = Pattern.compile("DROP TABLE (sentinel_[a-z_]+)" + suffix);
        for (String statement : statements) {
            Matcher matcher = drop.matcher(statement);
            assertTrue(matcher.matches(), "Uninstall must drop only its own table: " + statement);
            assertTrue(dropped.add(matcher.group(1)), "Duplicate drop: " + statement);
        }
        assertFalse(dropped.isEmpty());
        assertEquals(packagedTables(vendor), dropped,
                "Uninstall must cover all schema generations without affecting unrelated tables");

        // Derby does not declare every FK that other dialects enforce (notably
        // trigger_state -> alert_event), so inspect each packaged dialect too.
        List<String> order = statements.stream().map((sql) -> sql.split(" ")[2]).toList();
        Pattern create = Pattern.compile("(?s)CREATE TABLE (?:IF NOT EXISTS )?(sentinel_[a-z_]+)\\s*\\((.*?)\\n\\)");
        Pattern alter = Pattern.compile("(?s)ALTER TABLE (sentinel_[a-z_]+)\\s+(.*?)(?:\\n\\n|$)");
        Pattern reference = Pattern.compile("REFERENCES (sentinel_[a-z_]+)");
        for (String sql : packagedSql(vendor)) {
            for (Pattern declaration : List.of(create, alter)) {
                Matcher declared = declaration.matcher(sql);
                while (declared.find()) {
                    String child = declared.group(1);
                    Matcher referenced = reference.matcher(declared.group(2));
                    while (referenced.find()) {
                        String parent = referenced.group(1);
                        if (child.equals(parent)) continue; // A table can drop its own self-reference.
                        assertTrue(order.indexOf(child) < order.indexOf(parent),
                                vendor + " must drop " + child + " before referenced table " + parent);
                    }
                }
            }
        }
    }

    private static Set<String> packagedTables(String vendor) throws Exception {
        Set<String> tables = new HashSet<>();
        Pattern create = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?(sentinel_[a-z_]+)");
        for (String sql : packagedSql(vendor)) {
            Matcher matcher = create.matcher(sql);
            while (matcher.find()) tables.add(matcher.group(1));
        }
        return tables;
    }

    private static List<String> packagedSql(String vendor) throws Exception {
        List<String> scripts = new ArrayList<>();
        for (int version = 1; version <= SentinelMigrator.LATEST_VERSION; version++) {
            String suffix = version == 1 ? "tables" : "v" + version;
            try (InputStream resource = SentinelMigratorTest.class.getResourceAsStream(
                    "/" + vendor + "-sentinel-" + suffix + ".sql")) {
                if (resource == null) continue; // Some migrations are implemented in Java.
                scripts.add(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return scripts;
    }
}
