/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.donkey.server.data.jdbc.DBCPConnectionPool;
import com.mirth.connect.donkey.server.data.jdbc.HikariConnectionPool;
import com.mirth.connect.server.util.SqlConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.DelegatingConnection;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.apache.commons.dbcp2.PoolableConnection;
import org.apache.commons.dbcp2.PoolableConnectionFactory;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/** Uses the engine's pool constructors and pinned pool/MyBatis/Derby versions. */
class ActivityReadSnapshotPoolTest {
    private enum Failure { NONE, READ, COMMIT, RESET, ROLLBACK, RESET_ABORT, ROLLBACK_ABORT }

    static Stream<Arguments> poolFailures() {
        return Stream.of(false, true).flatMap(hikari -> Stream.of(false, true).flatMap(autoCommit ->
                Stream.of(Failure.values()).map(failure -> Arguments.of(hikari, autoCommit, failure))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void controlShowsTheEngineDbcpPoolDoesNotRestoreIsolationForMyBatis(boolean hikari) throws Exception {
        try (Pool fixture = new Pool(hikari, 1, Failure.NONE)) {
            fixture.sessions.startManagedSession(TransactionIsolationLevel.SERIALIZABLE);
            assertEquals(Connection.TRANSACTION_SERIALIZABLE,
                    fixture.sessions.getConnection().getTransactionIsolation());
            fixture.sessions.commit(true);
            fixture.sessions.close();
            try (Connection next = fixture.source.getConnection()) {
                assertEquals(hikari ? Connection.TRANSACTION_READ_COMMITTED : Connection.TRANSACTION_SERIALIZABLE,
                        next.getTransactionIsolation());
                // Clean up even this intentionally contaminated negative control.
                next.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("poolFailures")
    void snapshotRestoresOrDiscardsBeforeTheNextEngineBorrow(boolean hikari, boolean autoCommit, Failure failure) throws Exception {
        try (Pool fixture = new Pool(hikari, 1, failure, autoCommit)) {
            if (failure == Failure.NONE) {
                assertEquals(1, ActivityRepository.readActivitySnapshot(() -> fixture.readCount()));
            } else {
                RuntimeException rejected = assertThrows(RuntimeException.class,
                        () -> ActivityRepository.readActivitySnapshot(() -> fixture.readCount()));
                if (failure == Failure.READ || failure == Failure.ROLLBACK || failure == Failure.ROLLBACK_ABORT) {
                    assertSame(fixture.readFailure, rejected);
                }
            }
            assertFalse(fixture.sessions.isManagedSessionStarted(), "thread-local session must be cleared");
            Connection usedPhysical = fixture.lastPhysical;
            boolean discarded = failure == Failure.RESET || failure == Failure.ROLLBACK
                    || failure == Failure.RESET_ABORT || failure == Failure.ROLLBACK_ABORT;
            if (discarded) {
                for (int wait = 0; !usedPhysical.isClosed() && wait < 100; wait++) Thread.sleep(10);
            }
            assertEquals(discarded, usedPhysical.isClosed());
            fixture.injectFaults = false;
            try (Connection next = fixture.source.getConnection()) {
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, next.getTransactionIsolation());
                assertEquals(autoCommit, next.getAutoCommit(), "pool transaction default must survive too");
                assertEquals(discarded, usedPhysical != physical(next), "poisoned connections must be replaced");
                assertEquals(1, count(next));
                next.commit();
                System.out.println("snapshot-pool=" + fixture.poolName + ", fault=" + failure
                        + ", inside=SERIALIZABLE, next=READ_COMMITTED, physical-replaced=" + discarded);
            }

        }
    }

    static Stream<Arguments> dbcpCleanupFailures() {
        return Stream.of(false, true).flatMap(autoCommit ->
                Stream.of(Failure.RESET_ABORT, Failure.ROLLBACK_ABORT)
                        .map(failure -> Arguments.of(autoCommit, failure)));
    }

    @ParameterizedTest
    @MethodSource("dbcpCleanupFailures")
    void failedPhysicalCleanupReleasesDbcpPoolSlot(boolean autoCommit, Failure failure) throws Exception {
        try (Pool fixture = new Pool(false, 1, failure, autoCommit)) {
            fixture.refusePhysicalCleanup = true;
            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> ActivityRepository.readActivitySnapshot(fixture::readCount));
            if (failure == Failure.ROLLBACK_ABORT) assertSame(fixture.readFailure, rejected);
            assertTrue(Stream.of(rejected.getSuppressed())
                    .anyMatch(error -> error.getMessage().contains("physical close refused")),
                    "the unreleased physical resource must remain visible in the reported failure");
            assertFalse(fixture.sessions.isManagedSessionStarted());
            Connection failedPhysical = fixture.lastPhysical;
            assertFalse(failedPhysical.isClosed(), "slot release does not mean the driver accepted close");
            var pool = dbcpPool(fixture.source);
            System.out.println("dbcp-cleanup=" + failure + ", autoCommit=" + autoCommit
                    + ", active-after-failure=" + pool.getNumActive() + ", idle=" + pool.getNumIdle()
                    + ", physical-closed=" + failedPhysical.isClosed());
            fixture.injectFaults = false;
            // A bounded borrow exposes the old active-slot leak instead of hanging.
            try (Connection next = fixture.source.getConnection()) {
                assertNotSame(failedPhysical, physical(next));
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, next.getTransactionIsolation());
                assertEquals(autoCommit, next.getAutoCommit());
                assertEquals(1, count(next));
                next.commit();
            }
            assertEquals(0, pool.getNumActive(), "failed snapshot must release its borrowed slot");
            assertEquals(1, pool.getNumIdle(), "the replacement must return normally");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dbcpDestructionFailureStillReleasesTheSlotAndPreservesItsCause(boolean autoCommit) throws Exception {
        try (Pool fixture = new Pool(false, 1, Failure.ROLLBACK_ABORT, autoCommit)) {
            fixture.refusePhysicalCleanup = true;
            fixture.failStatementClose = true;
            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> ActivityRepository.readActivitySnapshot(() -> {
                        try {
                            // Leave a tracked statement for the real pool's
                            // destroyObject/passivate path to close.
                            fixture.sessions.getConnection().createStatement();
                        } catch (SQLException error) {
                            throw new AssertionError(error);
                        }
                        throw fixture.readFailure;
                    }));
            assertSame(fixture.readFailure, rejected);
            assertTrue(Stream.of(rejected.getSuppressed()).anyMatch(error ->
                    error.getMessage().contains("invalidating pooled object failed")
                            && error.getCause().getMessage().contains("statement close refused")));
            assertEquals(0, dbcpPool(fixture.source).getNumActive());
            assertFalse(fixture.sessions.isManagedSessionStarted());
            fixture.injectFaults = false;
            try (Connection next = fixture.source.getConnection()) {
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, next.getTransactionIsolation());
                assertEquals(1, count(next));
                next.commit();
            }
            System.out.println("dbcp-destroy-failure-slot-released, autoCommit=" + autoCommit);
            rejected.printStackTrace(System.out);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedAbortMayHaveAlreadyClosedThePhysicalConnection(boolean autoCommit) throws Exception {
        try (Pool fixture = new Pool(false, 1, Failure.RESET_ABORT, autoCommit)) {
            fixture.closePhysicalOnAbort = true;
            assertThrows(RuntimeException.class, () -> ActivityRepository.readActivitySnapshot(fixture::readCount));
            assertTrue(fixture.lastPhysical.isClosed());
            assertEquals(0, dbcpPool(fixture.source).getNumActive());
            fixture.injectFaults = false;
            try (Connection next = fixture.source.getConnection()) {
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, next.getTransactionIsolation());
                assertEquals(1, count(next));
                next.commit();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void collectorCannotChangeTheReadWindowBetweenQueries(boolean hikari) throws Exception {
        try (Pool fixture = new Pool(hikari, 2, Failure.NONE)) {
            var writer = Executors.newSingleThreadExecutor();
            CountDownLatch attempted = new CountDownLatch(1);
            try {
                assertEquals(1, ActivityRepository.readActivitySnapshot(() -> {
                    assertEquals(1, fixture.readCount());
                    var write = writer.submit(() -> {
                        try (Connection c = fixture.source.getConnection(); var statement = c.createStatement()) {
                            attempted.countDown();
                            statement.executeUpdate("INSERT INTO snapshot_sample VALUES (2)");
                            c.commit();
                        }
                        return null;
                    });
                    try {
                        assertTrue(attempted.await(5, TimeUnit.SECONDS));
                        assertThrows(TimeoutException.class, () -> write.get(200, TimeUnit.MILLISECONDS),
                                "the serializable range read must hold back a concurrent insert");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    return fixture.readCount();
                }));
                writer.shutdown();
                assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS), "writer must resume after snapshot commit");
                try (Connection after = fixture.source.getConnection()) {
                    assertEquals(2, count(after));
                    after.commit();
                }
            } finally {
                writer.shutdownNow();
                assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM snapshot_sample")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static GenericObjectPool<PoolableConnection> dbcpPool(DataSource source) throws Exception {
        var field = PoolingDataSource.class.getDeclaredField("_pool");
        field.setAccessible(true);
        return (GenericObjectPool<PoolableConnection>) field.get(source);
    }

    private static Connection physical(Connection connection) throws SQLException {
        return connection instanceof DelegatingConnection
                ? ((DelegatingConnection<?>) connection).getInnermostDelegate()
                : connection.unwrap(Connection.class);
    }

    private static final class Pool implements AutoCloseable {
        private final String url = "jdbc:derby:memory:sentinelSnapshot" + UUID.randomUUID().toString().replace("-", "");
        private final String poolName;
        private final DataSource source;
        private final SqlSessionManager sessions;
        private final MockedStatic<SqlConfig> config;
        private final Failure failure;
        private final IllegalStateException readFailure = new IllegalStateException("injected read failure");
        private Connection lastPhysical;
        private boolean injectFaults = true;
        private boolean refusePhysicalCleanup;
        private boolean closePhysicalOnAbort;
        private boolean failStatementClose;
        private final List<Statement> ownedStatements = new ArrayList<>();
        private final List<Connection> ownedPhysicalConnections = new ArrayList<>();

        private Pool(boolean hikari, int size, Failure failure) throws Exception {
            this(hikari, size, failure, false);
        }

        private Pool(boolean hikari, int size, Failure failure, boolean autoCommit) throws Exception {
            this.failure = failure;
            this.poolName = hikari ? "HikariCP" : "DBCP";
            Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
            try (Connection create = DriverManager.getConnection(url + ";create=true");
                 var statement = create.createStatement()) {
                statement.executeUpdate("CREATE TABLE snapshot_sample (id INT PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO snapshot_sample VALUES (1)");
            }
            DataSource engineSource = hikari ? new HikariConnectionPool("org.apache.derby.jdbc.EmbeddedDriver", url,
                    null, null, size, true, null, false).getDataSource()
                    : new DBCPConnectionPool(url, null, null, size, false).getDataSource();
            if (hikari) {
                ((HikariDataSource) engineSource).setAutoCommit(autoCommit);
                ((HikariDataSource) engineSource).setConnectionTimeout(2_000);
            } else {
                var pool = dbcpPool(engineSource);
                pool.setMaxWaitMillis(500);
                ((PoolableConnectionFactory) pool.getFactory()).setDefaultAutoCommit(autoCommit);
            }
            source = spy(engineSource);
            doAnswer(invocation -> {
                Connection pooled = (Connection) invocation.callRealMethod();
                if (!injectFaults) return pooled;
                lastPhysical = physical(pooled);
                if (refusePhysicalCleanup || closePhysicalOnAbort) {
                    Connection raw = lastPhysical;
                    ownedPhysicalConnections.add(raw);
                    Connection driver = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                                if (method.getName().equals("abort") && closePhysicalOnAbort) {
                                    raw.rollback();
                                    raw.close();
                                    throw new SQLException("abort failed after physical close");
                                }
                                if (refusePhysicalCleanup && (method.getName().equals("close")
                                        || method.getName().equals("abort")
                                        || (method.getName().equals("rollback") && failure == Failure.ROLLBACK_ABORT))) {
                                    throw new SQLException("physical " + method.getName() + " refused");
                                }
                                try {
                                    Object result = method.invoke(raw, args);
                                    if (failStatementClose && method.getName().equals("createStatement")) {
                                        Statement statement = (Statement) result;
                                        ownedStatements.add(statement);
                                        Statement faultyStatement = spy(statement);
                                        doThrow(new SQLException("statement close refused")).when(faultyStatement).close();
                                        return faultyStatement;
                                    }
                                    return result;
                                } catch (InvocationTargetException error) {
                                    throw error.getCause();
                                }
                            });
                    pooled.unwrap(PoolableConnection.class).setDelegate(driver);
                    lastPhysical = driver;
                }
                Connection faulty = spy(pooled);
                if (failure == Failure.COMMIT) {
                    doThrow(new SQLException("injected commit failure", "40001")).when(faulty).commit();
                }
                if (!refusePhysicalCleanup && (failure == Failure.ROLLBACK || failure == Failure.ROLLBACK_ABORT)) {
                    doThrow(new SQLException("injected rollback failure")).when(faulty).rollback();
                }
                if (failure == Failure.RESET || failure == Failure.RESET_ABORT) {
                    doThrow(new SQLException("injected isolation reset failure")).when(faulty)
                            .setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                }
                if (!refusePhysicalCleanup && !closePhysicalOnAbort
                        && (failure == Failure.RESET_ABORT || failure == Failure.ROLLBACK_ABORT)) {
                    doThrow(new SQLException("injected abort failure")).when(faulty).abort(any());
                }
                return faulty;
            }).when(source).getConnection();
            sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(new Configuration(
                    new Environment("snapshot-pool-test", new JdbcTransactionFactory(), source))));
            SqlConfig engine = mock(SqlConfig.class);
            when(engine.getSqlSessionManager()).thenReturn(sessions);
            config = mockStatic(SqlConfig.class);
            config.when(SqlConfig::getInstance).thenReturn(engine);
        }

        private int readCount() {
            try {
                assertEquals(Connection.TRANSACTION_SERIALIZABLE, sessions.getConnection().getTransactionIsolation());
                int result = count(sessions.getConnection());
                if (failure == Failure.READ || failure == Failure.ROLLBACK || failure == Failure.ROLLBACK_ABORT) throw readFailure;
                return result;
            } catch (SQLException error) {
                throw new AssertionError(error);
            }
        }

        @Override
        public void close() throws Exception {
            config.close();
            try {
                // Fault injection keeps the real owned driver connection so even
                // a failed assertion can clear its transaction and close it.
                for (Statement statement : ownedStatements) statement.close();
                for (Connection physical : ownedPhysicalConnections) {
                    if (!physical.isClosed()) {
                        physical.rollback();
                        physical.close();
                    }
                    assertTrue(physical.isClosed());
                    System.out.println("cleared-driver-fault-and-closed-owned-physical=" + url);
                }
                if (source instanceof HikariDataSource) {
                    ((HikariDataSource) source).close();
                } else {
                    var pool = PoolingDataSource.class.getDeclaredField("_pool");
                    pool.setAccessible(true);
                    ((ObjectPool<?>) pool.get(source)).close();
                }
            } finally {
                SQLException dropped = assertThrows(SQLException.class,
                        () -> DriverManager.getConnection(url + ";drop=true"));
                assertEquals("08006", dropped.getSQLState(), "only this test's isolated database is dropped");
                System.out.println("cleaned-pool=" + poolName + ", dropped-owned-db=" + url
                        + ", SQLState=" + dropped.getSQLState() + ", pid=" + ProcessHandle.current().pid());
            }
        }
    }
}
