package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class RollupTransportScopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"suppressed", "membership", "lookup-failure"})
    void queuedRollupRechecksOtherSubjectsBeforeDelivery(String change) throws Exception {
        Instant now = Instant.now();
        AlertEvent origin = event(1, "origin", now);
        AlertEvent other = event(2, "other-sensitive", now);
        Monitor monitor = new Monitor(); monitor.setId(7); monitor.setEnabled(true);
        monitor.setMonitorType(MonitorType.INACTIVITY);
        Action action = new Action(); action.setId(5); action.setEnabled(true);
        action.setOperationMode(OperationMode.ON_PROBLEM);
        action.setConditionJson("[{\"field\":\"CHANNEL_GROUP\",\"operator\":\"=\",\"value\":\"group\"}]");
        var originalVerdict = new AlertStormControl.CeilingVerdict(AlertStormControl.CeilingOutcome.ROLLUP,
                3, 600, List.of("origin", "other-sensitive"));
        AlertPayload payload = new AlertPayload(1, 7, "monitor", MonitorType.INACTIVITY, "origin", "origin",
                null, Severity.HIGH, "ROLLUP", AlertStormControl.rollupMessage(originalVerdict), now, "{}")
                .withRollupSummary(3, 600);
        assertTrue(payload.getMessage().contains("other-sensitive"));
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean changed = new AtomicBoolean();
        AtomicBoolean guardRan = new AtomicBoolean();
        AtomicReference<AlertPayload> sent = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> new Thread(() -> {
                    try (var events = mockStatic(AlertEventRepository.class);
                            var monitors = mockStatic(MonitorRepository.class);
                            var scopes = mockStatic(ScopeResolver.class);
                            var policy = mockStatic(NotificationSuppression.class)) {
                        events.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt())).thenAnswer(c -> {
                            if (change.equals("lookup-failure") && changed.get()) throw new IllegalStateException("unavailable");
                            return List.of(origin, other);
                        });
                        events.when(() -> AlertEventRepository.getAlertEvent(1L)).thenReturn(origin);
                        events.when(() -> AlertEventRepository.getAlertEvent(2L)).thenReturn(other);
                        monitors.when(() -> MonitorRepository.getMonitor(7)).thenReturn(monitor);
                        scopes.when(() -> ScopeResolver.channelName(anyString())).thenAnswer(c -> c.getArgument(0));
                        scopes.when(() -> ScopeResolver.groupChannelIds("group")).thenAnswer(c ->
                                changed.get() && change.equals("membership") ? Set.of("origin") : Set.of("origin", "other-sensitive"));
                        policy.when(() -> NotificationSuppression.decision(any(), any())).thenAnswer(c ->
                                c.getArgument(0) == other && changed.get() && change.equals("suppressed")
                                        ? NotificationSuppression.Decision.SUPPRESS : NotificationSuppression.Decision.ALLOW);
                        workerEntered.countDown();
                        assertTrue(releaseWorker.await(2, TimeUnit.SECONDS));
                        task.run();
                    } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
                }, "sentinel-rollup-scope-test"));
        var field = ActionDispatcher.class.getDeclaredField("transportExecutor"); field.setAccessible(true);
        Object previous = field.get(null); field.set(null, executor);
        Thread caller = new Thread(() -> {
            try {
                ActionDispatcher.sendWithWallClock((a, e, p) -> sent.set(p), action, origin, payload,
                        5, TimeUnit.SECONDS, () -> guardRan.set(true));
            } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        }, "sentinel-rollup-caller-test");
        try {
            caller.start(); assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
            // The body was already built and the work submitted; only the
            // other subject changes. Origin/action remain fully eligible.
            changed.set(true); releaseWorker.countDown(); caller.join(3000); assertFalse(caller.isAlive());
            if (change.equals("lookup-failure")) {
                assertInstanceOf(ActionDispatcher.DispatchDeferredException.class, failure.get());
                assertNull(sent.get()); assertFalse(guardRan.get(), "no transport attempt to charge");
            } else {
                assertNull(failure.get()); assertTrue(guardRan.get()); assertNotNull(sent.get());
                assertTrue(sent.get().getMessage().contains("origin"));
                assertFalse(sent.get().getMessage().contains("other-sensitive"));
                assertTrue(sent.get().getMessage().contains("3 notification(s) in 600 seconds"));
            }
        } finally {
            releaseWorker.countDown(); caller.join(3000); executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); field.set(null, previous);
        }
    }

    @Test void internalRollupContextNeverChangesTheSerializedPayload() {
        AlertPayload payload = new AlertPayload(1, 7, "monitor", MonitorType.INACTIVITY, "origin", "origin",
                null, Severity.HIGH, "ROLLUP", "summary", Instant.EPOCH, "{}");
        assertEquals(Json.write(payload), Json.write(payload.withRollupSummary(3, 600)));
    }

    private static AlertEvent event(long id, String channel, Instant now) {
        AlertEvent event = new AlertEvent(); event.setId(id); event.setMonitorId(7); event.setChannelId(channel);
        event.setStatus(AlertStatus.PROBLEM); event.setSeverity(Severity.HIGH); event.setOpenedTime(now.minusSeconds(5));
        return event;
    }
}
