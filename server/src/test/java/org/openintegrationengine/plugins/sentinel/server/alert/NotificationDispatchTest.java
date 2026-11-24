/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.mirth.connect.server.controllers.ConfigurationController;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.server.engine.TriggerEvaluatorJob;
import org.openintegrationengine.plugins.sentinel.server.log.LogNotifications;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * A NOTIFICATION row through the dispatcher's worker body, driven
 * synchronously as the other dispatcher tests do: it is written RESOLVED with
 * its opened edge pending, takes that edge once and never a resolved one.
 */
class NotificationDispatchTest {

    private static final long EVENT_ID = 81L;
    private static final Instant OPENED = Instant.parse("2026-11-21T12:00:00Z");

    private MockedStatic<ActionRepository> actions;
    private MockedStatic<ActionDispatchLogRepository> dispatches;
    private MockedStatic<AlertEventRepository> alerts;
    private MockedStatic<NotificationSuppression> suppression;
    private MockedStatic<AlertStormControl> storm;
    private final List<Action> enabled = new ArrayList<>();
    private final List<ActionDispatchLog> rows = new ArrayList<>();
    private AlertEvent notification;

    @BeforeEach
    void setUp() {
        enabled.add(action(9, OperationMode.ON_PROBLEM));
        enabled.add(action(10, OperationMode.BOTH));
        enabled.add(action(11, OperationMode.ON_RESOLVE));
        actions = ActionRepositoryTestSupport.mockActions();
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenReturn(enabled);
        actions.when(() -> ActionRepository.getAction(Mockito.anyInt())).thenAnswer(call -> enabled.stream()
                .filter(candidate -> candidate.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        dispatches = Mockito.mockStatic(ActionDispatchLogRepository.class);
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(anyLong()))
                .thenAnswer(call -> new ArrayList<>(rows));
        dispatches.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any()))
                .thenAnswer(call -> rows.add(call.getArgument(0)));
        notification = LogNotifications.notification(logMonitor(), "Matching ERROR log line from transformer",
                "{\"condition\":\"EACH\",\"count\":1}", OPENED);
        notification.setId(EVENT_ID);
        alerts = Mockito.mockStatic(AlertEventRepository.class);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(notification);
        suppression = Mockito.mockStatic(NotificationSuppression.class);
        suppression.when(() -> NotificationSuppression.decision(any(AlertEvent.class), any(Instant.class)))
                .thenReturn(NotificationSuppression.Decision.ALLOW);
        storm = Mockito.mockStatic(AlertStormControl.class);
        storm.when(() -> AlertStormControl.flapCheck(any(AlertEvent.class), any(Instant.class), anyBoolean()))
                .thenReturn(new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
        verdict(AlertStormControl.CeilingOutcome.SEND);
    }

    @AfterEach
    void tearDown() {
        storm.close();
        suppression.close();
        alerts.close();
        dispatches.close();
        actions.close();
    }

