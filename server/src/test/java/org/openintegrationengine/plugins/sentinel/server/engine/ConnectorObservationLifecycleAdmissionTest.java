/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ConnectorStatusRepository;
import org.openintegrationengine.plugins.sentinel.server.db.NodeLeaseRepository;
import org.openintegrationengine.plugins.sentinel.server.evaluate.ConnectionStatusEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/** A retired listener must not admit observations into a later lifecycle. */
class ConnectorObservationLifecycleAdmissionTest {
    private static final String CHANNEL = "connector-lifecycle-admission";
    private final Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
    private final Instant initialDeployment = now.minusSeconds(300);
    private final List<org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent> saved =
            new CopyOnWriteArrayList<>();
    private final List<MockedStatic<?>> mocks = new ArrayList<>();
    private final Map<String, Object> originalLifecycle = new LinkedHashMap<>();
    private final CountDownLatch paused = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);
    private final CountDownLatch releaseKey = new CountDownLatch(1);
    private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    private final AtomicReference<Channel> deployed = new AtomicReference<>();
    private ControllerFactory factory;
    private SentinelConnectorStatusListener oldListener;
    private SentinelConnectorStatusListener currentListener;
    private Thread oldConsumer;
    private Thread keyHolder;
    private volatile boolean failInsert;
    private Object originalRetryExecutor;
    private Object originalRetryInFlight;

    @BeforeEach
    void setup() throws Exception {
        assertNull(field(SentinelLeadership.class, "heartbeatExecutor").get(null));
        assertFalse(PendingConnectorObservations.isPending(CHANNEL, 0));
        for (String name : List.of("standingDown", "leadershipEngaged", "lifecycleGeneration",
                "leadershipEpoch", "leadershipValidUntilNanos")) {
            originalLifecycle.put(name, field(SentinelLeadership.class, name).get(null));
        }
        field(SentinelLeadership.class, "standingDown").set(null, false);
        originalRetryExecutor = field(PendingConnectorObservations.class, "retryExecutor").get(null);
        originalRetryInFlight = field(PendingConnectorObservations.class, "retryInFlight").get(null);
        // Keep retries on this test thread, where the repository mocks apply.
        // ConnectorRetryWorkerPersistenceTest covers actual background JDBC.
        ExecutorService retries = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("retry controlled by the test"))
                .when(retries).execute(any(Runnable.class));
        field(PendingConnectorObservations.class, "retryExecutor").set(null, retries);
        field(PendingConnectorObservations.class, "retryInFlight").set(null, false);

        EngineController engine = mock(EngineController.class);
        when(engine.getDeployedIds()).thenReturn(Set.of(CHANNEL));
        when(engine.getDeployedChannel(CHANNEL)).thenAnswer(call -> deployed.get());
        factory = mock(ControllerFactory.class);
        when(factory.createEngineController()).thenReturn(engine);
        MockedStatic<ControllerFactory> controllers = mockStatic(ControllerFactory.class);
        controllers.when(ControllerFactory::getFactory).thenReturn(factory);
        mocks.add(controllers);
        mocks.add(repositoryMock());
        MockedStatic<NodeLeaseRepository> leases = mockStatic(NodeLeaseRepository.class);
        leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(now);
        leases.when(NodeLeaseRepository::listHealthyObservationNodeIds)
                .thenReturn(Set.of(SentinelLeadership.nodeId()));
        leases.when(() -> NodeLeaseRepository.listActiveConnectorDeployments(CHANNEL))
                .thenAnswer(call -> Map.of(0, Map.of(SentinelLeadership.nodeId(),
                        deployed.get().getDeployDate().toInstant())));
        mocks.add(leases);

        deployed.set(channel(initialDeployment));
        // Exercise the real handlers without starting the base class's queue
        // consumers; the controlled thread below models the old consumer.
        oldListener = mock(SentinelConnectorStatusListener.class, CALLS_REAL_METHODS);
        currentListener = oldListener;
        CollectorState.getInstance().forgetChannel(CHANNEL);
    }

    @AfterEach
    void cleanup() throws Exception {
        resume.countDown();
        releaseKey.countDown();
        if (oldConsumer != null) {
            oldConsumer.join(5000);
            assertFalse(oldConsumer.isAlive(), "owned listener callback must exit before cleanup");
        }
        if (keyHolder != null) {
            keyHolder.join(5000);
            assertFalse(keyHolder.isAlive(), "owned connector-key holder must exit before cleanup");
        }
        SentinelLeadership.stopHeartbeat();
        // Failed assertions can leave retry intent. Remove only this fixture's
        // identity after its callback and heartbeat have stopped.
        @SuppressWarnings("unchecked")
        Map<Object, Object> pending = (Map<Object, Object>)
                field(PendingConnectorObservations.class, "pending").get(null);
        for (Object key : List.copyOf(pending.keySet())) {
            if (CHANNEL.equals(field(key.getClass(), "channelId").get(key))) pending.remove(key);
        }
        assertFalse(PendingConnectorObservations.isPending(CHANNEL, 0));
        CollectorState.getInstance().forgetChannel(CHANNEL);
        field(PendingConnectorObservations.class, "retryExecutor").set(null, originalRetryExecutor);
        field(PendingConnectorObservations.class, "retryInFlight").set(null, originalRetryInFlight);
        for (var entry : originalLifecycle.entrySet()) {
            field(SentinelLeadership.class, entry.getKey()).set(null, entry.getValue());
        }
        Collections.reverse(mocks);
        mocks.forEach(MockedStatic::close);
        assertNull(workerFailure.get(), () -> String.valueOf(workerFailure.get()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldListenerCannotEraseRedeployedConnectorsPendingOutage(boolean backwardDeploymentClock) throws Exception {
        SentinelLeadership.startHeartbeat();
        pauseOldObservation(ConnectionStatusEventType.CONNECTED, 60);
        stopOldLifecycle();
        Instant newDeployment = backwardDeploymentClock ? now.minusSeconds(600) : now.minusSeconds(10);
        deployed.set(channel(newDeployment));
        startNewLifecycle();
        emit(currentListener, ConnectionStatusEventType.CONNECTED, 8);
        failInsert = true;
        emit(currentListener, ConnectionStatusEventType.DISCONNECTED, 5);
        CollectorState.ConnectorState committed = CollectorState.getInstance().getConnectorState(CHANNEL, 0);
        assertTrue(PendingConnectorObservations.isPending(CHANNEL, 0));

        releaseOldObservation();
        assertAll(
                () -> assertTrue(PendingConnectorObservations.isPending(CHANNEL, 0),
                        "the old deployment cannot remove current retry intent"),
                () -> assertSame(committed, CollectorState.getInstance().getConnectorState(CHANNEL, 0),
                        "the old deployment cannot replace the current committed observation"));
        retryAndAssertOutage(newDeployment, 5, List.of("CONNECTED", "DISCONNECTED"));
    }

    @Test
    void sameDeploymentRestartRejectsOldListenerEvenWhenEventTimestampsTie() throws Exception {
        SentinelLeadership.startHeartbeat();
        // Equal engine millisecond timestamps force the lifecycle fence to do
        // the rejection; ordinary older-event ordering cannot hide this race.
        pauseOldObservation(ConnectionStatusEventType.CONNECTED, 5);
        stopOldLifecycle();
        startNewLifecycle();
        emit(currentListener, ConnectionStatusEventType.CONNECTED, 8);
        failInsert = true;
        emit(currentListener, ConnectionStatusEventType.DISCONNECTED, 5);
        CollectorState.ConnectorState committed = CollectorState.getInstance().getConnectorState(CHANNEL, 0);

        releaseOldObservation();
        assertTrue(PendingConnectorObservations.isPending(CHANNEL, 0));
        assertSame(committed, CollectorState.getInstance().getConnectorState(CHANNEL, 0));
        retryAndAssertOutage(initialDeployment, 5, List.of("CONNECTED", "DISCONNECTED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldAdmissionWhileStoppedCannotChangeCommittedOrPendingOutage(boolean pendingOutage) throws Exception {
        SentinelLeadership.startHeartbeat();
        if (pendingOutage) {
            emit(currentListener, ConnectionStatusEventType.CONNECTED, 90);
            failInsert = true;
        }
        emit(currentListener, ConnectionStatusEventType.DISCONNECTED, 60);
        CollectorState.ConnectorState committed = CollectorState.getInstance().getConnectorState(CHANNEL, 0);
        List<String> savedBeforeStop = savedStates();
        pauseOldObservation(ConnectionStatusEventType.CONNECTED, 30);
        stopOldLifecycle();

        releaseOldObservation();
        assertAll(
                () -> assertEquals(pendingOutage, PendingConnectorObservations.isPending(CHANNEL, 0),
                        "a stopped listener must neither create nor discard retry intent"),
                () -> assertSame(committed, CollectorState.getInstance().getConnectorState(CHANNEL, 0)),
                () -> assertEquals(savedBeforeStop, savedStates(), "a stopped listener must not persist its callback"));
        startNewLifecycle();
        retryAndAssertOutage(initialDeployment, 60,
                pendingOutage ? List.of("CONNECTED", "DISCONNECTED") : List.of("DISCONNECTED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newListenerRetainsOnlyObservationBeforeFirstOrRestartedHeartbeat(boolean restarting) throws Exception {
        if (restarting) {
            SentinelLeadership.startHeartbeat();
            stopOldLifecycle();
            currentListener = mock(SentinelConnectorStatusListener.class, CALLS_REAL_METHODS);
        }
        // Registration precedes heartbeat startup. On restart standingDown
        // is still true, but this new, unretired listener must retain its event.
        failInsert = true;
        emit(currentListener, ConnectionStatusEventType.DISCONNECTED, 5);
        assertTrue(PendingConnectorObservations.isPending(CHANNEL, 0));
        assertTrue(saved.isEmpty());
        SentinelLeadership.startHeartbeat();
        retryAndAssertOutage(initialDeployment, 5, List.of("DISCONNECTED"));
    }

    @Test
    void initialListenerCallbackCrossingHeartbeatStartRetainsItsOnlyObservation() throws Exception {
        // A callback may begin immediately after registration, before the
        // first synchronous heartbeat advances the leadership generation.
        failInsert = true;
        pauseOldObservation(ConnectionStatusEventType.DISCONNECTED, 5);
        SentinelLeadership.startHeartbeat();
        releaseOldObservation();
        assertTrue(PendingConnectorObservations.isPending(CHANNEL, 0));
        assertTrue(saved.isEmpty());
        retryAndAssertOutage(initialDeployment, 5, List.of("DISCONNECTED"));
    }

    @Test
    void retirementWhileWaitingForConnectorKeyCannotReplaceCurrentIntent() throws Exception {
        SentinelLeadership.startHeartbeat();
        emit(currentListener, ConnectionStatusEventType.CONNECTED, 90);
        failInsert = true;
        emit(currentListener, ConnectionStatusEventType.DISCONNECTED, 60);
        @SuppressWarnings("unchecked")
        Map<Object, Object> pending = (Map<Object, Object>)
                field(PendingConnectorObservations.class, "pending").get(null);
        Object key = null;
        for (Object candidate : pending.keySet()) {
            if (CHANNEL.equals(field(candidate.getClass(), "channelId").get(candidate))) key = candidate;
        }
        assertNotNull(key);
        Object connectorKey = key;
        Object pendingBeforeRetirement = pending.get(connectorKey);
        CountDownLatch keyHeld = new CountDownLatch(1);
        keyHolder = new Thread(() -> {
            try {
                pending.compute(connectorKey, (ignored, value) -> {
                    keyHeld.countDown();
                    awaitRelease(releaseKey);
                    return value;
                });
            } catch (Throwable error) {
                workerFailure.compareAndSet(null, error);
            }
        }, "sentinel-admission-key-holder-test");
        keyHolder.start();
        assertTrue(keyHeld.await(5, TimeUnit.SECONDS));
        startOldConsumer(ConnectionStatusEventType.CONNECTED, 30);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!blockedInConnectorCompute() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(blockedInConnectorCompute(), "old callback must already be waiting for the connector key");

        // A predicate checked only before compute would already have passed.
        // Retire while that callback waits, then let it acquire the real key.
        oldListener.onShutdown();
        releaseKey.countDown();
        keyHolder.join(5000);
        assertFalse(keyHolder.isAlive());
        releaseOldObservation();
        assertSame(pendingBeforeRetirement, pending.get(connectorKey),
                "retirement must be rechecked after acquiring the connector key");
        retryAndAssertOutage(initialDeployment, 60, List.of("CONNECTED", "DISCONNECTED"));
    }

    private boolean blockedInConnectorCompute() {
        if (oldConsumer.getState() != Thread.State.BLOCKED) return false;
        for (StackTraceElement frame : oldConsumer.getStackTrace()) {
            if (frame.getClassName().equals("java.util.concurrent.ConcurrentHashMap")
                    && frame.getMethodName().equals("compute")) return true;
        }
        return false;
    }

    private void pauseOldObservation(ConnectionStatusEventType state, long age) throws Exception {
        Channel oldChannel = deployed.get();
        Calendar deploymentSnapshot = oldChannel.getDeployDate();
        when(oldChannel.getDeployDate()).thenAnswer(call -> {
            if (Thread.currentThread() == oldConsumer) {
                paused.countDown();
                awaitRelease(resume);
            }
            return deploymentSnapshot;
        });
        startOldConsumer(state, age);
        assertTrue(paused.await(5, TimeUnit.SECONDS), "old callback must pause before pending admission");
    }

    private void startOldConsumer(ConnectionStatusEventType state, long age) {
        oldConsumer = new Thread(() -> {
            try (MockedStatic<ControllerFactory> controllers = mockStatic(ControllerFactory.class);
                    MockedStatic<ConnectorStatusRepository> repository = repositoryMock()) {
                controllers.when(ControllerFactory::getFactory).thenReturn(factory);
                emit(oldListener, state, age);
            } catch (Throwable error) {
                workerFailure.compareAndSet(null, error);
            }
        }, "sentinel-old-listener-admission-test");
        oldConsumer.start();
    }

    private void stopOldLifecycle() {
        SentinelLeadership.stopHeartbeat();
        oldListener.onShutdown();
        // The engine's EventListener.shutdown() interrupts without joining.
        // An already executing handler can still return after this interrupt.
        if (oldConsumer != null) oldConsumer.interrupt();
    }

    private void startNewLifecycle() {
        currentListener = mock(SentinelConnectorStatusListener.class, CALLS_REAL_METHODS);
        SentinelLeadership.startHeartbeat();
    }

    private void releaseOldObservation() throws Exception {
        resume.countDown();
        oldConsumer.join(5000);
        assertFalse(oldConsumer.isAlive());
        assertNull(workerFailure.get(), () -> String.valueOf(workerFailure.get()));
    }

    private void retryAndAssertOutage(Instant deploymentTime, long outageAge, List<String> expectedStates) {
        failInsert = false;
        PendingConnectorObservations.flush();
        PendingConnectorObservations.flush();
        CollectorState.ConnectorState committed = CollectorState.getInstance().getConnectorState(CHANNEL, 0);
        Monitor monitor = new Monitor();
        monitor.setConfigJson("{}");
        assertAll(
                () -> assertEquals(expectedStates, savedStates(), "retry must persist the outage exactly once"),
                () -> assertFalse(PendingConnectorObservations.isPending(CHANNEL, 0)),
                () -> assertEquals(ConnectionStatusEventType.DISCONNECTED, committed.state),
                () -> assertEquals(deploymentTime, committed.deploymentTime),
                () -> assertEquals(now.minusSeconds(outageAge), committed.since),
                () -> assertEquals(EvaluationOutcome.Result.BREACH,
                        ConnectionStatusEvaluator.evaluate(monitor, CHANNEL, now).get(0).outcome.getResult()));
    }

    private void awaitRelease(CountDownLatch gate) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean interrupted = false;
        try {
            while (gate.getCount() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    AssertionError timeout = new AssertionError("controlled lifecycle interleaving timed out");
                    workerFailure.compareAndSet(null, timeout);
                    throw timeout;
                }
                try {
                    gate.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException expectedDuringStop) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private MockedStatic<ConnectorStatusRepository> repositoryMock() {
        MockedStatic<ConnectorStatusRepository> result = mockStatic(ConnectorStatusRepository.class);
        result.when(() -> ConnectorStatusRepository.insertConnectorStatusEvent(any())).thenAnswer(call -> {
            if (failInsert) throw new IllegalStateException("controlled connector status write failure");
            saved.add(call.getArgument(0));
            return null;
        });
        result.when(() -> ConnectorStatusRepository.listLatestConnectorStatusEvents(CHANNEL))
                .thenAnswer(call -> saved.isEmpty() ? List.of() : List.of(saved.get(saved.size() - 1)));
        return result;
    }

    private List<String> savedStates() {
        return saved.stream().map(row -> row.getNewState()).toList();
    }

    private Channel channel(Instant at) {
        Channel channel = mock(Channel.class);
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(at.toEpochMilli());
        when(channel.getDeployDate()).thenReturn(date);
        when(channel.getMetaDataIds()).thenReturn(List.of(0));
        return channel;
    }

    private void emit(SentinelConnectorStatusListener listener, ConnectionStatusEventType state, long age) {
        ConnectionStatusEvent event = mock(ConnectionStatusEvent.class);
        when(event.getChannelId()).thenReturn(CHANNEL);
        when(event.getMetaDataId()).thenReturn(0);
        when(event.getState()).thenReturn(state);
        when(event.getDateTime()).thenReturn(now.minusSeconds(age).toEpochMilli());
        listener.processEvent(event);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
