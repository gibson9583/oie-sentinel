package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Dispatch parity across lifecycle paths: edits revoke stale work and retries use current definitions. */
class ActionDispatcherFreshnessPathsTest {
    static Stream<Arguments> cases() {
        Stream<Arguments> ordinary = Stream.of("opened", "repeat", "resolved", "replay")
                .flatMap(path -> Stream.of("unchanged", "disabled", "deleted", "recipient-edited",
                        "condition-edited", "phase-edited", "acknowledged-control", "suppressed-control")
                        .filter(change -> !path.equals("resolved") || !change.equals("acknowledged-control"))
                        .map(change -> Arguments.of(path, change)));
        Stream<Arguments> escalation = Stream.of("unchanged", "disabled", "deleted", "recipient-edited",
                        "acknowledged-control", "suppressed-control")
                .map(change -> Arguments.of("escalation", change));
        return Stream.concat(ordinary, escalation);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void currentActionConfigurationControlsEveryDispatchPath(String path, String change) {
        AlertEvent event = new AlertEvent();
        event.setId(71L); event.setMonitorId(7); event.setChannelId("channel");
        boolean resolved = path.equals("resolved") || path.equals("replay");
        event.setStatus(resolved ? AlertStatus.RESOLVED : AlertStatus.PROBLEM);
        event.setProblemPending(!path.equals("resolved"));
        event.setResolutionPending(resolved); event.setSeverity(Severity.HIGH);
        event.setOpenedTime(Instant.now().minusSeconds(60));
        Action first = action(1), snapshot = action(2), current = action(2);
        if (path.equals("escalation")) {
            first.setEscalateAfterSeconds(1); first.setEscalateToActionId(2);
            snapshot.setConditionJson("[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"other\"}]");
            current.setConditionJson(snapshot.getConditionJson());
        }
        Map<Integer, Action> stored = new LinkedHashMap<>();
        stored.put(1, first); stored.put(2, snapshot);
        List<ActionDispatchLog> rows = new ArrayList<>();
        if (path.equals("resolved")) {
            for (int id : List.of(1, 2)) {
                ActionDispatchLog row = new ActionDispatchLog(); row.setActionId(id);
                row.setAlertEventId(71L); row.setDispatchTime(Instant.now().minusSeconds(30)); rows.add(row);
            }
        }
        List<Integer> sent = new ArrayList<>(); List<String> destinations = new ArrayList<>();
        boolean[] suppressNow = {false};
        try (var actions = ActionRepositoryTestSupport.mockActions(); var alerts = mockStatic(AlertEventRepository.class);
                var logs = mockStatic(ActionDispatchLogRepository.class); var states = mockStatic(TriggerStateRepository.class);
                var suppression = mockStatic(NotificationSuppression.class); var storm = mockStatic(AlertStormControl.class);
                var leadership = mockStatic(SentinelLeadership.class);
                var dispatcher = mockStatic(ActionDispatcher.class, CALLS_REAL_METHODS)) {
            actions.when(() -> ActionRepository.listActions(true)).thenAnswer(call ->
                    stored.values().stream().filter(Action::isEnabled).toList());
            actions.when(() -> ActionRepository.getAction(anyInt())).thenAnswer(call -> stored.get(call.getArgument(0)));
            alerts.when(() -> AlertEventRepository.getAlertEvent(71L)).thenReturn(event);
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(71L))
                    .thenAnswer(call -> List.copyOf(rows));
            logs.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any())).thenAnswer(call -> {
                rows.add(call.getArgument(0)); return null;
            });
            suppression.when(() -> NotificationSuppression.decision(any(), any())).thenAnswer(call -> suppressNow[0]
                    ? NotificationSuppression.Decision.SUPPRESS : NotificationSuppression.Decision.ALLOW);
            leadership.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);
            storm.when(() -> AlertStormControl.flapCheck(any(), any(), anyBoolean())).thenReturn(
                    new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
            storm.when(() -> AlertStormControl.ceilingVerdict(any(), any())).thenReturn(
                    new AlertStormControl.CeilingVerdict(AlertStormControl.CeilingOutcome.SEND, 0, 0, List.of()));
            dispatcher.when(() -> ActionDispatcher.sendWithWallClock(any(), any(), any(), any(), anyLong(),
                    any(TimeUnit.class), any(Runnable.class))).thenAnswer(call -> {
                ((Runnable) call.getArgument(6)).run();
                Action actual = call.getArgument(1); sent.add(actual.getId()); destinations.add(actual.getConfigJson());
                if (sent.size() == 1) {
                    switch (change) {
                        case "disabled" -> { current.setEnabled(false); stored.put(2, current); }
                        case "deleted" -> stored.remove(2);
                        case "recipient-edited" -> { current.setConfigJson("{\"to\":\"new-recipient@example.invalid\"}"); stored.put(2, current); }
                        case "condition-edited" -> { current.setConditionJson("[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"other\"}]"); stored.put(2, current); }
                        case "phase-edited" -> { current.setOperationMode(path.equals("resolved") ? OperationMode.ON_PROBLEM : OperationMode.ON_RESOLVE); stored.put(2, current); }
                        case "acknowledged-control" -> event.setAcknowledgedBy(42);
                        case "suppressed-control" -> suppressNow[0] = true;
                    }
                }
                return null;
            });
            AlertPayload payload = new AlertPayload(71L, 7, "review-monitor", MonitorType.INACTIVITY,
                    "channel", "channel", null, Severity.HIGH, resolved ? "RESOLVED" : "PROBLEM", "review",
                    event.getOpenedTime(), "{}");
            if (path.equals("repeat") || path.equals("escalation")) ActionDispatcher.runRepeatCheck(event, payload);
            else ActionDispatcher.dispatchLifecycle(event, payload, resolved);
            List<Integer> expected = change.equals("unchanged")
                    ? path.equals("replay") ? List.of(1, 2, 1, 2) : List.of(1, 2)
                    : path.equals("replay") && change.equals("phase-edited") ? List.of(1, 1, 2)
                    : path.equals("replay") && !change.endsWith("-control") && !change.equals("recipient-edited")
                            ? List.of(1, 1) : List.of(1);
            assertEquals(expected, sent, path + "/" + change);
            if (change.equals("recipient-edited")) {
                assertTrue(resolved ? event.isResolutionPending() : event.isProblemPending(),
                        "a changed delivery definition keeps its durable edge pending");
                if (path.equals("repeat") || path.equals("escalation")) ActionDispatcher.runRepeatCheck(event, payload);
                else ActionDispatcher.dispatchLifecycle(event, payload, resolved);
                assertEquals(path.equals("replay") ? List.of(1, 2, 1, 2) : List.of(1, 2), sent);
                assertTrue(destinations.get(1).contains("new-recipient"), "retry sends to the current recipient");
                assertFalse(resolved ? event.isResolutionPending() : event.isProblemPending());
                int previousSends = sent.size();
                if (path.equals("repeat") || path.equals("escalation")) ActionDispatcher.runRepeatCheck(event, payload);
                else ActionDispatcher.dispatchLifecycle(event, payload, resolved);
                assertEquals(previousSends, sent.size(), "completed work is not duplicated");
            }

        }
    }

    private static Action action(int id) {
        Action action = new Action(); action.setId(id); action.setName("review-" + id);
        action.setEnabled(true); action.setActionType(ActionType.EMAIL); action.setOperationMode(OperationMode.BOTH);
        action.setConfigJson("{\"to\":\"old-recipient@example.invalid\"}"); return action;
    }
}
