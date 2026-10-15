package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** A stale exclusion must not consume a newly applicable action's durable edge. */
class ActionDispatcherEligibilityFreshnessTest {
    static Stream<Arguments> cases() {
        return Stream.of("opened", "repeat", "resolved", "replay")
                .flatMap(path -> Stream.of("phase", "filter", "lookup-failure", "unchanged-phase", "unchanged-filter")
                        .map(edit -> Arguments.of(path, edit)));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void revalidateBeforeExcludingAnActionAndCompletingItsEdge(String path, String edit) {
        runScenario(path, edit);
    }

    @Test
    void explicitRecoverySubscriptionAddedDuringFanoutDoesNotRequireAnEarlierProblem() {
        runScenario("resolved", "first-recovery");
    }

    @Test
    void unchangedBothSubscriptionWithoutProblemIsStillExcludedFromRecovery() {
        runScenario("resolved", "unchanged-first-recovery");
    }

    private static void runScenario(String path, String edit) {
        boolean resolved = path.equals("resolved") || path.equals("replay");
        boolean recoveryOnly = path.equals("resolved");
        boolean unchanged = edit.startsWith("unchanged-");
        AlertEvent event = new AlertEvent();
        event.setId(71L); event.setMonitorId(7); event.setChannelId("channel-a");
        event.setStatus(resolved ? AlertStatus.RESOLVED : AlertStatus.PROBLEM);
        event.setProblemPending(!recoveryOnly); event.setResolutionPending(resolved);
        event.setOpenedTime(Instant.now().minusSeconds(300));
        AlertPayload payload = new AlertPayload(71L, 7, "monitor", MonitorType.INACTIVITY,
                "channel-a", "Channel A", null, Severity.HIGH, resolved ? "RESOLVED" : "PROBLEM",
                "problem", event.getOpenedTime(), "{}");
        Map<Integer, Action> stored = new LinkedHashMap<>();
        Action first = action(1), stale = action(2), fresh = action(2);
        if (recoveryOnly) {
            first.setOperationMode(OperationMode.ON_RESOLVE);
            fresh.setOperationMode(OperationMode.ON_RESOLVE);
        }
        if (edit.endsWith("filter")) {
            stale.setOperationMode(fresh.getOperationMode());
            stale.setConditionJson("[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"other\"}]");
        } else if (!edit.endsWith("first-recovery")) {
            stale.setOperationMode(recoveryOnly ? OperationMode.ON_PROBLEM : OperationMode.ON_RESOLVE);
        }
        stored.put(1, first); stored.put(2, stale);
        List<ActionDispatchLog> rows = new ArrayList<>();
        List<Integer> sent = new ArrayList<>();
        boolean[] lookupUnavailable = {false};
        try (var actions = ActionRepositoryTestSupport.mockActions();
                var alerts = mockStatic(AlertEventRepository.class);
                var states = mockStatic(TriggerStateRepository.class);
                var logs = mockStatic(ActionDispatchLogRepository.class);
                var policy = mockStatic(NotificationSuppression.class);
                var storm = mockStatic(AlertStormControl.class);
                var leadership = mockStatic(SentinelLeadership.class);
                var dispatcher = mockStatic(ActionDispatcher.class, CALLS_REAL_METHODS)) {
            actions.when(() -> ActionRepository.listActions(true)).thenAnswer(c -> List.copyOf(stored.values()));
            actions.when(() -> ActionRepository.getAction(anyInt())).thenAnswer(c -> {
                int actionId = c.getArgument(0);
                if (actionId == 2 && lookupUnavailable[0]) {
                    throw new IllegalStateException("action lookup unavailable");
                }
                return stored.get(actionId);
            });
            alerts.when(() -> AlertEventRepository.getAlertEvent(71L)).thenReturn(event);
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(71L))
                    .thenAnswer(c -> List.copyOf(rows));
            logs.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any())).thenAnswer(c -> {
                rows.add(c.getArgument(0)); return null;
            });
            policy.when(() -> NotificationSuppression.decision(any(), any()))
                    .thenReturn(NotificationSuppression.Decision.ALLOW);
            leadership.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);
            storm.when(() -> AlertStormControl.flapCheck(any(), any(), anyBoolean())).thenReturn(
                    new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
            storm.when(() -> AlertStormControl.ceilingVerdict(any(), any())).thenReturn(
                    new AlertStormControl.CeilingVerdict(AlertStormControl.CeilingOutcome.SEND, 0, 0, List.of()));
            dispatcher.when(() -> ActionDispatcher.sendWithWallClock(any(), any(), any(), any(),
                    anyLong(), any(TimeUnit.class), any(Runnable.class))).thenAnswer(c -> {
                ((Runnable) c.getArgument(6)).run();
                sent.add(((Action) c.getArgument(1)).getId());
                if (sent.size() == 1 && !unchanged) {
                    // Replace the repository definition while an earlier transport
                    // is sending; the action-list snapshot must stay unchanged.
                    stored.put(2, fresh);
                    lookupUnavailable[0] = edit.equals("lookup-failure");
                }
                return null;
            });

            dispatch(path, event, payload, resolved);
            if (unchanged) {
                assertFalse(resolved ? event.isResolutionPending() : event.isProblemPending());
                // In replay, unchanged ON_RESOLVE explicitly receives recovery;
                // an unchanged filter still excludes it from both phases.
                assertEquals(path.equals("replay")
                        ? edit.equals("unchanged-phase") ? List.of(1, 1, 2) : List.of(1, 1)
                        : List.of(1), sent);
            } else {
                assertEquals(List.of(1), sent, "changed or unreadable definitions await a fresh pass");
                assertEquals(1, rows.size(), "no attempt row exists for unsent work");
                assertTrue(resolved ? event.isResolutionPending() : event.isProblemPending(),
                        "a stale skip must preserve the durable edge");
                if (path.equals("replay")) assertTrue(event.isProblemPending());
                lookupUnavailable[0] = false;
                dispatch(path, event, payload, resolved);
                assertEquals(path.equals("replay") ? List.of(1, 2, 1, 2) : List.of(1, 2), sent);
                assertFalse(event.isProblemPending());
                assertFalse(event.isResolutionPending());
            }
            int completedAttempts = sent.size();
            dispatch(path, event, payload, resolved);
            assertEquals(completedAttempts, sent.size(), "retry never duplicates completed attempts");
            assertEquals(completedAttempts, rows.size(), "each actual attempt has one durable row");
        }
    }

    private static void dispatch(String path, AlertEvent event, AlertPayload payload, boolean resolved) {
        if (path.equals("repeat")) ActionDispatcher.runRepeatCheck(event, payload);
        else ActionDispatcher.dispatchLifecycle(event, payload, resolved);
    }

    private static Action action(int id) {
        Action action = new Action(); action.setId(id); action.setName("action-" + id);
        action.setEnabled(true); action.setActionType(ActionType.EMAIL); action.setOperationMode(OperationMode.BOTH);
        action.setConfigJson("{\"to\":\"review@example.invalid\"}"); return action;
    }
}
