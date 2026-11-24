/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.client.core.api.MirthApiException;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.service.ExportImportService;
import org.openintegrationengine.plugins.sentinel.server.service.MonitorService;
import org.openintegrationengine.plugins.sentinel.server.service.NotificationService;
import org.openintegrationengine.plugins.sentinel.server.service.SentinelAuditLog;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelInfo;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * Log problems and Log monitors through the servlet. A channel-restricted
 * caller, even one authorized for every channel, gets 404 for a Log problem's
 * reads and single mutations, has it dropped from bulk ones, and gets 403 for
 * any change to a Log monitor. The engine's redaction check is never handed
 * the Log subject's null channel id.
 */
class ServerProblemVisibilityTest {

    private static final String ALLOWED = "allowed-channel";
    private static final long SERVER_PROBLEM = 1L;
    private static final long CHANNEL_PROBLEM = 2L;
    private static final String LOG_BODY = "{\"name\":\"Log errors\",\"monitorType\":\"LOG_EVENTS\",\"scopeType\":\"ALL\"}";
    private static final String CHANNEL_BODY = "{\"name\":\"Inactivity\",\"monitorType\":\"INACTIVITY\","
            + "\"scopeType\":\"CHANNEL\",\"scopeId\":\"" + ALLOWED + "\"}";

    private MockedStatic<AlertEventRepository> alerts;
    private MockedStatic<MonitorRepository> monitors;
    private MockedStatic<TriggerStateRepository> states;
    private MockedStatic<ActionDispatchLogRepository> dispatches;
    private MockedStatic<ScopeResolver> scope;
    private MockedStatic<SentinelAuditLog> audit;

