/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * Matched lines leave the plugin only while the monitor includes them at the
 * moment of the send. The payload is built earlier, so the option is read
 * again on the transport thread, for individual sends and rollups alike.
 */
class MatchedLinesDisclosureAtSendTest {

    private static final String LINE = "PID 4711 failed for patient Jane Example";

    private final AtomicReference<Monitor> stored = new AtomicReference<>();
    private final AtomicBoolean lookupFails = new AtomicBoolean();
    private final AtomicReference<AlertPayload> sent = new AtomicReference<>();
    private ThreadPoolExecutor executor;
    private Field field;
    private Object previous;
    private AlertEvent event;

    @BeforeEach
    void setUp() throws Exception {
        event = new AlertEvent();
        event.setId(51L);
        event.setMonitorId(8);
        event.setSubjectType(SubjectType.SERVER);
        event.setSeverity(Severity.HIGH);
        event.setStatus(AlertStatus.PROBLEM);
        event.setMessage("1 matching log line in the last 300s (at least 1)");
        event.setOpenedTime(Instant.now());
        event.setDetailsJson("{\"condition\":\"AT_LEAST\",\"count\":1,\"matches\":[{\"time\":\"2026-11-25T12:00:00.000Z\","
                + "\"node\":\"node-a\",\"level\":\"ERROR\",\"logger\":\"transformer\",\"message\":\"" + LINE + "\"}]}");
        stored.set(monitor(true));
        // Static mocks are thread-local, so the transport thread installs its own.
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                task -> new Thread(() -> {
                    try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class);
                            MockedStatic<AlertEventRepository> events = mockStatic(AlertEventRepository.class)) {
                        monitors.when(() -> MonitorRepository.getMonitor(8)).thenAnswer(call -> {
                            if (lookupFails.get()) {
                                throw new IllegalStateException("database unavailable");
                            }
                            return stored.get();
                        });
                        events.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt())).thenReturn(List.of());
                        task.run();
                    }
                }, "sentinel-transport-disclosure-test"));
        field = ActionDispatcher.class.getDeclaredField("transportExecutor");
        field.setAccessible(true);
        previous = field.get(null);
        field.set(null, executor);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        field.set(null, previous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"individual", "rollup"})
    void turningMatchesOffAfterThePayloadWasBuiltLeavesTheLinesOutOfTheSend(String shape) throws Exception {
        AlertPayload payload = payload(shape);
        assertNotNull(payload.getMatches(), "built while the monitor included matches");

        send(payload, () -> stored.set(monitor(false)));

        assertNotNull(sent.get());
        assertNull(sent.get().getMatches());
        assertFalse(sent.get().getValueJson().contains(LINE), sent.get().getValueJson());
        assertFalse(Json.write(sent.get()).contains(LINE));
        assertTrue(sent.get().getValueJson().contains("\"count\":1"), "the rest of the value is kept");
    }

    @ParameterizedTest
    @ValueSource(strings = {"individual", "rollup"})
    void aMonitorDeletedAfterThePayloadWasBuiltSendsNoLines(String shape) throws Exception {
        send(payload(shape), () -> stored.set(null));

        assertNull(sent.get().getMatches());
        assertFalse(Json.write(sent.get()).contains(LINE));
    }

    @Test
    void aMonitorThatStillIncludesMatchesSendsTheLines() throws Exception {
        send(payload("individual"), () -> { });

        assertNotNull(sent.get().getMatches());
        assertTrue(Json.write(sent.get()).contains(LINE));
    }

    @Test
    void aSendWhoseMonitorCannotBeReadIsDeferredWithoutLines() {
        Exception failure = null;
        try {
            send(payload("individual"), () -> lookupFails.set(true));
        } catch (Exception e) {
            failure = e;
        }
        assertInstanceOf(ActionDispatcher.DispatchDeferredException.class, failure);
        assertNull(sent.get(), "nothing reached the transport");
    }

    private AlertPayload payload(String shape) {
        AlertPayload payload = AlertPayload.of(event, monitor(true), "PROBLEM");
        return shape.equals("rollup")
                ? payload.copy("ROLLUP", "summary").withRollupSummary(3, 600)
                : payload;
    }

    private void send(AlertPayload payload, Runnable beforeTransport) throws Exception {
        Action action = new Action();
        action.setId(5);
        action.setName("ops");
        action.setEnabled(true);
        action.setActionType(ActionType.CHANNEL);
        action.setOperationMode(OperationMode.ON_PROBLEM);
        ActionDispatcher.sendWithWallClock((a, e, p) -> sent.set(p), action, event, payload,
                5, TimeUnit.SECONDS, beforeTransport);
    }

    private static Monitor monitor(boolean includeMatches) {
        Monitor monitor = new Monitor();
        monitor.setId(8);
        monitor.setName("Log errors");
        monitor.setEnabled(true);
        monitor.setMonitorType(MonitorType.LOG_EVENTS);
        monitor.setSeverity(Severity.HIGH);
        monitor.setConfigJson("{\"includeMatches\":" + includeMatches + "}");
        return monitor;
    }
}
