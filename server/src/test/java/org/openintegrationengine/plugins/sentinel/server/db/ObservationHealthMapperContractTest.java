package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ObservationHealthMapperContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void healthUsesDatabaseClockAndDoesNotFilterDeploymentInventory(String vendor) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream mapper = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            new XMLMapperBuilder(mapper, configuration, vendor, configuration.getSqlFragments()).parse();
        }
        String health = configuration.getMappedStatement("Sentinel.listHealthyObservationNodeIds")
                .getBoundSql(Map.of()).getSql();
        String clock = Map.of("derby", "CURRENT_TIMESTAMP", "mysql", "UTC_TIMESTAMP()",
                "oracle", "SYS_EXTRACT_UTC(SYSTIMESTAMP)", "postgres", "clock_timestamp() AT TIME ZONE 'UTC'",
                "sqlserver", "GETUTCDATE()").get(vendor);
        assertTrue(health.contains("sentinel-observation-%"));
        assertTrue(health.contains("expires_time >")); assertTrue(health.contains(clock));
        assertFalse(health.contains("sentinel-presence-%"));
        for (String query : new String[] {"listActiveDeployedChannelIds", "listActiveDeployedNodeIds", "listActiveConnectorNodes"}) {
            String inventory = configuration.getMappedStatement("Sentinel." + query)
                    .getBoundSql(Map.of("channelId", "test")).getSql();
            assertTrue(inventory.contains("sentinel-presence-%"));
            assertFalse(inventory.contains("sentinel-observation-%"), "telemetry expiry must not imply departure");
        }
    }
}
