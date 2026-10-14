/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.parameter.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The real Derby concurrency tests complement binding/lock contracts for every packaged dialect. */
class ImportReferenceLockMapperTest {
    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "postgres", "oracle", "sqlserver"})
    void bindsScalarIdentityAndBypassesCachesForTransactionHeldLocks(String vendor) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, vendor, configuration.getSqlFragments()).parse();
        }
        for (String kind : List.of("Monitor", "Action")) {
            var statement = configuration.getMappedStatement("Sentinel.lock" + kind);
            var bound = statement.getBoundSql(37);
            String sql = bound.getSql().replaceAll("\\s+", " ").trim();
            assertTrue(sql.startsWith("SELECT id FROM sentinel_" + kind.toLowerCase(java.util.Locale.ROOT)));
            assertTrue(sql.contains("WHERE id = ?"));
            assertFalse(statement.isUseCache());
            assertTrue(statement.isFlushCacheRequired(), "Do not reuse any earlier session snapshot after locking");
            assertEquals(Integer.class, statement.getResultMaps().get(0).getType());
            assertEquals(1, bound.getParameterMappings().size());
            PreparedStatement jdbc = mock(PreparedStatement.class);
            new DefaultParameterHandler(statement, 37, bound).setParameters(jdbc);
            verify(jdbc).setInt(1, 37);
            if (vendor.equals("sqlserver")) assertTrue(sql.contains("WITH (UPDLOCK, HOLDLOCK)"));
            else if (vendor.equals("derby")) assertTrue(sql.endsWith("FOR UPDATE WITH RS"));
            else assertTrue(sql.endsWith("FOR UPDATE"));
            System.out.println("IMPORT_LOCK_BINDING " + vendor + " " + kind + ": " + sql + " bound id=37");
        }
    }
}