    @BeforeEach
    void setUp() {
        alerts = mockStatic(AlertEventRepository.class);
        monitors = mockStatic(MonitorRepository.class);
        states = mockStatic(TriggerStateRepository.class);
        dispatches = mockStatic(ActionDispatchLogRepository.class);
        scope = mockStatic(ScopeResolver.class);
        audit = mockStatic(SentinelAuditLog.class);

        alerts.when(() -> AlertEventRepository.getAlertEvent(SERVER_PROBLEM)).thenAnswer(call -> event(SERVER_PROBLEM, null));
        alerts.when(() -> AlertEventRepository.getAlertEvent(CHANNEL_PROBLEM)).thenAnswer(call -> event(CHANNEL_PROBLEM, ALLOWED));
        alerts.when(() -> AlertEventRepository.acknowledgeAlertEvent(any())).thenReturn(true);
        alerts.when(() -> AlertEventRepository.resolveAlertEventManually(any())).thenReturn(true);
        Monitor logMonitor = new Monitor();
        logMonitor.setId(5);
        logMonitor.setName("Log errors");
        logMonitor.setMonitorType(MonitorType.LOG_EVENTS);
        logMonitor.setScopeType(ScopeType.ALL);
        monitors.when(() -> MonitorRepository.getMonitor(5)).thenReturn(logMonitor);
        Monitor channelMonitor = new Monitor();
        channelMonitor.setId(6);
        channelMonitor.setName("Inactivity");
        channelMonitor.setMonitorType(MonitorType.INACTIVITY);
        channelMonitor.setScopeType(ScopeType.CHANNEL);
        channelMonitor.setScopeId(ALLOWED);
        monitors.when(() -> MonitorRepository.getMonitor(6)).thenReturn(channelMonitor);
        dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(anyLong())).thenReturn(List.of());
        ChannelInfo channel = new ChannelInfo();
        channel.setChannelId(ALLOWED);
        scope.when(ScopeResolver::listChannels).thenReturn(List.of(channel));
        scope.when(() -> ScopeResolver.channelName(anyString())).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        audit.close();
        scope.close();
        dispatches.close();
        states.close();
        monitors.close();
        alerts.close();
    }

    @Test
    void aRestrictedCallerCannotReadOrMutateAServerProblemEvenWithEveryChannel() {
        RestrictedServlet servlet = servlet(true);
        assertStatus(404, () -> servlet.getProblem(SERVER_PROBLEM));
        assertStatus(404, () -> servlet.acknowledgeProblem(SERVER_PROBLEM, null));
        assertStatus(404, () -> servlet.resolveProblem(SERVER_PROBLEM, null));
        alerts.verify(() -> AlertEventRepository.acknowledgeAlertEvent(any()), never());
        alerts.verify(() -> AlertEventRepository.resolveAlertEventManually(any()), never());

        // Control: the same caller acts on its channel's problem.
        assertEquals(ALLOWED, tree(servlet.getProblem(CHANNEL_PROBLEM)).path("event").path("channelId").asText());
        servlet.acknowledgeProblem(CHANNEL_PROBLEM, null);
        alerts.verify(() -> AlertEventRepository.acknowledgeAlertEvent(any()));
        assertFalse(servlet.redactionAskedAboutNull, "the server's null channel id reached the engine check");
    }

    @Test
    void bulkMutationsDropServerProblemsForARestrictedCaller() {
        RestrictedServlet servlet = servlet(true);
        String body = "{\"ids\":[" + SERVER_PROBLEM + "," + CHANNEL_PROBLEM + "]}";
        assertEquals(1, tree(servlet.bulkAcknowledgeProblems(body)).path("acknowledged").asInt());
        assertEquals(1, tree(servlet.bulkResolveProblems(body)).path("resolved").asInt());
        alerts.verify(() -> AlertEventRepository.getAlertEvent(SERVER_PROBLEM), org.mockito.Mockito.times(2));
        alerts.verify(() -> AlertEventRepository.acknowledgeAlertEvent(
                org.mockito.ArgumentMatchers.argThat(e -> e.getId() == SERVER_PROBLEM)), never());
        alerts.verify(() -> AlertEventRepository.resolveAlertEventManually(
                org.mockito.ArgumentMatchers.argThat(e -> e.getId() == SERVER_PROBLEM)), never());
        assertFalse(servlet.redactionAskedAboutNull);
    }

    @Test
    void anUnrestrictedCallerReadsAndActsOnAServerProblem() {
        RestrictedServlet servlet = servlet(false);
        JsonNode detail = tree(servlet.getProblem(SERVER_PROBLEM));
        assertEquals("Log", detail.path("channelName").asText());
        assertTrue(detail.path("connectorName").isNull());
        assertEquals("LOG_EVENTS", detail.path("monitorType").asText());
        assertEquals("SERVER", detail.path("event").path("subjectType").asText());
        assertTrue(detail.path("event").path("channelId").isNull());
        assertEquals(2, tree(detail.path("event").path("detailsJson").asText()).path("perNode").size(),
                "the per-node counts reach the problem detail in the event's details");

        String body = "{\"ids\":[" + SERVER_PROBLEM + "," + CHANNEL_PROBLEM + "]}";
        assertEquals(2, tree(servlet.bulkAcknowledgeProblems(body)).path("acknowledged").asInt());
        servlet.resolveProblem(SERVER_PROBLEM, null);
        alerts.verify(() -> AlertEventRepository.resolveAlertEventManually(any()));
        assertFalse(servlet.redactionAskedAboutNull);
    }

    @Test
    void aRestrictedCallerCannotCreateChangeEnableDeleteOrImportALogMonitor() {
        RestrictedServlet servlet = servlet(true);
        try (MockedStatic<MonitorService> service = monitorService();
                MockedStatic<ExportImportService> imports = imports()) {
            assertForbidden(() -> servlet.createMonitor(LOG_BODY));
            assertForbidden(() -> servlet.updateMonitor(5, CHANNEL_BODY));
            assertForbidden(() -> servlet.updateMonitor(6, LOG_BODY));
            assertForbidden(() -> servlet.setMonitorEnabled(5, false));
            assertForbidden(() -> servlet.removeMonitor(5));
            assertForbidden(() -> servlet.importConfiguration("{\"schemaVersion\":2,\"monitors\":["
                    + CHANNEL_BODY + "," + LOG_BODY + "]}"));
            service.verify(() -> MonitorService.create(any(), anyInt()), never());
            service.verify(() -> MonitorService.update(anyInt(), any(), anyInt()), never());
            service.verify(() -> MonitorService.setEnabled(anyInt(), anyBoolean(), anyInt()), never());
            service.verify(() -> MonitorService.delete(anyInt(), anyInt()), never());
            imports.verify(() -> ExportImportService.importDocument(any(), anyInt()), never());
        }
    }

    @Test
    void aRestrictedCallerStillManagesChannelMonitorsAndAnUnrestrictedOneManagesLogMonitors() {
        RestrictedServlet restricted = servlet(true);
        RestrictedServlet unrestricted = servlet(false);
        try (MockedStatic<MonitorService> service = monitorService();
                MockedStatic<ExportImportService> imports = imports()) {
            restricted.createMonitor(CHANNEL_BODY);
            restricted.updateMonitor(6, CHANNEL_BODY);
            restricted.importConfiguration("{\"schemaVersion\":2,\"monitors\":[" + CHANNEL_BODY + "]}");
            unrestricted.createMonitor(LOG_BODY);
            unrestricted.updateMonitor(5, LOG_BODY);
            unrestricted.updateMonitor(6, LOG_BODY);
            unrestricted.setMonitorEnabled(5, false);
            unrestricted.removeMonitor(5);
            unrestricted.importConfiguration("{\"schemaVersion\":2,\"monitors\":[" + LOG_BODY + "]}");
            service.verify(() -> MonitorService.create(any(), eq(42)), org.mockito.Mockito.times(2));
            service.verify(() -> MonitorService.update(anyInt(), any(), eq(42)), org.mockito.Mockito.times(3));
            service.verify(() -> MonitorService.setEnabled(5, false, 42));
            service.verify(() -> MonitorService.delete(5, 42));
            imports.verify(() -> ExportImportService.importDocument(any(), eq(42)), org.mockito.Mockito.times(2));
        }
    }

    /** Spellings Jackson reads as LOG_EVENTS: the ordinal, the ordinal as text, the name with a space, the name. */
    @ParameterizedTest
    @ValueSource(strings = {"7", "\"7\"", "\" LOG_EVENTS\"", "\"LOG_EVENTS\""})
    void aRestrictedCallerCannotImportALogMonitorUnderAnySpellingTheImportReads(String monitorType) throws Exception {
        String entry = "{\"name\":\"Log errors\",\"monitorType\":" + monitorType + ",\"scopeType\":\"ALL\"}";
        assertEquals(MonitorType.LOG_EVENTS, Json.mapper().treeToValue(tree(entry), Monitor.class).getMonitorType(),
                "premise: the import's parser reads this spelling as a Log monitor");
        RestrictedServlet restricted = servlet(true);
        try (MockedStatic<MonitorService> service = monitorService();
                MockedStatic<ExportImportService> imports = imports()) {
            assertForbidden(() -> restricted.importConfiguration(document(CHANNEL_BODY, entry)));
            imports.verify(() -> ExportImportService.importDocument(any(), anyInt()), never());
            service.verify(() -> MonitorService.create(any(), anyInt()), never());
            service.verify(() -> MonitorService.update(anyInt(), any(), anyInt()), never());

            // Controls: the same caller imports the channel monitor alone; an unrestricted caller imports both.
            restricted.importConfiguration(document(CHANNEL_BODY));
            servlet(false).importConfiguration(document(CHANNEL_BODY, entry));
            imports.verify(() -> ExportImportService.importDocument(any(), eq(42)), times(2));
        }
    }

    /** Entries whose type the import's parser cannot read: unknown, missing, not text, or not a monitor at all. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"x\",\"monitorType\":\"NOPE\"}", "{\"name\":\"x\"}",
            "{\"name\":\"x\",\"monitorType\":{}}", "{\"name\":\"x\",\"monitorType\":null}", "7", "null"})
    void aRestrictedCallersImportIsRefusedWhenAnEntrysTypeCannotBeRead(String entry) {
        try (MockedStatic<MonitorService> service = monitorService();
                MockedStatic<ExportImportService> imports = imports()) {
            assertForbidden(() -> servlet(true).importConfiguration(document(CHANNEL_BODY, entry)));
            imports.verify(() -> ExportImportService.importDocument(any(), anyInt()), never());

            // Control: an unrestricted caller's import goes ahead and the import reports the entry itself.
            servlet(false).importConfiguration(document(CHANNEL_BODY, entry));
            imports.verify(() -> ExportImportService.importDocument(any(), eq(42)));
        }
    }

    @Test
    void notificationsAreReadWithTheCallersChannelSet() {
        try (MockedStatic<NotificationService> notifications = mockStatic(NotificationService.class)) {
            servlet(true).getNotifications(5, 1L, 2L, 0, 25);
            servlet(false).getNotifications(null, null, null, 1, 10);
            notifications.verify(() -> NotificationService.list(5, 1L, 2L, 0, 25, Set.of(ALLOWED)));
            notifications.verify(() -> NotificationService.list(isNull(), isNull(), isNull(), eq(1), eq(10), isNull()));
        }
    }

    public static class RestrictedServlet extends SentinelServlet {
        boolean restricted;
        boolean redactionAskedAboutNull;
        public RestrictedServlet() { super(null, null); }
        @Override protected boolean doesUserHaveChannelRestrictions() { return restricted; }
        @Override protected boolean isChannelRedacted(String id) {
            if (id == null) redactionAskedAboutNull = true;
            return restricted && !ALLOWED.equals(id);
        }
        @Override protected int getCurrentUserId() { return 42; }
        @Override protected Set<String> redactChannelIds(Set<String> ids) {
            Set<String> result = new HashSet<>(ids);
            result.retainAll(Set.of(ALLOWED));
            return result;
        }
    }

    private static RestrictedServlet servlet(boolean restricted) {
        RestrictedServlet servlet = mock(RestrictedServlet.class, CALLS_REAL_METHODS);
        servlet.restricted = restricted;
        return servlet;
    }

    /** MonitorService with a stored Log monitor 5 and channel monitor 6; saves echo their input. */
    private static MockedStatic<MonitorService> monitorService() {
        Monitor log = MonitorRepository.getMonitor(5);
        Monitor channel = MonitorRepository.getMonitor(6);
        MockedStatic<MonitorService> service = mockStatic(MonitorService.class);
        service.when(() -> MonitorService.get(5)).thenReturn(log);
        service.when(() -> MonitorService.get(6)).thenReturn(channel);
        service.when(() -> MonitorService.create(any(), anyInt())).thenAnswer(call -> call.getArgument(0));
        service.when(() -> MonitorService.update(anyInt(), any(), anyInt())).thenAnswer(call -> call.getArgument(1));
        return service;
    }

    /** ExportImportService with only importDocument, the writer, replaced; its document checks and parsing are real. */
    private static MockedStatic<ExportImportService> imports() {
        return mockStatic(ExportImportService.class,
                call -> "importDocument".equals(call.getMethod().getName()) ? null : call.callRealMethod());
    }

    private static String document(String... monitors) {
        return "{\"schemaVersion\":2,\"monitors\":[" + String.join(",", monitors) + "]}";
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable request) {
        MirthApiException failure = assertThrows(MirthApiException.class, request);
        assertEquals(403, failure.getResponse().getStatus());
        assertEquals("Only users without channel restrictions can create or change Log monitors",
                failure.getResponse().getEntity());
    }

    private static AlertEvent event(long id, String channelId) {
        AlertEvent event = new AlertEvent();
        event.setId(id);
        event.setMonitorId(channelId == null ? 5 : 6);
        event.setSubjectType(channelId == null ? SubjectType.SERVER : SubjectType.CHANNEL);
        event.setChannelId(channelId);
        event.setSeverity(Severity.HIGH);
        event.setStatus(AlertStatus.PROBLEM);
        event.setOpenedTime(Instant.parse("2026-11-21T12:00:00Z"));
        event.setMessage("3 matching log lines in the last 300s (threshold 3)");
        event.setDetailsJson("{\"count\":3,\"threshold\":3,\"windowSeconds\":300,"
                + "\"perNode\":{\"node-a\":2,\"node-b\":1}}");
        return event;
    }

    private static JsonNode tree(String json) {
        try {
            return Json.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertStatus(int status, org.junit.jupiter.api.function.Executable request) {
        MirthApiException failure = assertThrows(MirthApiException.class, request);
        assertEquals(status, failure.getResponse().getStatus());
    }
}
