/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.server.util.SqlConfig;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActivitySampleTimesTest {
    private static final Instant START = Instant.parse("2026-10-13T00:00:00.123Z");
    private static final Instant END = START.plusSeconds(120);

    @Test
    void convertsTimestampsAndRejectsUnboundedPageRequestsBeforeOpeningDatabase() {
        SqlConfig config = mock(SqlConfig.class);
        SqlSessionManager sessions = mock(SqlSessionManager.class);
        when(config.getSqlSessionManager()).thenReturn(sessions);
        when(sessions.selectList(eq("Sentinel.listActivitySampleTimes"), any())).thenAnswer(call -> {
            Map<String, Object> parameters = call.getArgument(1);
            assertEquals("channel", parameters.get("channelId"));
            assertEquals(Timestamp.from(START), parameters.get("from"));
            assertEquals(Timestamp.from(END), parameters.get("to"));
            assertEquals(Timestamp.from(START), parameters.get("after"));
            assertEquals(2, parameters.get("limit"));
            return List.of(Timestamp.from(START.plusSeconds(30)), Timestamp.from(END));
        });
        try (var sqlConfig = mockStatic(SqlConfig.class)) {
            sqlConfig.when(SqlConfig::getInstance).thenReturn(config);
            assertThrows(IllegalArgumentException.class,
                    () -> ActivityRepository.listActivitySampleTimes("channel", START, END, null, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> ActivityRepository.listActivitySampleTimes("channel", START, END, null,
                            ActivityRepository.SAMPLE_TIME_PAGE_SIZE + 1));
            verifyNoInteractions(config, sessions);
            assertEquals(List.of(START.plusSeconds(30), END),
                    ActivityRepository.listActivitySampleTimes("channel", START, END, START, 2));
        }
    }

    @Test
    void queryFailureRemainsAnUnmeasuredEvaluationRatherThanEmptySuccessfulCoverage() {
        SqlConfig config = mock(SqlConfig.class);
        SqlSessionManager sessions = mock(SqlSessionManager.class);
        when(config.getSqlSessionManager()).thenReturn(sessions);
        when(sessions.selectList(eq("Sentinel.listActivitySampleTimes"), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        try (var sqlConfig = mockStatic(SqlConfig.class)) {
            sqlConfig.when(SqlConfig::getInstance).thenReturn(config);
            assertThrows(RepositoryException.class,
                    () -> ActivityRepository.listActivitySampleTimes("channel", START, END, null, 2));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"derby", "mysql", "oracle", "postgres", "sqlserver"})
    void everyVendorBindsALimitAndStrictCursorForScalarTimestampPages(String vendor) throws Exception {
        Configuration config = new Configuration();
        try (InputStream stream = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, config, vendor, config.getSqlFragments()).parse();
        }
        var statement = config.getMappedStatement("Sentinel.listActivitySampleTimes");
        assertEquals(Timestamp.class, statement.getResultMaps().get(0).getType());
        Map<String, Object> params = new HashMap<>();
        params.put("channelId", "channel");
        params.put("from", Timestamp.from(START));
        params.put("to", Timestamp.from(END));
        params.put("limit", 2);
        for (boolean hasCursor : List.of(false, true)) {
            if (hasCursor) params.put("after", Timestamp.from(START));
            var bound = statement.getBoundSql(params);
            String sql = bound.getSql().replaceAll("\\s+", " ").trim();
            assertTrue(sql.startsWith("SELECT DISTINCT sample_time FROM sentinel_channel_activity_sample"), vendor);
            assertTrue(sql.contains("channel_id = ? AND sample_time >= ? AND sample_time <= ?"), vendor);
            assertEquals(hasCursor, sql.contains("AND sample_time > ?"), vendor);
            assertTrue(sql.contains("ORDER BY sample_time"), vendor);
            assertTrue(sql.endsWith(vendor.equals("mysql") || vendor.equals("postgres")
                    ? "LIMIT ?" : "OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY"), vendor);
            assertEquals(hasCursor ? List.of("channelId", "from", "to", "after", "limit")
                    : List.of("channelId", "from", "to", "limit"),
                    bound.getParameterMappings().stream().map(p -> p.getProperty()).toList(), vendor);
        }
    }
}
