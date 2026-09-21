/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LeaseMapperContractTest {

    @Test
    void clusteredVendorsUseOneTimezoneLessUtcClockAndNeverBindJvmTimestamps() throws Exception {
        Map<String, String> clocks = Map.of(
                "mysql", "UTC_TIMESTAMP()",
                "postgres", "clock_timestamp() AT TIME ZONE 'UTC'",
                "oracle", "SYS_EXTRACT_UTC(SYSTIMESTAMP)",
                "sqlserver", "GETUTCDATE()");

        for (Map.Entry<String, String> vendor : clocks.entrySet()) {
            String leaseSql = leaseSection(vendor.getKey());
            assertTrue(leaseSql.contains(vendor.getValue()), vendor.getKey() + " must use its UTC clock");
            assertTrue(leaseSql.contains("#{leaseSeconds}"), vendor.getKey() + " must derive expiry in SQL");
            assertFalse(leaseSql.contains("#{acquiredTime}"), vendor.getKey() + " must not bind JVM time");
            assertFalse(leaseSql.contains("#{expiresTime}"), vendor.getKey() + " must not bind JVM time");
        }
    }

    @Test
    void derbyAlsoDerivesExpiryInsideTheDatabaseStatement() throws Exception {
        String leaseSql = leaseSection("derby");
        assertTrue(leaseSql.contains("TIMESTAMPADD(SQL_TSI_SECOND, #{leaseSeconds}, CURRENT_TIMESTAMP)"));
        assertFalse(leaseSql.contains("#{acquiredTime}"));
        assertFalse(leaseSql.contains("#{expiresTime}"));
    }

    private static String leaseSection(String vendor) throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Path root = Files.isDirectory(cwd.resolve("package/resources/mapper")) ? cwd : cwd.getParent();
        String mapper = Files.readString(root.resolve("package/resources/mapper/" + vendor + "-sqlmap.xml"));
        return mapper.substring(mapper.indexOf("<select id=\"getNodeLease\""));
    }
}
