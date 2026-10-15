package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ActionDispatcherActionTimeTest {
    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted", "filter", "phase"})
    void editsDuringFirstDeliveryStopLaterRecipientAndFinishInapplicableWork(String edit) throws Exception {
        runScenario(edit);
    }

    @Test
    void editedCredentialsAtFinalTransportBoundaryDeferThenReplayFromFreshDefinition() throws Exception {
        runScenario("credentials");
    }

    private void runScenario(String edit) throws Exception {
        AlertEvent event = new AlertEvent(); event.setId(71L); event.setMonitorId(7);
        event.setChannelId("channel-a"); event.setStatus(AlertStatus.PROBLEM);
        event.setProblemPending(true); event.setOpenedTime(Instant.now());
        AlertPayload payload = new AlertPayload(71L, 7, "monitor", MonitorType.INACTIVITY,
                "channel-a", "Channel A", null, Severity.HIGH, "PROBLEM", "problem", Instant.now(), "{}");
        Map<Integer, Action> stored = new LinkedHashMap<>();
        stored.put(1, action(1, "first")); stored.put(2, action(2, "old-secret"));
        List<ActionDispatchLog> rows = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        boolean[] changed = {false};
        try (var actions = ActionRepositoryTestSupport.mockActions();
                var alerts = mockStatic(AlertEventRepository.class);
                var logs = mockStatic(ActionDispatchLogRepository.class);
                var policy = mockStatic(NotificationSuppression.class);
                var storm = mockStatic(AlertStormControl.class);
                var leader = mockStatic(SentinelLeadership.class);
                var dispatcher = mockStatic(ActionDispatcher.class, CALLS_REAL_METHODS)) {
            actions.when(() -> ActionRepository.listActions(true)).thenAnswer(c ->
                    stored.values().stream().filter(Action::isEnabled).toList());
            actions.when(() -> ActionRepository.getAction(anyInt())).thenAnswer(c -> stored.get(c.getArgument(0)));
            alerts.when(() -> AlertEventRepository.getAlertEvent(71L)).thenReturn(event);
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(71L))
                    .thenAnswer(c -> List.copyOf(rows));
            logs.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any())).thenAnswer(c -> {
                rows.add(c.getArgument(0)); return null;
            });
            policy.when(() -> NotificationSuppression.decision(any(), any())).thenReturn(NotificationSuppression.Decision.ALLOW);
            leader.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);
            storm.when(() -> AlertStormControl.flapCheck(any(), any(), anyBoolean()))
                    .thenReturn(new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
            storm.when(() -> AlertStormControl.ceilingVerdict(any(), any()))
                    .thenReturn(new AlertStormControl.CeilingVerdict(AlertStormControl.CeilingOutcome.SEND, 0, 0, List.of()));
            dispatcher.when(() -> ActionDispatcher.sendWithWallClock(any(), any(), any(), any(),
                    anyLong(), any(TimeUnit.class), any(Runnable.class))).thenAnswer(c -> {
                Action sending = c.getArgument(1);
                // Credential edit occurs after every dispatch-thread check,
                // immediately before the guard that runs on the I/O thread.
                if (sending.getId() == 2 && edit.equals("credentials") && !changed[0]) {
                    stored.put(2, action(2, "new-secret")); changed[0] = true;
                }
                ((Runnable) c.getArgument(6)).run();
                delivered.add(sending.getConfigJson());
                if (sending.getId() == 1 && !edit.equals("credentials")) {
                    Action replacement = action(2, "old-secret");
                    switch (edit) {
                        case "disabled" -> replacement.setEnabled(false);
                        case "filter" -> replacement.setConditionJson("[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"another-channel\"}]");
                        case "phase" -> replacement.setOperationMode(OperationMode.ON_RESOLVE);
                    }
                    if (edit.equals("deleted")) stored.remove(2); else stored.put(2, replacement);
                }
                return null;
            });
            ActionDispatcher.dispatchLifecycle(event, payload, false);
            assertEquals(List.of("first"), delivered);
            assertEquals(1, rows.size(), "no bookkeeping attempt before any I/O");
            if (edit.equals("credentials")) {
                assertTrue(event.isProblemPending(), "changed definition needs a fresh decision");
                ActionDispatcher.runRepeatCheck(event, payload);
                assertEquals(List.of("first", "new-secret"), delivered);
                assertFalse(event.isProblemPending());
            } else {
                assertFalse(event.isProblemPending(), "removed recipients are a final configuration outcome");
            }
        }
    }

    @Test
    void lookupUncertaintyDefersWithoutTreatingActionAsDeleted() {
        Action action = action(2, "secret");
        AlertPayload payload = new AlertPayload(1, 7, "monitor", MonitorType.INACTIVITY,
                "channel", "channel", null, Severity.HIGH, "PROBLEM", "problem", Instant.now(), "{}");
        try (var actions = ActionRepositoryTestSupport.mockActions()) {
            actions.when(() -> ActionRepository.getAction(2)).thenThrow(new IllegalStateException("database unavailable"));
            assertThrows(ActionDispatcher.DispatchDeferredException.class,
                    () -> ActionDispatcher.verifyActionAtTransport(action, payload, false));
        }
    }

    private static Action action(int id, String config) {
        Action action = new Action(); action.setId(id); action.setName("action-" + id);
        action.setEnabled(true); action.setActionType(ActionType.EMAIL);
        action.setOperationMode(OperationMode.BOTH); action.setConfigJson(config);
        return action;
    }
}
