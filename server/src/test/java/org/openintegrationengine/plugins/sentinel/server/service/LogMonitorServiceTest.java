/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.server.controllers.EventController;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.log.LogMatchTestDatabase;
import org.openintegrationengine.plugins.sentinel.server.log.LogNotifications;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.DashboardSummary;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorTestResult;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SeverityCount;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;
import org.openintegrationengine.plugins.sentinel.shared.model.TopChannel;

/**
 * Log monitors and their problems and notifications through the real
 * services, repositories and migrated Derby schema: save-time rules, import
 * and export, visibility, the problem and notification reads, the dashboard,
 * metrics and audit. Only the engine's controllers are replaced.
 */
class LogMonitorServiceTest {

    private static final String CONFIG = "{\"minLevel\":\"WARN\",\"loggerPrefix\":\"transformer\","
            + "\"pattern\":\"timed? out\",\"threshold\":3,\"windowSeconds\":600,\"includeMatches\":true}";
    /** channel_id is CHAR(36), so a shorter id would come back padded. */
    private static final String CHANNEL_A = "00000000-0000-0000-0000-00000000000a";
    private static final String CHANNEL_B = "00000000-0000-0000-0000-00000000000b";

    private LogMatchTestDatabase database;
    private MockedStatic<EventController> events;
    private MockedStatic<ChannelController> channels;
    private MockedStatic<MaintenanceWindowService> windows;
    private ChannelController channelController;
    private final List<ServerEvent> audited = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        database = new LogMatchTestDatabase();
        EventController eventController = mock(EventController.class);
        doAnswer(call -> audited.add(call.getArgument(0))).when(eventController).dispatchEvent(any(ServerEvent.class));
        events = mockStatic(EventController.class);
        events.when(EventController::getInstance).thenReturn(eventController);
        channelController = mock(ChannelController.class);
        Channel channel = new Channel();
        channel.setId(CHANNEL_A);
        channel.setName("ADT In");
        when(channelController.getChannelById(CHANNEL_A)).thenReturn(channel);
        when(channelController.getChannels(null)).thenReturn(List.of(channel));
        channels = mockStatic(ChannelController.class);
        channels.when(ChannelController::getInstance).thenReturn(channelController);
        windows = mockStatic(MaintenanceWindowService.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        windows.close();
        channels.close();
        events.close();
        database.close();
    }

    // ========== Save-time rules ==========