    @Test
    void aNotificationReachesOnProblemAndBothActionsOnceAndCompletesItsOpenedEdge() {
        ActionDispatcher.dispatchLifecycle(notification, payload(), false);
        ActionDispatcher.dispatchLifecycle(notification, payload(), false);

        assertEquals(List.of(9, 10), rows.stream().map(ActionDispatchLog::getActionId).toList(),
                "never the ON_RESOLVE action, and nothing the second time");
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false));
        alerts.verify(() -> AlertEventRepository.setResolutionPending(anyLong(), anyBoolean()), never());
    }

    @Test
    void theResolvedPhaseNeverSendsANotification() {
        ActionDispatcher.dispatchLifecycle(notification, payload(), true);

        assertEquals(List.of(), rows);
        alerts.verify(() -> AlertEventRepository.setProblemPending(anyLong(), anyBoolean()), never());
        alerts.verify(() -> AlertEventRepository.setResolutionPending(anyLong(), anyBoolean()), never());
    }

    @Test
    void aSuppressingPolicyConsumesTheNotificationWithoutSendingIt() {
        policy(NotificationSuppression.Decision.SUPPRESS);
        ActionDispatcher.dispatchLifecycle(notification, payload(), false);

        assertEquals(List.of(), rows);
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false));
    }

    @Test
    void anUnknownPolicyLeavesTheNotificationPending() {
        policy(NotificationSuppression.Decision.UNKNOWN);
        ActionDispatcher.dispatchLifecycle(notification, payload(), false);

        assertEquals(List.of(), rows);
        alerts.verify(() -> AlertEventRepository.setProblemPending(anyLong(), anyBoolean()), never());
    }

    @Test
    void aSilentCeilingCountsTheNotificationAsAccountedButNotAProblem() {
        verdict(AlertStormControl.CeilingOutcome.SILENT);
        ActionDispatcher.dispatchLifecycle(notification, payload(), false);
        assertEquals(List.of(), rows);
        alerts.verify(() -> AlertEventRepository.setProblemPending(EVENT_ID, false));

        AlertEvent problem = new AlertEvent();
        problem.setId(82L);
        problem.setMonitorId(8);
        problem.setSubjectType(SubjectType.SERVER);
        problem.setSeverity(Severity.HIGH);
        problem.setStatus(AlertStatus.PROBLEM);
        problem.setProblemPending(true);
        problem.setOpenedTime(OPENED);
        alerts.when(() -> AlertEventRepository.getAlertEvent(82L)).thenReturn(problem);
        ActionDispatcher.dispatchLifecycle(problem, AlertPayload.of(problem, logMonitor(), "PROBLEM"), false);
        alerts.verify(() -> AlertEventRepository.setProblemPending(82L, false), never());
    }

    @Test
    void aNotificationNeverFlapsAndReadsNoHistory() {
        storm.when(() -> AlertStormControl.flapCheck(any(AlertEvent.class), any(Instant.class), anyBoolean()))
                .thenCallRealMethod();
        alerts.when(() -> AlertEventRepository.listAlertEvents(any())).thenReturn(new PagedResult<>(List.of(), 0L, 0, 100));

        assertEquals(AlertStormControl.FlapOutcome.NOT_FLAPPING,
                AlertStormControl.flapCheck(notification, OPENED, false).outcome());
        alerts.verify(() -> AlertEventRepository.listAlertEvents(any()), never());
    }

    @Test
    void theOutboxPassSendsAPendingNotificationAsAnOpenedEdge() {
        AlertEvent problem = new AlertEvent();
        problem.setId(82L);
        problem.setMonitorId(8);
        problem.setSubjectType(SubjectType.SERVER);
        problem.setStatus(AlertStatus.PROBLEM);
        problem.setProblemPending(true);
        alerts.when(AlertEventRepository::listPendingProblemAlertEvents).thenReturn(List.of(notification, problem));
        ConfigurationController started = Mockito.mock(ConfigurationController.class);
        Mockito.when(started.getStatus(false)).thenReturn(ConfigurationController.STATUS_OK);
        try (MockedStatic<MonitorRepository> monitors = Mockito.mockStatic(MonitorRepository.class);
                MockedStatic<ActionDispatcher> dispatcher = Mockito.mockStatic(ActionDispatcher.class);
                MockedStatic<ConfigurationController> engine = Mockito.mockStatic(ConfigurationController.class)) {
            monitors.when(() -> MonitorRepository.getMonitor(8)).thenReturn(logMonitor());
            engine.when(ConfigurationController::getInstance).thenReturn(started);

            new TriggerEvaluatorJob().execute(null);

            ArgumentCaptor<AlertPayload> payload = ArgumentCaptor.forClass(AlertPayload.class);
            dispatcher.verify(() -> ActionDispatcher.onAlertOpened(eq(notification), payload.capture()));
            assertEquals("NOTIFICATION", payload.getValue().getEventType());
            assertEquals("Log", payload.getValue().getChannelName());
            dispatcher.verify(() -> ActionDispatcher.onRepeatCheck(eq(notification), any()), never());
            dispatcher.verify(() -> ActionDispatcher.onRepeatCheck(eq(problem), argThat(p -> "PROBLEM".equals(p.getEventType()))));
            dispatcher.verify(() -> ActionDispatcher.onAlertOpened(eq(problem), any()), never());
        }
    }

    private void policy(NotificationSuppression.Decision decision) {
        suppression.when(() -> NotificationSuppression.decision(any(AlertEvent.class), any(Instant.class)))
                .thenReturn(decision);
    }

    private void verdict(AlertStormControl.CeilingOutcome outcome) {
        storm.when(() -> AlertStormControl.ceilingVerdict(any(Action.class), any(Instant.class)))
                .thenReturn(new AlertStormControl.CeilingVerdict(outcome, 3, 600, List.of()));
    }

    private AlertPayload payload() {
        return AlertPayload.of(notification, logMonitor(), LogNotifications.EVENT_TYPE);
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

    private static Action action(int id, OperationMode mode) {
        Action action = new Action();
        action.setId(id);
        action.setName("action " + id);
        action.setEnabled(true);
        action.setOperationMode(mode);
        return action;
    }
}
