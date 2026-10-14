/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.server.util.SqlConfig;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ActivityReadSnapshotTest {
    private SqlConfig engine;
    private SqlSessionManager sessions;
    private MockedStatic<SqlConfig> config;

    @BeforeEach
    void setup() {
        sessions = mock(SqlSessionManager.class);
        engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(sessions);
        config = mockStatic(SqlConfig.class);
        config.when(SqlConfig::getInstance).thenReturn(engine);
    }

    @AfterEach
    void cleanup() {
        config.close();
    }

    @Test
    void commitsSerializableReadAndClosesBeforeReturningItsResult() {
        assertEquals("measured", ActivityRepository.readActivitySnapshot(() -> {
            verify(sessions).startManagedSession(TransactionIsolationLevel.SERIALIZABLE);
            verify(sessions, never()).commit(true);
            return "measured";
        }));
        var ordered = inOrder(sessions);
        ordered.verify(sessions).startManagedSession(TransactionIsolationLevel.SERIALIZABLE);
        ordered.verify(sessions).commit(true);
        ordered.verify(sessions).close();
        verify(sessions, never()).rollback(true);
    }

    @Test
    void rejectsAnExistingManagedTransactionWithoutClosingItsOwnersSession() {
        when(sessions.isManagedSessionStarted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> ActivityRepository.readActivitySnapshot(() -> fail("must not read")));
        verify(sessions, never()).startManagedSession(any(TransactionIsolationLevel.class));
        verify(sessions, never()).close();
    }

    @Test
    void sessionStartFailureLeavesNoOwnedSessionAndAllowsTheNextEvaluation() {
        SqlSessionFactory factory = mock(SqlSessionFactory.class);
        SqlSession session = mock(SqlSession.class);
        IllegalStateException failure = new IllegalStateException("session could not open");
        when(factory.openSession(TransactionIsolationLevel.SERIALIZABLE))
                .thenThrow(failure).thenReturn(session);
        SqlSessionManager manager = SqlSessionManager.newInstance(factory);
        when(engine.getSqlSessionManager()).thenReturn(manager);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> fail("must not read"))));
        assertFalse(manager.isManagedSessionStarted());
        verifyNoInteractions(session);

        assertEquals("retry", ActivityRepository.readActivitySnapshot(() -> "retry"));
        assertFalse(manager.isManagedSessionStarted());
        verify(session).commit(true);
        verify(session).close();
        verify(session, never()).rollback(anyBoolean());
    }

    @Test
    void failedReadRollsBackAndClosesWithoutPublishingAResult() {
        IllegalStateException failure = new IllegalStateException("read failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> { throw failure; })));
        var ordered = inOrder(sessions);
        ordered.verify(sessions).rollback(true);
        ordered.verify(sessions).close();
        verify(sessions, never()).commit(true);
    }

    @Test
    void serializationFailureAtCommitCannotReturnAConclusiveVerdict() {
        IllegalStateException failure = new IllegalStateException("serialization failure");
        doThrow(failure).when(sessions).commit(true);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> "OK")));
        verify(sessions).rollback(true);
        verify(sessions).close();
    }

    @Test
    void cleanupFailuresDoNotHideTheOriginalReadFailure() {
        IllegalStateException read = new IllegalStateException("read failed");
        IllegalStateException rollback = new IllegalStateException("rollback failed");
        IllegalStateException close = new IllegalStateException("close failed");
        doThrow(rollback).when(sessions).rollback(true);
        doThrow(close).when(sessions).close();
        assertSame(read, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> { throw read; })));
        assertArrayEquals(new Throwable[] {rollback, close}, read.getSuppressed());
    }

    @Test
    void closeFailurePreventsUsingTheReadResult() {
        IllegalStateException failure = new IllegalStateException("close failed");
        doThrow(failure).when(sessions).close();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> "OK")));
    }
}
