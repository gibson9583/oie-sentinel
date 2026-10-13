/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.server.util.SqlConfig;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.service.ProblemService;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Real service/repository parameter construction, then the exact packaged vendor SQL. */
class ScopedReadScaleTest {
    private static final Pattern MONITOR_LIST = Pattern.compile("monitor_id\\s+IN\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "postgres", "oracle", "sqlserver"})
    void largeAuthorizationSetsKeepEveryIdWithinVendorLimits(String vendor) throws Exception {
        Map<String, Object> parameters = captureServiceParameters(3101, null);
        Set<Integer> expected = new HashSet<>(IntStream.rangeClosed(1, 3101).boxed().toList());
        for (String statement : List.of("listAlertEvents", "countAlertEvents")) {
            BoundSql sql = sql(vendor, statement, parameters);
            assertEquals(expected, literalIds(sql));
            assertTrue(sql.getParameterMappings().size() < 20, "Authorization must not consume bind parameters");
            assertTrue(sql.getSql().contains("monitor_type"));
            System.out.println("SCOPED_READ_SCALE vendor=" + vendor + " statement=" + statement
                    + " monitors=" + expected.size() + " bindCount=" + sql.getParameterMappings().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "postgres", "oracle", "sqlserver"})
    void explicitMonitorSelectionIntersectsTheAuthorizationSet(String vendor) throws Exception {
        Map<String, Object> parameters = captureServiceParameters(2100, 37);
        for (String statement : List.of("listAlertEvents", "countAlertEvents")) {
            BoundSql sql = sql(vendor, statement, parameters);
            assertEquals(Set.of(37), literalIds(sql));
            assertTrue(sql.getParameterMappings().size() < 20);
            assertTrue(sql.getSql().contains("monitor_id = ?"));
        }
    }

    @Test
    void unsafeAndNullMonitorIdsCannotReachSqlExecution() {
        List<List<?>> invalid = List.of(List.of("1) OR 1=1 --"), List.of(1L), Arrays.asList(1, null));
        try (MockedStatic<SqlConfig> engine = mockStatic(SqlConfig.class)) {
            for (List<?> ids : invalid) {
                AlertEventFilter filter = new AlertEventFilter();
                setRawIds(filter, ids);
                assertThrows(RepositoryException.class, () -> AlertEventRepository.listAlertEvents(filter));
                engine.verifyNoInteractions();
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setRawIds(AlertEventFilter filter, List<?> ids) {
        // Deliberately bypass generic type checking, as raw callers can do.
        filter.setMonitorIdIn((List) ids);
    }

    @Test
    void integerBoundsRemainNumericAndFreeTextRemainsBound() throws Exception {
        AlertEventFilter filter = new AlertEventFilter();
        filter.setMonitorIdIn(List.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE));
        filter.setQ("SYNTHETIC') OR 1=1 --");
        try (SqlFixture f = new SqlFixture()) {
            AlertEventRepository.listAlertEvents(filter);
            for (String vendor : List.of("derby", "mysql", "postgres", "oracle", "sqlserver")) {
                BoundSql sql = sql(vendor, "listAlertEvents", f.list.get());
                assertEquals(Set.of(Integer.MIN_VALUE, 0, Integer.MAX_VALUE), literalIds(sql));
                assertFalse(sql.getSql().contains("SYNTHETIC"));
                assertTrue(sql.getParameterMappings().stream().anyMatch(p -> p.getProperty().equals("q")));
            }
        }
    }

    @Test
    void listAndCountKeepTheSameAuthorizationWhenTheInputFilterChanges() {
        AlertEventFilter filter = new AlertEventFilter();
        filter.setMonitorIdIn(List.of(3, 9));
        filter.setChannelIdIn(List.of("allowed"));
        try (SqlFixture f = new SqlFixture()) {
            when(f.sessions.selectList(eq("Sentinel.listAlertEvents"), any())).thenAnswer(call -> {
                f.list.set(new HashMap<>(call.<Map<String, Object>>getArgument(1)));
                filter.getMonitorIdIn().clear();
                filter.getChannelIdIn().clear();
                filter.setMonitorIdIn(null);
                return List.of();
            });
            AlertEventRepository.listAlertEvents(filter);
            assertEquals("(monitor_id IN (3,9))", f.count.get().get("monitorIdPredicate"));
            assertEquals(List.of("allowed"), f.count.get().get("channelIdIn"));
            assertEquals(f.list.get().get("monitorIdPredicate"), f.count.get().get("monitorIdPredicate"));
        }
    }

    private Map<String, Object> captureServiceParameters(int count, Integer requestedMonitor) {
        List<Monitor> monitors = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Monitor m = new Monitor(); m.setId(i); m.setName("Test monitor " + i);
            m.setScopeType(ScopeType.CHANNEL); m.setScopeId("allowed");
            m.setMonitorType(MonitorType.QUEUE_DEPTH); monitors.add(m);
        }
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class);
                SqlFixture f = new SqlFixture()) {
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null)).thenReturn(monitors);
            ProblemService.list("PROBLEM", null, "allowed", requestedMonitor, "QUEUE_DEPTH", null,
                    null, null, null, null, null, 0, 25, Set.of("allowed"));
            assertNotNull(f.list.get());
            assertNotNull(f.count.get());
            assertEquals(f.list.get().get("monitorIdPredicate"), f.count.get().get("monitorIdPredicate"));
            return f.list.get();
        }
    }

    private Set<Integer> literalIds(BoundSql sql) {
        var lists = MONITOR_LIST.matcher(sql.getSql());
        Set<Integer> actual = new HashSet<>();
        while (lists.find()) {
            String values = lists.group(1).trim();
            if (values.startsWith("SELECT")) continue; // SQL Server's separate type predicate.
            String[] ids = values.split(",");
            assertTrue(ids.length <= 1000, "Oracle permits at most 1,000 values per IN list");
            for (String id : ids) actual.add(Integer.parseInt(id.trim()));
        }
        return actual;
    }

    private BoundSql sql(String vendor, String statement, Map<String, Object> parameters) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream stream = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            new XMLMapperBuilder(stream, configuration, vendor, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("Sentinel." + statement).getBoundSql(parameters);
    }

    static class SqlFixture implements AutoCloseable {
        final MockedStatic<SqlConfig> config = mockStatic(SqlConfig.class);
        final SqlSessionManager sessions = mock(SqlSessionManager.class);
        final AtomicReference<Map<String, Object>> list = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> count = new AtomicReference<>();
        SqlFixture() {
            SqlConfig engine = mock(SqlConfig.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
            when(sessions.selectList(eq("Sentinel.listAlertEvents"), any())).thenAnswer(call -> {
                list.set(new HashMap<>(call.<Map<String, Object>>getArgument(1))); return List.of();
            });
            when(sessions.selectOne(eq("Sentinel.countAlertEvents"), any())).thenAnswer(call -> {
                count.set(new HashMap<>(call.<Map<String, Object>>getArgument(1))); return 0;
            });
        }
        @Override public void close() { config.close(); }
    }
}
