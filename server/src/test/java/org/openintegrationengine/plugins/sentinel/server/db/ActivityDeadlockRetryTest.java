/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.mirth.connect.server.util.SqlConfig;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;

/** Mocked transaction boundaries only; this class never starts a database. */
class ActivityDeadlockRetryTest {
    @Test void restartsTheWholeSnapshotAfterClosingTheFailedSession() throws Exception {
        SqlSessionFactory factory = mock(SqlSessionFactory.class);
        SqlSession first = mock(SqlSession.class), second = mock(SqlSession.class);
        Connection one = connection(), two = connection();
        when(first.getConnection()).thenReturn(one);
        when(second.getConnection()).thenReturn(two);
        when(factory.openSession(false)).thenReturn(first, second);
        var configuration = DeadlockRetryTest.sessions("derby").getConfiguration();
        when(factory.getConfiguration()).thenReturn(configuration);
        SqlSessionManager sessions = SqlSessionManager.newInstance(factory);
        SqlConfig engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(sessions);
        AtomicInteger aggregates = new AtomicInteger(), coverage = new AtomicInteger(), baselines = new AtomicInteger();
        try (var config = mockStatic(SqlConfig.class)) {
            config.when(SqlConfig::getInstance).thenReturn(engine);
            String result = ActivityRepository.readActivitySnapshot(() -> {
                aggregates.incrementAndGet();
                if (coverage.incrementAndGet() == 1) throw DeadlockRetryTest.failure();
                baselines.incrementAndGet();
                return "fresh outcome";
            });
            assertEquals("fresh outcome", result);
        }
        assertEquals(2, aggregates.get()); assertEquals(2, coverage.get()); assertEquals(1, baselines.get());
        var order = inOrder(first, second, one, two);
        order.verify(first).rollback(true);
        order.verify(one).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(first).close();
        order.verify(second).getConnection();
        order.verify(two).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        order.verify(second).commit(true);
        order.verify(two).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(second).close();
        assertFalse(sessions.isManagedSessionStarted());
    }

    @Test void genericReadTransactionDoesNotOptDispatchIntoRetry() throws Exception {
        SqlSessionManager sessions = DeadlockRetryTest.sessions("derby");
        Connection connection = connection();
        when(sessions.getConnection()).thenReturn(connection);
        AtomicInteger calls = new AtomicInteger();
        withEngine(sessions, () -> assertThrows(RuntimeException.class, () ->
                ReadTransaction.execute(Connection.TRANSACTION_READ_COMMITTED, () -> {
                    calls.incrementAndGet(); throw DeadlockRetryTest.failure();
                })));
        assertEquals(1, calls.get());
        verify(sessions).startManagedSession(false);
    }

    @Test void snapshotCommitDeadlockDiscardsTheEarlierResultAndRereads() throws Exception {
        SqlSessionManager sessions = DeadlockRetryTest.sessions("derby");
        Connection connection = connection();
        when(sessions.getConnection()).thenReturn(connection);
        doThrow(DeadlockRetryTest.failure()).doNothing().when(sessions).commit(true);
        AtomicInteger reads = new AtomicInteger();
        withEngine(sessions, () -> assertEquals(2,
                ActivityRepository.readActivitySnapshot(reads::incrementAndGet)));
        assertEquals(2, reads.get());
        verify(sessions).rollback(true);
        verify(sessions, times(2)).close();
    }

    @Test void snapshotCleanupFailuresAndSuccessfulCommitNeverReplay() throws Exception {
        for (String fault : List.of("rollback", "reset", "close", "committed-close")) {
            SqlSessionManager sessions = DeadlockRetryTest.sessions("derby");
            Connection connection = connection();
            when(sessions.getConnection()).thenReturn(connection);
            RuntimeException deadlock = DeadlockRetryTest.failure();
            RuntimeException cleanup = DeadlockRetryTest.failure();
            if (fault.equals("rollback")) doThrow(cleanup).when(sessions).rollback(true);
            if (fault.equals("reset")) doThrow(new SQLException("reset failed", "40001"))
                    .when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            if (fault.endsWith("close")) doThrow(cleanup).when(sessions).close();
            AtomicInteger calls = new AtomicInteger();
            withEngine(sessions, () -> assertThrows(RuntimeException.class, () ->
                    ActivityRepository.readActivitySnapshot(() -> {
                        calls.incrementAndGet();
                        if (!fault.equals("committed-close")) throw deadlock;
                        return "must not publish";
                    })));
            assertEquals(1, calls.get(), fault);
            verify(sessions).startManagedSession(false);
            if (fault.equals("rollback") || fault.equals("reset")) verify(connection).abort(any());
        }
    }

