package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.sql.*;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DispatchBudgetStatementTest {
    @ParameterizedTest
    @ValueSource(strings = {"derby", "postgres", "mysql", "oracle", "sqlserver"})
    void budgetRangeIsBoundedByDispatchTimeOnly(String vendor) throws Exception {
        Configuration config = new Configuration();
        try (InputStream in = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            new XMLMapperBuilder(in, config, vendor, config.getSqlFragments()).parse();
        }
        String sql = config.getMappedStatement("Sentinel.listActionDispatchLogsInRange")
                .getBoundSql(Map.of("from", Timestamp.valueOf("2026-10-12 00:00:00"),
                        "to", Timestamp.valueOf("2026-10-12 01:00:00"))).getSql();
        assertTrue(sql.contains("dispatch_time >= ? AND dispatch_time <= ?"));
        assertFalse(sql.contains("opened_time"));
        assertFalse(sql.contains("status"));
    }
}
