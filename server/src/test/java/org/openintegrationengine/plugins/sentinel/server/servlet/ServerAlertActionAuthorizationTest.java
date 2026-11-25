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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.client.core.api.MirthApiException;

import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.service.ActionService;
import org.openintegrationengine.plugins.sentinel.server.service.ExportImportService;
import org.openintegrationengine.plugins.sentinel.server.service.MaintenanceWindowService;
import org.openintegrationengine.plugins.sentinel.server.service.MonitorService;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;

/**
 * The Log access rule applied to actions and export. A channel-restricted
 * manager can't create, change, delete or import an action that can receive
 * Log alerts, and doesn't get Log monitors in an export. Channel-scoped
 * actions stay open to that manager, and an unrestricted caller is never
 * refused.
 */
class ServerAlertActionAuthorizationTest {

    private static final String FORBIDDEN =
            "Only users without channel restrictions can create, change or delete an action that can receive Log alerts";
    private static final int LOG_MONITOR = 5;
    private static final int CHANNEL_MONITOR = 6;
    /** Stored: 1 has no conditions, 2 and 3 are channel-scoped, and 1 escalates to 3. */
    private static final int UNFILTERED = 1;
    private static final int CHANNEL_SCOPED = 2;
    private static final int ESCALATION_TARGET = 3;
    private static final String CHANNEL = "[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"c-1\"}]";

    private final List<Action> stored = new ArrayList<>();
    private final List<Monitor> monitors = new ArrayList<>();
    private MockedStatic<ActionRepository> actionRows;
    private MockedStatic<MonitorRepository> monitorRows;
    private MockedStatic<ActionService> actions;
    private MockedStatic<MonitorService> monitorService;
    private MockedStatic<MaintenanceWindowService> windows;
    private MockedStatic<ExportImportService> imports;

    @BeforeEach
    void setUp() {
        stored.add(action(UNFILTERED, "Page everyone", null, ESCALATION_TARGET));
        stored.add(action(CHANNEL_SCOPED, "Channel team", CHANNEL, null));
        stored.add(action(ESCALATION_TARGET, "Escalation desk", CHANNEL, null));
        monitors.add(monitor(LOG_MONITOR, "Log errors", MonitorType.LOG_EVENTS));
        monitors.add(monitor(CHANNEL_MONITOR, "Inactivity", MonitorType.INACTIVITY));

        actionRows = mockStatic(ActionRepository.class);
        actionRows.when(() -> ActionRepository.listActions(null)).thenReturn(stored);
        monitorRows = mockStatic(MonitorRepository.class);
        monitorRows.when(() -> MonitorRepository.listMonitors(null, null, null, null)).thenReturn(monitors);
        actions = mockStatic(ActionService.class);
        actions.when(ActionService::list).thenReturn(stored);
        actions.when(() -> ActionService.get(anyInt())).thenAnswer(call -> stored.stream()
                .filter(action -> action.getId().equals(call.getArgument(0))).findFirst().orElseThrow());
        actions.when(() -> ActionService.create(any(), anyInt())).thenAnswer(call -> call.getArgument(0));
        actions.when(() -> ActionService.update(anyInt(), any(), anyInt())).thenAnswer(call -> call.getArgument(1));
        monitorService = mockStatic(MonitorService.class);
        monitorService.when(MonitorService::list).thenReturn(monitors);
        windows = mockStatic(MaintenanceWindowService.class);
        windows.when(MaintenanceWindowService::list).thenReturn(List.of());
        // Only the writer is replaced; reading entries and building the export are real.
        imports = mockStatic(ExportImportService.class,
                call -> "importDocument".equals(call.getMethod().getName()) ? null : call.callRealMethod());
    }

