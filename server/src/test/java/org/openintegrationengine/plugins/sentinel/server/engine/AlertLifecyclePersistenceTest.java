/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.alert.*;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.service.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Executes the shipped Derby mappings with the engine's MyBatis/null configuration. */
class AlertLifecyclePersistenceTest {
    private String url;
    private SqlSessionManager sessions;
    private MockedStatic<SqlConfig> config;
    private MockedStatic<ActionDispatcher> dispatch;
    private MockedStatic<AlertPayload> payload;
    private MockedStatic<SentinelAuditLog> audit;
    private Monitor monitor;
    private final String channel = "00000000-0000-0000-0000-000000000001";
    private final Instant now = Instant.parse("2026-09-17T12:00:00Z");

    @BeforeEach
    void setup() throws Exception {
        url = "jdbc:derby:memory:lifecycle" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url + ";create=true"); Statement sql = c.createStatement()) {
            for (String resource : List.of("derby-sentinel-tables.sql", "derby-sentinel-v2.sql",
                    "derby-sentinel-v3.sql", "derby-sentinel-v4.sql")) {
                try (InputStream in = getClass().getResourceAsStream("/" + resource)) {
                    assertNotNull(in);
                    for (String statement : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n\\s*\\r?\\n")) {
                        if (!statement.isBlank()) sql.execute(statement.trim());
                    }
                }
            }
        }
        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver", url, null, null)));
        cfg.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream in = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            new XMLMapperBuilder(in, cfg, "derby", cfg.getSqlFragments()).parse();
        }
        sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(cfg));
        SqlConfig engineConfig = mock(SqlConfig.class);
        when(engineConfig.getSqlSessionManager()).thenReturn(sessions);
        config = mockStatic(SqlConfig.class);
        config.when(SqlConfig::getInstance).thenReturn(engineConfig);
        dispatch = mockStatic(ActionDispatcher.class);
        payload = mockStatic(AlertPayload.class);
        audit = mockStatic(SentinelAuditLog.class);
        monitor = new Monitor();
        monitor.setName("test"); monitor.setMonitorType(MonitorType.INACTIVITY);
        monitor.setScopeType(ScopeType.ALL); monitor.setSeverity(Severity.WARNING);
        monitor.setConfigJson("{}"); monitor.setEnabled(true); monitor.setMinConsecutiveBreaches(1);
        monitor.setCreatedTime(now); monitor.setUpdatedTime(now);
        MonitorRepository.insertMonitor(monitor);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (audit != null) audit.close();
        if (payload != null) payload.close();
        if (dispatch != null) dispatch.close();
        if (config != null) config.close();
        if (sessions != null && sessions.isManagedSessionStarted()) sessions.close();
        if (url != null) {
            SQLException dropped = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection(url + ";drop=true"));
            assertEquals("08006", dropped.getSQLState());
        }
    }

    private void sql(String statement) throws Exception {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) { s.execute(statement); }
    }
    private int count(String table) throws Exception {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); return r.getInt(1); }
    }
    private TriggerState state() { return TriggerStateRepository.getTriggerState(monitor.getId(), channel, null); }
    private void breach() { TriggerEvaluatorJob.applyOutcome(monitor, channel, null, EvaluationOutcome.breach("{}", "breach"), now); }
    private AlertEvent open() { breach(); return AlertEventRepository.getAlertEvent(state().getOpenAlertEventId()); }

    @Test
    void failedTriggerInsertRollsBackAlertAndRetryDeliversOnlyCommittedIncident() throws Exception {
        sql("ALTER TABLE sentinel_trigger_state ADD CONSTRAINT reject_problem CHECK (state <> 'PROBLEM')");
        assertThrows(RuntimeException.class, this::breach);
        assertEquals(0, count("sentinel_alert_event"));
        assertEquals(0, count("sentinel_trigger_state"));
        dispatch.verifyNoInteractions();
        assertFalse(sessions.isManagedSessionStarted());
        sql("ALTER TABLE sentinel_trigger_state DROP CONSTRAINT reject_problem");
        AtomicInteger delivered = new AtomicInteger();
        dispatch.when(() -> ActionDispatcher.onAlertOpened(any(), any())).thenAnswer(call -> {
            assertFalse(sessions.isManagedSessionStarted());
            assertEquals(1, count("sentinel_alert_event"));
            assertEquals(1, count("sentinel_trigger_state"));
            delivered.incrementAndGet(); return null;
        });
        breach();
        assertEquals(1, delivered.get());
        assertEquals(1, count("sentinel_alert_event"));
    }

    @Test
    void failedTriggerUpdateRollsBackNewAlert() throws Exception {
        TriggerEvaluatorJob.applyOutcome(monitor, channel, null, EvaluationOutcome.ok("{}"), now);
        sql("ALTER TABLE sentinel_trigger_state ADD CONSTRAINT reject_problem CHECK (state <> 'PROBLEM')");
        assertThrows(RuntimeException.class, this::breach);
        assertEquals(0, count("sentinel_alert_event"));
        assertEquals(TriggerStatus.OK, state().getState());
        dispatch.verifyNoInteractions();
    }

    @Test
    void staleResolvedProblemRestartsFullHysteresis() throws Exception {
        AlertEvent event = open();
        event.setResolvedTime(now);
        assertTrue(AlertEventRepository.resolveAlertEvent(event)); // Simulates failed manual trigger reset.
        monitor.setMinConsecutiveBreaches(2);
        breach();
        assertEquals(TriggerStatus.OK, state().getState());
        assertEquals(1, state().getConsecutiveBreachCount());
        assertNull(state().getOpenAlertEventId());
        breach();
        assertEquals(2, count("sentinel_alert_event"));
        assertNotEquals(event.getId(), state().getOpenAlertEventId());
        assertEquals(TriggerStatus.PROBLEM, state().getState());
    }

    @Test
    void missingAlertAlsoRecoversWithoutDuplicatingLiveAlert() throws Exception {
        AlertEvent event = open();
        breach();
        assertEquals(1, count("sentinel_alert_event"));
        sql("DELETE FROM sentinel_alert_event WHERE id = " + event.getId());
        breach();
        assertNotEquals(event.getId(), state().getOpenAlertEventId());
        assertEquals(1, count("sentinel_alert_event"));
    }

    @Test
    void staleAcknowledgementCannotReopenResolvedAlertOrOverwriteFirstAck() {
        AlertEvent event = open();
        AlertEvent first = AlertEventRepository.getAlertEvent(event.getId());
        first.setAcknowledgedBy(1); first.setAcknowledgedTime(now); first.setAckComment("first");
        assertTrue(AlertEventRepository.acknowledgeAlertEvent(first));
        event.setAcknowledgedBy(2); event.setAcknowledgedTime(now); event.setAckComment("second");
        assertFalse(AlertEventRepository.acknowledgeAlertEvent(event));
        event.setResolvedTime(now);
        assertTrue(AlertEventRepository.resolveAlertEventManually(event));
        assertFalse(AlertEventRepository.acknowledgeAlertEvent(event));
        assertFalse(AlertEventRepository.resolveAlertEvent(event));
        AlertEvent stored = AlertEventRepository.getAlertEvent(event.getId());
        assertEquals(AlertStatus.RESOLVED, stored.getStatus());
        assertEquals(1, stored.getAcknowledgedBy());
        assertEquals("first", stored.getAckComment());
    }

    @Test
    void concurrentResolutionWinsOverBlockedStaleAcknowledgement() throws Exception {
        AlertEvent event = open();
        Map<String, Object> params = new HashMap<>();
        params.put("id", event.getId());
        params.put("resolved_time", Timestamp.from(now));
        params.put("acknowledged_by", 7);
        params.put("acknowledged_time", Timestamp.from(now));
        params.put("ack_comment", "stale acknowledgement");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SqlSession resolver = sessions.openSession(false);
        try {
            assertEquals(1, resolver.update("Sentinel.resolveAlertEvent", params));
            CountDownLatch started = new CountDownLatch(1);
            Future<Integer> acknowledgement = worker.submit(() -> {
                SqlSession other = sessions.openSession(false);
                try {
                    started.countDown();
                    int count = other.update("Sentinel.acknowledgeAlertEvent", params);
                    other.commit();
                    return count;
                } finally { other.close(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> acknowledgement.get(100, TimeUnit.MILLISECONDS));
            resolver.commit();
            assertEquals(0, acknowledgement.get(5, TimeUnit.SECONDS));
            AlertEvent stored = AlertEventRepository.getAlertEvent(event.getId());
            assertEquals(AlertStatus.RESOLVED, stored.getStatus());
            assertNull(stored.getAcknowledgedBy());
        } finally {
            resolver.close();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void bulkAndSingleAcknowledgeCountOnlySuccessfulClaims() {
        AlertEvent event = open();
        assertEquals(1, ProblemService.bulkAcknowledge(List.of(event.getId(), event.getId()), "note", 7));
        assertEquals(0, ProblemService.bulkAcknowledge(List.of(event.getId()), "again", 8));
        assertThrows(IllegalArgumentException.class, () -> ProblemService.acknowledge(event.getId(), "again", 8));
        assertEquals(1, ProblemService.bulkResolve(List.of(event.getId(), event.getId()), "done", 7));
        assertEquals(TriggerStatus.OK, state().getState());
        assertNull(state().getOpenAlertEventId());
    }

    @Test
    void oldManualResetCannotClobberNewIncident() {
        AlertEvent old = open();
        ProblemService.resolve(old.getId(), "done", 7);
        breach();
        Long current = state().getOpenAlertEventId();
        TriggerStateRepository.resetTriggerStateForAlert(old.getId(), now);
        assertEquals(current, state().getOpenAlertEventId());
        assertEquals(TriggerStatus.PROBLEM, state().getState());
    }

    @Test
    void failedManualResetSelfHealsOnNextBreach() throws Exception {
        AlertEvent event = open();
        sql("ALTER TABLE sentinel_trigger_state ADD CONSTRAINT reject_ok CHECK (state <> 'OK')");
        ProblemService.resolve(event.getId(), "done", 7);
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(event.getId()).getStatus());
        assertEquals(TriggerStatus.PROBLEM, state().getState());
        // minConsecutiveBreaches=1 repairs and opens in the same tick, so even
        // with the injected OK-write failure there is a new tracked incident.
        breach();
        assertEquals(2, count("sentinel_alert_event"));
        assertNotEquals(event.getId(), state().getOpenAlertEventId());
    }

    @Test
    void departedChannelResolutionAlsoRollsBackWithTriggerFailure() throws Exception {
        AlertEvent event = open();
        dispatch.reset();
        sql("ALTER TABLE sentinel_trigger_state ADD CONSTRAINT reject_departure CHECK (state <> 'INSUFFICIENT_DATA')");
        java.lang.reflect.Method sweep = TriggerEvaluatorJob.class.getDeclaredMethod(
                "autoResolveDepartedTriggers", Monitor.class, Set.class, Map.class, Instant.class);
        sweep.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> sweep.invoke(null, monitor, Set.of(), Map.of(), now));
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(event.getId()).getStatus());
        assertEquals(TriggerStatus.PROBLEM, state().getState());
        dispatch.verifyNoInteractions();
        sql("ALTER TABLE sentinel_trigger_state DROP CONSTRAINT reject_departure");
        sweep.invoke(null, monitor, Set.of(), Map.of(), now);
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(event.getId()).getStatus());
        assertEquals(TriggerStatus.INSUFFICIENT_DATA, state().getState());
        assertNull(state().getOpenAlertEventId());
        dispatch.verify(() -> ActionDispatcher.onAlertResolved(any(), any()), times(1));
    }

    @Test
    void resolutionAndTriggerUpdateRollbackTogether() throws Exception {
        AlertEvent event = open();
        dispatch.reset();
        sql("ALTER TABLE sentinel_trigger_state ADD CONSTRAINT reject_ok CHECK (state <> 'OK')");
        assertThrows(RuntimeException.class, () -> TriggerEvaluatorJob.applyOutcome(
                monitor, channel, null, EvaluationOutcome.ok("{}"), now));
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(event.getId()).getStatus());
        assertEquals(TriggerStatus.PROBLEM, state().getState());
        dispatch.verifyNoInteractions();
        sql("ALTER TABLE sentinel_trigger_state DROP CONSTRAINT reject_ok");
        TriggerEvaluatorJob.applyOutcome(monitor, channel, null, EvaluationOutcome.ok("{}"), now);
        assertNull(state().getOpenAlertEventId());
        dispatch.verify(() -> ActionDispatcher.onAlertResolved(any(), any()), times(1));
    }
}
