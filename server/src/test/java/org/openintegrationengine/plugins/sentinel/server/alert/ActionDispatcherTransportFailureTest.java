package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ActionDispatcherTransportFailureTest {
    @BeforeEach
    void startExecutors() {
        ActionDispatcher.startDispatchExecutor();
    }

    @AfterEach
    void stopExecutors() {
        ActionDispatcher.shutdownDispatchExecutor();
    }

    @ParameterizedTest
    @CsvSource({"false,1", "false,2", "true,1", "true,2"})
    void failedFinalFenceQueryLeavesTheUnsentEdgeRetryable(boolean resolved, int guardToFail) {
        try (Fixture fixture = new Fixture(resolved, guardToFail, false)) {
            fixture.dispatch();

            assertTrue(fixture.leaseFailureInjected.get(), "exercise the selected post-policy fence query");
            assertEquals(0, fixture.sends.get());
            assertTrue(fixture.rows.isEmpty(), "pre-transport uncertainty must not manufacture an attempt");
            assertTrue(fixture.pending(), "the unsent edge must remain in its durable outbox");
            fixture.storm.verify(() -> AlertStormControl.releaseReservation(fixture.verdict));
            fixture.alerts.verify(() -> AlertEventRepository.setProblemPending(Fixture.EVENT_ID, false), never());
            fixture.alerts.verify(() -> AlertEventRepository.setResolutionPending(Fixture.EVENT_ID, false), never());

            fixture.dispatch();
            fixture.dispatch();

            assertEquals(1, fixture.sends.get(), "the recovered edge is sent only once");
            assertEquals(1, fixture.rows.size());
            assertTrue(fixture.rows.get(0).isSuccess());
            assertEquals(resolved ? ActionDispatcher.RESOLUTION_MARKER : null,
                    fixture.rows.get(0).getErrorMessage());
            assertFalse(fixture.pending());
            fixture.storm.verify(() -> AlertStormControl.releaseReservation(fixture.verdict));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock", "cleanup"})
    void authorizationTransactionFailureDefersAndRetries(String boundary) {
        try (Fixture fixture = new Fixture(false, 0, false)) {
            fixture.failValidationAfterCallbacks = boundary.equals("cleanup");
            fixture.validationFailure.set(fixture.failValidationAfterCallbacks
                    ? new AssertionError("transaction cleanup failed")
                    : new IllegalStateException("action lock query failed"));
            fixture.dispatch();

            assertNull(fixture.validationFailure.get(), "exercise the selected authorization failure");
            assertEquals(0, fixture.sends.get());
            assertTrue(fixture.rows.isEmpty());
            assertTrue(fixture.pending());
            fixture.storm.verify(() -> AlertStormControl.releaseReservation(fixture.verdict));

            fixture.dispatch();
            fixture.dispatch();

            assertEquals(1, fixture.sends.get());
            assertEquals(1, fixture.rows.size());
            assertTrue(fixture.rows.get(0).isSuccess());
            assertFalse(fixture.pending());
            fixture.storm.verify(() -> AlertStormControl.releaseReservation(fixture.verdict));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void suppressionLatchIsPersistedOnlyAfterAuthorizationTransactionCloses(int guardToSuppress) {
        try (Fixture fixture = new Fixture(false, 0, false)) {
            fixture.guardToSuppress = guardToSuppress;
            fixture.dispatch();

            assertEquals(0, fixture.sends.get());
            assertTrue(fixture.rows.isEmpty());
            assertTrue(fixture.pending());
            assertTrue(fixture.event.isSuppressed());
            assertEquals(1, fixture.suppressionWrites.get(), "persist the suppression latch after releasing locks");
            fixture.storm.verify(() -> AlertStormControl.releaseReservation(fixture.verdict));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void senderFailureStillRecordsAnAttemptAndConsumesTheEdge(boolean resolved) {
        try (Fixture fixture = new Fixture(resolved, 0, true)) {
            fixture.dispatch();
            fixture.dispatch();

            assertEquals(1, fixture.sends.get());
            assertEquals(1, fixture.rows.size());
            ActionDispatchLog row = fixture.rows.get(0);
            assertFalse(row.isSuccess());
            assertEquals((resolved ? ActionDispatcher.RESOLUTION_MARKER + " " : "") + "sender unavailable",
                    row.getErrorMessage());
            assertFalse(fixture.pending(), "a real failed attempt is durably accounted");
            fixture.storm.verify(() -> AlertStormControl.releaseReservation(any()), never());
        }
    }

    private static final class Fixture implements AutoCloseable {
        static final long EVENT_ID = 680L;
        final boolean resolved;
        final int guardToFail;
        final boolean failSender;
        final AlertEvent event = new AlertEvent();
        final Action action = new Action();
        final AlertPayload payload;
        final List<ActionDispatchLog> rows = new ArrayList<>();
        final AtomicInteger sends = new AtomicInteger();
        final AtomicBoolean leaseFailureInjected = new AtomicBoolean();
        final AtomicReference<Throwable> validationFailure = new AtomicReference<>();
        final AtomicInteger suppressionWrites = new AtomicInteger();
        boolean failValidationAfterCallbacks;
        int guardToSuppress;
        final AlertStormControl.CeilingVerdict verdict = new AlertStormControl.CeilingVerdict(
                AlertStormControl.CeilingOutcome.SEND, 0, 0, List.of());
        final MockedStatic<ActionRepository> actions = ActionRepositoryTestSupport.mockActions();
        final MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class);
        final MockedStatic<TriggerStateRepository> triggers = mockStatic(TriggerStateRepository.class);
        final MockedStatic<ActionDispatchLogRepository> logs = mockStatic(ActionDispatchLogRepository.class);
        final MockedStatic<NotificationSuppression> policy = mockStatic(NotificationSuppression.class);
        final MockedStatic<AlertStormControl> storm = mockStatic(AlertStormControl.class);
        final MockedStatic<SentinelLeadership> leadership = mockStatic(SentinelLeadership.class);
        final MockedStatic<ActionDispatcher> dispatcher = mockStatic(ActionDispatcher.class, CALLS_REAL_METHODS);

        Fixture(boolean resolved, int guardToFail, boolean failSender) {
            this.resolved = resolved;
            this.guardToFail = guardToFail;
            this.failSender = failSender;
            event.setId(EVENT_ID); event.setMonitorId(68); event.setChannelId("channel");
            event.setStatus(resolved ? AlertStatus.RESOLVED : AlertStatus.PROBLEM);
            event.setProblemPending(!resolved); event.setResolutionPending(resolved);
            event.setOpenedTime(Instant.now());
            action.setId(1); action.setName("failure-boundary"); action.setEnabled(true);
            action.setActionType(ActionType.EMAIL); action.setConfigJson("recipient");
            action.setOperationMode(resolved ? OperationMode.ON_RESOLVE : OperationMode.ON_PROBLEM);
            payload = new AlertPayload(EVENT_ID, 68, "monitor", MonitorType.INACTIVITY,
                    "channel", "Channel", null, Severity.HIGH, resolved ? "RESOLVED" : "PROBLEM",
                    "message", event.getOpenedTime(), "{}");
            actions.when(() -> ActionRepository.listActions(true)).thenReturn(List.of(action));
            actions.when(() -> ActionRepository.getAction(1)).thenReturn(action);
            alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                    .thenAnswer(call -> List.copyOf(rows));
            logs.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any())).thenAnswer(call -> {
                rows.add(call.getArgument(0)); return null;
            });
            policy.when(() -> NotificationSuppression.decision(any(), any()))
                    .thenReturn(NotificationSuppression.Decision.ALLOW);
            leadership.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);
            storm.when(() -> AlertStormControl.flapCheck(any(), any(), anyBoolean())).thenReturn(
                    new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
            storm.when(() -> AlertStormControl.ceilingVerdict(any(), any())).thenReturn(verdict);
            dispatcher.when(() -> ActionDispatcher.sendWithWallClock(any(), any(), any(), any(), anyLong(),
                    any(TimeUnit.class), any(Runnable.class))).thenAnswer(call -> {
                sendOnWorker(call.getArgument(6));
                return null;
            });
        }

        void dispatch() {
            ActionDispatcher.dispatchLifecycle(event, payload, resolved);
        }

        boolean pending() {
            return resolved ? event.isResolutionPending() : event.isProblemPending();
        }

        void sendOnWorker(Runnable productionGuard) throws Throwable {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // Static mocks are thread-local. A separate caller reaches the real wall-clock
            // implementation, and installs repository mocks around its production worker guard.
            Thread caller = new Thread(() -> {
                try {
                    ActionDispatcher.sendWithWallClock((a, e, p) -> {
                        sends.incrementAndGet();
                        if (failSender) throw new IllegalStateException("sender unavailable");
                    }, action, event, payload, 3, TimeUnit.SECONDS, () -> {
                        assertTrue(Thread.currentThread().getName().startsWith("sentinel-transport-"));
                        try (var workerActions = ActionRepositoryTestSupport.mockActions();
                                var workerAlerts = mockStatic(AlertEventRepository.class);
                                var workerTriggers = mockStatic(TriggerStateRepository.class);
                                var workerPolicy = mockStatic(NotificationSuppression.class);
                                var workerLeadership = mockStatic(SentinelLeadership.class)) {
                            AtomicInteger policyChecks = new AtomicInteger();
                            AtomicBoolean failNextFence = new AtomicBoolean();
                            AtomicBoolean inValidation = new AtomicBoolean();
                            workerActions.when(() -> ActionRepository.getAction(1)).thenReturn(action);
                            workerActions.when(() -> ActionRepository.validateDispatch(
                                    anyCollection(), any(Runnable.class), any(Runnable.class))).thenAnswer(call -> {
                                Throwable transactionFailure = validationFailure.getAndSet(null);
                                inValidation.set(true);
                                try {
                                    if (transactionFailure != null && !failValidationAfterCallbacks) throw transactionFailure;
                                    Runnable validateActions = call.getArgument(1);
                                    Runnable validateState = call.getArgument(2);
                                    validateState.run();
                                    validateActions.run();
                                    validateState.run();
                                } finally {
                                    inValidation.set(false);
                                    if (transactionFailure != null && failValidationAfterCallbacks) throw transactionFailure;
                                }
                                return null;
                            });
                            workerAlerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
                            workerAlerts.when(() -> AlertEventRepository.setAlertEventSuppressed(EVENT_ID, true))
                                    .thenAnswer(call -> {
                                        assertFalse(inValidation.get(), "suppression persistence must not nest transactions");
                                        suppressionWrites.incrementAndGet();
                                        return null;
                                    });
                            workerPolicy.when(() -> NotificationSuppression.decision(any(), any())).thenAnswer(call -> {
                                int check = policyChecks.incrementAndGet();
                                if (check == guardToFail) failNextFence.set(true);
                                return check == guardToSuppress ? NotificationSuppression.Decision.SUPPRESS
                                        : NotificationSuppression.Decision.ALLOW;
                            });
                            workerLeadership.when(() -> SentinelLeadership.recheckFence(any())).thenAnswer(call -> {
                                if (failNextFence.getAndSet(false) && leaseFailureInjected.compareAndSet(false, true)) {
                                    // A failed lease query propagates an unchecked repository exception.
                                    throw new IllegalStateException("lease database unavailable");
                                }
                                return true;
                            });
                            productionGuard.run();
                        }
                    });
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "transport-failure-test-caller");
            caller.start();
            try {
                caller.join(5_000);
            } finally {
                if (caller.isAlive()) {
                    caller.interrupt();
                    caller.join(1_000);
                }
            }
            assertFalse(caller.isAlive(), "owned caller must finish before executor cleanup");
            if (failure.get() != null) throw failure.get();
        }

        @Override
        public void close() {
            dispatcher.close(); leadership.close(); storm.close(); policy.close();
            logs.close(); triggers.close(); alerts.close(); actions.close();
        }
    }
}
