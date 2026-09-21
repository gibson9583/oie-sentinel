/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Docker-backed PostgreSQL, MySQL, SQL Server, and Oracle Free compatibility rig. */
class DatabaseVendorMatrixIT {

    private static final List<String> ALL_VENDORS =
            List.of("postgres", "mysql", "sqlserver", "oracle");

    @TestFactory
    Stream<DynamicTest> realDatabaseMatrix() {
        return requestedVendors().stream()
                .map((vendor) -> dynamicTest(vendor + " migration and mapper matrix",
                        () -> verifyVendor(vendor)));
    }

    private static void verifyVendor(String vendor) throws Exception {
        JdbcDatabaseContainer<?> container = container(vendor);
        try {
            container.start();
            DatabaseMatrixSupport.MatrixDatabase database = new DatabaseMatrixSupport.MatrixDatabase(
                    vendor, () -> DriverManager.getConnection(
                            container.getJdbcUrl(), container.getUsername(), container.getPassword()));

            List<Executable> scenarios = new java.util.ArrayList<>();
            IntStream.rangeClosed(0, SentinelMigrator.LATEST_VERSION)
                    .forEach((sourceVersion) -> scenarios.add(() ->
                            DatabaseMatrixSupport.verifyUpgradeFrom(database, sourceVersion)));
            IntStream.rangeClosed(8, 10).forEach((target) -> scenarios.add(() ->
                    DatabaseMatrixSupport.verifyInterruptedMigration(database, target)));
            scenarios.add(() -> MapperStatementMatrix.verifyAll(database));
            scenarios.add(() -> DatabaseMatrixSupport.verifyUninstallRestartReinstall(database));
            assertAll(vendor + " database compatibility scenarios", scenarios);
        } finally {
            java.nio.file.Path evidence = MatrixEvidenceSupport.directory(vendor);
            String containerId = container.getContainerId();
            String imageId = containerId == null ? null : container.getContainerInfo().getImageId();
            try {
                if (containerId != null) {
                    java.nio.file.Files.writeString(evidence.resolve("container.log"), container.getLogs());
                    java.nio.file.Files.writeString(evidence.resolve("provenance.txt"),
                            "id=" + containerId + "\nimage=" + container.getDockerImageName()
                                    + "\nimageId=" + imageId + "\n");
                }
            } finally {
                container.stop();
                boolean removed = containerId == null;
                if (containerId != null) {
                    try {
                        org.testcontainers.DockerClientFactory.instance().client().inspectContainerCmd(containerId).exec();
                    } catch (com.github.dockerjava.api.exception.NotFoundException absent) {
                        removed = true;
                    }
                }
                boolean imageRetained = imageId != null && org.testcontainers.DockerClientFactory.instance().client()
                        .inspectImageCmd(imageId).exec() != null;
                java.nio.file.Files.writeString(evidence.resolve("cleanup-manifest.json"),
                        "{\n  \"containerId\": \"" + containerId + "\",\n  \"containerAbsentVerified\": " + removed
                                + ",\n  \"imageId\": \"" + imageId + "\",\n  \"sharedImageRetainedVerified\": " + imageRetained + "\n}\n");
                org.junit.jupiter.api.Assertions.assertTrue(removed, "Owned matrix container remains: " + containerId);
            }
        }
    }

    private static List<String> requestedVendors() {
        String requested = System.getProperty("sentinel.db.vendor", "all")
                .trim().toLowerCase(Locale.ROOT);
        if (requested.equals("all")) {
            return ALL_VENDORS;
        }
        List<String> vendors = Arrays.stream(requested.split(","))
                .map(String::trim)
                .filter((vendor) -> !vendor.isEmpty())
                .toList();
        if (vendors.isEmpty() || !ALL_VENDORS.containsAll(vendors)) {
            throw new IllegalArgumentException("sentinel.db.vendor must be all or a comma-separated "
                    + "subset of " + ALL_VENDORS + "; got " + requested);
        }
        return vendors;
    }

    private static JdbcDatabaseContainer<?> container(String vendor) {
        return switch (vendor) {
            case "postgres" -> new PostgreSQLContainer("postgres:17.10-alpine")
                    .withDatabaseName("sentinel")
                    .withUsername("sentinel")
                    .withPassword("sentinel");
            case "mysql" -> new MySQLContainer("mysql:8.4.11")
                    .withDatabaseName("sentinel")
                    .withUsername("sentinel")
                    .withPassword("sentinel");
            case "sqlserver" -> new MSSQLServerContainer(
                    "mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
                    .acceptLicense();
            case "oracle" -> new OracleContainer("gvenzl/oracle-free:23.26.1-faststart");
            default -> throw new IllegalArgumentException("Unsupported matrix vendor " + vendor);
        };
    }
}