    @AfterEach
    void tearDown() {
        imports.close();
        windows.close();
        monitorService.close();
        actions.close();
        monitorRows.close();
        actionRows.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            ",\"conditionJson\":\"[{\\\"field\\\":\\\"MONITOR_TYPE\\\",\\\"operator\\\":\\\"=\\\",\\\"value\\\":\\\"LOG_EVENTS\\\"}]\"",
            ",\"conditionJson\":\"[{\\\"field\\\":\\\"MONITOR\\\",\\\"operator\\\":\\\"IN\\\",\\\"value\\\":[\\\"5\\\"]}]\"",
            ",\"conditionJson\":\"[{\\\"field\\\":\\\"SEVERITY\\\",\\\"operator\\\":\\\">=\\\",\\\"value\\\":\\\"HIGH\\\"}]\""})
    void aRestrictedManagerCannotCreateAnActionThatCanReceiveLogAlerts(String condition) {
        assertForbidden(() -> servlet(true).createAction(body("New page", condition)));
        actions.verify(() -> ActionService.create(any(), anyInt()), never());

        servlet(false).createAction(body("New page", condition));
        actions.verify(() -> ActionService.create(any(), eq(42)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ",\"conditionJson\":\"[{\\\"field\\\":\\\"CHANNEL\\\",\\\"operator\\\":\\\"=\\\",\\\"value\\\":\\\"c-1\\\"}]\"",
            ",\"conditionJson\":\"[{\\\"field\\\":\\\"MONITOR\\\",\\\"operator\\\":\\\"=\\\",\\\"value\\\":\\\"6\\\"}]\""})
    void aRestrictedManagerCanStillCreateAChannelScopedAction(String condition) {
        servlet(true).createAction(body("Channel page", condition));
        actions.verify(() -> ActionService.create(any(), eq(42)));
    }

    @Test
    void aRestrictedManagerCannotChangeAnActionThatCanReceiveLogAlertsFromEitherSide() {
        assertForbidden(() -> servlet(true).updateAction(UNFILTERED, body("Page everyone", channelCondition())));
        assertForbidden(() -> servlet(true).updateAction(CHANNEL_SCOPED, body("Channel team", "")));
        assertForbidden(() -> servlet(true).updateAction(ESCALATION_TARGET, body("Escalation desk", channelCondition())));
        actions.verify(() -> ActionService.update(anyInt(), any(), anyInt()), never());

        servlet(true).updateAction(CHANNEL_SCOPED, body("Channel team", channelCondition()));
        servlet(false).updateAction(UNFILTERED, body("Page everyone", ""));
        actions.verify(() -> ActionService.update(anyInt(), any(), eq(42)), times(2));
    }

    @Test
    void aRestrictedManagerCannotDeleteAnActionThatCanReceiveLogAlerts() {
        assertForbidden(() -> servlet(true).removeAction(UNFILTERED));
        assertForbidden(() -> servlet(true).removeAction(ESCALATION_TARGET));
        actions.verify(() -> ActionService.delete(anyInt(), anyInt()), never());

        servlet(true).removeAction(CHANNEL_SCOPED);
        servlet(false).removeAction(UNFILTERED);
        actions.verify(() -> ActionService.delete(CHANNEL_SCOPED, 42));
        actions.verify(() -> ActionService.delete(UNFILTERED, 42));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"Imported page\"}",
            "{\"name\":\"Imported page\",\"conditionJson\":\"[{\\\"field\\\":\\\"MONITOR\\\",\\\"operator\\\":\\\"IN\\\","
                    + "\\\"value\\\":[\\\"Log errors\\\"]}]\"}",
            "{\"name\":\" page EVERYONE \",\"conditionJson\":\"" + "[{\\\"field\\\":\\\"CHANNEL\\\",\\\"operator\\\":"
                    + "\\\"=\\\",\\\"value\\\":\\\"c-1\\\"}]\"}",
            "{\"name\":\"Unreadable\",\"operationMode\":\"SOMETIMES\"}"})
    void aRestrictedManagerCannotImportAnActionThatCanReceiveOrReplacesOneThatCan(String entry) {
        String document = "{\"schemaVersion\":3,\"actions\":[" + entry + "]}";
        assertForbidden(() -> servlet(true).importConfiguration(document));
        imports.verify(() -> ExportImportService.importDocument(any(), anyInt()), never());

        servlet(false).importConfiguration(document);
        imports.verify(() -> ExportImportService.importDocument(any(), eq(42)));
    }

    @Test
    void aRestrictedManagerCanImportChannelScopedActions() {
        String channelEntry = "{\"name\":\"Channel team\",\"conditionJson\":\"[{\\\"field\\\":\\\"MONITOR\\\","
                + "\\\"operator\\\":\\\"=\\\",\\\"value\\\":\\\"Inactivity\\\"}]\"}";
        servlet(true).importConfiguration("{\"schemaVersion\":3,\"actions\":[" + channelEntry + "]}");
        imports.verify(() -> ExportImportService.importDocument(any(), eq(42)));
    }

    @Test
    void anExportForARestrictedManagerLeavesLogMonitorsOut() throws Exception {
        stored.add(action(4, "Log page", "[{\"field\":\"MONITOR\",\"operator\":\"IN\",\"value\":[\"5\"]}]", null));

        JsonNode restricted = Json.mapper().readTree(servlet(true).exportConfiguration());
        assertEquals(List.of("Inactivity"), names(restricted.path("monitors")));
        assertFalse(restricted.toString().contains("Log errors"), "not even as an action's monitor name");
        assertEquals("5", named(restricted.path("actions"), "Log page").path("unresolvedMonitorIds").get(0).asText());

        JsonNode unrestricted = Json.mapper().readTree(servlet(false).exportConfiguration());
        assertEquals(List.of("Inactivity", "Log errors"), names(unrestricted.path("monitors")));
        assertTrue(named(unrestricted.path("actions"), "Log page").path("conditionJson").asText().contains("Log errors"));
    }

    /** Entry names, sorted. */
    private static List<String> names(JsonNode entries) {
        List<String> names = new ArrayList<>();
        entries.forEach(entry -> names.add(entry.path("name").asText()));
        names.sort(null);
        return names;
    }

    private static JsonNode named(JsonNode entries, String name) {
        for (JsonNode entry : entries) {
            if (name.equals(entry.path("name").asText())) {
                return entry;
            }
        }
        throw new AssertionError("no entry named " + name + " in " + entries);
    }

    private static String channelCondition() {
        return ",\"conditionJson\":" + Json.write(CHANNEL);
    }

    private static String body(String name, String condition) {
        return "{\"name\":\"" + name + "\",\"actionType\":\"EMAIL\",\"operationMode\":\"ON_PROBLEM\",\"enabled\":true"
                + condition + "}";
    }

    private static void assertForbidden(Executable request) {
        MirthApiException failure = assertThrows(MirthApiException.class, request);
        assertEquals(403, failure.getResponse().getStatus());
        assertEquals(FORBIDDEN, failure.getResponse().getEntity());
    }

    private static Action action(int id, String name, String condition, Integer escalateTo) {
        Action action = new Action();
        action.setId(id);
        action.setName(name);
        action.setEnabled(true);
        action.setActionType(ActionType.EMAIL);
        action.setOperationMode(OperationMode.ON_PROBLEM);
        action.setConditionJson(condition);
        action.setEscalateToActionId(escalateTo);
        if (escalateTo != null) {
            action.setEscalateAfterSeconds(300);
        }
        return action;
    }

    private static Monitor monitor(int id, String name, MonitorType type) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setName(name);
        monitor.setMonitorType(type);
        monitor.setScopeType(type == MonitorType.LOG_EVENTS ? ScopeType.ALL : ScopeType.CHANNEL);
        monitor.setScopeId(type == MonitorType.LOG_EVENTS ? null : "c-1");
        monitor.setConfigJson("{}");
        return monitor;
    }

    private static ScopedServlet servlet(boolean restricted) {
        ScopedServlet servlet = mock(ScopedServlet.class, CALLS_REAL_METHODS);
        servlet.restricted = restricted;
        return servlet;
    }

    public static class ScopedServlet extends SentinelServlet {
        boolean restricted;
        public ScopedServlet() { super(null, null); }
        @Override protected boolean doesUserHaveChannelRestrictions() { return restricted; }
        @Override protected int getCurrentUserId() { return 42; }
    }
}
