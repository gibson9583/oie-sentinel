package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.server.controllers.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.evaluate.*;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/** Admission, ordering and onset must agree with concurrent retry publication. */
class ConnectorObservationAdmissionTest {
    private static final String ID = "connector-admission";
    private final Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
    private final Instant deployed = now.minusSeconds(300);
    private final List<org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent> saved = new CopyOnWriteArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final List<CountDownLatch> releaseOnCleanup = new ArrayList<>();
    private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    private final AtomicReference<Consumer<org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent>> beforeInsert =
            new AtomicReference<>(row -> { });
    private ControllerFactory factory;
    private EngineController engine;
    private SentinelConnectorStatusListener listener;
    private MockedStatic<ControllerFactory> controllers;
    private MockedStatic<ConnectorStatusRepository> repository;
    private volatile boolean fail;

    @BeforeEach void setup() {
        Channel channel = mock(Channel.class); when(channel.getMetaDataIds()).thenReturn(List.of(0));
        Calendar date = Calendar.getInstance(); date.setTimeInMillis(deployed.toEpochMilli());
        when(channel.getDeployDate()).thenReturn(date);
        engine = mock(EngineController.class); when(engine.getDeployedChannel(ID)).thenReturn(channel);
        factory = mock(ControllerFactory.class); when(factory.createEngineController()).thenReturn(engine);
        controllers = mockStatic(ControllerFactory.class); controllers.when(ControllerFactory::getFactory).thenReturn(factory);
        repository = repositoryMock();
        listener = mock(SentinelConnectorStatusListener.class, CALLS_REAL_METHODS);
        CollectorState.getInstance().forgetChannel(ID);
    }

    @AfterEach void cleanup() throws Exception {
        releaseOnCleanup.forEach(CountDownLatch::countDown);
        for (Thread worker : workers) {
            worker.join(5000);
            assertFalse(worker.isAlive(), "owned admission-test worker must exit before cleanup");
        }
        when(engine.getDeployedChannel(ID)).thenReturn(null);
        PendingConnectorObservations.flush();
        assertFalse(PendingConnectorObservations.isPending(ID, 0));
        CollectorState.getInstance().forgetChannel(ID);
        repository.close(); controllers.close();
        assertNull(workerFailure.get(), () -> String.valueOf(workerFailure.get()));
    }

    @ParameterizedTest
    @EnumSource(value = ConnectionStatusEventType.class, names = {"CONNECTED", "DISCONNECTED"})
    void concurrentRetryCannotDiscardTheOnlyNewTransition(ConnectionStatusEventType latest) throws Exception {
        ConnectionStatusEventType intermediate = latest == ConnectionStatusEventType.CONNECTED
                ? ConnectionStatusEventType.DISCONNECTED : ConnectionStatusEventType.CONNECTED;
        emit(latest, 120); fail = true; emit(intermediate, 90); fail = false;
        raceAdmissionWithPublication(latest, 30);
        assertEquals(List.of(latest.name(), intermediate.name(), latest.name()),
                saved.stream().map(row -> row.getNewState()).toList());
        assertEquals(latest, CollectorState.getInstance().getConnectorState(ID, 0).state);
        assertEquals(now.minusSeconds(30), saved.get(2).getChangedTime());
    }

    @Test void concurrentRetryCannotAdmitAnOlderQueuedState() throws Exception {
        emit(ConnectionStatusEventType.CONNECTED, 120);
        fail = true; emit(ConnectionStatusEventType.DISCONNECTED, 30); fail = false;
        raceAdmissionWithPublication(ConnectionStatusEventType.IDLE, 60);
        assertEquals(List.of("CONNECTED", "DISCONNECTED"), saved.stream().map(row -> row.getNewState()).toList());
        assertEquals(ConnectionStatusEventType.DISCONNECTED, CollectorState.getInstance().getConnectorState(ID, 0).state);
    }

