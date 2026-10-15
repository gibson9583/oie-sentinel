/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.donkey.server.data.jdbc.DBCPConnectionPool;
import com.mirth.connect.server.util.SqlConfig;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.PoolableConnection;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertSender;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/** Real shipped Derby statements, independent writers, and the engine's one-connection DBCP pool. */
class ActionDispatchValidationPersistenceTest {
    private String url;
    private DataSource source;
    private GenericObjectPool<PoolableConnection> pool;
    private SqlSessionManager sessions;
    private MockedStatic<SqlConfig> config;
    private ThreadPoolExecutor ownedTransports;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Action ancestor;
    private Action target;
    private Monitor monitor;
    private AlertEvent event;

    @BeforeEach
    void setup() throws Exception {
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        url = "jdbc:derby:memory:dispatchValidation" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url + ";create=true");
                Statement sql = connection.createStatement()) {
            for (String resource : List.of("/derby-sentinel-tables.sql", "/derby-sentinel-v2.sql",
                    "/derby-sentinel-v3.sql", "/derby-sentinel-v4.sql")) {
                try (InputStream input = getClass().getResourceAsStream(resource)) {
                    assertNotNull(input, resource);
                    for (String statement : new String(input.readAllBytes(), StandardCharsets.UTF_8)
                            .split("\\r?\\n\\s*\\r?\\n")) {
                        if (!statement.isBlank()) sql.execute(statement.trim());
                    }
                }
            }
            // These two migrations use portable Java DDL rather than shipped scripts.
            sql.execute("ALTER TABLE sentinel_alert_event ADD COLUMN problem_pending BOOLEAN");
            sql.execute("ALTER TABLE sentinel_alert_event ADD COLUMN resolution_pending BOOLEAN");
        }
        source = new DBCPConnectionPool(url, null, null, 1, false).getDataSource();
        pool = pool(source);
        pool.setMaxWaitMillis(1_000);
        Configuration mapping = new Configuration(new Environment("dispatch-validation",
                new JdbcTransactionFactory(), source));
        mapping.setJdbcTypeForNull(JdbcType.VARCHAR);
        try (InputStream input = getClass().getResourceAsStream("/mapper/derby-sqlmap.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input, mapping, "derby", mapping.getSqlFragments()).parse();
        }
        sessions = SqlSessionManager.newInstance(new SqlSessionFactoryBuilder().build(mapping));
        SqlConfig engine = mock(SqlConfig.class);
        when(engine.getSqlSessionManager()).thenReturn(sessions);
        config = mockStatic(SqlConfig.class);
        config.when(SqlConfig::getInstance).thenReturn(engine);

        ancestor = action("source");
        target = action("target");
        ancestor.setEscalateAfterSeconds(1);
        ancestor.setEscalateToActionId(target.getId());
        ActionRepository.updateAction(ancestor);
        monitor = new Monitor(); monitor.setName("monitor"); monitor.setEnabled(true);
        monitor.setMonitorType(MonitorType.INACTIVITY); monitor.setScopeType(ScopeType.ALL);
        monitor.setSeverity(Severity.HIGH); monitor.setConfigJson("{}");
        monitor.setMinConsecutiveBreaches(1); monitor.setCreatedTime(Instant.now());
        MonitorRepository.insertMonitor(monitor);
        event = new AlertEvent(); event.setMonitorId(monitor.getId());
        event.setChannelId("00000000-0000-0000-0000-000000000001");
        event.setStatus(AlertStatus.PROBLEM); event.setSeverity(Severity.HIGH);
        event.setMessage("problem"); event.setOpenedTime(Instant.now()); event.setProblemPending(true);
        AlertEventRepository.insertAlertEvent(event);
        // DBCP does not reset an unspecified isolation default on return.
        try (Connection pooled = source.getConnection()) {
            pooled.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (sessions != null && sessions.isManagedSessionStarted()) {
            sessions.rollback(true);
            sessions.close();
        }
        writer.shutdown();
        assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS),
                "Refuse database cleanup until this test's independent writer has exited: " + url);
        assertTrue(ownedTransports == null || ownedTransports.isTerminated(),
                "Refuse database cleanup while this test's transport workers still own it: " + url);
        try {
            if (config != null) {
                System.out.println("Dispatch validation final action rows " + url + ": "
                        + ActionRepository.listActions(null).stream().map(action ->
                                action.getId() + ":enabled=" + action.isEnabled()
                                        + ":escalatesTo=" + action.getEscalateToActionId()).toList());
            }
        } finally {
            try {
                if (config != null) config.close();
            } finally {
                try {
                    if (pool != null) pool.close();
                } finally {
                    if (url != null) {
                        SQLException dropped = assertThrows(SQLException.class,
                                () -> DriverManager.getConnection(url + ";drop=true"));
                        assertEquals("08006", dropped.getSQLState());
                        System.out.println("Dropped owned dispatch-validation database " + url
                                + " SQLState=" + dropped.getSQLState() + " pid=" + ProcessHandle.current().pid());
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"target-success", "ancestor-success", "target-failure", "ancestor-failure"})
    void actionWritesWaitThroughTheFinalPolicyReadAndResumeAfterCommitOrRollback(String scenario) throws Exception {
        int changedId = scenario.startsWith("target") ? target.getId() : ancestor.getId();
        boolean failFinalGuard = scenario.endsWith("failure");
        AtomicInteger stateChecks = new AtomicInteger();
        AtomicBoolean actionsVerified = new AtomicBoolean();
        AtomicReference<Future<?>> update = new AtomicReference<>();
        IllegalStateException guardFailure = new IllegalStateException("final state no longer permits dispatch");
        try (var monitors = mockStatic(MonitorRepository.class, CALLS_REAL_METHODS)) {
            monitors.when(() -> MonitorRepository.getMonitor(monitor.getId())).thenAnswer(invocation -> {
                if (stateChecks.get() == 2) {
                    assertTrue(actionsVerified.get(), "the edit must race the final state query after action validation");
                    CountDownLatch attempted = new CountDownLatch(1);
                    update.set(writer.submit(() -> {
                        try (Connection connection = DriverManager.getConnection(url);
                                PreparedStatement sql = connection.prepareStatement(
                                        "UPDATE sentinel_action SET enabled = FALSE WHERE id = ?")) {
                            sql.setInt(1, changedId);
                            attempted.countDown();
                            assertEquals(1, sql.executeUpdate());
                        }
                        return null;
                    }));
                    assertTrue(attempted.await(5, TimeUnit.SECONDS));
                    assertActionWriterWaiting();
                    assertFalse(update.get().isDone(), "a source or target edit cannot commit inside the final guard");
                }
                return invocation.callRealMethod();
            });
            Runnable validate = () -> ActionRepository.validateDispatch(
                    List.of(target.getId(), ancestor.getId(), target.getId()), () -> {
                        assertTrue(ActionRepository.getAction(ancestor.getId()).isEnabled());
                        assertTrue(ActionRepository.getAction(target.getId()).isEnabled());
                        actionsVerified.set(true);
                    }, () -> {
                        stateChecks.incrementAndGet();
                        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(event.getId()).getStatus());
                        assertEquals(NotificationSuppression.Decision.ALLOW,
                                NotificationSuppression.decision(event, Instant.now()));
                        if (failFinalGuard && stateChecks.get() == 2) throw guardFailure;
                    });
            if (failFinalGuard) assertSame(guardFailure, assertThrows(IllegalStateException.class, validate::run));
            else validate.run();
        }
        assertEquals(2, stateChecks.get());
        assertNotNull(update.get());
        update.get().get(5, TimeUnit.SECONDS);
        assertFalse(ActionRepository.getAction(changedId).isEnabled(), "the blocked edit must commit after authorization exits");
        assertCleanPool();
    }

    @Test
    void anEditCommittedBeforeLockAcquisitionIsVisibleToValidation() throws Exception {
        Action stale = ActionRepository.getAction(target.getId());
        assertTrue(stale.isEnabled());
        try (Connection connection = DriverManager.getConnection(url); PreparedStatement sql = connection.prepareStatement(
                "UPDATE sentinel_action SET enabled = FALSE WHERE id = ?")) {
            sql.setInt(1, target.getId());
            assertEquals(1, sql.executeUpdate());
        }
        AtomicBoolean checked = new AtomicBoolean();
        ActionRepository.validateDispatch(List.of(target.getId()), () -> {
            assertFalse(ActionRepository.getAction(target.getId()).isEnabled(), "do not reuse the queued action snapshot");
            checked.set(true);
        }, () -> assertEquals(NotificationSuppression.Decision.ALLOW,
                NotificationSuppression.decision(event, Instant.now())));
        assertTrue(checked.get());
        assertCleanPool();
    }

    @Test
    void finalLifecycleReadSeesCommittedAcknowledgementAfterClearingTheManagedSessionCache() {
        List<Integer> acknowledgements = new ArrayList<>();
        ActionRepository.validateDispatch(List.of(target.getId()), () -> {
            assertTrue(ActionRepository.getAction(target.getId()).isEnabled());
            try (Connection connection = DriverManager.getConnection(url); PreparedStatement sql = connection.prepareStatement(
                    "UPDATE sentinel_alert_event SET acknowledged_by = 99 WHERE id = ?")) {
                sql.setLong(1, event.getId());
                assertEquals(1, sql.executeUpdate());
            } catch (SQLException error) {
                throw new AssertionError(error);
            }
            assertNull(AlertEventRepository.getAlertEvent(event.getId()).getAcknowledgedBy(),
                    "control: MyBatis still serves the first lifecycle read before validateDispatch clears its cache");
        }, () -> acknowledgements.add(AlertEventRepository.getAlertEvent(event.getId()).getAcknowledgedBy()));
        assertEquals(Arrays.asList(null, 99), acknowledgements);
        assertCleanPool();
    }

    @Test
    void usesOnePooledConnectionAndSortedDistinctLocksAndRestoresOriginalIsolation() {
        List<Integer> locks = new ArrayList<>();
        List<String> order = new ArrayList<>();
        AtomicReference<Connection> borrowed = new AtomicReference<>();
        try (var actions = mockStatic(ActionRepository.class, CALLS_REAL_METHODS)) {
            actions.when(() -> ActionRepository.lockAction(anyInt())).thenAnswer(invocation -> {
                locks.add(invocation.getArgument(0));
                return invocation.callRealMethod();
            });
            ActionRepository.validateDispatch(List.of(target.getId(), ancestor.getId(), target.getId()), () -> {
                order.add("actions");
                assertTrue(ActionRepository.getAction(ancestor.getId()).isEnabled());
                assertTrue(ActionRepository.getAction(target.getId()).isEnabled());
            }, () -> {
                order.add("state");
                assertEquals(1, pool.getNumActive());
                Connection current = sessions.getConnection();
                if (borrowed.get() == null) borrowed.set(current);
                else assertSame(borrowed.get(), current);
                try {
                    assertEquals(Connection.TRANSACTION_READ_COMMITTED, current.getTransactionIsolation());
                } catch (SQLException error) {
                    throw new AssertionError(error);
                }
                assertEquals(NotificationSuppression.Decision.ALLOW,
                        NotificationSuppression.decision(event, Instant.now()));
            });
        }
        assertEquals(List.of(ancestor.getId(), target.getId()), locks);
        assertEquals(List.of("state", "actions", "state"), order);
        assertCleanPool();
    }

    @ParameterizedTest
    @ValueSource(strings = {"first-state", "actions", "last-state"})
    void everyGuardFailureReleasesLocksAndRestoresThePooledConnection(String phase) throws Exception {
        IllegalStateException failure = new IllegalStateException(phase);
        AtomicInteger checks = new AtomicInteger();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> ActionRepository.validateDispatch(
                List.of(ancestor.getId(), target.getId()), () -> {
                    if (phase.equals("actions")) throw failure;
                }, () -> {
                    int check = checks.incrementAndGet();
                    if (phase.equals("first-state") || (phase.equals("last-state") && check == 2)) throw failure;
                })));
        assertCleanPool();
        Future<?> after = writer.submit(() -> {
            try (Connection connection = DriverManager.getConnection(url); PreparedStatement sql = connection.prepareStatement(
                    "UPDATE sentinel_action SET enabled = FALSE WHERE id = ?")) {
                sql.setInt(1, target.getId());
                assertEquals(1, sql.executeUpdate());
            }
            return null;
        });
        after.get(5, TimeUnit.SECONDS);
        assertFalse(ActionRepository.getAction(target.getId()).isEnabled());
    }

    @Test
    void timeoutBeforeTransportCannotSendAfterAnUncooperativeGuardReleasesItsLocks() throws Exception {
        CountDownLatch finalGuardEntered = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        CountDownLatch guardExited = new CountDownLatch(1);
        AtomicInteger stateChecks = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        SqlConfig transportEngine = mock(SqlConfig.class);
        when(transportEngine.getSqlSessionManager()).thenReturn(sessions);
        Runnable guard = () -> {
            // Mockito static scopes are thread-local; bind the actual transport
            // worker to the same manager and its one-connection engine pool.
            try (var workerConfig = mockStatic(SqlConfig.class)) {
                workerConfig.when(SqlConfig::getInstance).thenReturn(transportEngine);
                ActionRepository.validateDispatch(List.of(target.getId(), ancestor.getId()), () -> {
                    assertTrue(ActionRepository.getAction(target.getId()).isEnabled());
                    assertTrue(ActionRepository.getAction(ancestor.getId()).isEnabled());
                }, () -> {
                    assertEquals(NotificationSuppression.Decision.ALLOW,
                            NotificationSuppression.decision(event, Instant.now()));
                    if (stateChecks.incrementAndGet() != 2) return;
                    finalGuardEntered.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (releaseGuard.getCount() != 0 && System.nanoTime() < deadline) {
                        try {
                            releaseGuard.await(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Model a JDBC read that outlives cancellation and
                            // clears the interrupt. The transport CAS must still
                            // prevent I/O when this guard eventually returns.
                            cancellationObserved.countDown();
                        }
                    }
                    assertEquals(0, releaseGuard.getCount(), "the test must release its blocked policy read");
                });
            } finally {
                guardExited.countDown();
            }
        };
        var invoke = ActionDispatcher.class.getDeclaredMethod("sendWithWallClock", AlertSender.class,
                Action.class, AlertEvent.class, AlertPayload.class, long.class, TimeUnit.class, Runnable.class);
        invoke.setAccessible(true);
        ActionDispatcher.startDispatchExecutor();
        var executorField = ActionDispatcher.class.getDeclaredField("transportExecutor");
        executorField.setAccessible(true);
        ownedTransports = (ThreadPoolExecutor) executorField.get(null);
        try {
            AlertSender sender = (action, alert, payload) -> sends.incrementAndGet();
            InvocationTargetException timeout = assertThrows(InvocationTargetException.class,
                    () -> invoke.invoke(null, sender, target, event, null, 1_000L, TimeUnit.MILLISECONDS, guard));
            assertEquals("DispatchDeferredException", timeout.getCause().getClass().getSimpleName());
            assertEquals(0, finalGuardEntered.getCount(), "timeout must occur inside the final guarded read");
            assertTrue(cancellationObserved.await(2, TimeUnit.SECONDS));
            assertEquals(0, sends.get());

            Future<?> update = writer.submit(() -> {
                try (Connection connection = DriverManager.getConnection(url); PreparedStatement sql = connection.prepareStatement(
                        "UPDATE sentinel_action SET enabled = FALSE WHERE id = ?")) {
                    sql.setInt(1, target.getId());
                    assertEquals(1, sql.executeUpdate());
                }
                return null;
            });
            assertActionWriterWaiting();
            releaseGuard.countDown();
            assertTrue(guardExited.await(5, TimeUnit.SECONDS));
            update.get(5, TimeUnit.SECONDS);
            assertCleanPool();
            assertFalse(ActionRepository.getAction(target.getId()).isEnabled());
        } finally {
            releaseGuard.countDown();
            boolean exited;
            try {
                exited = guardExited.await(5, TimeUnit.SECONDS);
            } finally {
                ActionDispatcher.shutdownDispatchExecutor();
                assertTrue(ownedTransports.awaitTermination(5, TimeUnit.SECONDS), "owned transport workers must exit");
            }
            assertTrue(exited, "guard must exit before database cleanup");
        }
        assertEquals(0, sends.get(), "a late successful guard must not revive the cancelled transport");
    }

    private Action action(String name) {
        Action action = new Action(); action.setName(name); action.setEnabled(true);
        action.setActionType(ActionType.EMAIL); action.setOperationMode(OperationMode.BOTH);
        action.setConditionJson("[]"); action.setConfigJson("{\"to\":\"" + name + "@example.test\"}");
        action.setCreatedTime(Instant.now());
        return ActionRepository.insertAction(action);
    }

    private void assertCleanPool() {
        assertFalse(sessions.isManagedSessionStarted());
        assertEquals(0, pool.getNumActive());
        try (Connection connection = source.getConnection()) {
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
        } catch (SQLException error) {
            throw new AssertionError(error);
        }
    }

    private void assertActionWriterWaiting() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Connection observer = DriverManager.getConnection(url); Statement sql = observer.createStatement()) {
            do {
                try (ResultSet locks = sql.executeQuery(
                        "SELECT STATE FROM SYSCS_DIAG.LOCK_TABLE WHERE TABLENAME = 'SENTINEL_ACTION'")) {
                    while (locks.next()) if ("WAIT".equals(locks.getString(1))) return;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
        }
        fail("The independent action writer never reached a database lock wait");
    }

    @SuppressWarnings("unchecked")
    private static GenericObjectPool<PoolableConnection> pool(DataSource source) throws Exception {
        var field = PoolingDataSource.class.getDeclaredField("_pool");
        field.setAccessible(true);
        return (GenericObjectPool<PoolableConnection>) field.get(source);
    }
}
