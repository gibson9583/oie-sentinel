/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.evaluate.AnomalyEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.BaselineResolver;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.InactivityEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.LowVolumeEvaluator;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Real coverage SQL, anomaly decisions and incident transactions; no external delivery. */
class MissingCoverageAnomalyPersistenceTest {
    private static final String CHANNEL = "coverage-anomaly-incident";
    private static final Instant NOW = Instant.parse("2026-10-14T12:05:00Z");
    private static final Instant HOUR = Instant.parse("2026-10-14T11:00:00Z");
    private String url;
    private SqlSessionManager sessions;
    private Monitor monitor;
    private AlertEvent incident;
    private MockedStatic<SqlConfig> config;
    private MockedStatic<SettingsService> settings;
    private MockedStatic<BaselineResolver> baseline;
    private MockedStatic<ActionDispatcher> dispatch;
    private MockedStatic<AlertPayload> payload;
    private Thread concurrentWriter;

    @BeforeEach
    void setup() throws Exception {
        url = "jdbc:derby:memory:coverageAnomaly" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url + ";create=true");
                var controllers = mockStatic(ConfigurationController.class)) {
            ConfigurationController controller = mock(ConfigurationController.class);
            controllers.when(ConfigurationController::getInstance).thenReturn(controller);
            SentinelMigrator migrator = new SentinelMigrator();
            migrator.setConnection(connection);
            migrator.setDatabaseType("derby");
            migrator.migrate();
        }
        Configuration mapping = new Configuration(new Environment("coverage-anomaly", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver", url, null, null)));
        mapping.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream resource = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            assertNotNull(resource);
            new XMLMapperBuilder(resource, mapping, "derby", mapping.getSqlFragments()).parse();
        }
        sessions = spy(SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(mapping)));
        SqlConfig engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(sessions);
        config = mockStatic(SqlConfig.class);
        config.when(SqlConfig::getInstance).thenReturn(engine);
        settings = mockStatic(SettingsService.class);
        settings.when(SettingsService::get).thenReturn(new SentinelSettings());
        baseline = mockStatic(BaselineResolver.class);
        baseline.when(() -> BaselineResolver.resolve(eq(CHANNEL), any(), anyString(), anyInt(), anyBoolean()))
                .thenReturn(new BaselineResolver.Baseline(100, 10, 30, 1));
        dispatch = mockStatic(ActionDispatcher.class);
        payload = mockStatic(AlertPayload.class);
        CollectorState.getInstance().forgetChannel(CHANNEL);
        CollectorState.getInstance().markChannelStarted(CHANNEL, HOUR.minusSeconds(3600));

        monitor = new Monitor();
        monitor.setName("High-volume anomaly");
        monitor.setMonitorType(MonitorType.ANOMALY);
        monitor.setScopeType(ScopeType.ALL);
        monitor.setSeverity(Severity.HIGH);
        monitor.setEnabled(true);
        monitor.setMinConsecutiveBreaches(1);
        monitor.setConfigJson("{\"direction\":\"HIGH_ONLY\"}");
        monitor.setCreatedTime(HOUR.minusSeconds(7200));
        monitor.setUpdatedTime(monitor.getCreatedTime());
        MonitorRepository.insertMonitor(monitor);
        incident = new AlertEvent();
        incident.setMonitorId(monitor.getId());
        incident.setChannelId(CHANNEL);
        incident.setStatus(AlertStatus.PROBLEM);
        incident.setSeverity(Severity.HIGH);
        incident.setMessage("Measured high volume");
        incident.setOpenedTime(HOUR.minusSeconds(3600));
        AlertEventRepository.insertAlertEvent(incident);
        TriggerState state = new TriggerState();
        state.setMonitorId(monitor.getId());
        state.setChannelId(CHANNEL);
        state.setState(TriggerStatus.PROBLEM);
        state.setOpenAlertEventId(incident.getId());
        state.setConsecutiveBreachCount(1);
        state.setLastChangeTime(incident.getOpenedTime());
        TriggerStateRepository.insertTriggerState(state);
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (config != null && incident != null && incident.getId() != null) {
                System.out.println("Final isolated coverage-anomaly rows " + url + ": " + snapshot());
            }
        } finally {
            try {
                CollectorState.getInstance().forgetChannel(CHANNEL);
                if (payload != null) payload.close();
                if (dispatch != null) dispatch.close();
                if (baseline != null) baseline.close();
                if (settings != null) settings.close();
                if (config != null) config.close();
                if (sessions != null && sessions.isManagedSessionStarted()) sessions.close();
            } finally {
                if (concurrentWriter != null) {
                    concurrentWriter.join(5000);
                    assertFalse(concurrentWriter.isAlive(), "Refusing to drop owned database while writer remains: " + url);
                }
                if (url != null) {
                    SQLException dropped = assertThrows(SQLException.class,
                            () -> DriverManager.getConnection(url + ";drop=true"));
                    assertEquals("08006", dropped.getSQLState());
                    System.out.println("Dropped isolated coverage-anomaly database " + url
                            + " SQLState=" + dropped.getSQLState());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "stale-tail", "internal-gap", "missing-anchor", "collector-restart"})
    void missingMeasurementsPreserveOpenIncidentWithoutRecovery(String scenario) {
        writeSamples(scenario, 0);
        if (scenario.equals("collector-restart")) {
            CollectorState.getInstance().forgetChannel(CHANNEL);
            CollectorState.getInstance().markChannelStarted(CHANNEL, HOUR.plusSeconds(60));
        }
        for (int retry = 0; retry < 2; retry++) {
            EvaluationOutcome outcome = AnomalyEvaluator.evaluate(monitor, CHANNEL, NOW);
            assertFalse(sessions.isManagedSessionStarted(), "Read transaction must close before incident mutation");
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult());
            TriggerEvaluatorJob.applyOutcome(monitor, CHANNEL, null, outcome, NOW, LeaseFence.unmanaged());
            AlertEvent stored = AlertEventRepository.getAlertEvent(incident.getId());
            assertEquals(AlertStatus.PROBLEM, stored.getStatus());
            assertNull(stored.getResolvedTime());
            assertFalse(stored.isResolutionPending());
            assertEquals(incident.getId(), state().getOpenAlertEventId());
            assertEquals(TriggerStatus.INSUFFICIENT_DATA, state().getState());
        }
        baseline.verifyNoInteractions();
        dispatch.verify(() -> ActionDispatcher.onAlertResolved(any(), any()), never());
        dispatch.verify(() -> ActionDispatcher.onAlertOpened(any(), any()), never());
        dispatch.verify(() -> ActionDispatcher.onRepeatCheck(any(), any()), times(2));
        assertEquals(1, incidentCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void measuredRecoveryResolvesOnceAndQueuesTheExistingIncident(int receivedPerSample) {
        writeSamples("complete", receivedPerSample);
        for (int retry = 0; retry < 2; retry++) {
            EvaluationOutcome outcome = AnomalyEvaluator.evaluate(monitor, CHANNEL, NOW);
            assertFalse(sessions.isManagedSessionStarted(), "Read transaction must close before incident mutation");
            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
            TriggerEvaluatorJob.applyOutcome(monitor, CHANNEL, null, outcome, NOW, LeaseFence.unmanaged());
            AlertEvent stored = AlertEventRepository.getAlertEvent(incident.getId());
            assertEquals(AlertStatus.RESOLVED, stored.getStatus());
            assertEquals(NOW, stored.getResolvedTime());
            assertTrue(stored.isResolutionPending());
            assertNull(state().getOpenAlertEventId());
            assertEquals(TriggerStatus.OK, state().getState());
        }
        dispatch.verify(() -> ActionDispatcher.onAlertResolved(
                argThat(event -> incident.getId().equals(event.getId())), any()), times(1));
        dispatch.verify(() -> ActionDispatcher.onAlertOpened(any(), any()), never());
        assertEquals(1, incidentCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sumActivitySamplesForRange", "listActivitySampleTimes"})
    void failedMeasurementReadCannotReachIncidentResolution(String statement) {
        writeSamples("complete", 1);
        String before = snapshot();
        if (statement.equals("listActivitySampleTimes")) {
            doThrow(new IllegalStateException("injected coverage read failure"))
                    .when(sessions).selectList(eq("Sentinel." + statement), any());
        } else {
            doThrow(new IllegalStateException("injected aggregate read failure"))
                    .when(sessions).selectOne(eq("Sentinel." + statement), any());
        }
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            EvaluationOutcome outcome = AnomalyEvaluator.evaluate(monitor, CHANNEL, NOW);
            TriggerEvaluatorJob.applyOutcome(monitor, CHANNEL, null, outcome, NOW, LeaseFence.unmanaged());
        });
        assertTrue(failure.getCause().getMessage().startsWith("injected"));
        assertFalse(sessions.isManagedSessionStarted());
        assertEquals(before, snapshot());
        dispatch.verifyNoInteractions();
    }

    @ParameterizedTest
    @CsvSource({"ANOMALY,insert", "INACTIVITY,insert", "LOW_VOLUME,insert",
            "ANOMALY,prune", "INACTIVITY,prune", "LOW_VOLUME,prune"})
    void aggregateAndCoverageShareOneSnapshotAcrossCollectorAndRetentionWrites(String type, String operation)
            throws Exception {
        monitor.setMonitorType(MonitorType.valueOf(type));
        monitor.setConfigJson("{\"direction\":\"HIGH_ONLY\",\"minCount\":10}");
        boolean insert = operation.equals("insert");
        writeSamples(insert ? "internal-gap" : "complete", insert ? 0 : 1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        CountDownLatch attemptingWrite = new CountDownLatch(1);
        CountDownLatch writeFinished = new CountDownLatch(1);
        doAnswer(call -> {
            Object aggregate = call.callRealMethod();
            if (firstRead.getAndSet(false)) {
                concurrentWriter = new Thread(() -> {
                    try (Connection connection = DriverManager.getConnection(url);
                            var statement = connection.prepareStatement(insert
                                    ? "INSERT INTO sentinel_channel_activity_sample (channel_id, sample_time, "
                                            + "received_delta, sent_delta, error_delta, filtered_delta, queued_snapshot) "
                                            + "VALUES (?, ?, 1000, 0, 0, 0, 0)"
                                    : "DELETE FROM sentinel_channel_activity_sample WHERE channel_id = ? AND sample_time < ?")) {
                        statement.setString(1, CHANNEL);
                        statement.setTimestamp(2, Timestamp.from(insert ? HOUR.plusSeconds(1800) : NOW));
                        attemptingWrite.countDown();
                        statement.executeUpdate();
                    } catch (Throwable failure) {
                        writerFailure.set(failure);
                    } finally {
                        writeFinished.countDown();
                    }
                }, "coverage-snapshot-writer");
                concurrentWriter.start();
                assertTrue(attemptingWrite.await(5, TimeUnit.SECONDS));
                assertFalse(writeFinished.await(200, TimeUnit.MILLISECONDS),
                        "Concurrent " + operation + " must not change the certified rows between aggregate and coverage");
            }
            return aggregate;
        }).when(sessions).selectOne(eq("Sentinel.sumActivitySamplesForRange"), any());

        Instant evaluationTime = type.equals("ANOMALY") ? NOW : HOUR.plusSeconds(3600);
        EvaluationOutcome first = evaluate(evaluationTime);
        assertEquals(insert ? EvaluationOutcome.Result.INSUFFICIENT_DATA : EvaluationOutcome.Result.OK, first.getResult());
        assertFalse(sessions.isManagedSessionStarted());
        assertTrue(writeFinished.await(5, TimeUnit.SECONDS), "Writer must finish once the read transaction closes");
        assertNull(writerFailure.get());
        EvaluationOutcome retry = evaluate(evaluationTime);
        EvaluationOutcome.Result expected = !insert ? EvaluationOutcome.Result.INSUFFICIENT_DATA
                : type.equals("ANOMALY") ? EvaluationOutcome.Result.BREACH : EvaluationOutcome.Result.OK;
        assertEquals(expected, retry.getResult(), "A retry must observe the newly committed sample set");
        assertFalse(sessions.isManagedSessionStarted());
    }

    private EvaluationOutcome evaluate(Instant now) {
        return switch (monitor.getMonitorType()) {
            case ANOMALY -> AnomalyEvaluator.evaluate(monitor, CHANNEL, now);
            case INACTIVITY -> InactivityEvaluator.evaluate(monitor, CHANNEL, now);
            default -> LowVolumeEvaluator.evaluate(monitor, CHANNEL, now);
        };
    }

    private void writeSamples(String scenario, int receivedPerSample) {
        for (int second = 0; second < 3600; second += 30) {
            if (scenario.equals("empty")
                    || (scenario.equals("stale-tail") && second >= 1800)
                    || (scenario.equals("internal-gap") && second == 1800)
                    || (scenario.equals("missing-anchor") && second == 0)) continue;
            ActivitySample sample = new ActivitySample();
            sample.setChannelId(CHANNEL);
            sample.setSampleTime(HOUR.plusSeconds(second));
            sample.setReceivedDelta(receivedPerSample);
            ActivityRepository.insertActivitySample(sample);
        }
    }

    private TriggerState state() {
        return TriggerStateRepository.getTriggerState(monitor.getId(), CHANNEL, null);
    }

    private long incidentCount() {
        AlertEventFilter filter = new AlertEventFilter();
        filter.setPageSize(10);
        return AlertEventRepository.listAlertEvents(filter).total();
    }

    private String snapshot() {
        return Json.write(Map.of("incident", AlertEventRepository.getAlertEvent(incident.getId()), "trigger", state()));
    }
}