    @Test void collectorReplaysFrozenRowsAndOriginalFenceOnlyAfterRollbackAndClose() throws Exception {
        SqlSessionManager sessions = DeadlockRetryTest.sessions("derby");
        SqlSession first = mock(SqlSession.class), second = mock(SqlSession.class);
        Connection one = connection(), two = connection();
        when(first.getConnection()).thenReturn(one); when(second.getConnection()).thenReturn(two);
        when(sessions.openSession(ExecutorType.BATCH, false)).thenReturn(first, second);
        ActivitySample sample = sample();
        doAnswer(invocation -> { sample.setReceivedDelta(999); throw DeadlockRetryTest.failure(); })
                .when(first).commit();
        LeaseFence fence = new LeaseFence("leader", "node", 7L);
        try (var fences = mockStatic(NodeLeaseRepository.class)) {
            fences.when(() -> NodeLeaseRepository.lockFence(first, fence)).thenReturn(true);
            fences.when(() -> NodeLeaseRepository.lockFence(second, fence)).thenReturn(true);
            withEngine(sessions, () -> ActivityRepository.insertActivitySamples(List.of(sample), fence));
            fences.verify(() -> NodeLeaseRepository.requireFence(first, fence));
            fences.verify(() -> NodeLeaseRepository.requireFence(second, fence));
        }
        ArgumentCaptor<Map<String, Object>> rows = ArgumentCaptor.forClass(Map.class);
        verify(first).insert(eq("Sentinel.insertActivitySample"), rows.capture());
        verify(second).insert(eq("Sentinel.insertActivitySample"), rows.capture());
        assertEquals(4L, rows.getAllValues().get(0).get("received_delta"));
        assertEquals(rows.getAllValues().get(0), rows.getAllValues().get(1));
        var order = inOrder(sessions, first, second);
        order.verify(first).rollback(true);
        order.verify(first).close();
        order.verify(sessions).openSession(ExecutorType.BATCH, false);
        order.verify(second).commit();
        order.verify(second).close();
    }

    @Test void collectorDoesNotReplayAmbiguousCommitOrCleanupFailure() throws Exception {
        for (String fault : List.of("ambiguous", "rollback", "close", "committed-close")) {
            SqlSessionManager sessions = DeadlockRetryTest.sessions("derby");
            SqlSession session = mock(SqlSession.class);
            Connection connection = connection();
            when(session.getConnection()).thenReturn(connection);
            when(sessions.openSession(ExecutorType.BATCH, false)).thenReturn(session);
            if (!fault.equals("committed-close")) doThrow(fault.equals("ambiguous")
                    ? new RepositoryException(new SQLException("lost commit reply", "08006"))
                    : DeadlockRetryTest.failure()).when(session).commit();
            if (fault.equals("rollback")) doThrow(new RuntimeException("rollback failed")).when(session).rollback(true);
            if (fault.endsWith("close")) doThrow(DeadlockRetryTest.failure()).when(session).close();
            withEngine(sessions, () -> assertThrows(RepositoryException.class,
                    () -> ActivityRepository.insertActivitySamples(List.of(sample()))));
            verify(sessions).openSession(ExecutorType.BATCH, false);
            if (fault.equals("rollback")) {
                var order = inOrder(session, connection);
                order.verify(session).rollback(true);
                order.verify(connection).abort(any());
                order.verify(session).close();
            }
            if (fault.equals("committed-close")) verify(session, never()).rollback(anyBoolean());
        }
    }

