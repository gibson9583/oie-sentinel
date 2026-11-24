/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * Action conditions and storm control for a Log alert. Group and tag
 * membership is stubbed to contain every channel, so a non-match for the Log
 * subject cannot be explained by an empty membership.
 */
class LogSubjectAlertTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String CHANNEL_A = "1c8f0a7e-5b3d-4d21-9a6f-2e7c4b91d0aa";
    private static final String CHANNEL_B = "9d4e6b12-0a77-4c58-8f3b-6ac1de205f47";

    private MockedStatic<AlertEventRepository> alerts;
    private MockedStatic<ActionDispatchLogRepository> logs;
    private MockedStatic<ScopeResolver> scope;
    private MockedStatic<MonitorRepository> monitors;
    private MockedStatic<NotificationSuppression> policy;
    private final List<AlertEvent> recent = new ArrayList<>();
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        AlertStormControl.reset();
        alerts = Mockito.mockStatic(AlertEventRepository.class);
        alerts.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt())).thenReturn(recent);
        alerts.when(() -> AlertEventRepository.getAlertEvent(anyLong())).thenAnswer(call -> recent.stream()
                .filter(event -> event.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        logs = Mockito.mockStatic(ActionDispatchLogRepository.class);
        scope = Mockito.mockStatic(ScopeResolver.class);
        scope.when(() -> ScopeResolver.strictGroupChannelIds(anyString())).thenReturn(Set.of(CHANNEL_A));
        scope.when(() -> ScopeResolver.strictTagChannelIds(anyString())).thenReturn(Set.of(CHANNEL_A));
        scope.when(() -> ScopeResolver.channelName(anyString())).thenAnswer(call -> "name:" + call.getArgument(0));
        monitors = Mockito.mockStatic(MonitorRepository.class);
        monitors.when(() -> MonitorRepository.getMonitor(7)).thenReturn(monitor(7, MonitorType.INACTIVITY));
        monitors.when(() -> MonitorRepository.getMonitor(8)).thenReturn(monitor(8, MonitorType.LOG_EVENTS));
        policy = Mockito.mockStatic(NotificationSuppression.class);
        policy.when(() -> NotificationSuppression.decision(any(), any()))
                .thenReturn(NotificationSuppression.Decision.ALLOW);
    }

    @AfterEach
    void tearDown() {
        policy.close();
        monitors.close();
        scope.close();
        logs.close();
        alerts.close();
        AlertStormControl.reset();
    }

    @ParameterizedTest(name = "{0} {1} never matches a Log alert")
    @CsvSource(delimiter = '|', value = {
            "CHANNEL       | =  | \"other\"",
            "CHANNEL       | != | \"other\"",
            "CHANNEL       | IN | [\"other\"]",
            "CHANNEL       | != | \"" + CHANNEL_A + "\"",
            "CHANNEL_GROUP | =  | \"g\"",
            "CHANNEL_GROUP | != | \"g\"",
            "CHANNEL_GROUP | IN | [\"g\"]",
            "CHANNEL_GROUP | != | \"unknown-group\"",
            "CHANNEL_TAG   | =  | \"t\"",
            "CHANNEL_TAG   | != | \"t\"",
            "CHANNEL_TAG   | IN | [\"t\"]",
            "CHANNEL_TAG   | != | \"unknown-tag\""
    })
    void channelGroupAndTagConditionsNeverMatchALogAlert(String field, String operator, String value) {
        Action action = action(field, operator, value);
        assertFalse(ActionConditionMatcher.matches(action, logPayload()));
        // The copies the dispatcher makes before matching keep the subject.
        assertFalse(ActionConditionMatcher.matches(action, logPayload().copy("RESOLVED", "recovered")));
        scope.verify(() -> ScopeResolver.strictGroupChannelIds(anyString()), never());
        scope.verify(() -> ScopeResolver.strictTagChannelIds(anyString()), never());
    }

    @ParameterizedTest(name = "control: {0} {1} {2} is {3} for a channel payload")
    @CsvSource(delimiter = '|', value = {
            "CHANNEL       | =  | \"" + CHANNEL_A + "\" | true",
            "CHANNEL       | != | \"other\"                | true",
            "CHANNEL       | IN | [\"" + CHANNEL_A + "\"] | true",
            "CHANNEL_GROUP | =  | \"g\"                    | true",
            "CHANNEL_GROUP | != | \"g\"                    | false",
            "CHANNEL_GROUP | IN | [\"g\"]                  | true",
            "CHANNEL_TAG   | =  | \"t\"                    | true",
            "CHANNEL_TAG   | != | \"t\"                    | false",
            "CHANNEL_TAG   | IN | [\"t\"]                  | true"
    })
    void theSameConditionsStillMatchAChannelPayload(String field, String operator, String value, boolean expected) {
        assertEquals(expected, ActionConditionMatcher.matches(action(field, operator, value), channelPayload()));
    }

    @Test
    void aSyntheticPayloadWithoutAChannelKeepsItsOldNotEqualsBehavior() {
        AlertPayload test = new AlertPayload(0L, 0, "Sentinel test", null, null, "Sentinel test", null,
                Severity.INFORMATION, "TEST", "test", NOW, "{\"test\":true}");
        assertTrue(ActionConditionMatcher.matches(action("CHANNEL", "!=", "\"other\""), test),
                "a channel subject without an id is not the Log subject; its behavior is unchanged");
    }

    @Test
    void anActionReachesLogAlertsThroughMonitorTypeOrMonitor() {
        assertTrue(ActionConditionMatcher.matches(action("MONITOR_TYPE", "=", "\"LOG_EVENTS\""), logPayload()));
        assertTrue(ActionConditionMatcher.matches(action("MONITOR", "IN", "[\"8\"]"), logPayload()));
        assertTrue(ActionConditionMatcher.matches(null, logPayload()));
        assertFalse(ActionConditionMatcher.matches(action("MONITOR_TYPE", "=", "\"LOG_EVENTS\""), channelPayload()));
    }

    @Test
    void aLogPayloadIsNamedLogAndCarriesNoChannelId() {
        AlertPayload payload = logPayload();
        assertEquals("Log", payload.getChannelName());
        assertNull(payload.getChannelId());
        assertTrue(payload.subject().isServer());
        assertTrue(payload.copy("PROBLEM", "x").withRollupSummary(3, 60).subject().isServer());
        scope.verify(() -> ScopeResolver.channelName(anyString()), never());
    }

    @Test
    void theStormRollupNamesTheLogSubjectOnceAfterTheChannels() {
        AlertEvent first = event(CHANNEL_B, 60, AlertKind.PROBLEM, AlertStatus.PROBLEM);
        event(null, 70, AlertKind.PROBLEM, AlertStatus.PROBLEM);
        event(CHANNEL_A, 80, AlertKind.PROBLEM, AlertStatus.PROBLEM);
        event(null, 90, AlertKind.NOTIFICATION, AlertStatus.RESOLVED);
        List<ActionDispatchLog> rows = new ArrayList<>();
        for (long age : new long[] {10, 20, 30}) {
            ActionDispatchLog row = new ActionDispatchLog();
            row.setAlertEventId(first.getId());
            row.setActionId(10);
            row.setDispatchTime(NOW.minusSeconds(age));
            row.setSuccess(true);
            rows.add(row);
        }
        logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsInRange(any(), any())).thenReturn(rows);

        AlertStormControl.CeilingVerdict verdict = AlertStormControl.ceilingVerdict(action(OperationMode.BOTH, null), NOW);
        assertEquals(AlertStormControl.CeilingOutcome.ROLLUP, verdict.outcome());
        assertEquals(List.of("name:" + CHANNEL_A, "name:" + CHANNEL_B, "Log"), verdict.channels());
        assertTrue(AlertStormControl.rollupMessage(verdict).endsWith("3 channel(s) affected: name:" + CHANNEL_A
                + ", name:" + CHANNEL_B + ", Log."), AlertStormControl.rollupMessage(verdict));
    }

    @Test
    void aRecipientsRollupNamesTheLogSubjectOnlyWhenItsConditionsAdmitIt() {
        event(CHANNEL_A, 60, AlertKind.PROBLEM, AlertStatus.PROBLEM);
        event(null, 70, AlertKind.PROBLEM, AlertStatus.PROBLEM);
        assertEquals(List.of("name:" + CHANNEL_A, "Log"),
                RollupRecipients.channels(action(OperationMode.BOTH, null), NOW.minusSeconds(600), NOW));
        String notB = "[{\"field\":\"CHANNEL\",\"operator\":\"!=\",\"value\":\"" + CHANNEL_B + "\"}]";
        assertEquals(List.of("name:" + CHANNEL_A),
                RollupRecipients.channels(action(OperationMode.BOTH, notB), NOW.minusSeconds(600), NOW),
                "a channel condition never admits a Log alert, even with !=");
    }

    @Test
    void aRecipientsRollupCountsANotificationAsAnOpenedEdge() {
        event(null, 70, AlertKind.NOTIFICATION, AlertStatus.RESOLVED);
        for (OperationMode opened : List.of(OperationMode.ON_PROBLEM, OperationMode.BOTH)) {
            assertEquals(List.of("Log"), RollupRecipients.channels(action(opened, null), NOW.minusSeconds(600), NOW),
                    opened.name());
        }
        assertEquals(List.of(), RollupRecipients.channels(action(OperationMode.ON_RESOLVE, null),
                NOW.minusSeconds(600), NOW));

        recent.clear();
        event(null, 70, AlertKind.PROBLEM, AlertStatus.RESOLVED);
        assertEquals(List.of("Log"), RollupRecipients.channels(action(OperationMode.ON_RESOLVE, null),
                NOW.minusSeconds(600), NOW), "control: a resolved problem is a resolution edge");
        assertEquals(List.of(), RollupRecipients.channels(action(OperationMode.ON_PROBLEM, null),
                NOW.minusSeconds(600), NOW));
    }

    @Test
    void aFlappingLogProblemIsReadFromItsOwnProblemHistory() {
        AlertEvent current = new AlertEvent();
        current.setId(999L);
        current.setMonitorId(8);
        current.setSubjectType(SubjectType.SERVER);
        current.setStatus(AlertStatus.PROBLEM);
        current.setOpenedTime(NOW);
        List<AlertEvent> history = new ArrayList<>();
        for (long minutesAgo : new long[] {2, 6, 10, 14}) {
            history.add(resolvedLogProblem(minutesAgo));
        }
        AlertEvent channelRow = resolvedLogProblem(3);
        channelRow.setSubjectType(SubjectType.CHANNEL); // left on the monitor by a direct edit; not this subject
        channelRow.setChannelId(CHANNEL_A);
        history.add(channelRow);
        AtomicReference<AlertEventFilter> asked = new AtomicReference<>();
        alerts.when(() -> AlertEventRepository.listAlertEvents(any())).thenAnswer(call -> {
            asked.set(call.getArgument(0));
            return new PagedResult<>(history, history.size(), 0, 100);
        });

        AlertStormControl.FlapCheck check = AlertStormControl.flapCheck(current, NOW, false);
        assertEquals(AlertStormControl.FlapOutcome.ONSET, check.outcome());
        assertEquals(4, check.cycles(), "the channel row on the same monitor is not part of the Log subject's history");
        assertNull(asked.get().getChannelIdIn(), "a Log alert has no channel to filter on");
        assertEquals(8, asked.get().getMonitorId());
        assertEquals(AlertKind.PROBLEM, asked.get().getKind(), "notifications never resolve, so they are no history");
    }

    private AlertEvent event(String channelId, long openedSecondsAgo, AlertKind kind, AlertStatus status) {
        AlertEvent event = new AlertEvent();
        event.setId(nextId++);
        event.setMonitorId(channelId == null ? 8 : 7);
        event.setSubjectType(channelId == null ? SubjectType.SERVER : SubjectType.CHANNEL);
        event.setChannelId(channelId);
        event.setKind(kind);
        event.setSeverity(Severity.HIGH);
        event.setStatus(status);
        event.setOpenedTime(NOW.minusSeconds(openedSecondsAgo));
        recent.add(event);
        return event;
    }

    private AlertEvent resolvedLogProblem(long resolvedMinutesAgo) {
        AlertEvent event = new AlertEvent();
        event.setId(nextId++);
        event.setMonitorId(8);
        event.setSubjectType(SubjectType.SERVER);
        event.setStatus(AlertStatus.RESOLVED);
        event.setOpenedTime(NOW.minusSeconds((resolvedMinutesAgo + 1) * 60));
        event.setResolvedTime(NOW.minusSeconds(resolvedMinutesAgo * 60));
        return event;
    }

    private static AlertPayload logPayload() {
        AlertEvent event = new AlertEvent();
        event.setId(11L);
        event.setMonitorId(8);
        event.setSubjectType(SubjectType.SERVER);
        event.setSeverity(Severity.HIGH);
        event.setMessage("3 matching log lines in the last 300s (at least 3)");
        event.setOpenedTime(NOW);
        return AlertPayload.of(event, monitor(8, MonitorType.LOG_EVENTS), "PROBLEM");
    }

    private static AlertPayload channelPayload() {
        return new AlertPayload(12L, 7, "Inactivity", MonitorType.INACTIVITY, CHANNEL_A, "ADT In", null,
                Severity.HIGH, "PROBLEM", "quiet", NOW, "{}");
    }

    private static Monitor monitor(int id, MonitorType type) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setName("monitor " + id);
        monitor.setEnabled(true);
        monitor.setMonitorType(type);
        return monitor;
    }

    private static Action action(String field, String operator, String valueJson) {
        return action(OperationMode.BOTH, "[{\"field\":\"" + field + "\",\"operator\":\"" + operator
                + "\",\"value\":" + valueJson + "}]");
    }

    private static Action action(OperationMode mode, String conditionJson) {
        Action action = new Action();
        action.setId(10);
        action.setName("Page on-call");
        action.setEnabled(true);
        action.setOperationMode(mode);
        action.setMaxNotificationsPerWindow(3);
        action.setRollupWindowSeconds(600);
        action.setConditionJson(conditionJson);
        return action;
    }
}
