/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.LeaseFence;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

/**
 * Pins the durable hand-off on the executor's actual rejection path. Four
 * workers are held, the bounded queue is filled exactly, and the lifecycle
 * hook is then rejected by the production handler. The later synchronous pass
 * stands in for the next evaluator tick and must reconstruct the edge solely
 * from database state — no callback from the dropped Runnable exists.
 */
@DisplayName("ActionDispatcher queue-saturation durability")
class ActionDispatcherQueueDurabilityTest {

    private static final long EVENT_ID = 7201L;
    private static final String CHANNEL_ID = "channel-a";

    private MockedStatic<ActionRepository> actions;
    private MockedStatic<ActionDispatchLogRepository> dispatches;
    private MockedStatic<AlertEventRepository> alerts;
    private MockedStatic<TriggerStateRepository> states;
    private MockedStatic<NotificationSuppression> suppression;
    private MockedStatic<AlertStormControl> storm;
    private MockedStatic<SentinelLeadership> leadership;
    private CountDownLatch releaseWorkers;
    private List<ActionDispatchLog> persistedRows;

    @BeforeEach
    void setUp() {
        ActionDispatcher.startDispatchExecutor();

        actions = Mockito.mockStatic(ActionRepository.class);
        dispatches = Mockito.mockStatic(ActionDispatchLogRepository.class);
        persistedRows = new ArrayList<>();
        dispatches.when(() -> ActionDispatchLogRepository
                .listActionDispatchLogsForEvent(EVENT_ID)).thenAnswer(invocation -> List.copyOf(persistedRows));
        dispatches.when(() -> ActionDispatchLogRepository
                .insertActionDispatchLog(any(ActionDispatchLog.class))).thenAnswer(invocation -> {
                    persistedRows.add(invocation.getArgument(0));
                    return invocation.getArgument(0);
                });
        alerts = Mockito.mockStatic(AlertEventRepository.class);
        states = Mockito.mockStatic(TriggerStateRepository.class);
        TriggerState state = new TriggerState();
        state.setMonitorId(7);
        state.setChannelId(CHANNEL_ID);
        states.when(() -> TriggerStateRepository.getTriggerState(7, CHANNEL_ID, null))
                .thenReturn(state);
        suppression = Mockito.mockStatic(NotificationSuppression.class);
        suppression.when(() -> NotificationSuppression.decision(
                any(AlertEvent.class), any(Instant.class)))
                .thenReturn(NotificationSuppression.Decision.ALLOW);
        storm = Mockito.mockStatic(AlertStormControl.class);
        storm.when(() -> AlertStormControl.flapCheck(
                any(AlertEvent.class), any(Instant.class), Mockito.anyBoolean()))
                .thenReturn(new AlertStormControl.FlapCheck(
                        AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
        storm.when(() -> AlertStormControl.ceilingVerdict(
                any(Action.class), any(Instant.class)))
                .thenReturn(new AlertStormControl.CeilingVerdict(
                        AlertStormControl.CeilingOutcome.SEND, 0, 0, List.of()));
        leadership = Mockito.mockStatic(SentinelLeadership.class);
        leadership.when(SentinelLeadership::captureFence).thenReturn(LeaseFence.unmanaged());
        leadership.when(() -> SentinelLeadership.recheckFence(any(LeaseFence.class)))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (releaseWorkers != null) {
            releaseWorkers.countDown();
        }
        ActionDispatcher.shutdownDispatchExecutor();
        leadership.close();
        storm.close();
        suppression.close();
        states.close();
        alerts.close();
        dispatches.close();
        actions.close();
    }

    @Test
    @DisplayName("a dropped opened edge is reconstructed from the missing action row")
    void droppedOpenIsReconstructedOnStillOpenPass() throws Exception {
        AlertEvent event = event(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(action(OperationMode.ON_PROBLEM)));
        saturateDispatchQueue();

        ActionDispatcher.onAlertOpened(event, payload("PROBLEM"));
        alerts.verify(() -> AlertEventRepository.getAlertEvent(EVENT_ID), never());

        ActionDispatcher.runRepeatCheck(event, payload("PROBLEM"));

        ActionDispatchLog row = insertedRow();
        assertEquals(EVENT_ID, row.getAlertEventId());
        assertEquals(Integer.valueOf(12), row.getActionId());
    }

    @Test
    @DisplayName("a dropped resolved edge remains pending and is reconstructed from the outbox")
    void droppedResolveIsReconstructedFromOutbox() throws Exception {
        AlertEvent event = event(AlertStatus.RESOLVED);
        event.setResolutionPending(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(action(OperationMode.ON_RESOLVE)));
        saturateDispatchQueue();

        ActionDispatcher.onAlertResolved(event, payload("RESOLVED"));
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false), never());

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        ActionDispatchLog row = insertedRow();
        assertTrue(row.getErrorMessage().startsWith(ActionDispatcher.RESOLUTION_MARKER),
                row.getErrorMessage());
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false));
    }

    @Test
    @DisplayName("a short-lived dropped incident replays problem before resolution")
    void resolvedBeforeRecoveryStillAccountsBothEdgesInOrder() throws Exception {
        AlertEvent event = event(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(action(OperationMode.BOTH)));
        saturateDispatchQueue();

        ActionDispatcher.onAlertOpened(event, payload("PROBLEM"));
        event.setStatus(AlertStatus.RESOLVED);
        event.setResolvedTime(Instant.now());
        event.setResolutionPending(true);
        ActionDispatcher.onAlertResolved(event, payload("RESOLVED"));

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertEquals(2, persistedRows.size());
        assertTrue(persistedRows.get(0).getErrorMessage() == null
                        || !persistedRows.get(0).getErrorMessage()
                                .startsWith(ActionDispatcher.RESOLUTION_MARKER),
                "the reconstructed problem row must be first");
        assertTrue(persistedRows.get(1).getErrorMessage()
                        .startsWith(ActionDispatcher.RESOLUTION_MARKER),
                "the resolution row must follow the problem row");
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false));
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false));
    }

    @Test
    @DisplayName("resolution replay cannot race an in-flight problem decision")
    void overlappingResolutionDoesNotDuplicateProblemEdge() throws Exception {
        AlertEvent event = event(AlertStatus.RESOLVED);
        event.setProblemPending(true);
        event.setResolutionPending(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(action(OperationMode.BOTH)));
        Map<Long, Object> problemClaims = problemClaims();
        Object owner = new Object();
        assertNull(problemClaims.putIfAbsent(EVENT_ID, owner), "test requires an unclaimed event");
        try {
            ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);
            assertEquals(0, persistedRows.size(),
                    "resolution must defer while problem work owns the shared claim");
        } finally {
            problemClaims.remove(EVENT_ID, owner);
        }

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertEquals(2, persistedRows.size());
        assertTrue(persistedRows.get(1).getErrorMessage()
                .startsWith(ActionDispatcher.RESOLUTION_MARKER));
    }

    @Test
    void acknowledgedDroppedOpenThenManualResolveDoesNotReplayProblem() throws Exception {
        AlertEvent event = event(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        Action both = action(OperationMode.BOTH);
        Action resolveOnly = action(OperationMode.ON_RESOLVE);
        resolveOnly.setId(13);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(both, resolveOnly));
        saturateDispatchQueue();
        ActionDispatcher.onAlertOpened(event, payload("PROBLEM"));

        event.setAcknowledgedBy(42);
        event.setStatus(AlertStatus.RESOLVED);
        event.setResolutionPending(true);
        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertEquals(1, persistedRows.size());
        assertEquals(Integer.valueOf(13), persistedRows.get(0).getActionId());
        assertTrue(persistedRows.get(0).getErrorMessage().contains(ActionDispatcher.RESOLUTION_MARKER));
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false));
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false));
    }

    @Test
    void replayRechecksAcknowledgementBeforeAProblemAttempt() {
        AlertEvent queued = event(AlertStatus.RESOLVED);
        queued.setProblemPending(true);
        queued.setResolutionPending(true);
        AlertEvent acknowledged = event(AlertStatus.RESOLVED);
        acknowledged.setProblemPending(true);
        acknowledged.setResolutionPending(true);
        acknowledged.setAcknowledgedBy(42);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID))
                .thenReturn(queued, queued, acknowledged);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(action(OperationMode.BOTH)));

        ActionDispatcher.dispatchLifecycle(queued, payload("RESOLVED"), true);

        assertTrue(persistedRows.isEmpty());
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false), never());
        ActionDispatcher.dispatchLifecycle(acknowledged, payload("RESOLVED"), true);
        assertTrue(persistedRows.isEmpty());
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false));
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false));
    }

    @Test
    void exhaustedTransportCapacityDoesNotConsumeDurableProblem() throws Exception {
        AlertEvent event = event(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        Action action = action(OperationMode.ON_PROBLEM);
        action.setActionType(ActionType.EMAIL);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenReturn(List.of(action));

        Field field = ActionDispatcher.class.getDeclaredField("transportExecutor");
        field.setAccessible(true);
        ThreadPoolExecutor transports = (ThreadPoolExecutor) field.get(null);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            transports.execute(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exited.countDown();
                }
            });
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            ActionDispatcher.dispatchLifecycle(event, payload("PROBLEM"), false);
            assertTrue(persistedRows.isEmpty(), "No send occurred: no completed attempt may be recorded");
            alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false), never());
            assertTrue(event.isProblemPending());
        } finally {
            release.countDown();
            assertTrue(exited.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void leadershipLossAfterAttemptLeavesOutboxForSuccessorWithoutRepeatingAccountedAction() {
        AlertEvent event = event(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(action(OperationMode.ON_PROBLEM)));
        AtomicBoolean leader = new AtomicBoolean(true);
        leadership.when(() -> SentinelLeadership.recheckFence(any(LeaseFence.class)))
                .thenAnswer(invocation -> leader.get());
        dispatches.when(() -> ActionDispatchLogRepository
                .insertActionDispatchLog(any(ActionDispatchLog.class))).thenAnswer(invocation -> {
                    persistedRows.add(invocation.getArgument(0));
                    leader.set(false);
                    return invocation.getArgument(0);
                });

        ActionDispatcher.dispatchLifecycle(event, payload("PROBLEM"), false);

        assertEquals(1, persistedRows.size());
        assertTrue(event.isProblemPending());
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false), never());

        leader.set(true);
        ActionDispatcher.runRepeatCheck(event, payload("PROBLEM"));

        assertEquals(1, persistedRows.size(), "A durable attempt must not repeat after takeover");
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false));
    }

    @Test
    void stoppedTransportLeavesRecoveryPendingForRestart() {
        AlertEvent event = event(AlertStatus.RESOLVED);
        event.setProblemPending(false);
        event.setResolutionPending(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        Action action = action(OperationMode.ON_RESOLVE);
        action.setActionType(ActionType.EMAIL);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenReturn(List.of(action));
        ActionDispatcher.shutdownDispatchExecutor();

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertTrue(persistedRows.isEmpty());
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false), never());
        assertTrue(event.isResolutionPending());
    }

    @ParameterizedTest
    @ValueSource(strings = {"repeat", "open", "resolve", "paced"})
    void returningStoppedWorkerCannotReleaseRestartedWorkersClaim(String phase) throws Exception {
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch oldRelease = new CountDownLatch(1);
        CountDownLatch currentEntered = new CountDownLatch(1);
        CountDownLatch currentRelease = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread old = blockedDecision(phase, oldEntered, oldRelease, failure);
        Thread current = null;
        old.start();
        try {
            assertTrue(oldEntered.await(2, TimeUnit.SECONDS));
            // Model a worker whose JDBC call outlived executor shutdown. The
            // real worker body remains blocked while shutdown retires claims.
            ActionDispatcher.shutdownDispatchExecutor();
            ActionDispatcher.startDispatchExecutor();
            current = blockedDecision(phase, currentEntered, currentRelease, failure);
            current.start();
            assertTrue(currentEntered.await(2, TimeUnit.SECONDS));

            oldRelease.countDown();
            old.join(2_000);
            assertFalse(old.isAlive());
            assertNull(failure.get());

            // A third worker must still see the current worker's claim after
            // the stopped worker's finally has completed.
            runDecision(phase);
            alerts.verify(() -> AlertEventRepository.getAlertEvent(EVENT_ID), never());
        } finally {
            oldRelease.countDown();
            currentRelease.countDown();
            old.join(2_000);
            if (current != null) current.join(2_000);
        }
        assertFalse(old.isAlive(), "Stopped worker must exit before test cleanup");
        assertTrue(current == null || !current.isAlive(), "Current worker must exit before test cleanup");
        assertNull(failure.get());
    }

    private static Thread blockedDecision(String phase, CountDownLatch entered,
            CountDownLatch release, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try (MockedStatic<AlertEventRepository> workerAlerts = Mockito.mockStatic(AlertEventRepository.class);
                    MockedStatic<SentinelLeadership> workerLeadership = Mockito.mockStatic(SentinelLeadership.class)) {
                workerLeadership.when(() -> SentinelLeadership.recheckFence(any(LeaseFence.class))).thenReturn(true);
                workerAlerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenAnswer(invocation -> {
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                    return null;
                });
                runDecision(phase);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, "sentinel-claim-restart-test");
    }

    private static void runDecision(String phase) throws Exception {
        AlertEvent event = event("resolve".equals(phase) ? AlertStatus.RESOLVED : AlertStatus.PROBLEM);
        switch (phase) {
            case "repeat":
                ActionDispatcher.runRepeatCheck(event, payload("PROBLEM"));
                break;
            case "open":
                ActionDispatcher.dispatchLifecycle(event, payload("PROBLEM"), false);
                break;
            case "resolve":
                ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);
                break;
            case "paced":
                var method = ActionDispatcher.class.getDeclaredMethod("attemptPaced",
                        Action.class, AlertEvent.class, AlertPayload.class);
                method.setAccessible(true);
                method.invoke(null, action(OperationMode.ON_PROBLEM), event, payload("PROBLEM"));
                break;
            default:
                throw new IllegalArgumentException(phase);
        }
    }

    private void saturateDispatchQueue() throws Exception {
        ThreadPoolExecutor executor = dispatchExecutor();
        CountDownLatch workersEntered = new CountDownLatch(executor.getMaximumPoolSize());
        releaseWorkers = new CountDownLatch(1);
        for (int i = 0; i < executor.getMaximumPoolSize(); i++) {
            executor.execute(() -> {
                workersEntered.countDown();
                try {
                    releaseWorkers.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(workersEntered.await(2, TimeUnit.SECONDS), "all dispatch workers must be occupied");

        int capacity = executor.getQueue().remainingCapacity();
        for (int i = 0; i < capacity; i++) {
            executor.execute(() -> { });
        }
        assertEquals(0, executor.getQueue().remainingCapacity());
    }

    private static ThreadPoolExecutor dispatchExecutor() throws Exception {
        Field field = ActionDispatcher.class.getDeclaredField("dispatchExecutor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Object> problemClaims() throws Exception {
        Field field = ActionDispatcher.class.getDeclaredField("inFlightProblemDecisions");
        field.setAccessible(true);
        return (Map<Long, Object>) field.get(null);
    }

    private static Action action(OperationMode mode) {
        Action action = new Action();
        action.setId(12);
        action.setName("durability probe");
        action.setEnabled(true);
        action.setOperationMode(mode);
        // Deliberately null: the attempt fails before any transport call but
        // must still write its durable attempt row, which is the contract.
        action.setActionType(null);
        return action;
    }

    private static AlertEvent event(AlertStatus status) {
        AlertEvent event = new AlertEvent();
        event.setId(EVENT_ID);
        event.setMonitorId(7);
        event.setChannelId(CHANNEL_ID);
        event.setSeverity(Severity.HIGH);
        event.setStatus(status);
        event.setProblemPending(status == AlertStatus.PROBLEM);
        event.setMessage("durability test");
        event.setOpenedTime(Instant.now().minusSeconds(60));
        if (status == AlertStatus.RESOLVED) {
            event.setResolvedTime(Instant.now());
        }
        return event;
    }

    private static AlertPayload payload(String phase) {
        return new AlertPayload(EVENT_ID, 7, "Queue durability", MonitorType.INACTIVITY,
                CHANNEL_ID, "Channel A", null, Severity.HIGH, phase,
                "durability test", Instant.now().minusSeconds(60), null);
    }

    private ActionDispatchLog insertedRow() {
        ArgumentCaptor<ActionDispatchLog> row = ArgumentCaptor.forClass(ActionDispatchLog.class);
        dispatches.verify(() -> ActionDispatchLogRepository.insertActionDispatchLog(row.capture()));
        return row.getValue();
    }
}
