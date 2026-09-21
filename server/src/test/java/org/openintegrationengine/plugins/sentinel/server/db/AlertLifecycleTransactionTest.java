/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.Test;

class AlertLifecycleTransactionTest {
    @Test
    void commitFailureRollsBackClosesAndNeverDispatches() {
        try (var config = mockStatic(SqlConfig.class)) {
            SqlConfig engine = mock(SqlConfig.class);
            SqlSessionManager sessions = mock(SqlSessionManager.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
            RuntimeException failure = new RuntimeException("commit failed");
            doThrow(failure).when(sessions).commit();
            Runnable delivery = mock(Runnable.class);
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> AlertLifecycleTransaction.execute(after -> after.add(delivery))));
            var order = inOrder(sessions);
            order.verify(sessions).startManagedSession(false);
            order.verify(sessions).commit();
            order.verify(sessions).rollback();
            order.verify(sessions).close();
            verifyNoInteractions(delivery);
        }
    }

    @Test
    void cleanupFailuresPreserveOriginalFailure() {
        try (var config = mockStatic(SqlConfig.class)) {
            SqlConfig engine = mock(SqlConfig.class);
            SqlSessionManager sessions = mock(SqlSessionManager.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
            RuntimeException original = new RuntimeException("write failed");
            doThrow(new RuntimeException("rollback failed")).when(sessions).rollback();
            doThrow(new RuntimeException("close failed")).when(sessions).close();
            assertSame(original, assertThrows(RuntimeException.class,
                    () -> AlertLifecycleTransaction.execute(after -> { throw original; })));
            assertEquals(2, original.getSuppressed().length);
            verify(sessions, never()).commit();
        }
    }

    @Test
    void refusesNestedTransactionWithoutTouchingItsOwner() {
        try (var config = mockStatic(SqlConfig.class)) {
            SqlConfig engine = mock(SqlConfig.class);
            SqlSessionManager sessions = mock(SqlSessionManager.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
            when(sessions.isManagedSessionStarted()).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> AlertLifecycleTransaction.execute(after -> fail()));
            verify(sessions, never()).startManagedSession(false);
            verify(sessions, never()).commit();
            verify(sessions, never()).rollback();
            verify(sessions, never()).close();
        }
    }

    @Test
    void staleFenceCannotStartLifecycleWrites() {
        LeaseFence fence = new LeaseFence("sentinel", "former-leader", 2L);
        try (var config = mockStatic(SqlConfig.class); var leases = mockStatic(NodeLeaseRepository.class)) {
            SqlConfig engine = mock(SqlConfig.class);
            SqlSessionManager sessions = mock(SqlSessionManager.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
            RuntimeException expired = new RuntimeException("stale lease");
            leases.when(() -> NodeLeaseRepository.requireFence(sessions, fence)).thenThrow(expired);
            assertSame(expired, assertThrows(RuntimeException.class,
                    () -> AlertLifecycleTransaction.execute(fence, after -> fail("stale owner wrote"))));
            verify(sessions).rollback();
            verify(sessions, never()).commit();
            verify(sessions).close();
        }
    }

    @Test
    void leaseExpiryDuringWorkRollsBackAndNeverDispatches() {
        LeaseFence fence = new LeaseFence("sentinel", "leader", 2L);
        try (var config = mockStatic(SqlConfig.class); var leases = mockStatic(NodeLeaseRepository.class)) {
            SqlConfig engine = mock(SqlConfig.class);
            SqlSessionManager sessions = mock(SqlSessionManager.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
            java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
            RuntimeException expired = new RuntimeException("lease expired while paused");
            leases.when(() -> NodeLeaseRepository.requireFence(sessions, fence)).thenAnswer(call -> {
                if (checks.incrementAndGet() == 2) throw expired;
                return null;
            });
            Runnable delivery = mock(Runnable.class);
            assertSame(expired, assertThrows(RuntimeException.class,
                    () -> AlertLifecycleTransaction.execute(fence, after -> after.add(delivery))));
            assertEquals(2, checks.get());
            verify(sessions).rollback();
            verify(sessions, never()).commit();
            verifyNoInteractions(delivery);
        }
    }
}
