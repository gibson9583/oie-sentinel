/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.NodeLease;

/** Actual mapped statements and independent connections, with the engine's MyBatis configuration. */
class LeaseFencePersistenceTest {
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000001";
    private String url;
    private String baseUrl;
    private String schema;
    private SqlSessionManager sessions;
    private MockedStatic<SqlConfig> config;

    @BeforeEach
    void setup() throws Exception {
        String vendor = System.getProperty("sentinel.fencing.vendor", "derby");
        String driver = vendor.equals("postgres") ? "org.postgresql.Driver" : "org.apache.derby.jdbc.EmbeddedDriver";
        Class.forName(driver);
        String identity = "fencing" + UUID.randomUUID().toString().replace("-", "");
        baseUrl = System.getProperty("sentinel.fencing.jdbcUrl");
        if (baseUrl == null) {
            assertEquals("derby", vendor, "An external database requires sentinel.fencing.jdbcUrl");
            url = "jdbc:derby:memory:" + identity;
            DriverManager.getConnection(url + ";create=true").close();
        } else {
            assertEquals("postgres", vendor, "External runs currently isolate a PostgreSQL schema");
            schema = identity;
            try (Connection connection = DriverManager.getConnection(baseUrl); Statement sql = connection.createStatement()) {
                sql.execute("CREATE SCHEMA " + schema);
            }
            url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        try (Connection connection = DriverManager.getConnection(url); Statement sql = connection.createStatement()) {
            for (String suffix : List.of("-sentinel-tables.sql", "-sentinel-v4.sql", "-sentinel-v7.sql",
                    "-sentinel-v8.sql", "-sentinel-v11.sql", "-sentinel-v12.sql")) {
                try (InputStream in = getClass().getResourceAsStream("/" + vendor + suffix)) {
                    assertNotNull(in);
                    for (String statement : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n\\s*\\r?\\n")) {
                        if (!statement.isBlank()) sql.execute(statement.trim());
                    }
                }
            }
        }
        Configuration cfg = new Configuration(new Environment("fencing", new JdbcTransactionFactory(),
                new UnpooledDataSource(driver, url, null, null)));
        cfg.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream in = getClass().getResourceAsStream("/mapper/" + vendor + "-sqlmap.xml")) {
            new XMLMapperBuilder(in, cfg, vendor, cfg.getSqlFragments()).parse();
        }
        sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(cfg));
        SqlConfig engineConfig = mock(SqlConfig.class);
        when(engineConfig.getSqlSessionManager()).thenReturn(sessions);
        config = mockStatic(SqlConfig.class);
        config.when(SqlConfig::getInstance).thenReturn(engineConfig);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (config != null) config.close();
        if (sessions != null && sessions.isManagedSessionStarted()) sessions.close();
        if (schema != null) {
            try (Connection connection = DriverManager.getConnection(baseUrl); Statement sql = connection.createStatement()) {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        } else if (url != null) {
            SQLException dropped = assertThrows(SQLException.class,
                    () -> DriverManager.getConnection(url + ";drop=true"));
            assertEquals("08006", dropped.getSQLState());
        }
    }

    private LeaseFence acquire(String name, String node, int seconds) {
        NodeLease lease = new NodeLease();
        lease.setLeaseName(name);
        lease.setNodeId(node);
        lease.setLeaseEpoch(1L);
        assertTrue(NodeLeaseRepository.insertNodeLease(lease, seconds));
        return new LeaseFence(name, node, 1L);
    }

    @Test
    void releasedEpochCannotRenewReclaimOrAuthorizeEvenWhenTheSameNodeReacquires() {
        LeaseFence first = acquire("sentinel-engine", "node-a", 90);
        assertTrue(NodeLeaseRepository.releaseNodeLease(first.leaseName(), first.nodeId(), first.epoch()));
        assertEquals(2L, NodeLeaseRepository.getNodeLease(first.leaseName()).getLeaseEpoch());
        assertFalse(NodeLeaseRepository.renewNodeLease(first.leaseName(), first.nodeId(), first.epoch(), 90));
        assertFalse(NodeLeaseRepository.stealExpiredNodeLease(first.leaseName(), first.nodeId(), first.epoch(), 90));
        assertTrue(NodeLeaseRepository.stealExpiredNodeLease(first.leaseName(), first.nodeId(), 2L, 90));
        SqlSession transaction = sessions.openSession(false);
        try {
            assertThrows(RepositoryException.class, () -> NodeLeaseRepository.requireFence(transaction, first));
            NodeLeaseRepository.requireFence(transaction, new LeaseFence(first.leaseName(), first.nodeId(), 3L));
            transaction.rollback();
        } finally {
            transaction.close();
        }
    }

    @Test
    void nullFenceFailsClosedAndDatabaseFailurePropagates() throws Exception {
        SqlSession first = sessions.openSession(false);
        try {
            assertThrows(RepositoryException.class, () -> NodeLeaseRepository.requireFence(first, null));
        } finally {
            first.close();
        }
        LeaseFence fence = acquire("sentinel-engine", "node-a", 90);
        try (Connection connection = DriverManager.getConnection(url); Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE sentinel_node_lease");
        }
        SqlSession transaction = sessions.openSession(false);
        try {
            assertThrows(RuntimeException.class, () -> NodeLeaseRepository.requireFence(transaction, fence));
        } finally {
            transaction.close();
        }
    }

