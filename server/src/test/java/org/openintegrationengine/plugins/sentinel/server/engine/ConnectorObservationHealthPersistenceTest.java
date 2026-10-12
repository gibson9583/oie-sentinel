package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.alert.*;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.evaluate.*;
import org.openintegrationengine.plugins.sentinel.server.service.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Real heartbeat, packaged Derby leases/inventory, evaluator and incident transactions. */
class ConnectorObservationHealthPersistenceTest {
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000002";
    private static final long GENERATION = 8123L;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final Instant deployed = now.minusSeconds(300);
    private final Map<String, Object> originalLifecycle = new LinkedHashMap<>();
    private final List<MockedStatic<?>> mocks = new ArrayList<>();
    private String url;
    private SqlSessionManager sessions;
    private MockedStatic<ActionDispatcher> dispatch;
    private MockedStatic<PendingConnectorObservations> pending;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private Monitor monitor;

    @BeforeEach
    void setup() throws Exception {
        for (String name : List.of("standingDown", "leadershipEngaged", "lifecycleGeneration",
                "leadershipEpoch", "leadershipValidUntilNanos")) {
            Field field = field(name); originalLifecycle.put(name, field.get(null));
        }
        field("standingDown").set(null, false); field("leadershipEngaged").set(null, false);
        field("lifecycleGeneration").set(null, GENERATION); field("leadershipEpoch").set(null, null);
        field("leadershipValidUntilNanos").set(null, 0L);
        assertNull(field("heartbeatExecutor").get(null), "This fixture owns no heartbeat executor");
        url = "jdbc:derby:memory:observationhealth" + UUID.randomUUID().toString().replace("-", "");
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
        SqlConfig sql = mock(SqlConfig.class); when(sql.getSqlSessionManager()).thenReturn(sessions);
        mockStaticOwned(SqlConfig.class).when(SqlConfig::getInstance).thenReturn(sql);
        dispatch = mockStaticOwned(ActionDispatcher.class);
        mockStaticOwned(AlertPayload.class); mockStaticOwned(SentinelAuditLog.class);

        Channel channel = mock(Channel.class); when(channel.getMetaDataIds()).thenReturn(List.of(0));
        Calendar date = Calendar.getInstance(); date.setTimeInMillis(deployed.toEpochMilli());
        when(channel.getDeployDate()).thenReturn(date);
        EngineController engine = mock(EngineController.class);
        when(engine.getDeployedIds()).thenReturn(Set.of(CHANNEL));
        when(engine.getDeployedChannel(CHANNEL)).thenReturn(channel);
        ControllerFactory factory = mock(ControllerFactory.class); when(factory.createEngineController()).thenReturn(engine);
        mockStaticOwned(ControllerFactory.class).when(ControllerFactory::getFactory).thenReturn(factory);
        pending = mockStaticOwned(PendingConnectorObservations.class);
        pending.when(PendingConnectorObservations::hasPending).thenAnswer(i -> dirty.get());
        // The evaluator has no local pending hint, as on a different cluster
        // node. Its only confidence signal is the durable health lease.

        monitor = new Monitor(); monitor.setName("health retention"); monitor.setMonitorType(MonitorType.CONNECTION_STATUS);
        monitor.setScopeType(ScopeType.ALL); monitor.setSeverity(Severity.WARNING); monitor.setEnabled(true);
        monitor.setMinConsecutiveBreaches(1); monitor.setConfigJson("{}");
        monitor.setCreatedTime(now); monitor.setUpdatedTime(now); MonitorRepository.insertMonitor(monitor);
        ScopeResolver.ChannelResolution resolution = new ScopeResolver.ChannelResolution();
        resolution.targets.add(new ScopeResolver.ChannelTarget(CHANNEL, "test channel"));
        mockStaticOwned(ScopeResolver.class).when(() -> ScopeResolver.resolveScopedChannelSet(monitor)).thenReturn(resolution);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (url != null) System.out.println("Final isolated observation-health rows before database drop: " + leaseSnapshot());
        Collections.reverse(mocks); mocks.forEach(MockedStatic::close);
        if (sessions != null && sessions.isManagedSessionStarted()) sessions.close();
        for (var entry : originalLifecycle.entrySet()) field(entry.getKey()).set(null, entry.getValue());
        if (url != null) {
            SQLException dropped = assertThrows(SQLException.class, () -> DriverManager.getConnection(url + ";drop=true"));
            assertEquals("08006", dropped.getSQLState());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,CONNECTOR", "true,CONNECTOR", "false,CHANNEL", "true,CHANNEL"})
    void pendingPastLeaseExpiryKeepsIncidentAndInventoryEvenWithHealthyPeer(boolean healthyPeer, String rollup)
            throws Exception {
        monitor.setConfigJson("{\"rollup\":\"" + rollup + "\"}");
        String dirtyNode = SentinelLeadership.nodeId();
        record(dirtyNode, "DISCONNECTED", now.minusSeconds(60));
        SentinelLeadership.heartbeat(GENERATION);
        if (healthyPeer) {
            assertTrue(NodeLeaseRepository.refreshNodePresence("sentinel-presence-healthy-peer", "healthy-peer", 90,
                    Map.of(CHANNEL, new NodeLeaseRepository.ChannelDeployment(Set.of(0), deployed)), () -> true));
            assertTrue(NodeLeaseRepository.refreshObservationHealth("sentinel-observation-healthy-peer", "healthy-peer", 90, () -> true));
            record("healthy-peer", "CONNECTED", now.minusSeconds(60));
        }
        TriggerEvaluatorJob.evaluateMonitor(monitor, now);
        Integer metadata = rollup.equals("CHANNEL") ? null : 0;
        TriggerState opened = TriggerStateRepository.getTriggerState(monitor.getId(), CHANNEL, metadata);
        assertNotNull(opened); Long incident = opened.getOpenAlertEventId(); assertNotNull(incident);
        dispatch.reset();

        dirty.set(true);
        // Advance this node's durable leases beyond the 90-second confidence
        // window without sleeping. The next real heartbeat must republish
        // membership despite the still-failing observation stream.
        try (Connection c = DriverManager.getConnection(url); PreparedStatement expire = c.prepareStatement(
                "UPDATE sentinel_node_lease SET expires_time = TIMESTAMP('2000-01-01 00:00:00') WHERE node_id = ?")) {
            expire.setString(1, dirtyNode); expire.executeUpdate();
        }
        SentinelLeadership.heartbeat(GENERATION);
        assertTrue(NodeLeaseRepository.listActiveDeployedChannelIds().contains(CHANNEL), this::leaseSnapshot);
        assertTrue(NodeLeaseRepository.listActiveConnectorDeployments(CHANNEL).get(0).containsKey(dirtyNode));
        assertFalse(NodeLeaseRepository.listHealthyObservationNodeIds().contains(dirtyNode));
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA,
                ConnectionStatusEvaluator.evaluate(monitor, CHANNEL, now.plusSeconds(91)).get(0).outcome.getResult());
        TriggerEvaluatorJob.evaluateMonitor(monitor, now.plusSeconds(91));
        TriggerState retained = TriggerStateRepository.getTriggerState(monitor.getId(), CHANNEL, metadata);
        assertEquals(TriggerStatus.INSUFFICIENT_DATA, retained.getState());
        assertEquals(incident, retained.getOpenAlertEventId());
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(incident).getStatus());
        assertFalse(AlertEventRepository.getAlertEvent(incident).isResolutionPending());
        dispatch.verify(() -> ActionDispatcher.onAlertResolved(any(), any()), never());

        if (healthyPeer) {
            record("healthy-peer", "DISCONNECTED", now.plusSeconds(92));
            assertEquals(EvaluationOutcome.Result.BREACH,
                    ConnectionStatusEvaluator.evaluate(monitor, CHANNEL, now.plusSeconds(93)).get(0).outcome.getResult(),
                    "A healthy node's real breach still wins over another node's unknown telemetry");
            TriggerEvaluatorJob.evaluateMonitor(monitor, now.plusSeconds(93));
            assertEquals(incident, TriggerStateRepository.getTriggerState(monitor.getId(), CHANNEL, metadata).getOpenAlertEventId());
            record("healthy-peer", "CONNECTED", now.plusSeconds(94));
        }

        // Only a recovered committed observation AND a renewed health lease
        // can close the same incident. The retry is then idempotent.
        record(dirtyNode, "CONNECTED", now.plusSeconds(95)); dirty.set(false);
        SentinelLeadership.heartbeat(GENERATION);
        TriggerEvaluatorJob.evaluateMonitor(monitor, now.plusSeconds(96));
        TriggerEvaluatorJob.evaluateMonitor(monitor, now.plusSeconds(97));
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(incident).getStatus());
        dispatch.verify(() -> ActionDispatcher.onAlertResolved(any(), any()), times(1));
    }

    @Test
    void dirtyHeartbeatExpiresHealthImmediatelyWithoutWaitingForItsLease() {
        SentinelLeadership.heartbeat(GENERATION);
        assertTrue(NodeLeaseRepository.listHealthyObservationNodeIds().contains(SentinelLeadership.nodeId()));
        dirty.set(true); SentinelLeadership.heartbeat(GENERATION);
        assertFalse(NodeLeaseRepository.listHealthyObservationNodeIds().contains(SentinelLeadership.nodeId()));
        assertTrue(NodeLeaseRepository.listActiveDeployedChannelIds().contains(CHANNEL));
        dirty.set(false); SentinelLeadership.heartbeat(GENERATION);
        assertTrue(NodeLeaseRepository.listHealthyObservationNodeIds().contains(SentinelLeadership.nodeId()));
    }

    @Test
    void healthPublicationRollbackAndCleanStopDoNotDeleteInventory() throws Exception {
        SentinelLeadership.heartbeat(GENERATION);
        String node = SentinelLeadership.nodeId();
        assertTrue(NodeLeaseRepository.listHealthyObservationNodeIds().contains(node), this::leaseSnapshot);
        assertFalse(NodeLeaseRepository.refreshObservationHealth("sentinel-observation-aborted", "aborted", 90, () -> false));
        assertNull(NodeLeaseRepository.getNodeLease("sentinel-observation-aborted"));
        assertTrue(NodeLeaseRepository.listActiveDeployedChannelIds().contains(CHANNEL), this::leaseSnapshot);
        SentinelLeadership.stopHeartbeat();
        assertFalse(NodeLeaseRepository.listHealthyObservationNodeIds().contains(node));
        assertFalse(NodeLeaseRepository.listActiveDeployedChannelIds().contains(CHANNEL));
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement();
                ResultSet rows = s.executeQuery("SELECT COUNT(*) FROM sentinel_channel_presence")) {
            rows.next(); assertEquals(1, rows.getInt(1), "lease expiry retains the inventory rows");
        }
        SentinelLeadership.heartbeat(GENERATION);
        assertFalse(NodeLeaseRepository.listHealthyObservationNodeIds().contains(node), "stopped generation cannot republish health");
    }

    private void record(String node, String state, Instant changed) {
        ConnectorStatusEvent event = new ConnectorStatusEvent(); event.setChannelId(CHANNEL); event.setMetadataId(0);
        event.setNodeId(node); event.setNewState(state); event.setChangedTime(changed); event.setDeploymentTime(deployed);
        ConnectorStatusRepository.insertConnectorStatusEvent(event);
    }

    private String leaseSnapshot() {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement();
                ResultSet rows = s.executeQuery("SELECT lease_name,node_id,expires_time,CURRENT_TIMESTAMP FROM sentinel_node_lease")) {
            List<String> result = new ArrayList<>();
            while (rows.next()) result.add(rows.getString(1) + " node=" + rows.getString(2)
                    + " expires=" + rows.getTimestamp(3) + " now=" + rows.getTimestamp(4));
            try (ResultSet inventory = s.executeQuery("SELECT channel_id,node_id,metadata_id FROM sentinel_channel_presence")) {
                while (inventory.next()) result.add("inventory=" + inventory.getString(1) + "/" + inventory.getString(2)
                        + "/" + inventory.getInt(3));
            }
            try (ResultSet incidents = s.executeQuery("SELECT id,status,resolution_pending FROM sentinel_alert_event")) {
                while (incidents.next()) result.add("incident=" + incidents.getLong(1) + "/" + incidents.getString(2)
                        + "/resolutionPending=" + incidents.getBoolean(3));
            }
            return result.toString();
        } catch (SQLException error) { return error.toString(); }
    }

    private <T> MockedStatic<T> mockStaticOwned(Class<T> type) {
        MockedStatic<T> mock = mockStatic(type); mocks.add(mock); return mock;
    }

    private static Field field(String name) throws Exception {
        Field field = SentinelLeadership.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
