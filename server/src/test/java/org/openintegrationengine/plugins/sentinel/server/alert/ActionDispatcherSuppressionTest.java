/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

class ActionDispatcherSuppressionTest {

    private static final long EVENT_ID = 71L;

    private MockedStatic<ActionRepository> actions;
    private MockedStatic<ActionDispatchLogRepository> dispatches;
    private MockedStatic<AlertEventRepository> alerts;
    private MockedStatic<TriggerStateRepository> states;
    private MockedStatic<NotificationSuppression> suppression;
    private MockedStatic<AlertStormControl> storm;
    private Action action;

    @BeforeEach
    void setUp() {
        action = new Action();
        action.setId(9);
        action.setName("pager");
        action.setEnabled(true);
        action.setOperationMode(OperationMode.BOTH);

        actions = Mockito.mockStatic(ActionRepository.class);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenReturn(List.of(action));
        dispatches = Mockito.mockStatic(ActionDispatchLogRepository.class);
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                .thenReturn(List.of(problemRow(9)));
        alerts = Mockito.mockStatic(AlertEventRepository.class);
        states = Mockito.mockStatic(TriggerStateRepository.class);
        TriggerState state = new TriggerState();
        state.setMonitorId(7);
        state.setChannelId("channel-a");
        states.when(() -> TriggerStateRepository.getTriggerState(7, "channel-a", null))
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
    }

    @AfterEach
    void tearDown() {
        storm.close();
        suppression.close();
        states.close();
        alerts.close();
        dispatches.close();
        actions.close();
    }

    @Test
    void openDecisionRechecksPolicyImmediatelyBeforeTheActionAttempt() {
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                .thenReturn(List.of());
        suppression.when(() -> NotificationSuppression.decision(
                any(AlertEvent.class), any(Instant.class)))
                .thenReturn(NotificationSuppression.Decision.ALLOW,
                        NotificationSuppression.Decision.SUPPRESS);
        AlertEvent event = event(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);

        ActionDispatcher.dispatchLifecycle(event, payload("PROBLEM"), false);

        assertNoAttempt();
        alerts.verify(() -> AlertEventRepository.setAlertEventSuppressed(EVENT_ID, true));
    }