    /**
     * Pause a listener after it reads committed state. A real second thread
     * then tries to publish the retry. Either publication completes first or
     * atomic admission holds it until the read is consumed; both schedules
     * must preserve the later event. The barrier is on the stable cache API,
     * not the old listener's removed isPending/observe implementation seams.
     */
    private void raceAdmissionWithPublication(ConnectionStatusEventType latest, long age) throws Exception {
        CollectorState actual = CollectorState.getInstance();
        CollectorState pausedReader = spy(actual);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = releaseGate();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        doAnswer(call -> {
            CollectorState.ConnectorState snapshot = actual.getConnectorState(ID, 0);
            read.countDown(); await(resume); return snapshot;
        }).when(pausedReader).getConnectorState(ID, 0);
        Thread arrival = worker("connector-admission-arrival", () -> {
            try (MockedStatic<CollectorState> cache = mockStatic(CollectorState.class)) {
                cache.when(CollectorState::getInstance).thenReturn(pausedReader);
                emit(latest, age);
            }
        });
        assertTrue(read.await(5, TimeUnit.SECONDS));
        beforeInsert.set(row -> inserted.countDown());
        Thread retry = worker("connector-admission-retry", () -> {
            PendingConnectorObservations.flush(); published.countDown();
        });
        assertTrue(inserted.await(5, TimeUnit.SECONDS));
        // An atomic implementation cannot finish publishing until the reader
        // continues. Permit that ordering without requiring either scheduler
        // speed or a specific lock implementation in the assertions.
        published.await(250, TimeUnit.MILLISECONDS);
        resume.countDown(); arrival.join(5000); retry.join(5000);
        assertFalse(arrival.isAlive()); assertFalse(retry.isAlive()); assertNull(workerFailure.get());
        PendingConnectorObservations.flush();
        assertFalse(PendingConnectorObservations.isPending(ID, 0));
    }

    @Test void repeatedPendingStateRetainsOnsetAndRejectsOlderStatesBeforeAndAfterCommit() {
        emit(ConnectionStatusEventType.CONNECTED, 120);
        fail = true; emit(ConnectionStatusEventType.DISCONNECTED, 90);
        emit(ConnectionStatusEventType.DISCONNECTED, 30);
        emit(ConnectionStatusEventType.CONNECTED, 60);
        fail = false; PendingConnectorObservations.flush();
        assertEquals(2, saved.size()); assertEquals("DISCONNECTED", saved.get(1).getNewState());
        assertEquals(now.minusSeconds(90), saved.get(1).getChangedTime());
        emit(ConnectionStatusEventType.CONNECTED, 60);
        assertEquals(2, saved.size(), "the newest observation watermark must survive successful persistence");
        assertEquals(EvaluationOutcome.Result.BREACH, evaluate(60).getResult());
    }

    @Test void repeatedCommittedStateAdvancesOrderingWithoutChangingOnsetOrWritingHistory() {
        emit(ConnectionStatusEventType.DISCONNECTED, 90);
        emit(ConnectionStatusEventType.DISCONNECTED, 30);
        emit(ConnectionStatusEventType.CONNECTED, 60);
        assertEquals(1, saved.size());
        CollectorState.ConnectorState state = CollectorState.getInstance().getConnectorState(ID, 0);
        assertEquals(ConnectionStatusEventType.DISCONNECTED, state.state);
        assertEquals(now.minusSeconds(90), state.since);
        assertEquals(EvaluationOutcome.Result.BREACH, evaluate(60).getResult());
    }

    @Test void sameStateArrivingDuringInsertPreservesOnsetAndWatermarkWithoutDuplicateInsert() throws Exception {
        fail = true; emit(ConnectionStatusEventType.DISCONNECTED, 90); fail = false;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = releaseGate();
        beforeInsert.set(row -> { entered.countDown(); await(release); });
        Thread retry = worker("connector-onset-retry", PendingConnectorObservations::flush);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        // These must finish while JDBC is blocked, preserving the bound on
        // writer resources without blocking admission behind that writer.
        Thread arrival = worker("connector-onset-arrival", () -> {
            emit(ConnectionStatusEventType.DISCONNECTED, 30);
            emit(ConnectionStatusEventType.CONNECTED, 60);
        });
        arrival.join(2000); assertFalse(arrival.isAlive(), "admission must not wait for blocked JDBC");
        release.countDown(); retry.join(5000); assertFalse(retry.isAlive());
        assertFalse(PendingConnectorObservations.isPending(ID, 0));
        assertEquals(1, saved.size()); assertEquals(now.minusSeconds(90), saved.get(0).getChangedTime());
        emit(ConnectionStatusEventType.CONNECTED, 60);
        assertEquals(1, saved.size());
        assertEquals(EvaluationOutcome.Result.BREACH, evaluate(60).getResult());
    }

