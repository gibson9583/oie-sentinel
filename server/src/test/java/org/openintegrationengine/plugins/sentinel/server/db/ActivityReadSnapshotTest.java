/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.server.util.SqlConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ActivityReadSnapshotTest {
    private SqlConfig engine;
    private SqlSessionManager sessions;
    private Connection connection;
    private HikariDataSource pool;
    private MockedStatic<SqlConfig> config;

    @BeforeEach
    void setup() throws SQLException {
        sessions = mock(SqlSessionManager.class);
        connection = mock(Connection.class);
        pool = mock(HikariDataSource.class);
        when(connection.unwrap(Connection.class)).thenReturn(mock(Connection.class));
        when(sessions.getConfiguration()).thenReturn(new Configuration(
                new Environment("snapshot-test", new JdbcTransactionFactory(), pool)));
        when(sessions.getConnection()).thenReturn(connection);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
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
    void commitsSerializableReadAndRestoresOriginalIsolationBeforeReturningItsResult() throws SQLException {
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        assertEquals("measured", ActivityRepository.readActivitySnapshot(() -> {
            try {
                verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            } catch (SQLException error) {
                throw new AssertionError(error);
            }
            verify(sessions, never()).commit(true);
            return "measured";
        }));
        var ordered = inOrder(sessions, connection);
        ordered.verify(sessions).startManagedSession(false);
        ordered.verify(sessions).getConnection();
        ordered.verify(connection).getTransactionIsolation();
        ordered.verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        ordered.verify(sessions).commit(true);
        ordered.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        ordered.verify(sessions).close();
        verify(sessions, never()).rollback(true);
        verify(connection, never()).abort(any());
    }

    @Test
    void rejectsAnExistingManagedTransactionWithoutClosingItsOwnersSession() {
        when(sessions.isManagedSessionStarted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> ActivityRepository.readActivitySnapshot(() -> fail("must not read")));
        verify(sessions, never()).startManagedSession(false);
        verify(sessions, never()).close();
        verifyNoInteractions(connection);
    }

    @Test
    void sessionStartFailureLeavesNoOwnedSessionAndAllowsTheNextEvaluation() {
        SqlSessionFactory factory = mock(SqlSessionFactory.class);
        SqlSession session = mock(SqlSession.class);
        when(session.getConnection()).thenReturn(connection);
        IllegalStateException failure = new IllegalStateException("session could not open");
        when(factory.openSession(false)).thenThrow(failure).thenReturn(session);
        SqlSessionManager manager = SqlSessionManager.newInstance(factory);
        when(engine.getSqlSessionManager()).thenReturn(manager);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> fail("must not read"))));
        assertFalse(manager.isManagedSessionStarted());
        verify(session, never()).close();
        verifyNoInteractions(connection);

        assertEquals("retry", ActivityRepository.readActivitySnapshot(() -> "retry"));
        assertFalse(manager.isManagedSessionStarted());
        verify(session).commit(true);
        verify(session).close();
        verify(session, never()).rollback(anyBoolean());
    }

    @Test
    void connectionFailureClosesTheOwnedSessionWithoutPublishingAResult() {
        IllegalStateException failure = new IllegalStateException("pool exhausted");
        when(sessions.getConnection()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> fail("must not read"))));
        verify(sessions).rollback(true);
        verify(sessions).close();
        verifyNoInteractions(connection);
    }

    @Test
    void originalIsolationFailureDoesNotGuessAReplacementLevel() throws SQLException {
        SQLException failure = new SQLException("cannot read isolation");
        when(connection.getTransactionIsolation()).thenThrow(failure);
        assertSame(failure, assertThrows(RepositoryException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> fail("must not read"))).getCause());
        verify(sessions).rollback(true);
        verify(sessions).close();
        verify(connection, never()).setTransactionIsolation(anyInt());
    }

    @Test
    void settingSerializableFailureStillRollsBackAndRestoresTheOriginalLevel() throws SQLException {
        SQLException failure = new SQLException("cannot set isolation");
        doThrow(failure).when(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertSame(failure, assertThrows(RepositoryException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> fail("must not read"))).getCause());
        var ordered = inOrder(sessions, connection);
        ordered.verify(sessions).rollback(true);
        ordered.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        ordered.verify(sessions).close();
        verify(connection, never()).abort(any());
    }

    @Test
    void failedReadRollsBackBeforeRestoringIsolationAndClosing() throws SQLException {
        IllegalStateException failure = new IllegalStateException("read failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> { throw failure; })));
        var ordered = inOrder(sessions, connection);
        ordered.verify(sessions).rollback(true);
        ordered.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        ordered.verify(sessions).close();
        verify(sessions, never()).commit(true);
        verify(connection, never()).abort(any());
    }

    @Test
    void serializationFailureAtCommitCannotReturnAConclusiveVerdict() throws SQLException {
        IllegalStateException failure = new IllegalStateException("serialization failure");
        doThrow(failure).when(sessions).commit(true);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> "OK")));
        var ordered = inOrder(sessions, connection);
        ordered.verify(sessions).rollback(true);
        ordered.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        ordered.verify(sessions).close();
        verify(connection, never()).abort(any());
    }

    @Test
    void failedRollbackAbortsWithoutAnIsolationChangeThatMightImplicitlyCommit() throws SQLException {
        IllegalStateException read = new IllegalStateException("read failed");
        IllegalStateException rollback = new IllegalStateException("rollback failed");
        IllegalStateException close = new IllegalStateException("close failed");
        doThrow(rollback).when(sessions).rollback(true);
        doThrow(close).when(sessions).close();
        assertSame(read, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> { throw read; })));
        assertArrayEquals(new Throwable[] {rollback, close}, read.getSuppressed());
        var ordered = inOrder(sessions, connection);
        ordered.verify(sessions).rollback(true);
        ordered.verify(connection).abort(any());
        ordered.verify(sessions).close();
        verify(connection, never()).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    void failedIsolationRestoreDiscardsTheConnectionAndRejectsTheReadResult() throws SQLException {
        SQLException reset = new SQLException("restore failed");
        doThrow(reset).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertSame(reset, assertThrows(RepositoryException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> "OK")).getCause());
        var ordered = inOrder(sessions, connection);
        ordered.verify(sessions).commit(true);
        ordered.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        ordered.verify(connection).abort(any());
        ordered.verify(sessions).close();
    }

    @Test
    void cleanupFailuresDoNotHideTheOriginalReadFailure() throws SQLException {
        IllegalStateException read = new IllegalStateException("read failed");
        SQLException reset = new SQLException("reset failed");
        SQLException abort = new SQLException("abort failed");
        IllegalStateException close = new IllegalStateException("close failed");
        doThrow(reset).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        doThrow(abort).when(connection).abort(any());
        doThrow(close).when(sessions).close();
        assertSame(read, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> { throw read; })));
        assertArrayEquals(new Throwable[] {reset, abort, close}, read.getSuppressed());
        verify(pool).evictConnection(connection);
    }

    @Test
    void readErrorAlsoRollsBackRestoresAndCloses() throws SQLException {
        AssertionError failure = new AssertionError("read error");
        assertSame(failure, assertThrows(AssertionError.class,
                () -> ActivityRepository.readActivitySnapshot(() -> { throw failure; })));
        verify(sessions).rollback(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(sessions).close();
    }

    @Test
    void closeFailurePreventsUsingTheReadResult() throws SQLException {
        IllegalStateException failure = new IllegalStateException("close failed");
        doThrow(failure).when(sessions).close();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ActivityRepository.readActivitySnapshot(() -> "OK")));
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }
}
