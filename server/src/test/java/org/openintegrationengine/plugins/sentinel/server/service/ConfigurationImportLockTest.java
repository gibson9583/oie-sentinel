package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionManager;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.shared.model.NodeLease;

class ConfigurationImportLockTest {
    @Test void activeOwnerPreventsCallbackAndDoesNotReleaseItsLease() {
        try (var leases=mockStatic(NodeLeaseRepository.class); var sql=mockStatic(SqlConfig.class)) {
            NodeLease current=new NodeLease(); current.setLeaseEpoch(9L); current.setNodeId("another-import");
            leases.when(() -> NodeLeaseRepository.getNodeLease("sentinel-config-import")).thenReturn(current);
            AtomicInteger calls=new AtomicInteger();
            assertThrows(IllegalArgumentException.class,()->ConfigurationImportLock.withLock(()->calls.incrementAndGet()));
            assertEquals(0,calls.get()); sql.verifyNoInteractions();
            leases.verify(() -> NodeLeaseRepository.releaseNodeLease(anyString(),anyString(),anyLong()), never());
        }
    }
    @Test void separateRowLockSurroundsCallbackAndReleasesOnFailure() {
        try (var leases=mockStatic(NodeLeaseRepository.class); var sql=mockStatic(SqlConfig.class)) {
            NodeLease[] owned={null};
            leases.when(() -> NodeLeaseRepository.insertNodeLease(any(),eq(60))).thenAnswer(call -> { owned[0]=call.getArgument(0); return true; });
            leases.when(() -> NodeLeaseRepository.getNodeLease("sentinel-config-import")).thenAnswer(call -> owned[0]);
            SqlConfig config=mock(SqlConfig.class); SqlSessionManager manager=mock(SqlSessionManager.class); SqlSession session=mock(SqlSession.class);
            sql.when(SqlConfig::getInstance).thenReturn(config); when(config.getSqlSessionManager()).thenReturn(manager); when(manager.openSession(false)).thenReturn(session);
            boolean[] fenced={false};
            leases.when(() -> NodeLeaseRepository.requireFence(eq(session),any())).thenAnswer(call -> { fenced[0]=true; return null; });
            RuntimeException failure=new RuntimeException("failed import");
            assertSame(failure,assertThrows(RuntimeException.class,()->ConfigurationImportLock.withLock(()->{assertTrue(fenced[0]);verify(session,never()).close();throw failure;})));
            verify(session).close();
            leases.verify(() -> NodeLeaseRepository.releaseNodeLease("sentinel-config-import",owned[0].getNodeId(),1L));
        }
    }
    @Test void expiredFenceCannotExecuteAndStillReleasesOnlyCapturedEpoch() {
        try (var leases=mockStatic(NodeLeaseRepository.class); var sql=mockStatic(SqlConfig.class)) {
            NodeLease[] owned={null};
            leases.when(() -> NodeLeaseRepository.insertNodeLease(any(),eq(60))).thenAnswer(call -> { owned[0]=call.getArgument(0); return true; });
            leases.when(() -> NodeLeaseRepository.getNodeLease("sentinel-config-import")).thenAnswer(call -> owned[0]);
            SqlConfig config=mock(SqlConfig.class); SqlSessionManager manager=mock(SqlSessionManager.class); SqlSession session=mock(SqlSession.class);
            sql.when(SqlConfig::getInstance).thenReturn(config); when(config.getSqlSessionManager()).thenReturn(manager); when(manager.openSession(false)).thenReturn(session);
            leases.when(() -> NodeLeaseRepository.requireFence(eq(session),any())).thenThrow(new RuntimeException("expired fence"));
            AtomicInteger calls=new AtomicInteger(); assertThrows(RuntimeException.class,()->ConfigurationImportLock.withLock(calls::incrementAndGet));assertEquals(0,calls.get());
            verify(session).close(); leases.verify(() -> NodeLeaseRepository.releaseNodeLease("sentinel-config-import",owned[0].getNodeId(),1L));
        }
    }
}
