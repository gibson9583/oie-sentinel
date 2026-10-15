/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Exercise the queued transport guard after the escalation decision has already been made. */
class ActionDispatcherEscalationActionTimeTest {
    @ParameterizedTest
    @ValueSource(strings = {"filter", "phase", "both"})
    void explicitEscalationDoesNotRequireTargetsOrdinarySubscription(String restriction) {
        try (Scenario s = new Scenario()) {
            if (!restriction.equals("phase")) s.stored.get(2).setConditionJson(
                    "[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"other-channel\"}]");
            if (!restriction.equals("filter")) s.stored.get(2).setOperationMode(OperationMode.ON_RESOLVE);
            s.run();
            s.run();
            assertEquals(List.of(2), s.sent, "an explicit escalation route authorizes its enabled target");
            assertEquals(List.of(1, 2), s.rows.stream().map(ActionDispatchLog::getActionId).toList());
        }
    }

    @Test
    void alreadyNotifiedIntermediateDoesNotNeedAnIndependentSubscription() {
        try (Scenario s = new Scenario()) {
            s.addSecondHop();
            s.stored.get(2).setOperationMode(OperationMode.ON_RESOLVE);
            s.stored.get(2).setConditionJson(
                    "[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"other-channel\"}]");
            s.stored.get(3).setOperationMode(OperationMode.ON_RESOLVE);
            s.run();
            s.run();
            assertEquals(List.of(3), s.sent, "the source route remains valid through an enabled intermediate");
            assertEquals(List.of(1, 2, 3), s.rows.stream().map(ActionDispatchLog::getActionId).toList());
        }
    }

    @Test
    void sourceLookupFailureBeforeChainTraversalPreservesPendingInitialWork() {
        try (Scenario s = new Scenario()) {
            s.event.setProblemPending(true);
            s.stored.remove(3);
            s.stored.get(2).setOperationMode(OperationMode.ON_RESOLVE);
            s.unavailableAction = 1;
            s.run();
            assertEquals(List.of(), s.sent);
            assertTrue(s.event.isProblemPending(), "uncertainty must not complete the event outbox");
            s.unavailableAction = null;
            s.run();
            s.run();
            assertEquals(List.of(2), s.sent);
            assertFalse(s.event.isProblemPending());
        }
    }

