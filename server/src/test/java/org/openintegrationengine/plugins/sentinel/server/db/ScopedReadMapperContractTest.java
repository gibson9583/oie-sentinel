/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** All five shipped mapper branches are generated, including empty authorization and combined filters. */
class ScopedReadMapperContractTest {
    @ParameterizedTest
    @ValueSource(strings={"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void monitorAndActorAuthorizationPredicatesHaveSafeNullEmptyAndNonemptyBranches(String vendor) throws Exception {
        Configuration mapping = new Configuration();
        try (InputStream in = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            new XMLMapperBuilder(in, mapping, vendor, mapping.getSqlFragments()).parse();
        }
        Map<String,Object> params = new HashMap<>();
        params.put("sortColumn", "opened_time"); params.put("sortDir", "DESC");
        params.put("limit", 25); params.put("offset", 0);
        for (String statement : List.of("listAlertEvents", "countAlertEvents")) {
            var mapped = mapping.getMappedStatement("Sentinel." + statement);
            params.put("monitorIdPredicate", null);
            assertFalse(normalized(mapped.getBoundSql(params).getSql()).contains("1 = 0"));
            params.put("monitorIdPredicate", "1 = 0");
            assertTrue(normalized(mapped.getBoundSql(params).getSql()).contains("1 = 0"));
            params.put("monitorIdPredicate", "(monitor_id IN (3,9))");
            params.put("monitorId", 3); params.put("monitorType", "QUEUE_DEPTH");
            var bound = mapped.getBoundSql(params);
            String sql = normalized(bound.getSql());
            assertFalse(sql.contains("1 = 0"));
            assertTrue(sql.replace(" ", "").contains("monitor_idIN(3,9)"), sql);
            assertTrue(sql.contains("monitor_id = ?"), sql);
            assertTrue(sql.contains("monitor_type = ?"), sql);
            assertEquals(0, bound.getParameterMappings().stream().filter(p -> p.getProperty().startsWith("__frch_mid_")).count());
        }
        var actors = mapping.getMappedStatement("Sentinel.listAcknowledgingUserIds");
        params.put("channelIdIn", null);
        assertFalse(normalized(actors.getBoundSql(params).getSql()).contains("channel_id IN"));
        params.put("channelIdIn", new ArrayList<>());
        assertTrue(normalized(actors.getBoundSql(params).getSql()).contains("1 = 0"));
        params.put("channelIdIn", new ArrayList<>(List.of("allowed")));
        String sql = normalized(actors.getBoundSql(params).getSql());
        assertTrue(sql.replace(" ", "").contains("channel_idIN(?)"), sql);
        assertTrue(sql.contains("acknowledged_by IS NOT NULL"));
    }
    private static String normalized(String sql) { return sql.replaceAll("\\s+", " ").trim(); }
}