    @Test
    void expiryRecheckBypassesSessionCacheAndUsesCurrentDatabaseClockWithinOneTransaction() {
        LeaseFence fence = acquire("sentinel-engine", "node-a", 1);
        Instant expiry = NodeLeaseRepository.getNodeLease(fence.leaseName()).getExpiresTime();
        SqlSession transaction = sessions.openSession(false);
        try {
            NodeLeaseRepository.requireFence(transaction, fence);
            awaitExpiry(transaction, expiry);
            assertThrows(RepositoryException.class, () -> NodeLeaseRepository.requireFence(transaction, fence));
            transaction.rollback();
        } finally {
            transaction.close();
        }
    }

    @Test
    void successorCannotAcquireUntilTheExpiredWriterRollsBackItsProtectedWork() throws Exception {
        LeaseFence fence = acquire("sentinel-engine", "node-a", 1);
        Instant expiry = NodeLeaseRepository.getNodeLease(fence.leaseName()).getExpiresTime();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SqlSession transaction = sessions.openSession(false);
        Future<Integer> successor = null;
        try {
            NodeLeaseRepository.requireFence(transaction, fence);
            transaction.insert("Sentinel.insertConnectorStatusEvent", eventParams("node-a", "DISCONNECTED", Instant.now()));
            awaitExpiry(transaction, expiry);
            CountDownLatch attempted = new CountDownLatch(1);
            successor = executor.submit(() -> {
                SqlSession next = sessions.openSession(false);
                try {
                    attempted.countDown();
                    int changed = next.update("Sentinel.stealExpiredNodeLease", Map.of(
                            "leaseName", fence.leaseName(), "nodeId", "node-b", "expectedLeaseEpoch", 1L, "leaseSeconds", 90));
                    next.commit();
                    return changed;
                } finally {
                    next.close();
                }
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            Future<Integer> waiting = successor;
            assertThrows(TimeoutException.class, () -> waiting.get(150, TimeUnit.MILLISECONDS));
            assertThrows(RepositoryException.class, () -> NodeLeaseRepository.requireFence(transaction, fence));
            transaction.rollback();
            assertEquals(1, successor.get(5, TimeUnit.SECONDS));
            assertTrue(ConnectorStatusRepository.listLatestConnectorStatusEvents(CHANNEL).isEmpty(),
                    "The expired writer's protected insert must be rolled back");
            assertEquals("node-b", NodeLeaseRepository.getNodeLease(fence.leaseName()).getNodeId());
            assertEquals(2L, NodeLeaseRepository.getNodeLease(fence.leaseName()).getLeaseEpoch());
        } finally {
            transaction.rollback();
            transaction.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Owned worker must exit before database cleanup");
        }
    }

    @Test
    void followerPresenceAndConnectorHistorySurviveLeaderChangesAndClockRollback() {
        LeaseFence leader = acquire("sentinel-engine", "node-a", 90);
        acquire("sentinel-presence-a", "node-a", 90);
        acquire("sentinel-presence-b", "node-b", 90);
        Instant future = Instant.now().plusSeconds(3600);
        sessions.insert("Sentinel.insertConnectorStatusEvent", eventParams("node-a", "CONNECTED", future));
        sessions.insert("Sentinel.insertConnectorStatusEvent", eventParams("node-b", "CONNECTED", future));
        sessions.insert("Sentinel.insertConnectorStatusEvent", eventParams("node-b", "DISCONNECTED", Instant.EPOCH));
        assertEquals(Set.of("node-a", "node-b"), NodeLeaseRepository.listActiveSentinelNodeIds());
        assertTrue(NodeLeaseRepository.releaseNodeLease(leader.leaseName(), leader.nodeId(), leader.epoch()));
        assertTrue(NodeLeaseRepository.stealExpiredNodeLease(leader.leaseName(), "node-b", 2L, 90));
        List<ConnectorStatusEvent> latest = ConnectorStatusRepository.listLatestConnectorStatusEvents(CHANNEL);
        assertEquals(2, latest.size());
        assertEquals("CONNECTED", latest.get(0).getNewState());
        assertEquals("node-b", latest.get(1).getNodeId());
        assertEquals("DISCONNECTED", latest.get(1).getNewState(), "Generated arrival id must win over wall-clock order");
        ConnectorStatusRepository.deleteConnectorStatusEventsOlderThan(future.plusSeconds(1));
        assertEquals(2, ConnectorStatusRepository.listLatestConnectorStatusEvents(CHANNEL).size(),
                "Retention must preserve each node's durable current reading");
    }

    @Test
    void presenceAndCompleteChannelInventoryCommitOrRollbackTogether() throws Exception {
        assertTrue(NodeLeaseRepository.refreshNodePresence("sentinel-presence-a", "node-a", 90,
                Map.of(CHANNEL, new NodeLeaseRepository.ChannelDeployment(Set.of(0, 1), Instant.EPOCH)), () -> true));
        assertEquals(Set.of(CHANNEL), NodeLeaseRepository.listActiveDeployedChannelIds());
        assertEquals(Set.of("node-a"), NodeLeaseRepository.listActiveDeployedNodeIds(CHANNEL));
        assertEquals(Map.of(0, Set.of("node-a"), 1, Set.of("node-a")),
                NodeLeaseRepository.listActiveConnectorNodes(CHANNEL));
        Instant originalExpiry = NodeLeaseRepository.getNodeLease("sentinel-presence-a").getExpiresTime();
        String rejected = "00000000-0000-0000-0000-000000000002";
        try (Connection connection = DriverManager.getConnection(url); Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE sentinel_channel_presence ADD CONSTRAINT reject_inventory CHECK (channel_id <> '" + rejected + "')");
        }
        assertThrows(RepositoryException.class, () -> NodeLeaseRepository.refreshNodePresence(
                "sentinel-presence-a", "node-a", 90, Map.of(rejected, new NodeLeaseRepository.ChannelDeployment(Set.of(0), Instant.EPOCH)), () -> true));
        assertEquals(Set.of(CHANNEL), NodeLeaseRepository.listActiveDeployedChannelIds(),
                "Deleting old inventory must roll back when a replacement insert fails");
        assertEquals(originalExpiry, NodeLeaseRepository.getNodeLease("sentinel-presence-a").getExpiresTime(),
                "Presence renewal must roll back with the failed inventory");
        assertThrows(RepositoryException.class, () -> NodeLeaseRepository.refreshNodePresence(
                "sentinel-presence-b", "node-b", 90, Map.of(rejected, new NodeLeaseRepository.ChannelDeployment(Set.of(0), Instant.EPOCH)), () -> true));
        assertNull(NodeLeaseRepository.getNodeLease("sentinel-presence-b"),
                "Failed startup must not expose an active node without a complete inventory");
    }

    @Test
    void canceledPublicationRetainsPreviousInventoryAndAnExplicitEmptySnapshotRemovesIt() {
        assertTrue(NodeLeaseRepository.refreshNodePresence("sentinel-presence-a", "node-a", 90,
                Map.of(CHANNEL, new NodeLeaseRepository.ChannelDeployment(Set.of(0, 1), Instant.EPOCH)), () -> true));
        assertFalse(NodeLeaseRepository.refreshNodePresence("sentinel-presence-a", "node-a", 90,
                Map.of(), () -> false));
        assertEquals(Set.of(CHANNEL), NodeLeaseRepository.listActiveDeployedChannelIds());
        assertTrue(NodeLeaseRepository.refreshNodePresence("sentinel-presence-a", "node-a", 90,
                Map.of(), () -> true));
        assertTrue(NodeLeaseRepository.listActiveDeployedChannelIds().isEmpty());
        assertEquals(Set.of("node-a"), NodeLeaseRepository.listActiveSentinelNodeIds(),
                "A live node with no deployed channels is a valid complete inventory");
    }

    @Test
    void followerOnlyDeploymentIsVisibleUntilThatNodesPresenceExpires() {
        acquire("sentinel-engine", "node-a", 90);
        assertTrue(NodeLeaseRepository.refreshNodePresence("sentinel-presence-a", "node-a", 90,
                Map.of(), () -> true));
        assertTrue(NodeLeaseRepository.refreshNodePresence("sentinel-presence-b", "node-b", 90,
                Map.of(CHANNEL, new NodeLeaseRepository.ChannelDeployment(Set.of(0, 1), Instant.EPOCH)), () -> true));
        assertEquals(Set.of(CHANNEL), NodeLeaseRepository.listActiveDeployedChannelIds());
        assertEquals(Set.of("node-b"), NodeLeaseRepository.listActiveDeployedNodeIds(CHANNEL));
        assertTrue(NodeLeaseRepository.releaseNodeLease("sentinel-presence-b", "node-b", 1L));
        assertTrue(NodeLeaseRepository.listActiveDeployedChannelIds().isEmpty());
        assertTrue(NodeLeaseRepository.listActiveDeployedNodeIds(CHANNEL).isEmpty());
        assertTrue(NodeLeaseRepository.listActiveConnectorNodes(CHANNEL).isEmpty());
    }

    private void awaitExpiry(SqlSession transaction, Instant expiry) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            transaction.clearCache();
            Timestamp databaseNow = transaction.selectOne("Sentinel.getDatabaseTime");
            if (!databaseNow.toInstant().isBefore(expiry)) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("Database lease did not expire within five seconds");
    }

    private Map<String, Object> eventParams(String node, String state, Instant when) {
        Map<String, Object> params = new HashMap<>();
        params.put("channel_id", CHANNEL);
        params.put("metadata_id", 1);
        params.put("node_id", node);
        params.put("previous_state", null);
        params.put("new_state", state);
        params.put("changed_time", Timestamp.from(when));
        params.put("deployment_time", Timestamp.from(Instant.EPOCH));
        return params;
    }
}