    @Test
    void intermediateLookupFailureBeforeChainTraversalPreservesPendingWork() {
        try (Scenario s = new Scenario()) {
            s.addSecondHop();
            s.event.setProblemPending(true);
            s.stored.get(3).setOperationMode(OperationMode.ON_RESOLVE);
            s.unavailableAction = 2;
            s.run();
            assertEquals(List.of(), s.sent);
            assertTrue(s.event.isProblemPending());
            s.unavailableAction = null;
            s.run();
            s.run();
            assertEquals(List.of(3), s.sent);
            assertFalse(s.event.isProblemPending());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted", "filter", "phase", "retargeted", "delay",
            "credentials", "lookup-failure"})
    void sourceChangedAtIoBoundaryCannotAuthorizeOldTarget(String change) {
        try (Scenario s = new Scenario()) {
            s.beforeGuard = target -> s.changeSource(change);
            s.run();
            assertEquals(List.of(), s.sent);
            assertEquals(List.of(2), s.blocked);
            assertEquals(1, s.rows.size(), "a cancelled send must not create an attempt row");
            s.storm.verify(() -> AlertStormControl.releaseReservation(s.verdict));

            // A new tick takes a fresh snapshot and can retry the intended route;
            // neither the paced claim nor the successful source send may be lost.
            s.unavailableAction = null;
            s.run();
            s.run();
            List<Integer> expected = switch (change) {
                case "disabled", "deleted", "filter", "phase" -> List.of();
                case "retargeted" -> List.of(3);
                default -> List.of(2);
            };
            assertEquals(expected, s.sent);
            assertEquals(1 + expected.size(), s.rows.size());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void everyAlreadyNotifiedAncestorIsCheckedAtIoBoundary(int ancestorId) {
        try (Scenario s = new Scenario()) {
            s.addSecondHop();
            s.beforeGuard = target -> {
                Action changed = action(ancestorId);
                changed.setEnabled(false);
                s.stored.put(ancestorId, changed);
            };
            s.run();
            assertEquals(List.of(3), s.blocked, "the first A -> B -> C attempt must recheck both A and B");
            // B may independently authorize C after A is disabled. A revoked
            // B cancels both routes. Only the route actually sent is accounted.
            assertEquals(ancestorId == 1 ? List.of(3) : List.of(), s.sent);
            assertEquals(ancestorId == 1 ? 3 : 2, s.rows.size());
            s.storm.verify(() -> AlertStormControl.releaseReservation(s.verdict));

            s.stored.put(ancestorId, source(ancestorId, ancestorId + 1));
            s.run();
            s.run();
            assertEquals(List.of(3), s.sent);
            assertEquals(3, s.rows.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted", "retargeted", "lookup-failure"})
    void cancelledEscalationDoesNotConsumeTargetsIndependentInitialEdge(String change) {
        try (Scenario s = new Scenario()) {
            s.stored.remove(3);
            s.event.setProblemPending(true);
            s.beforeGuard = target -> s.changeSource(change);
            s.run();
            assertEquals(List.of(2), s.blocked);
            assertEquals(List.of(2), s.sent, "B still has an independently eligible initial notification");
            assertEquals(2, s.rows.size());
            s.unavailableAction = null;
            s.run();
            assertFalse(s.event.isProblemPending());
            assertEquals(List.of(2), s.sent, "the successful initial notification is not replayed");
        }
    }

    @Test
    void cancelledEscalationAndUnavailableTargetPreserveIndependentInitialEdge() {
        try (Scenario s = new Scenario()) {
            s.stored.remove(3);
            s.event.setProblemPending(true);
            s.beforeGuard = target -> {
                s.changeSource("disabled");
                s.unavailableAction = 2;
            };
            s.run();
            assertEquals(List.of(), s.sent);
            assertEquals(1, s.rows.size());
            assertTrue(s.event.isProblemPending());
            s.unavailableAction = null;
            s.run();
            assertEquals(List.of(2), s.sent);
            assertEquals(2, s.rows.size());
            assertFalse(s.event.isProblemPending());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void escalationWithFailedLogRetainsInitialEdgeWithoutSendingTwiceInOnePass(boolean transportFails) {
        try (Scenario s = new Scenario()) {
            s.stored.remove(3);
            s.event.setProblemPending(true);
            s.failLogInsert = true;
            s.failTransport = transportFails;
            s.run();
            assertEquals(List.of(2), s.sent, "the independent initial path must not duplicate attempted I/O");
            assertEquals(1, s.rows.size());
            assertTrue(s.event.isProblemPending(), "failed durable accounting must leave the edge pending");

            s.failLogInsert = false;
            s.run();
            s.run();
            assertEquals(List.of(2, 2), s.sent, "a missing log may replay once under the at-least-once contract");
            assertEquals(2, s.rows.size());
            assertFalse(s.event.isProblemPending());
        }
    }

    @Test
    void unchangedChainUsesTargetAccountingAndSendsOnlyOnce() {
        try (Scenario s = new Scenario()) {
            s.addSecondHop();
            s.run();
            s.run();
            assertEquals(List.of(3), s.sent);
            assertEquals(List.of(), s.blocked);
            assertEquals(List.of(1, 2, 3), s.rows.stream().map(ActionDispatchLog::getActionId).toList());
            s.storm.verify(() -> AlertStormControl.ceilingVerdict(eq(s.stored.get(3)), any()));
            s.storm.verify(() -> AlertStormControl.releaseReservation(any()), never());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "lookup-failure", "retargeted"})
    void rollupKeepsSourceAuthorizationAndReleasesCancelledReservation(String change) {
        try (Scenario s = new Scenario()) {
            // Both actions require the original phase, even though the actual
            // payload becomes ROLLUP. The final matcher must restore PROBLEM.
            s.stored.get(1).setConditionJson(eventCondition());
            s.stored.get(2).setConditionJson(eventCondition());
            s.verdict = new AlertStormControl.CeilingVerdict(
                    AlertStormControl.CeilingOutcome.ROLLUP, 1, 60, List.of("Channel A"));
            s.beforeGuard = target -> s.changeSource(change);
            s.run();
            assertEquals(List.of(2), s.blocked);
            assertEquals(List.of(), s.sent);
            assertEquals(1, s.rows.size());
            s.storm.verify(() -> AlertStormControl.releaseReservation(s.verdict));

            s.unavailableAction = null;
            s.stored.put(1, source(1, 2));
            s.stored.get(1).setConditionJson(eventCondition());
            s.run();
            s.run();
            assertEquals(List.of(2), s.sent);
            assertEquals(List.of("ROLLUP"), s.sentPhases);
            assertTrue(s.rows.get(1).getErrorMessage().startsWith(AlertStormControl.ROLLUP_MARKER));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolved", "acknowledged", "suppressed", "unknown-policy", "leadership"})
    void sourceGuardPreservesLifecycleAndPolicyCancellation(String change) {
        try (Scenario s = new Scenario()) {
            s.beforeGuard = target -> {
                switch (change) {
                    case "resolved" -> s.event.setStatus(AlertStatus.RESOLVED);
                    case "acknowledged" -> s.event.setAcknowledgedBy(42);
                    case "suppressed" -> s.policyDecision = NotificationSuppression.Decision.SUPPRESS;
                    case "unknown-policy" -> s.policyDecision = NotificationSuppression.Decision.UNKNOWN;
                    case "leadership" -> s.leaderValid = false;
                }
            };
            s.run();
            assertEquals(List.of(2), s.blocked);
            assertEquals(List.of(), s.sent);
            assertEquals(1, s.rows.size());
            s.storm.verify(() -> AlertStormControl.releaseReservation(s.verdict));
        }
    }

    private static String eventCondition() {
        return "[{\"field\":\"EVENT_TYPE\",\"operator\":\"=\",\"value\":\"PROBLEM\"}]";
    }

    private static Action action(int id) {
        Action action = new Action();
        action.setId(id);
        action.setName("action-" + id);
        action.setEnabled(true);
        action.setActionType(ActionType.EMAIL);
        action.setOperationMode(OperationMode.ON_PROBLEM);
        return action;
    }

    private static Action source(int id, int targetId) {
        Action source = action(id);
        source.setEscalateAfterSeconds(60);
        source.setEscalateToActionId(targetId);
        return source;
    }

    private static ActionDispatchLog row(int actionId) {
        ActionDispatchLog row = new ActionDispatchLog();
        row.setAlertEventId(71L);
        row.setActionId(actionId);
        row.setDispatchTime(Instant.now().minusSeconds(90));
        row.setSuccess(true);
        return row;
    }

    private static final class Scenario implements AutoCloseable {
        final AlertEvent event = new AlertEvent();
        final Map<Integer, Action> stored = new LinkedHashMap<>();
        final List<ActionDispatchLog> rows = new ArrayList<>();
        final List<Integer> sent = new ArrayList<>();
        final List<String> sentPhases = new ArrayList<>();
        final List<Integer> blocked = new ArrayList<>();
        final MockedStatic<ActionRepository> actions = ActionRepositoryTestSupport.mockActions();
        final MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class);
        final MockedStatic<ActionDispatchLogRepository> logs = mockStatic(ActionDispatchLogRepository.class);
        final MockedStatic<NotificationSuppression> policy = mockStatic(NotificationSuppression.class);
        final MockedStatic<AlertStormControl> storm = mockStatic(AlertStormControl.class);
        final MockedStatic<SentinelLeadership> leadership = mockStatic(SentinelLeadership.class);
        final MockedStatic<ActionDispatcher> dispatcher = mockStatic(ActionDispatcher.class, CALLS_REAL_METHODS);
        Consumer<Action> beforeGuard;
        Integer unavailableAction;
        boolean leaderValid = true;
        boolean failLogInsert;
        boolean failTransport;
        NotificationSuppression.Decision policyDecision = NotificationSuppression.Decision.ALLOW;
        AlertStormControl.CeilingVerdict verdict = new AlertStormControl.CeilingVerdict(
                AlertStormControl.CeilingOutcome.SEND, 0, 0, List.of());

        Scenario() {
            event.setId(71L);
            event.setMonitorId(7);
            event.setChannelId("channel-a");
            event.setStatus(AlertStatus.PROBLEM);
            event.setSeverity(Severity.HIGH);
            event.setProblemPending(false);
            event.setOpenedTime(Instant.now().minusSeconds(300));
            stored.put(1, source(1, 2));
            stored.put(2, action(2));
            stored.put(3, action(3));
            rows.add(row(1));
            actions.when(() -> ActionRepository.listActions(true)).thenAnswer(c ->
                    stored.values().stream().filter(Action::isEnabled).toList());
            actions.when(() -> ActionRepository.getAction(anyInt())).thenAnswer(c -> {
                Integer id = c.getArgument(0);
                if (id.equals(unavailableAction)) throw new IllegalStateException("action lookup unavailable");
                return stored.get(id);
            });
            alerts.when(() -> AlertEventRepository.getAlertEvent(71L)).thenReturn(event);
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(71L))
                    .thenAnswer(c -> List.copyOf(rows));
            logs.when(() -> ActionDispatchLogRepository.insertActionDispatchLog(any())).thenAnswer(c -> {
                if (failLogInsert) throw new IllegalStateException("dispatch log unavailable");
                rows.add(c.getArgument(0));
                return null;
            });
            policy.when(() -> NotificationSuppression.decision(any(), any())).thenAnswer(c -> policyDecision);
            leadership.when(() -> SentinelLeadership.recheckFence(any())).thenAnswer(c -> leaderValid);
            storm.when(() -> AlertStormControl.flapCheck(any(), any(), anyBoolean())).thenReturn(
                    new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
            storm.when(() -> AlertStormControl.ceilingVerdict(any(), any())).thenAnswer(c -> verdict);
            dispatcher.when(() -> ActionDispatcher.sendWithWallClock(any(), any(), any(), any(),
                    anyLong(), any(TimeUnit.class), any(Runnable.class))).thenAnswer(c -> {
                Action target = c.getArgument(1);
                if (beforeGuard != null) {
                    Consumer<Action> edit = beforeGuard;
                    beforeGuard = null;
                    edit.accept(target);
                }
                try {
                    ((Runnable) c.getArgument(6)).run();
                } catch (ActionDispatcher.DispatchDeferredException blockedSend) {
                    blocked.add(target.getId());
                    throw blockedSend;
                }
                sent.add(target.getId());
                sentPhases.add(((AlertPayload) c.getArgument(3)).getEventType());
                if (failTransport) throw new IllegalStateException("transport failed after I/O began");
                return null;
            });
        }

        void addSecondHop() {
            stored.put(2, source(2, 3));
            rows.add(row(2));
        }

        void changeSource(String change) {
            Action updated = source(1, 2);
            switch (change) {
                case "disabled" -> updated.setEnabled(false);
                case "filter" -> updated.setConditionJson(
                        "[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"other-channel\"}]");
                case "phase" -> updated.setOperationMode(OperationMode.ON_RESOLVE);
                case "retargeted" -> updated.setEscalateToActionId(3);
                case "delay" -> updated.setEscalateAfterSeconds(90);
                case "credentials" -> updated.setConfigJson("{\"credential\":\"new\"}");
                case "lookup-failure" -> unavailableAction = 1;
            }
            if (change.equals("deleted")) stored.remove(1); else stored.put(1, updated);
        }

        void run() {
            ActionDispatcher.runRepeatCheck(event, new AlertPayload(71L, 7, "monitor", MonitorType.INACTIVITY,
                    "channel-a", "Channel A", null, Severity.HIGH, "PROBLEM", "problem",
                    event.getOpenedTime(), "{}"));
        }

        @Override
        public void close() {
            dispatcher.close();
            leadership.close();
            storm.close();
            policy.close();
            logs.close();
            alerts.close();
            actions.close();
        }
    }
}
