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
}
