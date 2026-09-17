/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.parameter.DefaultParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MonitorParameterBindingTest {

    static Stream<Arguments> monitorStatements() {
        return Stream.of("postgres", "mysql", "sqlserver", "oracle", "derby")
                .flatMap(vendor -> Stream.of("insertMonitor", "updateMonitor")
                        .map(statement -> Arguments.of(vendor, statement)));
    }

    @ParameterizedTest(name = "{0} {1}: absent, assigned, cleared, retried suppression")
    @MethodSource("monitorStatements")
    void bindsOptionalSuppressionAsInteger(String vendor, String statement) throws Exception {
        Configuration configuration = new Configuration();
        // Match the engine's SqlMapConfig.xml, not MyBatis's default OTHER.
        configuration.setJdbcTypeForNull(JdbcType.VARCHAR);
        String resource = "/mapper/" + vendor + "-sqlmap.xml";
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            new XMLMapperBuilder(stream, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        MappedStatement mapped = configuration.getMappedStatement("Sentinel." + statement);
        Map<String, Object> parameters = new HashMap<>();
        // Reuse the statement and parameter map to cover clearing a previously
        // assigned parent and retrying the same null-valued operation.
        for (Integer parent : new Integer[] { null, 42, null, null }) {
            parameters.put("suppressed_by_monitor_id", parent);
            BoundSql sql = mapped.getBoundSql(parameters);
            int index = -1;
            for (int i = 0; i < sql.getParameterMappings().size(); i++) {
                if ("suppressed_by_monitor_id".equals(sql.getParameterMappings().get(i).getProperty())) {
                    index = i + 1;
                }
            }
            assertTrue(index > 0, "Suppression parameter missing from " + statement);
            PreparedStatement jdbc = mock(PreparedStatement.class);
            new DefaultParameterHandler(mapped, parameters, sql).setParameters(jdbc);
            if (parent == null) {
                verify(jdbc).setNull(index, Types.INTEGER);
            } else {
                verify(jdbc).setInt(index, parent);
            }
            verify(jdbc, never()).setNull(index, Types.VARCHAR);
        }
    }
}
