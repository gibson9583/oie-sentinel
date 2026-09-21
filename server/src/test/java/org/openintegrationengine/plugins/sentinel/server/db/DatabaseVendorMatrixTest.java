/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Always-on embedded Derby half of the database compatibility rig. */
class DatabaseVendorMatrixTest {

    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private final List<String> databases = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (String name : databases) {
            try {
                DriverManager.getConnection("jdbc:derby:memory:" + name + ";drop=true");
            } catch (SQLException e) {
                if (!"08006".equals(e.getSQLState()) && !"XJ004".equals(e.getSQLState())) throw e;
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> migratesFromEmptyAndEveryHistoricalVersion() {
        return IntStream.rangeClosed(0, SentinelMigrator.LATEST_VERSION)
                .mapToObj((sourceVersion) -> dynamicTest("Derby schema v" + sourceVersion + " to latest",
                        () -> {
                            try {
                                DatabaseMatrixSupport.verifyUpgradeFrom(
                                        derbyDatabase("history-v" + sourceVersion), sourceVersion);
                            } finally {
                                cleanup();
                                databases.clear();
                            }
                        }));
    }

    @Test
    void executesEveryPackagedDerbyMapperStatement() throws Exception {
        DatabaseMatrixSupport.MatrixDatabase database = derbyDatabase("mapper");
        DatabaseMatrixSupport.verifyUpgradeFrom(database, 0);
        MapperStatementMatrix.verifyAll(database);
    }

    @Test
    void survivesUninstallRestartAndReinstall() throws Exception {
        DatabaseMatrixSupport.verifyUninstallRestartReinstall(derbyDatabase("reinstall"));
    }

    @Test
    void retriesInterruptedNewMigrations() throws Exception {
        for (int target = 8; target <= 10; target++) {
            DatabaseMatrixSupport.verifyInterruptedMigration(derbyDatabase("interrupted-v" + target), target);
        }
    }

    private DatabaseMatrixSupport.MatrixDatabase derbyDatabase(String purpose) {
        String name = "sentinelMatrix" + DATABASE_SEQUENCE.incrementAndGet()
                + purpose.replaceAll("[^A-Za-z0-9]", "");
        databases.add(name);
        String url = "jdbc:derby:memory:" + name + ";create=true";
        return new DatabaseMatrixSupport.MatrixDatabase(
                "derby", () -> DriverManager.getConnection(url));
    }
}
