/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import org.openintegrationengine.plugins.sentinel.server.log.LogNotifications;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * An EVENT_TYPE condition sees the alert's own kind on every pass that matches
 * conditions: the initial selection, the authorization on the transport
 * thread, and the rollup's recipient selection. A notification is matched as
 * NOTIFICATION, never as PROBLEM.
 */
class NotificationEventTypeRoutingTest {

    private static final long NOTIFICATION_ID = 91L;
    private static final long PROBLEM_ID = 92L;
    private static final int UNFILTERED = 1;
    private static final int NOTIFICATION_ONLY = 2;
    private static final int PROBLEM_ONLY = 3;

    private MockedStatic<ActionRepository> actions;
    private MockedStatic<ActionDispatchLogRepository> dispatches;
    private MockedStatic<AlertEventRepository> alerts;
    private MockedStatic<NotificationSuppression> suppression;
    private MockedStatic<AlertStormControl> storm;
    private MockedStatic<SentinelLeadership> leadership;
    private MockedStatic<ActionDispatcher> dispatcher;
    private final List<Action> enabled = new ArrayList<>();
    private final List<ActionDispatchLog> rows = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private AlertEvent notification;
    private AlertEvent problem;

    @BeforeEach
    void setUp() {
        enabled.add(action(UNFILTERED, null));
        enabled.add(action(NOTIFICATION_ONLY, "NOTIFICATION"));
        enabled.add(action(PROBLEM_ONLY, "PROBLEM"));
        actions = ActionRepositoryTestSupport.mockActions();
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenReturn(enabled);
        actions.when(() -> ActionRepository.getAction(anyInt())).thenAnswer(call -> enabled.stream()
                .filter(candidate -> candidate.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        dispatches = mockStatic(ActionDispatchLogRepository.class);
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(anyLong()))
                .thenAnswer(call -> new ArrayList<>(rows));
        dispatches.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any()))
                .thenAnswer(call -> rows.add(call.getArgument(0)));

        notification = LogNotifications.notification(logMonitor(), "Matching ERROR log line from transformer",
                "{\"condition\":\"EACH\",\"count\":1}", Instant.now().minusSeconds(5));
        notification.setId(NOTIFICATION_ID);
        problem = new AlertEvent();
        problem.setId(PROBLEM_ID);
        problem.setMonitorId(8);
        problem.setSubjectType(SubjectType.SERVER);
        problem.setSeverity(Severity.HIGH);
        problem.setStatus(AlertStatus.PROBLEM);
        problem.setProblemPending(true);
        problem.setOpenedTime(Instant.now().minusSeconds(5));
        alerts = mockStatic(AlertEventRepository.class);
        alerts.when(() -> AlertEventRepository.getAlertEvent(NOTIFICATION_ID)).thenReturn(notification);
        alerts.when(() -> AlertEventRepository.getAlertEvent(PROBLEM_ID)).thenReturn(problem);

        suppression = mockStatic(NotificationSuppression.class);
        suppression.when(() -> NotificationSuppression.decision(any(AlertEvent.class), any(Instant.class)))
                .thenReturn(NotificationSuppression.Decision.ALLOW);
        storm = mockStatic(AlertStormControl.class, CALLS_REAL_METHODS);
        storm.when(() -> AlertStormControl.flapCheck(any(AlertEvent.class), any(Instant.class), anyBoolean()))
                .thenReturn(new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
        verdict(AlertStormControl.CeilingOutcome.SEND);
        leadership = mockStatic(SentinelLeadership.class);
        leadership.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);

        // The transport call runs its production guard on this thread, where
        // the static mocks live, then records who would have been sent what.
        dispatcher = mockStatic(ActionDispatcher.class, CALLS_REAL_METHODS);
        dispatcher.when(() -> ActionDispatcher.sendWithWallClock(any(), any(), any(), any(), anyLong(),
                any(TimeUnit.class), any(Runnable.class))).thenAnswer(call -> {
                    Runnable guard = call.getArgument(6);
                    guard.run();
                    Action action = call.getArgument(1);
                    AlertPayload payload = call.getArgument(3);
                    sent.add(action.getId() + ":" + payload.getEventType());
                    return null;
                });
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
        leadership.close();
        storm.close();
        suppression.close();
        alerts.close();
        dispatches.close();
        actions.close();
    }

    @Test
    void aNotificationReachesTheNotificationOnlyActionAndNotTheProblemOnlyOne() {
        ActionDispatcher.dispatchLifecycle(notification,
                AlertPayload.of(notification, logMonitor(), LogNotifications.EVENT_TYPE), false);

        assertEquals(List.of(UNFILTERED + ":NOTIFICATION", NOTIFICATION_ONLY + ":NOTIFICATION"), sent);
        alerts.verify(() -> AlertEventRepository.setProblemPending(NOTIFICATION_ID, false));
    }

    @Test
    void aLogProblemStillReachesTheProblemOnlyActionAndNotTheNotificationOnlyOne() {
        ActionDispatcher.dispatchLifecycle(problem, AlertPayload.of(problem, logMonitor(), "PROBLEM"), false);

        assertEquals(List.of(UNFILTERED + ":PROBLEM", PROBLEM_ONLY + ":PROBLEM"), sent);
    }

    @Test
    void aRolledUpNotificationIsStillMatchedAsANotification() {
        verdict(AlertStormControl.CeilingOutcome.ROLLUP);
        ActionDispatcher.dispatchLifecycle(notification,
                AlertPayload.of(notification, logMonitor(), LogNotifications.EVENT_TYPE), false);

        assertEquals(List.of(UNFILTERED + ":ROLLUP", NOTIFICATION_ONLY + ":ROLLUP"), sent);
    }

    @Test
    void rollupRecipientSelectionMatchesANotificationAsANotification() {
        alerts.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt())).thenReturn(List.of(notification));
        Instant now = Instant.now();
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class)) {
            monitors.when(() -> MonitorRepository.getMonitor(8)).thenReturn(logMonitor());

            assertEquals(List.of("Log"), RollupRecipients.channels(enabled.get(0), now.minusSeconds(600), now));
            assertEquals(List.of("Log"), RollupRecipients.channels(enabled.get(1), now.minusSeconds(600), now));
            assertEquals(List.of(), RollupRecipients.channels(enabled.get(2), now.minusSeconds(600), now));
        }
    }

    private void verdict(AlertStormControl.CeilingOutcome outcome) {
        storm.when(() -> AlertStormControl.ceilingVerdict(any(Action.class), any(Instant.class)))
                .thenReturn(new AlertStormControl.CeilingVerdict(outcome, 3, 600, List.of("Log")));
    }

    private static Monitor logMonitor() {
        Monitor monitor = new Monitor();
        monitor.setId(8);
        monitor.setName("Log errors");
        monitor.setEnabled(true);
        monitor.setMonitorType(MonitorType.LOG_EVENTS);
        monitor.setSeverity(Severity.HIGH);
        monitor.setConfigJson("{\"condition\":\"EACH\",\"outcome\":\"NOTIFICATION\"}");
        return monitor;
    }

    private static Action action(int id, String eventType) {
        Action action = new Action();
        action.setId(id);
        action.setName("action " + id);
        action.setEnabled(true);
        action.setActionType(ActionType.EMAIL);
        action.setConfigJson("{\"recipients\":\"ops@example.com\"}");
        action.setOperationMode(OperationMode.ON_PROBLEM);
        if (eventType != null) {
            action.setConditionJson("[{\"field\":\"EVENT_TYPE\",\"operator\":\"=\",\"value\":\"" + eventType + "\"}]");
        }
        return action;
    }
}