    @Test void collectorStopsWhenOriginalFenceIsLostBetweenAttempts() throws Exception {
        SqlSessionManager sessions = DeadlockRetryTest.sessions("derby");
        SqlSession first = mock(SqlSession.class), second = mock(SqlSession.class);
        Connection one = connection(), two = connection();
        when(first.getConnection()).thenReturn(one); when(second.getConnection()).thenReturn(two);
        when(sessions.openSession(ExecutorType.BATCH, false)).thenReturn(first, second);
        doThrow(DeadlockRetryTest.failure()).when(first).commit();
        LeaseFence fence = new LeaseFence("leader", "node", 7L);
        try (var fences = mockStatic(NodeLeaseRepository.class)) {
            fences.when(() -> NodeLeaseRepository.lockFence(first, fence)).thenReturn(true);
            fences.when(() -> NodeLeaseRepository.lockFence(second, fence)).thenReturn(false);
            withEngine(sessions, () -> assertThrows(RepositoryException.class,
                    () -> ActivityRepository.insertActivitySamples(List.of(sample()), fence)));
        }
        verify(second, never()).insert(anyString(), any());
        verify(second, never()).commit();
        verify(second).rollback(true);
        verify(second).close();
        verify(sessions, times(2)).openSession(ExecutorType.BATCH, false);
    }

    @Test void collectorRetriesDeadlockDuringFenceSelectBatchFlush() throws Exception {
        SqlSessionManager sessions = DeadlockRetryTest.sessions("mysql");
        SqlSession first = mock(SqlSession.class), second = mock(SqlSession.class);
        Connection one = connection(), two = connection();
        when(first.getConnection()).thenReturn(one); when(second.getConnection()).thenReturn(two);
        when(sessions.openSession(ExecutorType.BATCH, false)).thenReturn(first, second);
        LeaseFence fence = new LeaseFence("leader", "node", 7L);
        var batch = new java.sql.BatchUpdateException("batch flush", null, 0, new int[]{1});
        batch.setNextException(new SQLException("victim", "40001", 1213));
        try (var fences = mockStatic(NodeLeaseRepository.class)) {
            fences.when(() -> NodeLeaseRepository.lockFence(first, fence)).thenReturn(true);
            fences.when(() -> NodeLeaseRepository.lockFence(second, fence)).thenAnswer(call -> {
                verify(first).rollback(true);
                verify(first).close();
                return true;
            });
            fences.when(() -> NodeLeaseRepository.requireFence(first, fence))
                    .thenThrow(new RepositoryException(batch));
            withEngine(sessions, () -> ActivityRepository.insertActivitySamples(List.of(sample()), fence));
            fences.verify(() -> NodeLeaseRepository.requireFence(second, fence));
        }
        verify(first, never()).commit();
        verify(second).insert(eq("Sentinel.insertActivitySample"), any());
        verify(second).commit();
    }

    @Test void rollbackAndAbortFailureDiscardsTheConnectionBeforeClosingSession() throws Exception {
        SqlSessionManager sessions = DeadlockRetryTest.sessions("derby");
        var pool = mock(com.zaxxer.hikari.HikariDataSource.class);
        sessions.getConfiguration().setEnvironment(new org.apache.ibatis.mapping.Environment("test",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), pool));
        SqlSession session = mock(SqlSession.class);
        Connection connection = connection(), physical = connection();
        when(connection.unwrap(Connection.class)).thenReturn(physical);
        when(session.getConnection()).thenReturn(connection);
        when(sessions.openSession(ExecutorType.BATCH, false)).thenReturn(session);
        RuntimeException original = DeadlockRetryTest.failure();
        doThrow(original).when(session).commit();
        doThrow(original).when(session).rollback(true);
        doThrow(new SQLException("abort failed")).when(connection).abort(any());
        withEngine(sessions, () -> assertSame(original, assertThrows(RepositoryException.class,
                () -> ActivityRepository.insertActivitySamples(List.of(sample()))).getCause()));
        var order = inOrder(session, connection, physical, pool);
        order.verify(session).rollback(true);
        order.verify(connection).abort(any());
        order.verify(physical).rollback();
        order.verify(pool).evictConnection(connection);
        order.verify(session).close();
        verify(sessions).openSession(ExecutorType.BATCH, false);
    }

    private static Connection connection() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }

    private static ActivitySample sample() {
        ActivitySample sample = new ActivitySample();
        sample.setChannelId("channel"); sample.setSampleTime(Instant.parse("2026-10-15T10:00:00Z"));
        sample.setReceivedDelta(4);
        return sample;
    }

    private static void withEngine(SqlSessionManager sessions, Runnable work) {
        SqlConfig engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(sessions);
        try (var config = mockStatic(SqlConfig.class)) {
            config.when(SqlConfig::getInstance).thenReturn(engine);
            work.run();
        }
    }
}
