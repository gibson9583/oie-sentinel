package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.mirth.connect.model.Channel;
import com.mirth.connect.server.controllers.ChannelController;
import org.openintegrationengine.plugins.sentinel.server.engine.ActivityRollupJob;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import java.util.*;
import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityTrend;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;

class ActivityRollupTransactionTest {
    private String url;
    private MockedStatic<SqlConfig> config;
    private final Instant hour = Instant.parse("2026-10-12T00:00:00Z");

    @BeforeEach void setup() throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        url = "jdbc:derby:memory:rollup" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url + ";create=true");
             Statement sql = connection.createStatement();
             InputStream in = getClass().getResourceAsStream("/derby-sentinel-tables.sql")) {
            for (String statement : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n\\s*\\r?\\n")) {
                if (!statement.isBlank()) sql.execute(statement.trim());
            }
        }
        Configuration cfg = new Configuration(new Environment("rollup", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver", url, null, null)));
        cfg.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream in = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            new XMLMapperBuilder(in, cfg, "derby", cfg.getSqlFragments()).parse();
        }
        SqlConfig engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(cfg)));
        config = mockStatic(SqlConfig.class);
        config.when(SqlConfig::getInstance).thenReturn(engine);
    }

    @AfterEach void cleanup() {
        if (config != null) config.close();
        if (url != null) {
            SQLException dropped = assertThrows(SQLException.class, () -> DriverManager.getConnection(url + ";drop=true"));
            assertEquals("08006", dropped.getSQLState());
        }
    }

    private ActivityTrend trend(String channel, long count) {
        ActivityTrend trend = new ActivityTrend(); trend.setChannelId(channel); trend.setHourBucket(hour);
        trend.setReceivedSum(count); return trend;
    }

    @Test void failedSecondInsertRollsBackFirstReplacementAndAllowsRetry() {
        ActivityRepository.replaceActivityTrendForHour(trend("a", 1));
        ActivityTrend invalid = trend(null, 2); // real database NOT NULL rejection after first replacement
        assertThrows(RepositoryException.class, () -> ActivityRepository.replaceActivityTrendsForHour(
                List.of(trend("a", 9), invalid), LeaseFence.unmanaged()));
        assertEquals(1, ActivityRepository.listActivityTrendSince("a", hour).get(0).getReceivedSum());
        for (int attempt = 0; attempt < 2; attempt++) {
            ActivityRepository.replaceActivityTrendsForHour(List.of(trend("a", 9), trend("b", 2)), LeaseFence.unmanaged());
        }
        assertEquals(1, ActivityRepository.listActivityTrendSince("a", hour).size());
        assertEquals(9, ActivityRepository.listActivityTrendSince("a", hour).get(0).getReceivedSum());
        assertEquals(1, ActivityRepository.listActivityTrendSince("b", hour).size());
        assertEquals(hour, ActivityRepository.getLatestTrendHourBucket());
    }

    @Test void emptyReplayPreservesPreviouslyRolledSamplesAfterRetention() {
        ActivityRepository.replaceActivityTrendForHour(trend("a", 3));
        ActivityRepository.replaceActivityTrendsForHour(List.of(), LeaseFence.unmanaged());
        assertEquals(3, ActivityRepository.listActivityTrendSince("a", hour).get(0).getReceivedSum());
    }

    @Test void earliestSampleSurvivesAbsenceOfFirstTrend() {
        assertNull(ActivityRepository.getEarliestActivitySampleTime());
        for (int offset : List.of(50, 10, 90)) {
            ActivitySample sample = new ActivitySample(); sample.setChannelId("a"); sample.setSampleTime(hour.plusSeconds(offset));
            ActivityRepository.insertActivitySample(sample);
        }
        assertEquals(hour.plusSeconds(10), ActivityRepository.getEarliestActivitySampleTime());
        assertNull(ActivityRepository.getLatestTrendHourBucket());
    }
    @Test void replayPreservesCompleteBucketsWhenOnlyPartOfTheirRawHourRemains() {
        Instant previousHour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        ActivityTrend complete = trend("a", 100);
        complete.setHourBucket(previousHour);
        ActivityRepository.replaceActivityTrendForHour(complete);
        // Model partial retention: A's durable100 total outlives all but one
        // raw sample; B has no trend because the old per-channel run failed.
        for (String id : List.of("a", "b")) {
            ActivitySample retained = new ActivitySample(); retained.setChannelId(id);
            retained.setSampleTime(previousHour.plusSeconds(1800)); retained.setReceivedDelta(2);
            ActivityRepository.insertActivitySample(retained);
        }
        try (var leadership = mockStatic(SentinelLeadership.class);
             var controller = mockStatic(ChannelController.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(LeaseFence.unmanaged());
            leadership.when(() -> SentinelLeadership.recheckFence(LeaseFence.unmanaged())).thenReturn(true);
            ChannelController channels = mock(ChannelController.class);
            controller.when(ChannelController::getInstance).thenReturn(channels);
            Channel a = new Channel(); a.setId("a"); Channel b = new Channel(); b.setId("b");
            when(channels.getChannels(null)).thenReturn(List.of(a, b));
            new ActivityRollupJob().execute(null);
            new ActivityRollupJob().execute(null);
        }
        assertEquals(100, ActivityRepository.listActivityTrendSince("a", previousHour).get(0).getReceivedSum());
        assertEquals(2, ActivityRepository.listActivityTrendSince("b", previousHour).get(0).getReceivedSum());
        assertEquals(1, ActivityRepository.listActivityTrendSince("b", previousHour).size());
    }

}