    @Test
    void anAllScopeLogMonitorSavesAndABlankScopeIdIsStoredAsNull() {
        Monitor saved = MonitorService.create(logMonitor("Log errors", CONFIG), 99);
        assertEquals(MonitorType.LOG_EVENTS, MonitorRepository.getMonitor(saved.getId()).getMonitorType());

        Monitor blank = logMonitor("Blank scope id", "{}");
        blank.setScopeId(" ");
        assertNull(MonitorRepository.getMonitor(MonitorService.create(blank, 99).getId()).getScopeId());
    }

    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"CHANNEL", "GROUP", "TAG"})
    void aNarrowerScopeIsRejected(ScopeType scope) {
        Monitor monitor = logMonitor("Narrow", "{}");
        monitor.setScopeType(scope);
        monitor.setScopeId("some-id");
        assertEquals("A Log monitor watches no channel, so its scope must be ALL",
                assertThrows(IllegalArgumentException.class, () -> MonitorService.create(monitor, 99)).getMessage());
        assertTrue(MonitorRepository.listMonitors(null, null, null, null).isEmpty());
    }

    @Test
    void anAllScopeWithAScopeIdIsRejected() {
        Monitor monitor = logMonitor("Scoped", "{}");
        monitor.setScopeId("c-1");
        assertEquals("A Log monitor takes no scope id; its scope is ALL",
                assertThrows(IllegalArgumentException.class, () -> MonitorService.create(monitor, 99)).getMessage());
    }

    @ParameterizedTest(name = "{0} is rejected with \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "{\"threshold\":0}           | threshold must be at least 1",
            "{\"minLevel\":\"LOUD\"}     | minLevel must be one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL",
            "[]                          | Monitor config must be a JSON object"
    })
    void anInvalidConfigIsRejectedWithTheParsersMessage(String config, String message) {
        assertEquals(message, assertThrows(IllegalArgumentException.class,
                () -> MonitorService.create(logMonitor("Bad config", config), 99)).getMessage());
    }

    @Test
    void theWindowIsCheckedAgainstTheCurrentSettingsAtSaveTime() {
        database.setting("evaluator.interval.seconds", 120);
        database.setting("retention.sample.days", 1);
        assertEquals("windowSeconds must be at least the evaluator interval (120 seconds)",
                assertThrows(IllegalArgumentException.class, () -> MonitorService.create(
                        logMonitor("Short", "{\"windowSeconds\":60}"), 99)).getMessage());
        assertEquals("windowSeconds must be at most the sample retention (86400 seconds, 1 days)",
                assertThrows(IllegalArgumentException.class, () -> MonitorService.create(
                        logMonitor("Long", "{\"windowSeconds\":86401}"), 99)).getMessage());
        assertNotNull(MonitorService.create(logMonitor("Each", "{\"condition\":\"EACH\",\"outcome\":\"NOTIFICATION\","
                + "\"windowSeconds\":60}"), 99).getId(), "each occurrence as a notification has no window to check");
        assertNotNull(MonitorService.create(logMonitor("Fits", "{\"windowSeconds\":120}"), 99).getId());
    }

    @Test
    void anUpdateCannotSwitchKindsButCanChangeTypeOrConfigWithinOne() {
        Monitor log = MonitorService.create(logMonitor("Log errors", CONFIG), 99);
        Monitor channel = MonitorService.create(channelMonitor("Quiet feed", null), 99);

        assertEquals("Monitor 'Log errors' is a Log monitor and cannot become a Channel monitor. Create a new monitor instead",
                assertThrows(IllegalArgumentException.class,
                        () -> MonitorService.update(log.getId(), channelMonitor("Log errors", null), 99)).getMessage());
        assertEquals("Monitor 'Quiet feed' is a Channel monitor and cannot become a Log monitor. Create a new monitor instead",
                assertThrows(IllegalArgumentException.class,
                        () -> MonitorService.update(channel.getId(), logMonitor("Quiet feed", "{}"), 99)).getMessage());
        assertEquals(MonitorType.INACTIVITY, MonitorRepository.getMonitor(channel.getId()).getMonitorType());

        Monitor errorRate = channelMonitor("Quiet feed", null);
        errorRate.setMonitorType(MonitorType.ERROR_RATE);
        assertEquals(MonitorType.ERROR_RATE, MonitorService.update(channel.getId(), errorRate, 99).getMonitorType());
        assertEquals("{\"threshold\":9}",
                MonitorService.update(log.getId(), logMonitor("Log errors", "{\"threshold\":9}"), 99).getConfigJson());
    }

    @Test
    void aSuppressionDependencyMustStayWithinOneKind() {
        Monitor logParent = MonitorService.create(logMonitor("Log parent", "{}"), 99);
        Monitor channelParent = MonitorService.create(channelMonitor("Channel parent", null), 99);

        Monitor logChild = logMonitor("Log child", "{}");
        logChild.setSuppressedByMonitorId(channelParent.getId());
        assertEquals("Monitor 'Channel parent' is a Channel monitor; a Log monitor can only be suppressed by another"
                + " Log monitor", assertThrows(IllegalArgumentException.class, () -> MonitorService.create(logChild, 99))
                        .getMessage());
        Monitor channelChild = channelMonitor("Channel child", logParent.getId());
        assertEquals("Monitor 'Log parent' is a Log monitor; a Channel monitor can only be suppressed by another"
                + " Channel monitor", assertThrows(IllegalArgumentException.class,
                        () -> MonitorService.create(channelChild, 99)).getMessage());

        logChild.setSuppressedByMonitorId(logParent.getId());
        assertNotNull(MonitorService.create(logChild, 99).getId());
        channelChild.setSuppressedByMonitorId(channelParent.getId());
        assertNotNull(MonitorService.create(channelChild, 99).getId());
    }

    @Test
    void aKindChangeCannotBreakADependencyInEitherDirection() {
        Monitor channelParent = MonitorService.create(channelMonitor("Channel parent", null), 99);
        Monitor channelChild = MonitorService.create(channelMonitor("Channel child", channelParent.getId()), 99);

        assertThrows(IllegalArgumentException.class,
                () -> MonitorService.update(channelParent.getId(), logMonitor("Channel parent", "{}"), 99));
        Monitor childAsLog = logMonitor("Channel child", "{}");
        childAsLog.setSuppressedByMonitorId(channelParent.getId());
        assertThrows(IllegalArgumentException.class, () -> MonitorService.update(channelChild.getId(), childAsLog, 99));
        assertEquals(channelParent.getId(), MonitorRepository.getMonitor(channelChild.getId()).getSuppressedByMonitorId());
        assertEquals(MonitorType.INACTIVITY, MonitorRepository.getMonitor(channelParent.getId()).getMonitorType());
    }

    @Test
    void aLogMonitorCannotBePreviewed() {
        for (Set<String> caller : Arrays.asList(null, Set.<String>of(), Set.of(CHANNEL_A))) {
            MonitorTestResult result = MonitorService.test(logMonitor("Log errors", CONFIG), caller);
            assertTrue(result.isOk());
            assertEquals(List.of(), result.getOutcomes());
            assertEquals("Log monitors can't be previewed: they count lines as the engine logs them, from the first "
                    + "collector run after the monitor is saved", result.getMessage());
        }
    }

    // ========== Import, export and visibility ==========

    @Test
    void aLogMonitorRoundTripsThroughExportAndImport() throws Exception {
        Monitor parent = MonitorService.create(logMonitor("Log parent", "{\"minLevel\":\"FATAL\"}"), 99);
        Monitor child = logMonitor("Log errors", CONFIG);
        child.setSuppressedByMonitorId(parent.getId());
        MonitorService.create(child, 99);

        ObjectNode exported = ExportImportService.export();
        assertEquals(2, exported.path("monitors").size());
        JsonNode node = entry(exported, "Log errors");
        assertEquals("LOG_EVENTS", node.path("monitorType").asText());
        assertEquals("ALL", node.path("scopeType").asText());
        assertEquals("Log parent", node.path("suppressedByMonitorName").asText());

        String before = MonitorIdentityDatabaseTestSupport.snapshot();
        assertEquals(2, ExportImportService.importDocument(exported, 99).getSkipped(), "re-importing changes nothing");
        assertEquals(before, MonitorIdentityDatabaseTestSupport.snapshot());

        MonitorRepository.deleteMonitor(MonitorIdentityDatabaseTestSupport.namedMonitor("Log errors").getId());
        MonitorRepository.deleteMonitor(parent.getId());
        assertEquals(2, ExportImportService.importDocument(exported, 99).getCreated());
        Monitor imported = MonitorIdentityDatabaseTestSupport.namedMonitor("Log errors");
        assertEquals(MonitorType.LOG_EVENTS, imported.getMonitorType());
        assertEquals(ScopeType.ALL, imported.getScopeType());
        assertNull(imported.getScopeId());
        assertEquals(CONFIG, imported.getConfigJson());
        assertEquals(MonitorIdentityDatabaseTestSupport.namedMonitor("Log parent").getId(), imported.getSuppressedByMonitorId());
    }

    @Test
    void importReportsAKindSwitchOrAnInvalidLogMonitorAsASkippedEntry() {
        MonitorService.create(channelMonitor("Quiet feed", null), 99);
        ObjectNode document = MonitorIdentityDatabaseTestSupport.document();
        ObjectNode narrowed = logEntry("Narrow", "{}");
        narrowed.put("scopeType", "CHANNEL").put("scopeId", "c-1");
        document.putArray("monitors").add(logEntry("Quiet feed", "{}")).add(narrowed)
                .add(logEntry("Bad threshold", "{\"threshold\":-1}"));

        ExportImportService.ImportResult result = ExportImportService.importDocument(document, 99);
        assertEquals(3, result.getSkipped());
        assertEquals(0, result.getCreated() + result.getUpdated());
        assertEquals("Monitor 'Quiet feed' is a Channel monitor and cannot become a Log monitor. Create a new monitor instead",
                reason(result, "Quiet feed"));
        assertEquals("A Log monitor watches no channel, so its scope must be ALL", reason(result, "Narrow"));
        assertEquals("threshold must be at least 1", reason(result, "Bad threshold"));
        assertEquals(MonitorType.INACTIVITY, MonitorIdentityDatabaseTestSupport.namedMonitor("Quiet feed").getMonitorType());
    }

    @Test
    void aLogMonitorIsHiddenFromEveryRestrictedReaderEvenOneWithEveryChannel() {
        Monitor log = MonitorService.create(logMonitor("Log errors", "{}"), 99);
        Monitor allChannels = MonitorService.create(channelMonitor("Every channel", null), 99);
        Set<String> everyChannel = Set.of(CHANNEL_A);

        assertTrue(MonitorVisibility.isVisible(allChannels, everyChannel), "control: the ALL-scope rule shows it");
        assertFalse(MonitorVisibility.isVisible(log, everyChannel));
        assertEquals(List.of("Every channel"), MonitorService.list(everyChannel).stream().map(Monitor::getName).toList());
        assertThrows(NoSuchElementException.class, () -> MonitorService.get(log.getId(), everyChannel));
        assertTrue(MonitorVisibility.isVisible(log, null));
        assertEquals(2, MonitorService.list(null).size());
    }

    // ========== Problems and notifications ==========

    @Test
    void theProblemsListAsksForProblemsOnly() {
        Monitor monitor = database.logMonitor("Log errors", "{}");
        AlertEvent problem = AlertEventRepository.insertAlertEvent(open(monitor.getId(), null, Severity.HIGH, 60));
        notification(monitor, Instant.now());

        assertEquals(AlertKind.PROBLEM, ProblemService.buildFilter(null, null, null, null, null, null, null, null, null,
                null, null, 0, 25).getKind());
        assertEquals(List.of(problem.getId()), ids(ProblemService.list(null, null, null, null, null, null, null, null,
                null, null, null, 0, 25).items()));
    }

    @Test
    void aNotificationCannotBeAcknowledgedOrResolved() {
        Monitor monitor = database.logMonitor("Log errors", "{}");
        long id = notification(monitor, Instant.now()).getId();
        assertEquals(id + " is a notification, which cannot be acknowledged",
                assertThrows(IllegalArgumentException.class, () -> ProblemService.acknowledge(id, null, 1)).getMessage());
        assertEquals(id + " is a notification, which cannot be resolved",
                assertThrows(IllegalArgumentException.class, () -> ProblemService.resolve(id, null, 1)).getMessage());
        assertEquals(0, ProblemService.bulkAcknowledge(List.of(id), null, 1));
        assertEquals(0, ProblemService.bulkResolve(List.of(id), null, 1));
        AlertEvent stored = AlertEventRepository.getAlertEvent(id);
        assertNull(stored.getAcknowledgedBy());
        assertTrue(stored.isProblemPending(), "still waiting for its one delivery");
    }

    @Test
    void notificationsAreListedNewestFirstAndOnlyOnTheCallersChannels() {
        Monitor monitor = database.logMonitor("Log errors", "{}");
        Monitor other = database.logMonitor("Other", "{}");
        Instant now = Instant.now();
        AlertEventRepository.insertAlertEvent(open(monitor.getId(), null, Severity.HIGH, 60));
        AlertEvent older = notification(monitor, now.minusSeconds(120));
        AlertEvent newer = notification(monitor, now.minusSeconds(60));
        AlertEvent otherMonitors = notification(other, now.minusSeconds(30));
        AlertEvent onChannel = LogNotifications.notification(other, "on a channel", null, now.minusSeconds(90));
        onChannel.setSubjectType(SubjectType.CHANNEL);
        onChannel.setChannelId(CHANNEL_A);
        AlertEventRepository.insertAlertEvent(onChannel);

        assertEquals(List.of(otherMonitors.getId(), newer.getId(), onChannel.getId(), older.getId()),
                ids(NotificationService.list(null, null, null, 0, 25, null).items()));
        assertEquals(List.of(newer.getId(), older.getId()),
                ids(NotificationService.list(monitor.getId(), null, null, 0, 25, null).items()));
        assertEquals(List.of(onChannel.getId()),
                ids(NotificationService.list(null, null, null, 0, 25, Set.of(CHANNEL_A, CHANNEL_B)).items()));
        PagedResult<AlertEvent> none = NotificationService.list(null, null, null, 0, 25, Set.of());
        assertEquals(List.of(), none.items());
        assertEquals(0L, none.total());
    }

    @Test
    void storedMatchesAreReadOnlyWhileTheMonitorIncludesThem() throws Exception {
        Monitor monitor = database.logMonitor("Log errors", "{\"includeMatches\":true}");
        String details = "{\"count\":1,\"matches\":[{\"time\":\"2026-11-21T12:00:00Z\",\"node\":\"node-a\","
                + "\"level\":\"ERROR\",\"logger\":\"transformer\",\"message\":\"boom\"}]}";
        AlertEvent problem = open(monitor.getId(), null, Severity.HIGH, 60);
        problem.setDetailsJson(details);
        long problemId = AlertEventRepository.insertAlertEvent(problem).getId();
        AlertEventRepository.insertAlertEvent(LogNotifications.notification(monitor, "matched", details, Instant.now()));

        for (Map.Entry<String, JsonNode> read : readEveryWay(problemId).entrySet()) {
            assertEquals("boom", read.getValue().path("matches").path(0).path("message").asText(), read.getKey());
        }

        monitor.setConfigJson("{\"includeMatches\":false}");
        MonitorRepository.updateMonitor(monitor);
        Map<String, JsonNode> reads = readEveryWay(problemId);
        reads.put("acknowledge response",
                Json.mapper().readTree(ProblemService.acknowledge(problemId, null, 42).getDetailsJson()));
        reads.put("resolve response",
                Json.mapper().readTree(ProblemService.resolve(problemId, null, 42).getDetailsJson()));
        for (Map.Entry<String, JsonNode> read : reads.entrySet()) {
            assertFalse(read.getValue().has("matches"), read.getKey() + ": " + read.getValue());
            assertEquals(1, read.getValue().path("count").asInt(), read.getKey() + ": the rest of the details stay");
        }
        assertTrue(AlertEventRepository.getAlertEvent(problemId).getDetailsJson().contains("boom"),
                "the stored row keeps them, so switching the option back on shows them again");
    }

    @Test
    void aLogProblemIsAuditedWithoutChannelAttributesAndAChannelProblemWithThem() {
        AlertEvent log = auditedEvent(null);
        SentinelAuditLog.problemAcknowledged(42, log, "looking");
        SentinelAuditLog.problemResolved(42, log, null);
        assertEquals(2, audited.size());
        for (ServerEvent event : audited) {
            Map<String, String> attributes = event.getAttributes();
            assertEquals("7", attributes.get("Problem ID"));
            assertFalse(attributes.containsKey("Channel ID"), attributes.toString());
            assertFalse(attributes.containsKey("Channel Name"));
            assertFalse(attributes.containsKey("channel"));
        }
        verify(channelController, never()).getChannelById(any());

        SentinelAuditLog.problemAcknowledged(42, auditedEvent(CHANNEL_A), null);
        Map<String, String> attributes = audited.get(2).getAttributes();
        assertEquals(CHANNEL_A, attributes.get("Channel ID"));
        assertEquals("ADT In", attributes.get("Channel Name"));
        assertTrue(attributes.containsKey("channel"));
    }

    // ========== Dashboard and metrics ==========

    @Test
    void anUnrestrictedDashboardCountsTheLogProblemButNotItsNotificationsAndGivesItNoChannelBadge() {
        seedReadSide();
        try (MockedStatic<ControllerFactory> engine = deployed(); MockedStatic<ScopeResolver> scope = startedChannels();
                MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class, CALLS_REAL_METHODS)) {
            DashboardSummary summary = DashboardService.build(null);
            assertEquals(2, summary.getOpenTotal());
            assertEquals(1, severity(summary, Severity.HIGH));
            assertEquals(1, severity(summary, Severity.WARNING));
            assertEquals(2, summary.getUnacknowledged());
            assertEquals(List.of(CHANNEL_A), summary.getTopChannels().stream().map(TopChannel::getChannelId).toList());
            assertEquals(2, summary.getRecentProblems().size(), "the newer notification is not a recent problem");
            assertTrue(summary.getRecentProblems().stream().anyMatch(e -> e.getSubjectType() == SubjectType.SERVER));
            assertEquals(1, summary.getChannelsWatched(), "the log monitor's ALL scope watches no channel");
            assertEquals(2, summary.getMonitorsTotal());
            alerts.verify(() -> AlertEventRepository.listRecentAlertEvents(anyInt(), eq(AlertKind.PROBLEM)));
            alerts.verify(() -> AlertEventRepository.listAlertEvents(argThat(filter ->
                    filter.getStatus() == AlertStatus.PROBLEM && filter.getKind() == AlertKind.PROBLEM)));
        }
    }

    @Test
    void aRestrictedDashboardSeesNeitherTheLogProblemNorTheLogMonitor() {
        seedReadSide();
        try (MockedStatic<ControllerFactory> engine = deployed(); MockedStatic<ScopeResolver> scope = startedChannels()) {
            DashboardSummary summary = DashboardService.build(Set.of(CHANNEL_A, CHANNEL_B));
            assertEquals(1, summary.getOpenTotal());
            assertEquals(0, severity(summary, Severity.HIGH));
            assertEquals(1, severity(summary, Severity.WARNING));
            assertEquals(List.of(CHANNEL_A), summary.getRecentProblems().stream().map(AlertEvent::getChannelId).toList());
            assertEquals(1, summary.getMonitorsTotal(), "a log monitor is hidden from every restricted reader");
            assertEquals(1, summary.getChannelsWatched());
        }
    }

    @Test
    void coverageIgnoresTheLogMonitorsAllScope() {
        seedReadSide();
        try (MockedStatic<ControllerFactory> engine = deployed(); MockedStatic<ScopeResolver> scope = startedChannels()) {
            Map<String, Object> coverage = DashboardService.coverage(null);
            assertEquals(false, coverage.get("coversAll"));
            assertEquals(List.of(CHANNEL_A), coverage.get("channelIds"));
            assertEquals(Map.of("coversAll", false, "channelIds", List.of(CHANNEL_A)),
                    DashboardService.coverage(Set.of(CHANNEL_A, CHANNEL_B)));
        }
    }

    @Test
    void metricsCountTheLogProblemForAnUnrestrictedScrapeOnly() {
        seedReadSide();
        try (MockedStatic<ControllerFactory> engine = deployed(); MockedStatic<ScopeResolver> scope = startedChannels()) {
            String unrestricted = MetricsService.render(null);
            assertTrue(unrestricted.contains("sentinel_problems_open{severity=\"HIGH\"} 1\n"), unrestricted);
            assertTrue(unrestricted.contains("sentinel_problems_open{severity=\"WARNING\"} 1\n"), unrestricted);
            assertFalse(unrestricted.contains("channel_id=\"\""), "no channel series for the Log subject");

            String restricted = MetricsService.render(Set.of(CHANNEL_A, CHANNEL_B));
            assertTrue(restricted.contains("sentinel_problems_open{severity=\"HIGH\"} 0\n"), restricted);
            assertTrue(restricted.contains("sentinel_problems_open{severity=\"WARNING\"} 1\n"), restricted);
        }
    }

    // ========== helpers ==========

    /** A Log monitor with an open HIGH problem and a newer notification, and a channel monitor on A with an open WARNING. */
    private void seedReadSide() {
        Monitor log = database.logMonitor("Log errors", "{}");
        Monitor channel = channelMonitor("Inactivity on A", null);
        channel.setScopeType(ScopeType.CHANNEL);
        channel.setScopeId(CHANNEL_A);
        channel.setSeverity(Severity.WARNING);
        channel.setCreatedTime(Instant.parse("2026-11-21T00:00:00Z"));
        MonitorRepository.insertMonitor(channel);
        AlertEventRepository.insertAlertEvent(open(log.getId(), null, Severity.HIGH, 60));
        AlertEventRepository.insertAlertEvent(open(channel.getId(), CHANNEL_A, Severity.WARNING, 30));
        notification(log, Instant.now().minusSeconds(10));
    }

    /** The engine reports two deployed channels, so an ALL-scope Log monitor counted as a channel monitor would show. */
    private static MockedStatic<ControllerFactory> deployed() {
        EngineController engine = mock(EngineController.class);
        when(engine.getDeployedIds()).thenReturn(Set.of(CHANNEL_A, CHANNEL_B));
        ControllerFactory factory = mock(ControllerFactory.class);
        when(factory.createEngineController()).thenReturn(engine);
        MockedStatic<ControllerFactory> controllers = mockStatic(ControllerFactory.class);
        controllers.when(ControllerFactory::getFactory).thenReturn(factory);
        return controllers;
    }

    private static MockedStatic<ScopeResolver> startedChannels() {
        MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class, CALLS_REAL_METHODS);
        scope.when(() -> ScopeResolver.isChannelStarted(anyString())).thenReturn(true);
        scope.when(() -> ScopeResolver.channelName(anyString())).thenAnswer(call -> "name of " + call.getArgument(0));
        scope.when(ScopeResolver::listChannels).thenReturn(List.of());
        return scope;
    }

    private static AlertEvent open(int monitorId, String channelId, Severity severity, long openedSecondsAgo) {
        AlertEvent event = new AlertEvent();
        event.setMonitorId(monitorId);
        event.setSubjectType(channelId == null ? SubjectType.SERVER : SubjectType.CHANNEL);
        event.setChannelId(channelId);
        event.setSeverity(severity);
        event.setStatus(AlertStatus.PROBLEM);
        event.setMessage("open");
        event.setOpenedTime(Instant.now().minusSeconds(openedSecondsAgo));
        return event;
    }

    private static AlertEvent notification(Monitor monitor, Instant at) {
        return AlertEventRepository.insertAlertEvent(LogNotifications.notification(monitor, "matched", "{}", at));
    }

    private static AlertEvent auditedEvent(String channelId) {
        AlertEvent event = new AlertEvent();
        event.setId(7L);
        event.setMonitorId(5);
        event.setSubjectType(channelId == null ? SubjectType.SERVER : SubjectType.CHANNEL);
        event.setChannelId(channelId);
        event.setSeverity(Severity.HIGH);
        event.setMessage("3 matching log lines in the last 300s (at least 3)");
        return event;
    }

    private static int severity(DashboardSummary summary, Severity severity) {
        return summary.getOpenBySeverity().stream().filter(c -> c.getSeverity() == severity)
                .mapToInt(SeverityCount::getCount).findFirst().orElse(-1);
    }

    private static List<Long> ids(List<AlertEvent> events) {
        return events.stream().map(AlertEvent::getId).toList();
    }

    private static JsonNode readDetails(long problemId) throws Exception {
        return Json.mapper().readTree(ProblemService.getDetail(problemId).getEvent().getDetailsJson());
    }

    /** The Log problem's details as each read returns them, and the only notification's. */
    private static Map<String, JsonNode> readEveryWay(long problemId) throws Exception {
        Map<String, JsonNode> reads = new LinkedHashMap<>();
        reads.put("problem detail", readDetails(problemId));
        reads.put("notification list", readNotification());
        reads.put("problem list", Json.mapper().readTree(ProblemService.list(null, null, null, null, null, null, null,
                null, null, null, null, 0, 25).items().get(0).getDetailsJson()));
        try (MockedStatic<ControllerFactory> engine = deployed(); MockedStatic<ScopeResolver> scope = startedChannels()) {
            reads.put("dashboard recent problems",
                    Json.mapper().readTree(DashboardService.build(null).getRecentProblems().get(0).getDetailsJson()));
        }
        return reads;
    }

    /** The details of the only notification, as the Notifications list returns it. */
    private static JsonNode readNotification() throws Exception {
        List<AlertEvent> items = NotificationService.list(null, null, null, 0, 25, null).items();
        assertEquals(1, items.size());
        return Json.mapper().readTree(items.get(0).getDetailsJson());
    }

    private static Monitor logMonitor(String name, String config) {
        Monitor monitor = MonitorIdentityDatabaseTestSupport.monitor(name, config, null);
        monitor.setMonitorType(MonitorType.LOG_EVENTS);
        return monitor;
    }

    private static Monitor channelMonitor(String name, Integer parent) {
        return MonitorIdentityDatabaseTestSupport.monitor(name, "{}", parent);
    }

    private static ObjectNode logEntry(String name, String config) {
        ObjectNode node = MonitorIdentityDatabaseTestSupport.monitorEntry(name, config, null);
        node.put("monitorType", "LOG_EVENTS");
        return node;
    }

    private static JsonNode entry(ObjectNode exported, String name) {
        for (JsonNode node : exported.path("monitors")) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("No exported monitor named " + name);
    }

    private static String reason(ExportImportService.ImportResult result, String name) {
        return result.getEntries().stream().filter(e -> name.equals(e.getName())).findFirst().orElseThrow().getReason();
    }
}