    @Test void returningToPreviouslyCommittedStateStartsANewContinuousInterval() {
        emit(ConnectionStatusEventType.DISCONNECTED, 120);
        fail = true; emit(ConnectionStatusEventType.CONNECTED, 90);
        emit(ConnectionStatusEventType.DISCONNECTED, 30);
        emit(ConnectionStatusEventType.DISCONNECTED, 10);
        fail = false; PendingConnectorObservations.flush();
        assertEquals(2, saved.size()); assertEquals(now.minusSeconds(30), saved.get(1).getChangedTime());
        assertEquals(EvaluationOutcome.Result.OK, evaluate(60).getResult(), "the intervening recovery resets outage duration");
    }

    @Test void equalTimestampDifferentStatesRetainArrivalOrder() {
        fail = true; emit(ConnectionStatusEventType.CONNECTED, 60);
        emit(ConnectionStatusEventType.DISCONNECTED, 60);
        fail = false; PendingConnectorObservations.flush();
        assertEquals("DISCONNECTED", saved.get(0).getNewState());
        emit(ConnectionStatusEventType.IDLE, 60);
        assertEquals(List.of("DISCONNECTED", "IDLE"), saved.stream().map(row -> row.getNewState()).toList());
        assertEquals(ConnectionStatusEventType.IDLE, CollectorState.getInstance().getConnectorState(ID, 0).state);
    }

    private EvaluationOutcome evaluate(int minDuration) {
        try (MockedStatic<NodeLeaseRepository> leases = mockStatic(NodeLeaseRepository.class)) {
            leases.when(NodeLeaseRepository::listHealthyObservationNodeIds).thenReturn(Set.of(SentinelLeadership.nodeId()));
            leases.when(() -> NodeLeaseRepository.listActiveConnectorDeployments(ID))
                    .thenReturn(Map.of(0, Map.of(SentinelLeadership.nodeId(), deployed)));
            Monitor monitor = new Monitor(); monitor.setConfigJson("{\"minDurationSeconds\":" + minDuration + "}");
            return ConnectionStatusEvaluator.evaluate(monitor, ID, now).get(0).outcome;
        }
    }

    private MockedStatic<ConnectorStatusRepository> repositoryMock() {
        MockedStatic<ConnectorStatusRepository> result = mockStatic(ConnectorStatusRepository.class);
        result.when(() -> ConnectorStatusRepository.insertConnectorStatusEvent(any())).thenAnswer(call -> {
            if (fail) throw new IllegalStateException("selective write failure");
            org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent row = call.getArgument(0);
            beforeInsert.get().accept(row); saved.add(row); return null;
        });
        result.when(() -> ConnectorStatusRepository.listLatestConnectorStatusEvents(ID))
                .thenAnswer(call -> saved.isEmpty() ? List.of() : List.of(saved.get(saved.size() - 1)));
        return result;
    }

    private Thread worker(String name, Runnable task) {
        Thread thread = new Thread(() -> {
            try (MockedStatic<ControllerFactory> workerControllers = mockStatic(ControllerFactory.class);
                    MockedStatic<ConnectorStatusRepository> workerRepository = repositoryMock()) {
                workerControllers.when(ControllerFactory::getFactory).thenReturn(factory);
                task.run();
            } catch (Throwable failure) { workerFailure.compareAndSet(null, failure); }
        }, name);
        workers.add(thread); thread.start(); return thread;
    }

    private CountDownLatch releaseGate() {
        CountDownLatch latch = new CountDownLatch(1); releaseOnCleanup.add(latch); return latch;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "controlled test interleaving timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private void emit(ConnectionStatusEventType state, long age) {
        ConnectionStatusEvent event = mock(ConnectionStatusEvent.class);
        when(event.getChannelId()).thenReturn(ID); when(event.getMetaDataId()).thenReturn(0);
        when(event.getState()).thenReturn(state); when(event.getDateTime()).thenReturn(now.minusSeconds(age).toEpochMilli());
        listener.processEvent(event);
    }
}
