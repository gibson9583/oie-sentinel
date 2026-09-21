/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.mirth.connect.server.util.SqlConfig;

class NodeLeaseRepositoryTest {

    private MockedStatic<SqlConfig> sqlConfigStatic;
    private SqlSessionManager manager;

    @BeforeEach
    void setUp() {
        SqlConfig sqlConfig = mock(SqlConfig.class);
        manager = mock(SqlSessionManager.class);
        when(sqlConfig.getSqlSessionManager()).thenReturn(manager);
        sqlConfigStatic = Mockito.mockStatic(SqlConfig.class);
        sqlConfigStatic.when(SqlConfig::getInstance).thenReturn(sqlConfig);
    }

    @AfterEach
    void tearDown() {
        sqlConfigStatic.close();
    }

    @Test
    void databaseClockValueIsReturnedAsInstant() {
        Instant databaseNow = Instant.parse("2026-08-30T12:34:56Z");
        when(manager.selectOne("Sentinel.getDatabaseTime")).thenReturn(Timestamp.from(databaseNow));

        assertEquals(databaseNow, NodeLeaseRepository.getDatabaseTime());
    }

    @Test
    void takeoverCarriesObservedEpochAndDatabaseDuration() {
        ArgumentCaptor<Map<String, Object>> params = mapCaptor();
        when(manager.update(eq("Sentinel.stealExpiredNodeLease"), params.capture())).thenReturn(1);
        assertTrue(NodeLeaseRepository.stealExpiredNodeLease(
                "sentinel-engine", "node-b", 17L, 90));
        assertEquals(17L, params.getValue().get("expectedLeaseEpoch"));
        assertEquals(90, params.getValue().get("leaseSeconds"));
        assertFalse(params.getValue().containsKey("acquiredTime"));
        assertFalse(params.getValue().containsKey("expiresTime"));
    }

    @Test
    void transactionFenceBindsTheExactClaimAndFailsClosedWhenMissing() {
        SqlSession session = mock(SqlSession.class);
        LeaseFence fence = new LeaseFence("sentinel-engine", "node-a", 23L);
        ArgumentCaptor<Map<String, Object>> params = mapCaptor();
        when(session.selectOne(eq("Sentinel.lockNodeLeaseFence"), params.capture()))
                .thenReturn(null);

        assertFalse(NodeLeaseRepository.lockFence(session, fence));
        assertEquals("sentinel-engine", params.getValue().get("leaseName"));
        assertEquals("node-a", params.getValue().get("nodeId"));
        assertEquals(23L, params.getValue().get("leaseEpoch"));
        verify(session).selectOne(eq("Sentinel.lockNodeLeaseFence"), eq(params.getValue()));
    }

    @Test
    void unmanagedFenceDoesNotTouchTheDatabase() {
        SqlSession session = mock(SqlSession.class);

        assertTrue(NodeLeaseRepository.lockFence(session, LeaseFence.unmanaged()));
        Mockito.verifyNoInteractions(session);
    }

    @Test
    void acquiredLockIsFollowedByAnIndependentExpiryCheck() {
        SqlSession session = mock(SqlSession.class);
        LeaseFence fence = new LeaseFence("sentinel-engine", "node-a", 23L);
        Map<String, Object> params = Map.of("leaseName", "sentinel-engine", "nodeId", "node-a", "leaseEpoch", 23L);
        when(session.selectOne("Sentinel.lockNodeLeaseFence", params)).thenReturn(23L);
        when(session.selectOne("Sentinel.checkNodeLeaseFence", params)).thenReturn(null);

        assertThrows(RepositoryException.class, () -> NodeLeaseRepository.requireFence(session, fence));

        org.mockito.InOrder calls = Mockito.inOrder(session);
        calls.verify(session).selectOne("Sentinel.lockNodeLeaseFence", params);
        calls.verify(session).selectOne("Sentinel.checkNodeLeaseFence", params);
    }

    @Test
    void nullFenceDoesNotAuthorizeWork() {
        SqlSession session = mock(SqlSession.class);
        assertThrows(RepositoryException.class, () -> NodeLeaseRepository.requireFence(session, null));
        Mockito.verifyNoInteractions(session);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
