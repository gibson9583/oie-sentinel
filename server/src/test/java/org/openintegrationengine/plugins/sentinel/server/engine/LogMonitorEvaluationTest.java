/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MaintenanceWindowRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MatchSample;
import org.openintegrationengine.plugins.sentinel.server.db.MatchSampleRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.LogEventsEvaluator;
import org.openintegrationengine.plugins.sentinel.server.log.LogMatchTestDatabase;
import org.openintegrationengine.plugins.sentinel.server.service.ProblemService;
import org.openintegrationengine.plugins.sentinel.server.service.SentinelAuditLog;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowMode;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowRepeat;

/**
 * A Log monitor through TriggerEvaluatorJob on the real Derby schema and
 * mapper: its problems, its clear modes and its notification outcome. Only the
 * dispatcher and the engine's channel lookups are replaced. The first test
 * runs the real evaluator; the others script its verdicts, which
 * LogEventsEvaluatorTest covers.
 */
class LogMonitorEvaluationTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    /** A real 36-character id: channel_id is CHAR(36), so a short one would come back padded. */
    private static final String CHANNEL = "00000000-0000-0000-0000-0000000000c1";
    private static final String OUTCOME_CHANGED =
            "Monitor outcome changed to notifications; this problem is no longer evaluated";

    private LogMatchTestDatabase database;
    private MockedStatic<ActionDispatcher> dispatch;
    private MockedStatic<ScopeResolver> scope;
    private final List<AlertPayload> opened = new ArrayList<>();
    private final List<AlertPayload> repeated = new ArrayList<>();
    private final List<AlertPayload> resolved = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        database = new LogMatchTestDatabase();
        dispatch = mockStatic(ActionDispatcher.class);
        dispatch.when(() -> ActionDispatcher.onAlertOpened(any(), any()))
                .thenAnswer(call -> opened.add(call.getArgument(1)));
        dispatch.when(() -> ActionDispatcher.onRepeatCheck(any(), any()))
                .thenAnswer(call -> repeated.add(call.getArgument(1)));
        dispatch.when(() -> ActionDispatcher.onAlertResolved(any(), any()))
                .thenAnswer(call -> resolved.add(call.getArgument(1)));
        scope = mockStatic(ScopeResolver.class);
        scope.when(() -> ScopeResolver.channelName(anyString())).thenAnswer(call -> "name of " + call.getArgument(0));
        scope.when(() -> ScopeResolver.resolveStartedChannelSet(any())).thenAnswer(call -> new ScopeResolver.ChannelResolution());
        scope.when(() -> ScopeResolver.strictGroupChannelIds(anyString())).thenReturn(Set.of("c-1"));
        scope.when(() -> ScopeResolver.strictTagChannelIds(anyString())).thenReturn(Set.of("c-1"));
    }

    @AfterEach
    void tearDown() throws Exception {
        scope.close();
        dispatch.close();
        database.close();
    }

    @Test
    void aLogProblemOpensStaysOpenThroughAGapAndClearsOnACoveredCompleteOk() throws Exception {
        Monitor monitor = database.logMonitor("Log errors", "{\"threshold\":2,\"includeMatches\":true}");

        samples(monitor, sample("node-a", NOW.minusSeconds(290), 1, matches("node-a", NOW.minusSeconds(291))),
                sample("node-b", NOW.minusSeconds(5), 1, matches("node-b", NOW.minusSeconds(6))));
        TriggerEvaluatorJob.evaluateMonitor(monitor, NOW);
        TriggerState state = logState(monitor);
        assertEquals(TriggerStatus.PROBLEM, state.getState());
        assertEquals(SubjectType.SERVER, state.getSubjectType());
        assertNull(state.getChannelId());
        assertNull(state.getMetadataId());
        AlertEvent event = AlertEventRepository.getAlertEvent(state.getOpenAlertEventId());
        assertEquals(SubjectType.SERVER, event.getSubjectType());
        assertNull(event.getChannelId());
        assertFalse(event.isSuppressed());
        assertEquals("2 matching log lines in the last 300s (at least 2)", event.getMessage());
        assertEquals(2, details(event).path("perNode").size());
        assertEquals(1, opened.size());
        assertEquals("Log", opened.get(0).getChannelName());
        assertEquals("node-a", opened.get(0).getMatches().get(0).node());

        // Still breaching: the same problem takes the new evaluation and gets a still-open pass.
        samples(monitor, sample("node-a", NOW.plusSeconds(15), 1, null));
        TriggerEvaluatorJob.evaluateMonitor(monitor, NOW.plusSeconds(20));
        assertEquals(event.getId(), logState(monitor).getOpenAlertEventId());
        assertEquals(1, events(monitor, AlertKind.PROBLEM).size());
        assertEquals(NOW.plusSeconds(20).toString(), details(event).path("lastBreach").asText());
        assertEquals(1, repeated.size());

        // No rows in the window: INSUFFICIENT_DATA keeps the problem open.
        TriggerEvaluatorJob.evaluateMonitor(monitor, NOW.plusSeconds(400));
        assertEquals(TriggerStatus.INSUFFICIENT_DATA, logState(monitor).getState());
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(event.getId()).getStatus());
        assertEquals(2, repeated.size());

        samples(monitor, sample("node-a", NOW.plusSeconds(410), 0, null), sample("node-a", NOW.plusSeconds(690), 0, null));
        TriggerEvaluatorJob.evaluateMonitor(monitor, NOW.plusSeconds(700));
        assertEquals(TriggerStatus.OK, logState(monitor).getState());
        assertNull(logState(monitor).getOpenAlertEventId());
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(event.getId()).getStatus());
        assertEquals(List.of("Log"), resolved.stream().map(AlertPayload::getChannelName).toList());
        assertEquals(1, TriggerStateRepository.listTriggerStatesByMonitor(monitor.getId()).size());
    }

    @Test
    void aManualProblemStaysOpenOnOkUntilAPersonResolvesIt() {
        Monitor monitor = database.logMonitor("Log errors", "{\"clearMode\":\"MANUAL\"}");
        evaluate(monitor, NOW, breach(NOW));
        long id = logState(monitor).getOpenAlertEventId();

        evaluate(monitor, NOW.plusSeconds(60), ok());
        evaluate(monitor, NOW.plusSeconds(3600), ok());
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(id).getStatus());
        assertEquals(TriggerStatus.OK, logState(monitor).getState());
        assertEquals(2, repeated.size(), "a held problem keeps its still-open pass");

        evaluate(monitor, NOW.plusSeconds(3660), breach(NOW.plusSeconds(3660)));
        assertEquals(1, events(monitor, AlertKind.PROBLEM).size(), "a new breach adds to the held problem");

        try (MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            ProblemService.resolve(id, null, 1);
        }
        evaluate(monitor, NOW.plusSeconds(3720), ok());
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(id).getStatus());
        assertNull(logState(monitor).getOpenAlertEventId());
        assertEquals(1, events(monitor, AlertKind.PROBLEM).size());
    }

    @Test
    void aTimeoutProblemClearsOnOkOnlyOnceTheTimeSinceItsLastBreachIsUp() throws Exception {
        Monitor monitor = database.logMonitor("Log errors", "{\"clearMode\":\"TIMEOUT\",\"clearAfterSeconds\":600}");
        evaluate(monitor, NOW, breach(NOW));
        long id = logState(monitor).getOpenAlertEventId();
        evaluate(monitor, NOW.plusSeconds(60), breach(NOW.plusSeconds(60)));
        assertEquals(1, events(monitor, AlertKind.PROBLEM).size());
        assertEquals(NOW.plusSeconds(60).toString(),
                details(AlertEventRepository.getAlertEvent(id)).path("lastBreach").asText(), "the details were rewritten");

        evaluate(monitor, NOW.plusSeconds(659), ok());
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(id).getStatus(),
                "600 seconds after the first breach but not after the last");
        evaluate(monitor, NOW.plusSeconds(660), ok());
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(id).getStatus());
        assertEquals(1, resolved.size());
    }

    @Test
    void aFreshBreachExtendsAHeldTimeoutProblemBeforeTheOpeningThresholdIsCounted() throws Exception {
        Monitor monitor = database.logMonitor("Log errors", "{\"clearMode\":\"TIMEOUT\",\"clearAfterSeconds\":180}");
        monitor.setMinConsecutiveBreaches(2);
        evaluate(monitor, NOW, breach(NOW));
        evaluate(monitor, NOW.plusSeconds(60), breach(NOW.plusSeconds(60)));
        long id = logState(monitor).getOpenAlertEventId();
        evaluate(monitor, NOW.plusSeconds(120), ok());
        evaluate(monitor, NOW.plusSeconds(180), ok());
        assertEquals(TriggerStatus.OK, logState(monitor).getState(), "held open by its timeout");

        // One breach, under the two it takes to open a problem: it is still the open problem's last breach.
        evaluate(monitor, NOW.plusSeconds(240), breach(NOW.plusSeconds(240)));
        evaluate(monitor, NOW.plusSeconds(300), ok());
        evaluate(monitor, NOW.plusSeconds(419), ok());
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(id).getStatus(),
                "180 seconds after the breach at 60 but not after the breach at 240");
        assertEquals(NOW.plusSeconds(240).toString(),
                details(AlertEventRepository.getAlertEvent(id)).path("lastBreach").asText());
        evaluate(monitor, NOW.plusSeconds(420), ok());
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(id).getStatus());
        assertEquals(1, events(monitor, AlertKind.PROBLEM).size());
        assertEquals(1, resolved.size());
    }

    @Test
    void aTimeoutProblemAlsoClearsWithoutDataOnceTheTimeIsUp() {
        Monitor monitor = database.logMonitor("Log errors", "{\"clearMode\":\"TIMEOUT\",\"clearAfterSeconds\":600}");
        evaluate(monitor, NOW, breach(NOW));
        long id = logState(monitor).getOpenAlertEventId();
        evaluate(monitor, NOW.plusSeconds(599), noData());
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(id).getStatus());
        evaluate(monitor, NOW.plusSeconds(600), noData());
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(id).getStatus());
        assertNull(logState(monitor).getOpenAlertEventId());
        assertEquals(1, resolved.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AT_LEAST", "AT_MOST"})
    void aNotificationOutcomeFiresOncePerTransitionIntoBreach(String condition) throws Exception {
        Monitor monitor = database.logMonitor("Log errors",
                "{\"condition\":\"" + condition + "\",\"outcome\":\"NOTIFICATION\"}");
        evaluate(monitor, NOW, breach(NOW));
        evaluate(monitor, NOW.plusSeconds(60), breach(NOW.plusSeconds(60)));
        assertEquals(1, events(monitor, AlertKind.NOTIFICATION).size(), "none while the breach continues");

        evaluate(monitor, NOW.plusSeconds(120), noData());
        evaluate(monitor, NOW.plusSeconds(180), breach(NOW.plusSeconds(180)));
        assertEquals(1, events(monitor, AlertKind.NOTIFICATION).size(), "a data gap does not re-arm it");

        evaluate(monitor, NOW.plusSeconds(240), ok());
        evaluate(monitor, NOW.plusSeconds(300), breach(NOW.plusSeconds(300)));
        List<AlertEvent> notifications = events(monitor, AlertKind.NOTIFICATION);
        assertEquals(2, notifications.size(), "an OK re-arms it");
        assertEquals(List.of(), events(monitor, AlertKind.PROBLEM));

        AlertEvent first = notifications.get(1);
        assertEquals(AlertStatus.RESOLVED, first.getStatus());
        assertTrue(first.isProblemPending());
        assertFalse(first.isResolutionPending());
        assertEquals(NOW, first.getOpenedTime());
        assertEquals("1 matching log line in the last 300s", first.getMessage());
        assertEquals(NOW.toString(), details(first).path("lastBreach").asText());
        assertEquals(List.of("NOTIFICATION", "NOTIFICATION"), opened.stream().map(AlertPayload::getEventType).toList());
        assertNull(logState(monitor).getOpenAlertEventId(), "a notification is never held open");
        assertTrue(repeated.isEmpty() && resolved.isEmpty());
    }

    @Test
    void aNotificationOutcomeWaitsForMinConsecutiveBreaches() {
        Monitor monitor = database.logMonitor("Log errors", "{\"outcome\":\"NOTIFICATION\"}");
        monitor.setMinConsecutiveBreaches(2);
        evaluate(monitor, NOW, breach(NOW));
        evaluate(monitor, NOW.plusSeconds(60), ok());
        evaluate(monitor, NOW.plusSeconds(120), breach(NOW.plusSeconds(120)));
        assertEquals(List.of(), events(monitor, AlertKind.NOTIFICATION), "an OK in between restarts the count");
        evaluate(monitor, NOW.plusSeconds(180), breach(NOW.plusSeconds(180)));
        assertEquals(1, events(monitor, AlertKind.NOTIFICATION).size());
    }

    @Test
    void switchingTheOutcomeToNotificationsResolvesTheOpenProblemAndStartsFresh() {
        Monitor monitor = database.logMonitor("Log errors", "{}");
        evaluate(monitor, NOW, breach(NOW));
        long problem = logState(monitor).getOpenAlertEventId();

        configure(monitor, "{\"outcome\":\"NOTIFICATION\"}");
        evaluate(monitor, NOW.plusSeconds(60), ok());
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(problem).getStatus());
        assertNull(logState(monitor).getOpenAlertEventId());
        assertEquals(List.of(OUTCOME_CHANGED), resolved.stream().map(AlertPayload::getMessage).toList());
        assertEquals("Log", resolved.get(0).getChannelName());

        evaluate(monitor, NOW.plusSeconds(120), breach(NOW.plusSeconds(120)));
        assertEquals(1, events(monitor, AlertKind.NOTIFICATION).size());
        assertEquals(List.of(AlertStatus.RESOLVED),
                events(monitor, AlertKind.PROBLEM).stream().map(AlertEvent::getStatus).toList(), "nothing else is open");
        assertNull(logState(monitor).getOpenAlertEventId());
        assertEquals(List.of("PROBLEM", "NOTIFICATION"), opened.stream().map(AlertPayload::getEventType).toList());
        assertEquals(1, resolved.size());
    }

    @Test
    void aBreachAtTheSwitchResolvesTheProblemAndNotifiesOnceOnTheFreshState() {
        Monitor monitor = database.logMonitor("Log errors", "{}");
        evaluate(monitor, NOW, breach(NOW));
        long problem = logState(monitor).getOpenAlertEventId();

        configure(monitor, "{\"outcome\":\"NOTIFICATION\"}");
        evaluate(monitor, NOW.plusSeconds(60), breach(NOW.plusSeconds(60)));
        evaluate(monitor, NOW.plusSeconds(120), breach(NOW.plusSeconds(120)));
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(problem).getStatus());
        assertEquals(List.of(OUTCOME_CHANGED), resolved.stream().map(AlertPayload::getMessage).toList());
        assertEquals(1, events(monitor, AlertKind.NOTIFICATION).size(), "one per transition into breach");
        assertEquals(TriggerStatus.PROBLEM, logState(monitor).getState());
        assertNull(logState(monitor).getOpenAlertEventId());
    }

    @Test
    void switchingToANotificationForEachOccurrenceAlsoResolvesTheOpenProblem() {
        Monitor monitor = database.logMonitor("Log errors", "{}");
        evaluate(monitor, NOW, breach(NOW));
        long problem = logState(monitor).getOpenAlertEventId();

        configure(monitor, "{\"condition\":\"EACH\",\"outcome\":\"NOTIFICATION\"}");
        evaluate(monitor, NOW.plusSeconds(60), breach(NOW.plusSeconds(60)));
        evaluate(monitor, NOW.plusSeconds(120), breach(NOW.plusSeconds(120)));
        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(problem).getStatus());
        assertNull(logState(monitor).getOpenAlertEventId());
        assertEquals(List.of(OUTCOME_CHANGED), resolved.stream().map(AlertPayload::getMessage).toList());
        assertEquals(List.of(), events(monitor, AlertKind.NOTIFICATION), "the nodes write these, not the leader");
    }

    @Test
    void eachOccurrenceAsANotificationIsLeftToTheNodesAndEachOccurrenceAsAProblemIsEvaluated() {
        Monitor notifications = database.logMonitor("each", "{\"condition\":\"EACH\",\"outcome\":\"NOTIFICATION\"}");
        Monitor problems = database.logMonitor("each problem", "{\"condition\":\"EACH\"}");
        try (MockedStatic<LogEventsEvaluator> evaluator = mockStatic(LogEventsEvaluator.class)) {
            evaluator.when(() -> LogEventsEvaluator.evaluate(any(), any())).thenReturn(breach(NOW));
            TriggerEvaluatorJob.evaluateMonitor(notifications, NOW);
            evaluator.verify(() -> LogEventsEvaluator.evaluate(any(), any()), never());
            TriggerEvaluatorJob.evaluateMonitor(problems, NOW);
        }
        assertEquals(List.of(), TriggerStateRepository.listTriggerStatesByMonitor(notifications.getId()));
        assertEquals(List.of(), events(notifications, AlertKind.NOTIFICATION));
        assertEquals(1, events(problems, AlertKind.PROBLEM).size());
    }

    @Test
    void aChannelRowOnALogMonitorIsClosedAsAKindChangeAndTheLogRowStays() {
        Monitor monitor = database.logMonitor("Log errors", "{\"threshold\":1}");
        AlertEvent channelEvent = openRow(monitor, SubjectType.CHANNEL, CHANNEL);
        AlertEvent logEvent = openRow(monitor, SubjectType.SERVER, null);

        // No rows at all: INSUFFICIENT_DATA, which never clears the Log row.
        TriggerEvaluatorJob.evaluateMonitor(monitor, NOW);

        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(channelEvent.getId()).getStatus());
        assertEquals(AlertStatus.PROBLEM, AlertEventRepository.getAlertEvent(logEvent.getId()).getStatus());
        assertEquals(1, resolved.size());
        assertTrue(resolved.get(0).getMessage().startsWith("Monitor kind changed"), resolved.get(0).getMessage());
        assertEquals("name of " + CHANNEL, resolved.get(0).getChannelName());
        assertNull(TriggerStateRepository.getTriggerState(monitor.getId(), CHANNEL, null).getOpenAlertEventId());
        assertEquals(logEvent.getId(), logState(monitor).getOpenAlertEventId());
    }

    @Test
    void aLogRowOnAChannelMonitorIsClosedAsAKindChange() {
        Monitor channelMonitor = new Monitor();
        channelMonitor.setName("Inactivity");
        channelMonitor.setMonitorType(MonitorType.INACTIVITY);
        channelMonitor.setScopeType(ScopeType.ALL);
        channelMonitor.setSeverity(Severity.WARNING);
        channelMonitor.setConfigJson("{}");
        channelMonitor.setEnabled(true);
        channelMonitor.setMinConsecutiveBreaches(1);
        channelMonitor.setCreatedTime(NOW);
        MonitorRepository.insertMonitor(channelMonitor);
        AlertEvent logEvent = openRow(channelMonitor, SubjectType.SERVER, null);

        TriggerEvaluatorJob.evaluateMonitor(channelMonitor, NOW);

        assertEquals(AlertStatus.RESOLVED, AlertEventRepository.getAlertEvent(logEvent.getId()).getStatus());
        assertEquals(1, resolved.size());
        assertTrue(resolved.get(0).getMessage().startsWith("Monitor kind changed"), resolved.get(0).getMessage());
        assertEquals("Log", resolved.get(0).getChannelName());
        TriggerState state = logState(channelMonitor);
        assertNotNull(state);
        assertNull(state.getOpenAlertEventId());
    }

    @Test
    void aStoredLogEventIsAllowedWhileAChannelEventWithoutAChannelIdIsUnknown() {
        Monitor monitor = logMonitor(5);
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class);
                MockedStatic<MaintenanceWindowRepository> windows = mockStatic(MaintenanceWindowRepository.class)) {
            monitors.when(() -> MonitorRepository.getMonitor(5)).thenReturn(monitor);
            AlertEvent logEvent = new AlertEvent();
            logEvent.setId(99L);
            logEvent.setMonitorId(5);
            logEvent.setSubjectType(SubjectType.SERVER);
            assertEquals(NotificationSuppression.Decision.ALLOW, NotificationSuppression.decision(logEvent, NOW));
            assertFalse(NotificationSuppression.isSuppressed(logEvent, NOW));
            assertFalse(NotificationSuppression.isSuppressed(monitor, ProblemSubject.server(), NOW));

            AlertEvent damaged = new AlertEvent();
            damaged.setMonitorId(5);
            assertEquals(NotificationSuppression.Decision.UNKNOWN, NotificationSuppression.decision(damaged, NOW));
            assertTrue(NotificationSuppression.isSuppressed(monitor, (String) null, NOW));
        }
    }

    @Test
    void onlyAnAllScopeWindowOrScheduleAppliesToALogSubject() {
        Monitor monitor = logMonitor(5);
        List<MaintenanceWindow> narrow = new ArrayList<>();
        for (ScopeType scopeType : List.of(ScopeType.CHANNEL, ScopeType.GROUP, ScopeType.TAG)) {
            narrow.add(window(WindowMode.SUPPRESS, scopeType, NOW.minusSeconds(60), NOW.plusSeconds(60)));
            narrow.add(window(WindowMode.ACTIVE, scopeType, NOW.plusSeconds(600), NOW.plusSeconds(1200)));
        }
        try (MockedStatic<MaintenanceWindowRepository> windows = mockStatic(MaintenanceWindowRepository.class)) {
            windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows).thenReturn(narrow);
            assertFalse(NotificationSuppression.isSuppressed(monitor, ProblemSubject.server(), NOW));
            scope.verify(() -> ScopeResolver.strictGroupChannelIds(anyString()), never());
            scope.verify(() -> ScopeResolver.strictTagChannelIds(anyString()), never());
            for (MaintenanceWindow each : narrow) {
                windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows).thenReturn(List.of(each));
                assertTrue(NotificationSuppression.isSuppressed(monitor, ProblemSubject.channel("c-1"), NOW),
                        "control: the window holds channel c-1 (" + each.getMode() + " " + each.getScopeType() + ")");
            }

            windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows).thenReturn(
                    List.of(window(WindowMode.SUPPRESS, ScopeType.ALL, NOW.minusSeconds(60), NOW.plusSeconds(60))));
            assertTrue(NotificationSuppression.isSuppressed(monitor, ProblemSubject.server(), NOW));
            assertFalse(NotificationSuppression.isSuppressed(monitor, ProblemSubject.server(), NOW.plusSeconds(120)),
                    "a window that has ended no longer suppresses");
            windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows).thenReturn(
                    List.of(window(WindowMode.ACTIVE, ScopeType.ALL, NOW.minusSeconds(60), NOW.plusSeconds(60))));
            assertFalse(NotificationSuppression.isSuppressed(monitor, ProblemSubject.server(), NOW));
            assertTrue(NotificationSuppression.isSuppressed(monitor, ProblemSubject.server(), NOW.plusSeconds(120)),
                    "outside an ALL-scope alerting schedule a Log problem is held too");
        }
    }

    @Test
    void aDependencyMatchesOnlyTheParentsOpenProblemOnTheSameSubjectKind() {
        Monitor child = logMonitor(3);
        child.setSuppressedByMonitorId(2);
        Monitor channelChild = logMonitor(4);
        channelChild.setMonitorType(MonitorType.INACTIVITY);
        channelChild.setSuppressedByMonitorId(2);
        AlertEvent open = new AlertEvent();
        open.setId(88L);
        open.setStatus(AlertStatus.PROBLEM);
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class);
                MockedStatic<TriggerStateRepository> states = mockStatic(TriggerStateRepository.class);
                MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class);
                MockedStatic<MaintenanceWindowRepository> windows = mockStatic(MaintenanceWindowRepository.class)) {
            monitors.when(() -> MonitorRepository.getMonitor(2)).thenReturn(logMonitor(2));
            alerts.when(() -> AlertEventRepository.getAlertEvent(88L)).thenReturn(open);
            TriggerState parentLog = parentState(SubjectType.SERVER, null);
            states.when(() -> TriggerStateRepository.listTriggerStatesByMonitor(2)).thenReturn(List.of(parentLog));
            assertTrue(NotificationSuppression.isSuppressed(child, ProblemSubject.server(), NOW));
            assertFalse(NotificationSuppression.isSuppressed(channelChild, ProblemSubject.channel("c-1"), NOW));

            // A null-channel CHANNEL row is not the Log subject.
            states.when(() -> TriggerStateRepository.listTriggerStatesByMonitor(2)).thenReturn(
                    List.of(parentState(SubjectType.CHANNEL, "c-1"), parentState(SubjectType.CHANNEL, null)));
            assertFalse(NotificationSuppression.isSuppressed(child, ProblemSubject.server(), NOW));
        }
    }

    private void evaluate(Monitor monitor, Instant at, EvaluationOutcome outcome) {
        try (MockedStatic<LogEventsEvaluator> evaluator = mockStatic(LogEventsEvaluator.class)) {
            evaluator.when(() -> LogEventsEvaluator.evaluate(any(), any())).thenReturn(outcome);
            TriggerEvaluatorJob.evaluateMonitor(monitor, at);
        }
    }

    /** Saves a new config, as an edit in the UI would; the evaluator reads the monitor it is handed. */
    private static void configure(Monitor monitor, String configJson) {
        monitor.setConfigJson(configJson);
        MonitorRepository.updateMonitor(monitor);
    }

    private static EvaluationOutcome breach(Instant at) {
        return EvaluationOutcome.breach("{\"count\":1,\"lastBreach\":\"" + at + "\"}", "1 matching log line in the last 300s");
    }

    private static EvaluationOutcome ok() {
        return EvaluationOutcome.ok("{\"count\":0}");
    }

    private static EvaluationOutcome noData() {
        return EvaluationOutcome.insufficientData("{\"count\":0}");
    }

    private static TriggerState logState(Monitor monitor) {
        return TriggerStateRepository.getTriggerState(monitor.getId(), SubjectType.SERVER, null, null);
    }

    /** A monitor's alert events of one kind, newest first. */
    private static List<AlertEvent> events(Monitor monitor, AlertKind kind) {
        return AlertEventRepository.listRecentAlertEvents(100, kind).stream()
                .filter(event -> monitor.getId().equals(event.getMonitorId())).toList();
    }

    private static JsonNode details(AlertEvent event) throws Exception {
        return Json.mapper().readTree(AlertEventRepository.getAlertEvent(event.getId()).getDetailsJson());
    }

    /** Writes an open problem and its trigger row directly, the way a database edit would leave them. */
    private static AlertEvent openRow(Monitor monitor, SubjectType type, String channelId) {
        AlertEvent event = new AlertEvent();
        event.setMonitorId(monitor.getId());
        event.setSubjectType(type);
        event.setChannelId(channelId);
        event.setSeverity(Severity.HIGH);
        event.setStatus(AlertStatus.PROBLEM);
        event.setMessage("opened before the kind changed");
        event.setOpenedTime(NOW.minusSeconds(3600));
        AlertEventRepository.insertAlertEvent(event);
        TriggerState state = new TriggerState();
        state.setMonitorId(monitor.getId());
        state.setSubjectType(type);
        state.setChannelId(channelId);
        state.setState(TriggerStatus.PROBLEM);
        state.setConsecutiveBreachCount(1);
        state.setOpenAlertEventId(event.getId());
        state.setLastChangeTime(event.getOpenedTime());
        state.setLastEvaluatedTime(event.getOpenedTime());
        TriggerStateRepository.insertTriggerState(state);
        return event;
    }

    /** A row whose lines were all logged in the second it was written. */
    private static MatchSample sample(String node, Instant time, long count, String matchesJson) {
        return new MatchSample(null, node, 0, null, time, count, matchesJson, true,
                count > 0 ? "{\"" + time.getEpochSecond() + "\":" + count + "}" : null);
    }

    private static void samples(Monitor monitor, MatchSample... samples) {
        List<MatchSample> rows = new ArrayList<>();
        for (MatchSample sample : samples) {
            rows.add(new MatchSample(null, sample.nodeId(), monitor.getId(), null, sample.sampleTime(),
                    sample.matchCount(), sample.samplesJson(), true, sample.secondCounts()));
        }
        MatchSampleRepository.insertSamples(rows);
    }

    private static String matches(String node, Instant time) {
        return "[{\"time\":\"" + time + "\",\"node\":\"" + node + "\",\"level\":\"ERROR\","
                + "\"logger\":\"transformer\",\"message\":\"boom on " + node + "\"}]";
    }

    private static Monitor logMonitor(int id) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setEnabled(true);
        monitor.setMonitorType(MonitorType.LOG_EVENTS);
        monitor.setScopeType(ScopeType.ALL);
        return monitor;
    }

    private static TriggerState parentState(SubjectType type, String channelId) {
        TriggerState state = new TriggerState();
        state.setMonitorId(2);
        state.setSubjectType(type);
        state.setChannelId(channelId);
        state.setState(TriggerStatus.PROBLEM);
        state.setOpenAlertEventId(88L);
        return state;
    }

    private static MaintenanceWindow window(WindowMode mode, ScopeType scopeType, Instant from, Instant until) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(1);
        window.setName("schedule");
        window.setEnabled(true);
        window.setMode(mode);
        window.setScopeType(scopeType);
        window.setScopeId(scopeType == ScopeType.ALL ? null : scopeType == ScopeType.CHANNEL ? "c-1" : "scope");
        window.setRepeatType(WindowRepeat.NONE);
        window.setActiveFrom(from);
        window.setActiveUntil(until);
        return window;
    }
}
