/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.HashMap;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LifecycleStatementContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"derby", "postgres", "mysql", "oracle", "sqlserver"})
    void allVendorsGuardLifecycleOwnershipAndAllowClearingTriggerLink(String vendor) throws Exception {
        Configuration config = new Configuration();
        try (InputStream in = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            new XMLMapperBuilder(in, config, vendor, config.getSqlFragments()).parse();
        }
        String ack = sql(config, "acknowledgeAlertEvent");
        assertTrue(ack.endsWith("WHERE id = ? AND status = 'PROBLEM' AND acknowledged_by IS NULL"));
        assertFalse(ack.substring(0, ack.indexOf("WHERE")).contains("status ="));
        for (String statement : new String[] {"resolveAlertEvent", "resolveAlertEventManually"}) {
            assertTrue(sql(config, statement).endsWith("WHERE id = ? AND status = 'PROBLEM'"));
        }
        String manual = sql(config, "resolveAlertEventManually");
        assertTrue(manual.contains("acknowledged_time = CASE WHEN acknowledged_by IS NULL"));
        // MySQL evaluates single-table assignments left to right: write the
        // acknowledgement owner last so all CASE expressions see its old value.
        assertTrue(manual.indexOf("acknowledged_by = CASE") > manual.indexOf("acknowledged_time = CASE"));
        assertTrue(manual.indexOf("acknowledged_by = CASE") > manual.indexOf("ack_comment = CASE"));
        String reset = sql(config, "resetTriggerStateForAlert");
        assertTrue(reset.contains("open_alert_event_id = NULL"));
        assertTrue(reset.endsWith("WHERE open_alert_event_id = ?"));
        assertTrue(sql(config, "updateTriggerState").contains("open_alert_event_id = ?"));
        assertFalse(config.hasStatement("Sentinel.updateAlertEvent"));
    }

    private static String sql(Configuration config, String statement) {
        return config.getMappedStatement("Sentinel." + statement).getBoundSql(new HashMap<>())
                .getSql().replaceAll("\\s+", " ").trim();
    }
}
