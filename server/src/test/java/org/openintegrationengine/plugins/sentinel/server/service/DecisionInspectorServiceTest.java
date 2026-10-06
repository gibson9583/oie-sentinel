package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class DecisionInspectorServiceTest {
    @Test
    void readsCurrentRoutingWithoutCredentialsOrWritesAndKeepsPendingDistinct() throws Exception {
        try (var events = mockStatic(AlertEventRepository.class);
             var monitors = mockStatic(MonitorRepository.class);
             var windows = mockStatic(MaintenanceWindowRepository.class);
             var states = mockStatic(TriggerStateRepository.class);
             var actions = mockStatic(ActionRepository.class);
             var scopes = mockStatic(ScopeResolver.class)) {
            AlertEvent event = new AlertEvent();
            event.setId(7L); event.setMonitorId(1); event.setChannelId("visible");
            event.setStatus(AlertStatus.PROBLEM); event.setSeverity(Severity.HIGH);
            event.setProblemPending(true); event.setDetailsJson("{\"threshold\":5,\"observed\":10}");
            events.when(() -> AlertEventRepository.getAlertEvent(7)).thenReturn(event);
            Monitor monitor = new Monitor(); monitor.setEnabled(true); monitor.setName("Rule");
            monitor.setMinConsecutiveBreaches(3);
            monitors.when(() -> MonitorRepository.getMonitor(1)).thenReturn(monitor);
            windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows).thenReturn(List.of());
            Action action = new Action(); action.setId(3); action.setName("Pager");
            action.setActionType(ActionType.EMAIL); action.setOperationMode(OperationMode.ON_PROBLEM);
            action.setConfigJson("{\"password\":\"never-emit-secret\"}");
            Action recovery = new Action(); recovery.setId(4); recovery.setOperationMode(OperationMode.ON_RESOLVE);
            actions.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenReturn(List.of(action, recovery));
            Map<String, Object> result = DecisionInspectorService.inspect(7);
            String wire = Json.write(result);
            assertFalse(wire.contains("never-emit-secret"));
            assertTrue(wire.contains("\"decision\":\"ALLOW\""));
            assertTrue(wire.contains("\"problemPending\":true"));
            assertEquals(1, ((List<?>) result.get("matchingActions")).size());
            assertSame(event, result.get("event"));
            events.verify(() -> AlertEventRepository.getAlertEvent(7)); events.verifyNoMoreInteractions();
            windows.verify(MaintenanceWindowRepository::listEnabledMaintenanceWindows); windows.verifyNoMoreInteractions();
            states.verify(() -> TriggerStateRepository.getTriggerState(1, "visible", null)); states.verifyNoMoreInteractions();
            actions.verify(() -> ActionRepository.listActions(Boolean.TRUE)); actions.verifyNoMoreInteractions();
            actions.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenThrow(new RuntimeException("SQL secret"));
            result = DecisionInspectorService.inspect(7);
            assertSame(event, result.get("event"));
            assertTrue(result.containsKey("routingError")); assertFalse(Json.write(result).contains("SQL secret"));
            event.setStatus(AlertStatus.RESOLVED);
            actions.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenReturn(List.of(action, recovery));
            assertEquals(4, ((DecisionInspectorService.ActionMatch) ((List<?>) DecisionInspectorService.inspect(7).get("matchingActions")).get(0)).id());
        }
    }
}
