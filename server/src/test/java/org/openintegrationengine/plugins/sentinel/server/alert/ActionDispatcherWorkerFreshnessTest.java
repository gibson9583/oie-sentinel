package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Executes the production dispatch guard on the actual transport worker, with no external I/O. */
class ActionDispatcherWorkerFreshnessTest {
    @ParameterizedTest
    @ValueSource(strings = {"unchanged", "disabled", "deleted", "filter", "phase", "credentials", "lookup-failure"})
    void editAfterDispatchDecisionIsRecheckedByTheIoWorker(String change) throws Exception {
        Action expected = action();
        AtomicReference<Action> stored = new AtomicReference<>(expected);
        AtomicBoolean unavailable = new AtomicBoolean();
        AlertEvent event = new AlertEvent(); event.setId(71L); event.setMonitorId(7);
        event.setChannelId("channel"); event.setStatus(AlertStatus.PROBLEM); event.setProblemPending(true);
        event.setOpenedTime(Instant.now());
        AlertPayload payload = new AlertPayload(71L, 7, "monitor", MonitorType.INACTIVITY,
                "channel", "Channel", null, Severity.HIGH, "PROBLEM", "problem", event.getOpenedTime(), "{}");
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger recorded = new AtomicInteger();
        CountDownLatch atWorkerGuard = new CountDownLatch(1);
        CountDownLatch allowWorkerGuard = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        ActionDispatcher.startDispatchExecutor();
        try (var actions = mockStatic(ActionRepository.class); var alerts = mockStatic(AlertEventRepository.class);
                var logs = mockStatic(ActionDispatchLogRepository.class); var policy = mockStatic(NotificationSuppression.class);
                var storm = mockStatic(AlertStormControl.class); var leader = mockStatic(SentinelLeadership.class);
                var dispatcher = mockStatic(ActionDispatcher.class, CALLS_REAL_METHODS)) {
            actions.when(() -> ActionRepository.listActions(true)).thenReturn(List.of(expected));
            actions.when(() -> ActionRepository.getAction(2)).thenAnswer(call -> stored.get());
            alerts.when(() -> AlertEventRepository.getAlertEvent(71L)).thenReturn(event);
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(71L)).thenReturn(List.of());
            logs.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any())).thenAnswer(call -> {
                recorded.incrementAndGet(); return null;
            });
            policy.when(() -> NotificationSuppression.decision(any(), any())).thenReturn(NotificationSuppression.Decision.ALLOW);
            leader.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);
            storm.when(() -> AlertStormControl.flapCheck(any(), any(), anyBoolean())).thenReturn(
                    new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
            var verdict = new AlertStormControl.CeilingVerdict(AlertStormControl.CeilingOutcome.SEND, 0, 0, List.of());
            storm.when(() -> AlertStormControl.ceilingVerdict(any(), any())).thenReturn(verdict);
            dispatcher.when(() -> ActionDispatcher.sendWithWallClock(any(), any(), any(), any(), anyLong(),
                    any(TimeUnit.class), any(Runnable.class))).thenAnswer(call -> {
                Runnable productionGuard = call.getArgument(6);
                // Mockito static mocks are thread-local. The helper invokes the
                // real wall-clock method; its worker receives isolated mocks
                // around the actual guard captured from the production attempt.
                Thread caller = new Thread(() -> {
                    try {
                        ActionDispatcher.sendWithWallClock((a, e, p) -> sent.incrementAndGet(), expected,
                                event, payload, 3, TimeUnit.SECONDS, () -> {
                                    assertTrue(Thread.currentThread().getName().startsWith("sentinel-transport-"));
                                    atWorkerGuard.countDown();
                                    try {
                                        assertTrue(allowWorkerGuard.await(2, TimeUnit.SECONDS));
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(interrupted);
                                    }
                                    try (var workerActions = mockStatic(ActionRepository.class);
                                            var workerAlerts = mockStatic(AlertEventRepository.class);
                                            var workerPolicy = mockStatic(NotificationSuppression.class);
                                            var workerLeader = mockStatic(SentinelLeadership.class)) {
                                        workerActions.when(() -> ActionRepository.getAction(2)).thenAnswer(read -> {
                                            if (unavailable.get()) throw new IllegalStateException("action lookup unavailable");
                                            return stored.get();
                                        });
                                        workerAlerts.when(() -> AlertEventRepository.getAlertEvent(71L)).thenReturn(event);
                                        workerPolicy.when(() -> NotificationSuppression.decision(any(), any()))
                                                .thenReturn(NotificationSuppression.Decision.ALLOW);
                                        workerLeader.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);
                                        productionGuard.run();
                                        workerActions.verify(() -> ActionRepository.getAction(2));
                                    }
                                });
                    } catch (Throwable error) { result.set(error); }
                }, "action-freshness-caller");
                caller.start();
                try {
                    assertTrue(atWorkerGuard.await(2, TimeUnit.SECONDS));
                    Action current = action();
                    switch (change) {
                        case "disabled" -> current.setEnabled(false);
                        case "deleted" -> current = null;
                        case "filter" -> current.setConditionJson(
                                "[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"other\"}]");
                        case "phase" -> current.setOperationMode(OperationMode.ON_RESOLVE);
                        case "credentials" -> current.setConfigJson("new-recipient");
                        case "lookup-failure" -> unavailable.set(true);
                    }
                    stored.set(current);
                } finally {
                    allowWorkerGuard.countDown();
                    caller.join(4_000);
                    assertFalse(caller.isAlive(), "owned caller must finish before test cleanup");
                }
                Throwable failure = result.get();
                if (failure != null) throw failure;
                return null;
            });
            ActionDispatcher.dispatchLifecycle(event, payload, false);
            boolean unchanged = change.equals("unchanged");
            assertEquals(unchanged ? 1 : 0, sent.get());
            assertEquals(unchanged ? 1 : 0, recorded.get(), "a cancelled pre-I/O decision is not an attempt");
            assertEquals(change.equals("credentials") || change.equals("lookup-failure"), event.isProblemPending());
            if (unchanged) storm.verify(() -> AlertStormControl.releaseReservation(any()), never());
            else storm.verify(() -> AlertStormControl.releaseReservation(verdict));
        } finally {
            allowWorkerGuard.countDown();
            ActionDispatcher.shutdownDispatchExecutor();
        }
    }

    private static Action action() {
        Action action = new Action(); action.setId(2); action.setName("worker-test");
        action.setEnabled(true); action.setActionType(ActionType.EMAIL); action.setOperationMode(OperationMode.BOTH);
        action.setConfigJson("old-recipient"); return action;
    }
}
