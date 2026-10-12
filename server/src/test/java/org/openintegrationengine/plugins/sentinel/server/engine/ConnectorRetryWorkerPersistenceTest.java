package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.server.controllers.*;
import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.alert.*;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.evaluate.*;
import org.openintegrationengine.plugins.sentinel.server.service.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** A heartbeat-triggered JDBC retry must not become the node's liveness clock. */
class ConnectorRetryWorkerPersistenceTest {
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000082";
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final Instant deployed = now.minusSeconds(300);
    private final Map<String, Object> originalLifecycle = new LinkedHashMap<>();
    private final List<MockedStatic<?>> mocks = new ArrayList<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    private String url;
    private SqlSessionManager sessions;
    private SqlConfig sql;
    private ControllerFactory factory;
    private EngineController engine;
    private ExecutorService ownedWorker;
    private MockedStatic<ActionDispatcher> dispatch;
    private Monitor monitor;

    @BeforeEach
    void setup() throws Exception {
        assertNull(field(SentinelLeadership.class, "heartbeatExecutor").get(null));
        assertFalse(PendingConnectorObservations.isPending(CHANNEL, 0));
        for (String name : List.of("standingDown", "leadershipEngaged", "lifecycleGeneration",
                "leadershipEpoch", "leadershipValidUntilNanos")) {
            originalLifecycle.put(name, field(SentinelLeadership.class, name).get(null));
        }
        field(SentinelLeadership.class, "standingDown").set(null, false);
        field(SentinelLeadership.class, "leadershipEngaged").set(null, false);
        field(SentinelLeadership.class, "lifecycleGeneration").set(null, 8200L);
        field(SentinelLeadership.class, "leadershipEpoch").set(null, null);
        field(SentinelLeadership.class, "leadershipValidUntilNanos").set(null, 0L);
        CollectorState.getInstance().forgetChannel(CHANNEL);
        url = "jdbc:derby:memory:retryworker" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url + ";create=true");
                MockedStatic<ConfigurationController> controllers = mockStatic(ConfigurationController.class)) {
            controllers.when(ConfigurationController::getInstance).thenReturn(mock(ConfigurationController.class));
            SentinelMigrator migrator = new SentinelMigrator();
            migrator.setConnection(connection); migrator.setDatabaseType("derby"); migrator.migrate();
        }
        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.apache.derby.jdbc.EmbeddedDriver", url, null, null)));
        cfg.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream in = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            new XMLMapperBuilder(in, cfg, "derby", cfg.getSqlFragments()).parse();
        }
        sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(cfg));
        sql = mock(SqlConfig.class); when(sql.getSqlSessionManager()).thenReturn(sessions);
        ownStatic(SqlConfig.class).when(SqlConfig::getInstance).thenReturn(sql);
        dispatch = ownStatic(ActionDispatcher.class);
        ownStatic(AlertPayload.class); ownStatic(SentinelAuditLog.class);
        Channel channel = mock(Channel.class); when(channel.getMetaDataIds()).thenReturn(List.of(0));
        Calendar date = Calendar.getInstance(); date.setTimeInMillis(deployed.toEpochMilli());
        when(channel.getDeployDate()).thenReturn(date);
        engine = mock(EngineController.class); when(engine.getDeployedIds()).thenReturn(Set.of(CHANNEL));
        when(engine.getDeployedChannel(CHANNEL)).thenReturn(channel);
        factory = mock(ControllerFactory.class); when(factory.createEngineController()).thenReturn(engine);
        ownStatic(ControllerFactory.class).when(ControllerFactory::getFactory).thenReturn(factory);
        monitor = new Monitor(); monitor.setName("blocked retry retention");
        monitor.setMonitorType(MonitorType.CONNECTION_STATUS); monitor.setScopeType(ScopeType.ALL);
        monitor.setSeverity(Severity.WARNING); monitor.setEnabled(true); monitor.setMinConsecutiveBreaches(1);
        monitor.setConfigJson("{}"); monitor.setCreatedTime(now); monitor.setUpdatedTime(now);
        MonitorRepository.insertMonitor(monitor);
        ScopeResolver.ChannelResolution resolution = new ScopeResolver.ChannelResolution();
        resolution.targets.add(new ScopeResolver.ChannelTarget(CHANNEL, "blocked retry channel"));
        ownStatic(ScopeResolver.class).when(() -> ScopeResolver.resolveScopedChannelSet(monitor)).thenReturn(resolution);
    }

    @AfterEach
    void cleanup() throws Exception {
        release.countDown();
        SentinelLeadership.stopHeartbeat();
        if (ownedWorker != null) {
            ownedWorker.shutdownNow();
            assertTrue(ownedWorker.awaitTermination(5, TimeUnit.SECONDS), "owned retry worker must exit before database cleanup");
        }
        // A failed assertion can leave deliberate retry intent after stop.
        // Once our only worker has exited, remove only this fixture's identity
        // so failed cases also release their in-memory state before DB cleanup.
        @SuppressWarnings("unchecked")
        Map<Object, PendingConnectorObservations.PendingObservation> pending =
                (Map<Object, PendingConnectorObservations.PendingObservation>)
                field(PendingConnectorObservations.class, "pending").get(null);
        pending.entrySet().removeIf(entry -> CHANNEL.equals(entry.getValue().row().getChannelId()));
        assertFalse(PendingConnectorObservations.isPending(CHANNEL, 0));
        CollectorState.getInstance().forgetChannel(CHANNEL);
        if (url != null) System.out.println("Final isolated blocked-retry rows before database drop: " + snapshot());
        Collections.reverse(mocks); mocks.forEach(MockedStatic::close);
        if (sessions != null && sessions.isManagedSessionStarted()) sessions.close();
        for (var entry : originalLifecycle.entrySet()) {
            field(SentinelLeadership.class, entry.getKey()).set(null, entry.getValue());
        }
        if (url != null) {
            SQLException dropped = assertThrows(SQLException.class, () -> DriverManager.getConnection(url + ";drop=true"));
            assertEquals("08006", dropped.getSQLState());
        }
        assertNull(workerFailure.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedHeartbeatRetryDoesNotExpireInventoryOrResolveIncident(boolean healthyPeer) throws Exception {
        Long incident = openIncident(healthyPeer);
        stageAndStartBlockedRetry();
        try (MockedStatic<PendingConnectorObservations> remoteView = mockStatic(PendingConnectorObservations.class, CALLS_REAL_METHODS)) {
            // Model a peer evaluator: it cannot inspect this node's local dirty map.
            remoteView.when(() -> PendingConnectorObservations.isPending(CHANNEL, 0)).thenReturn(false);
            for (int pass = 0; pass < 3; pass++) {
                expireThisNodesLeases();
                assertTimeout(Duration.ofSeconds(2), () -> SentinelLeadership.heartbeat(generation()));
                assertEquals(1, attempts.get(), "no queued or parallel retry may accumulate behind blocked JDBC");
                assertTrue(NodeLeaseRepository.listActiveDeployedChannelIds().contains(CHANNEL), this::snapshot);
                assertTrue(NodeLeaseRepository.listActiveConnectorDeployments(CHANNEL).get(0)
                        .containsKey(SentinelLeadership.nodeId()));
                assertFalse(NodeLeaseRepository.listHealthyObservationNodeIds().contains(SentinelLeadership.nodeId()));
                NodeLease leader = NodeLeaseRepository.getNodeLease("sentinel-engine");
                assertEquals(SentinelLeadership.nodeId(), leader.getNodeId());
                assertTrue(leader.getExpiresTime().isAfter(NodeLeaseRepository.getDatabaseTime()), this::snapshot);
                assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA,
                        ConnectionStatusEvaluator.evaluate(monitor, CHANNEL, now.plusSeconds(91 + pass)).get(0).outcome.getResult());
                TriggerEvaluatorJob.evaluateMonitor(monitor, now.plusSeconds(91 + pass));
                TriggerState retained = TriggerStateRepository.getTriggerState(monitor.getId(), CHANNEL, 0);
                assertEquals(incident, retained.getOpenAlertEventId());
                assertEquals(TriggerStatus.INSUFFICIENT_DATA, retained.getState());
                assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(incident).getStatus());
            }
            dispatch.verify(() -> ActionDispatcher.onAlertResolved(any(), any()), never());
        }
        release.countDown();
        awaitRetryComplete();
        SentinelLeadership.heartbeat(generation());
        TriggerEvaluatorJob.evaluateMonitor(monitor, now.plusSeconds(96));
        TriggerEvaluatorJob.evaluateMonitor(monitor, now.plusSeconds(97));
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(incident).getStatus());
        dispatch.verify(() -> ActionDispatcher.onAlertResolved(any(), any()), times(1));
    }

    @Test
    void repeatedStopRestartCannotMultiplyBlockedWorkersOrPublishOldGeneration() throws Exception {
        openIncident(true);
        stageAndStartBlockedRetry();
        for (int restart = 0; restart < 3; restart++) {
            SentinelLeadership.stopHeartbeat();
            SentinelLeadership.startHeartbeat();
            assertSame(ownedWorker, field(PendingConnectorObservations.class, "retryExecutor").get(null),
                    "restart must retain the blocked worker instead of creating another JDBC writer");
            assertEquals(1, attempts.get());
            assertTrue(PendingConnectorObservations.isPending(CHANNEL, 0));
            assertFalse(NodeLeaseRepository.listHealthyObservationNodeIds().contains(SentinelLeadership.nodeId()));
        }
        release.countDown();
        assertTrue(ownedWorker.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(PendingConnectorObservations.isPending(CHANNEL, 0), "old lifecycle cannot clear current retry intent");
        assertNull(CollectorState.getInstance().getConnectorState(CHANNEL, 0), "old lifecycle cannot publish cache state");
        // Complete retained intent synchronously on the current generation;
        // this test's worker intentionally ignores stop interrupts until released.
        PendingConnectorObservations.flush();
        assertFalse(PendingConnectorObservations.isPending(CHANNEL, 0));
        assertEquals("CONNECTED", CollectorState.getInstance().getConnectorState(CHANNEL, 0).state.name());
    }

    private Long openIncident(boolean healthyPeer) {
        ConnectorStatusRepository.insertConnectorStatusEvent(row(SentinelLeadership.nodeId(), "DISCONNECTED", 60));
        SentinelLeadership.heartbeat(8200L);
        if (healthyPeer) {
            NodeLeaseRepository.refreshNodePresence("sentinel-presence-retry-peer", "retry-peer", 90,
                    Map.of(CHANNEL, new NodeLeaseRepository.ChannelDeployment(Set.of(0), deployed)), () -> true);
            NodeLeaseRepository.refreshObservationHealth("sentinel-observation-retry-peer", "retry-peer", 90, () -> true);
            ConnectorStatusRepository.insertConnectorStatusEvent(row("retry-peer", "CONNECTED", 60));
        }
        TriggerEvaluatorJob.evaluateMonitor(monitor, now);
        Long incident = TriggerStateRepository.getTriggerState(monitor.getId(), CHANNEL, 0).getOpenAlertEventId();
        assertNotNull(incident); dispatch.reset(); return incident;
    }

    private void stageAndStartBlockedRetry() throws Exception {
        try (MockedStatic<ConnectorStatusRepository> insert = mockStatic(ConnectorStatusRepository.class)) {
            insert.when(() -> ConnectorStatusRepository.insertConnectorStatusEvent(any()))
                    .thenThrow(new IllegalStateException("first write unavailable"));
            PendingConnectorObservations.observe(row(SentinelLeadership.nodeId(), "CONNECTED", 5),
                    SentinelLeadership.connectorObservationGeneration(), () -> true);
        }
        assertTrue(PendingConnectorObservations.isPending(CHANNEL, 0));
        ownedWorker = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
            try (MockedStatic<ControllerFactory> controllers = mockStatic(ControllerFactory.class);
                    MockedStatic<SqlConfig> sqlConfig = mockStatic(SqlConfig.class);
                    MockedStatic<ConnectorStatusRepository> repository = mockStatic(ConnectorStatusRepository.class, CALLS_REAL_METHODS)) {
                controllers.when(ControllerFactory::getFactory).thenReturn(factory);
                sqlConfig.when(SqlConfig::getInstance).thenReturn(sql);
                repository.when(() -> ConnectorStatusRepository.insertConnectorStatusEvent(any())).thenAnswer(invocation -> {
                    attempts.incrementAndGet(); entered.countDown();
                    boolean released = false;
                    while (!released) {
                        try { release.await(); released = true; }
                        catch (InterruptedException ignored) { /* model a JDBC call that ignores interruption */ }
                    }
                    return invocation.callRealMethod();
                });
                task.run();
            } catch (Throwable failure) { workerFailure.set(failure); }
        }, "sentinel-blocked-retry-test"));
        field(PendingConnectorObservations.class, "retryExecutor").set(null, ownedWorker);
        SentinelLeadership.startHeartbeat();
        assertTrue(entered.await(3, TimeUnit.SECONDS), "heartbeat must schedule its retry on the isolated worker");
    }

    private void awaitRetryComplete() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (PendingConnectorObservations.isPending(CHANNEL, 0) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(PendingConnectorObservations.isPending(CHANNEL, 0), this::snapshot);
        assertNull(workerFailure.get());
    }

    private ConnectorStatusEvent row(String node, String state, long age) {
        ConnectorStatusEvent event = new ConnectorStatusEvent(); event.setChannelId(CHANNEL); event.setMetadataId(0);
        event.setNodeId(node); event.setNewState(state); event.setChangedTime(now.minusSeconds(age));
        event.setDeploymentTime(deployed); return event;
    }
    private void expireThisNodesLeases() throws Exception {
        try (Connection c = DriverManager.getConnection(url); PreparedStatement statement = c.prepareStatement(
                "UPDATE sentinel_node_lease SET expires_time = TIMESTAMP('2000-01-01 00:00:00') WHERE node_id = ?")) {
            statement.setString(1, SentinelLeadership.nodeId()); statement.executeUpdate();
        }
    }
    private String snapshot() {
        try (Connection c = DriverManager.getConnection(url); Statement statement = c.createStatement();
                ResultSet rows = statement.executeQuery("SELECT lease_name,node_id,expires_time FROM sentinel_node_lease")) {
            List<String> result = new ArrayList<>();
            while (rows.next()) result.add(rows.getString(1) + "/" + rows.getString(2) + "/" + rows.getTimestamp(3));
            try (ResultSet incidents = statement.executeQuery("SELECT id,status,resolution_pending FROM sentinel_alert_event")) {
                while (incidents.next()) result.add("incident=" + incidents.getLong(1) + "/" + incidents.getString(2)
                        + "/resolutionPending=" + incidents.getBoolean(3));
            }
            return result.toString();
        } catch (SQLException error) { return error.toString(); }
    }
    private long generation() throws Exception { return (long) field(SentinelLeadership.class, "lifecycleGeneration").get(null); }
    private <T> MockedStatic<T> ownStatic(Class<T> type) {
        MockedStatic<T> result = mockStatic(type); mocks.add(result); return result;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