    @Test
    void eligibleOpenDecisionDispatches() {
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                .thenReturn(List.of());
        AlertEvent event = event(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        ActionDispatcher.dispatchLifecycle(event, payload("PROBLEM"), false);

        assertOneAttempt();
    }

    @Test
    void policyIsRecheckedAfterCeilingWorkAtTheTransportBoundary() {
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                .thenReturn(List.of());
        suppression.when(() -> NotificationSuppression.decision(
                any(AlertEvent.class), any(Instant.class)))
                .thenReturn(NotificationSuppression.Decision.ALLOW,
                        NotificationSuppression.Decision.ALLOW,
                        NotificationSuppression.Decision.SUPPRESS);
        AlertEvent event = event(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);

        ActionDispatcher.dispatchLifecycle(event, payload("PROBLEM"), false);

        assertNoAttempt();
        storm.verify(() -> AlertStormControl.ceilingVerdict(
                any(Action.class), any(Instant.class)));
        storm.verify(() -> AlertStormControl.releaseReservation(
                any(AlertStormControl.CeilingVerdict.class)));
    }

    @Test
    void resolveDecisionIsSuppressedByCurrentPolicy() {
        suppression.when(() -> NotificationSuppression.decision(
                any(AlertEvent.class), any(Instant.class)))
                .thenReturn(NotificationSuppression.Decision.SUPPRESS);

        AlertEvent event = event(AlertStatus.RESOLVED);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertNoAttempt();
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false));
    }

    @Test
    void eligibleResolveDecisionDispatches() {
        AlertEvent event = event(AlertStatus.RESOLVED);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertOneAttempt();
    }

    @Test
    void transientPolicyFailureLeavesOneShotResolvePendingForRetry() {
        suppression.when(() -> NotificationSuppression.decision(
                any(AlertEvent.class), any(Instant.class)))
                .thenReturn(NotificationSuppression.Decision.UNKNOWN);
        AlertEvent event = event(AlertStatus.RESOLVED);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertNoAttempt();
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false), never());
    }

    @Test
    void pendingResolveRetriesAfterTransientPolicyFailure() {
        suppression.when(() -> NotificationSuppression.decision(
                any(AlertEvent.class), any(Instant.class)))
                .thenReturn(NotificationSuppression.Decision.UNKNOWN,
                        NotificationSuppression.Decision.ALLOW,
                        NotificationSuppression.Decision.ALLOW,
                        NotificationSuppression.Decision.ALLOW);
        AlertEvent event = event(AlertStatus.RESOLVED);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);
        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertOneAttempt();
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false));
    }

    @Test
    void neverAnnouncedSuppressedIncidentDoesNotEmitALoneRecovery() {
        AlertEvent event = event(AlertStatus.RESOLVED);
        event.setSuppressed(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                .thenReturn(List.of());

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertNoAttempt();
    }

    @Test
    void previouslyAnnouncedIncidentCanResolveAfterSuppressionEnds() {
        AlertEvent event = event(AlertStatus.RESOLVED);
        event.setSuppressed(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        ActionDispatchLog prior = new ActionDispatchLog();
        prior.setAlertEventId(EVENT_ID);
        prior.setActionId(9);
        prior.setDispatchTime(Instant.parse("2026-08-31T10:05:00Z"));
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                .thenReturn(List.of(prior));

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertOneAttempt();
        alerts.verify(() -> AlertEventRepository.setAlertEventSuppressed(EVENT_ID, false));
    }

    @Test
    void oneActionsProblemRowDoesNotAuthorizeAnotherActionsLoneRecovery() {
        Action second = new Action();
        second.setId(10);
        second.setName("secondary");
        second.setEnabled(true);
        second.setOperationMode(OperationMode.BOTH);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(action, second));
        AlertEvent event = event(AlertStatus.RESOLVED);
        event.setSuppressed(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        ActionDispatchLog prior = new ActionDispatchLog();
        prior.setAlertEventId(EVENT_ID);
        prior.setActionId(9);
        prior.setDispatchTime(Instant.parse("2026-08-31T10:05:00Z"));
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                .thenReturn(List.of(prior));

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertOneAttempt();
    }

    @Test
    void resolveOnlyActionRetainsItsConfiguredRecoverySemantics() {
        action.setOperationMode(OperationMode.ON_RESOLVE);
        AlertEvent event = event(AlertStatus.RESOLVED);
        event.setSuppressed(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertOneAttempt();
    }

    @Test
    void failedSnapshotClearDoesNotBlockOneShotResolve() {
        action.setOperationMode(OperationMode.ON_RESOLVE);
        AlertEvent event = event(AlertStatus.RESOLVED);
        event.setSuppressed(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);
        alerts.when(() -> AlertEventRepository.setAlertEventSuppressed(EVENT_ID, false))
                .thenThrow(new RuntimeException("state write unavailable"));

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        assertOneAttempt();
    }

    @Test
    void partialResolutionRetrySkipsActionsWithDurableResolutionRows() {
        Action second = new Action();
        second.setId(10);
        second.setName("secondary");
        second.setEnabled(true);
        second.setOperationMode(OperationMode.BOTH);
        actions.when(() -> ActionRepository.listActions(Boolean.TRUE))
                .thenReturn(List.of(action, second));
        List<ActionDispatchLog> rows = new ArrayList<>();
        rows.add(problemRow(9));
        rows.add(problemRow(10));
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(EVENT_ID))
                .thenAnswer(invocation -> rows);
        boolean[] failSecondOnce = {true};
        dispatches.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any()))
                .thenAnswer(invocation -> {
                    ActionDispatchLog row = invocation.getArgument(0);
                    if (Integer.valueOf(10).equals(row.getActionId()) && failSecondOnce[0]) {
                        failSecondOnce[0] = false;
                        throw new RuntimeException("dispatch log unavailable");
                    }
                    rows.add(row);
                    return null;
                });
        AlertEvent event = event(AlertStatus.RESOLVED);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(event);

        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);
        ActionDispatcher.dispatchLifecycle(event, payload("RESOLVED"), true);

        ArgumentCaptor<ActionDispatchLog> captor = ArgumentCaptor.forClass(ActionDispatchLog.class);
        dispatches.verify(() -> ActionDispatchLogRepository.insertActionDispatchLog(captor.capture()),
                Mockito.times(3));
        assertEquals(List.of(9, 10, 10), captor.getAllValues().stream()
                .map(ActionDispatchLog::getActionId).toList());
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false));
    }

    @Test
    void queuedOpenDecisionCannotSendAfterTheEventResolved() {
        AlertEvent queued = event(AlertStatus.PROBLEM);
        AlertEvent current = event(AlertStatus.RESOLVED);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(current);

        ActionDispatcher.dispatchLifecycle(queued, payload("PROBLEM"), false);

        assertNoAttempt();
    }

    @Test
    void repeatCannotBecomeAnExtraProblemEdgeIfRecoveryCommitsDuringFanout() {
        AlertEvent open = event(AlertStatus.PROBLEM);
        open.setProblemPending(false);
        AlertEvent resolved = event(AlertStatus.RESOLVED);
        resolved.setProblemPending(false);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(open, resolved);
        action.setRepeatIntervalSeconds(1);

        ActionDispatcher.runRepeatCheck(open, payload("PROBLEM"));

        assertNoAttempt();
    }

    @Test
    void oldRecoveryIsRetiredWhenANewerProblemOwnsTheTrigger() {
        AlertEvent resolved = event(AlertStatus.RESOLVED);
        AlertEvent newer = event(AlertStatus.PROBLEM);
        newer.setId(72L);
        alerts.when(() -> AlertEventRepository.getAlertEvent(EVENT_ID)).thenReturn(resolved);
        alerts.when(() -> AlertEventRepository.getAlertEvent(72L)).thenReturn(newer);
        TriggerState state = new TriggerState();
        state.setMonitorId(7);
        state.setChannelId("channel-a");
        state.setOpenAlertEventId(72L);
        states.when(() -> TriggerStateRepository.getTriggerState(7, "channel-a", null))
                .thenReturn(state);

        ActionDispatcher.dispatchLifecycle(resolved, payload("RESOLVED"), true);

        assertNoAttempt();
        alerts.verify(() -> AlertEventRepository.setResolutionPending(EVENT_ID, false));
    }

    private static AlertEvent event(AlertStatus status) {
        AlertEvent event = new AlertEvent();
        event.setId(EVENT_ID);
        event.setMonitorId(7);
        event.setChannelId("channel-a");
        event.setStatus(status);
        event.setProblemPending(status == AlertStatus.PROBLEM);
        event.setResolutionPending(status == AlertStatus.RESOLVED);
        event.setSeverity(Severity.HIGH);
        event.setOpenedTime(Instant.parse("2026-08-31T10:00:00Z"));
        return event;
    }

    private static AlertPayload payload(String eventType) {
        return new AlertPayload(EVENT_ID, 7, "monitor", MonitorType.INACTIVITY,
                "channel-a", "Channel A", null, Severity.HIGH, eventType,
                "message", Instant.parse("2026-08-31T10:00:00Z"), null);
    }

    private static ActionDispatchLog problemRow(int actionId) {
        ActionDispatchLog row = new ActionDispatchLog();
        row.setAlertEventId(EVENT_ID);
        row.setActionId(actionId);
        row.setDispatchTime(Instant.parse("2026-08-31T10:05:00Z"));
        return row;
    }

    private void assertNoAttempt() {
        dispatches.verify(() -> ActionDispatchLogRepository.insertActionDispatchLog(any()), never());
    }

    private void assertOneAttempt() {
        ArgumentCaptor<ActionDispatchLog> captor = ArgumentCaptor.forClass(ActionDispatchLog.class);
        dispatches.verify(() -> ActionDispatchLogRepository.insertActionDispatchLog(captor.capture()));
        assertEquals(Integer.valueOf(9), captor.getValue().getActionId());
        assertEquals(EVENT_ID, captor.getValue().getAlertEventId());
    }
}
