package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ActionDispatcherTransportGuardOrderTest {
    @ParameterizedTest
    @ValueSource(strings = {"leadership", "acknowledged", "suppressed"})
    void changeDuringTransportActionLookupDefersSend(String change) throws Exception {
        AlertEvent event = new AlertEvent(); event.setId(81L); event.setMonitorId(8);
        event.setChannelId("channel-a"); event.setStatus(AlertStatus.PROBLEM);
        event.setProblemPending(true); event.setOpenedTime(Instant.now());
        AlertPayload payload = new AlertPayload(81L, 8, "monitor", MonitorType.INACTIVITY,
                "channel-a", "Channel A", null, Severity.HIGH, "PROBLEM", "problem", Instant.now(), "{}");
        Action stored = action(1);
        List<ActionDispatchLog> rows = new ArrayList<>();
        List<Integer> delivered = new ArrayList<>();
        boolean[] inTransport = {false};
        boolean[] leader = {true};
        NotificationSuppression.Decision[] decision = {NotificationSuppression.Decision.ALLOW};
        try (var actions = ActionRepositoryTestSupport.mockActions();
                var alerts = mockStatic(AlertEventRepository.class);
                var logs = mockStatic(ActionDispatchLogRepository.class);
                var policy = mockStatic(NotificationSuppression.class);
                var storm = mockStatic(AlertStormControl.class);
                var leadership = mockStatic(SentinelLeadership.class);
                var dispatcher = mockStatic(ActionDispatcher.class, CALLS_REAL_METHODS)) {
            actions.when(() -> ActionRepository.listActions(true)).thenReturn(List.of(stored));
            actions.when(() -> ActionRepository.getAction(1)).thenAnswer(c -> {
                // The change lands during the transport-thread action lookup,
                // after the lifecycle, policy and leadership checks passed once.
                if (inTransport[0]) {
                    switch (change) {
                        case "leadership" -> leader[0] = false;
                        case "acknowledged" -> event.setAcknowledgedBy(5);
                        case "suppressed" -> decision[0] = NotificationSuppression.Decision.SUPPRESS;
                    }
                }
                return stored;
            });
            alerts.when(() -> AlertEventRepository.getAlertEvent(81L)).thenReturn(event);
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(81L))
                    .thenAnswer(c -> List.copyOf(rows));
            logs.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any())).thenAnswer(c -> {
                rows.add(c.getArgument(0)); return null;
            });
            policy.when(() -> NotificationSuppression.decision(any(), any())).thenAnswer(c -> decision[0]);
            leadership.when(() -> SentinelLeadership.recheckFence(any())).thenAnswer(c -> leader[0]);
            storm.when(() -> AlertStormControl.flapCheck(any(), any(), anyBoolean()))
                    .thenReturn(new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
            storm.when(() -> AlertStormControl.ceilingVerdict(any(), any()))
                    .thenReturn(new AlertStormControl.CeilingVerdict(AlertStormControl.CeilingOutcome.SEND, 0, 0, List.of()));
            dispatcher.when(() -> ActionDispatcher.sendWithWallClock(any(), any(), any(), any(),
                    anyLong(), any(TimeUnit.class), any(Runnable.class))).thenAnswer(c -> {
                inTransport[0] = true;
                ((Runnable) c.getArgument(6)).run();
                delivered.add(((Action) c.getArgument(1)).getId());
                return null;
            });

            ActionDispatcher.dispatchLifecycle(event, payload, false);

            assertEquals(List.of(), delivered, "no send after the " + change + " change");
            assertEquals(List.of(), rows, "a deferred edge records no attempt");
            assertTrue(event.isProblemPending(), "the deferred edge stays pending");
        }
    }

    private static Action action(int id) {
        Action action = new Action(); action.setId(id); action.setName("action-" + id);
        action.setEnabled(true); action.setActionType(ActionType.EMAIL);
        action.setOperationMode(OperationMode.BOTH); action.setConfigJson("config");
        return action;
    }
}
