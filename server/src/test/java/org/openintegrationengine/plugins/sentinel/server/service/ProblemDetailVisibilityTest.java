package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ProblemDetailVisibilityTest {
    @Test
    void retargetedMonitorCannotRevealCurrentDefinitionThroughHistoricalAllowedEvent() {
        try (Fixture f = new Fixture()) {
            assertEquals("Visible monitor", ProblemService.getDetail(1, Set.of("allowed")).getMonitorName());
            f.monitor.setScopeId("forbidden");
            f.monitor.setName("Secret renamed monitor");
            var detail = ProblemService.getDetail(1, Set.of("allowed"));
            assertEquals("(unavailable monitor)", detail.getMonitorName());
            assertNull(detail.getMonitorType());
            assertSame(f.event, detail.getEvent());
            assertEquals("Secret renamed monitor", ProblemService.getDetail(1).getMonitorName());
        }
    }

    @Test
    void hiddenSuppressionParentUsesSameVisibilityAsMonitorReads() {
        try (Fixture f = new Fixture()) {
            Monitor parent = monitor(2, "forbidden");
            f.monitor.setSuppressedByMonitorId(2);
            f.monitors.when(() -> MonitorRepository.getMonitor(2)).thenReturn(parent);
            assertEquals("(unavailable monitor)", ProblemService.getDetail(1, Set.of("allowed")).getMonitorName());
        }
    }

    @Test
    void eventAuthorizationIsCheckedBeforeAnyEnrichmentOnEveryRead() {
        try (Fixture f = new Fixture()) {
            for (Set<String> allowed : List.of(Set.<String>of(), Set.of("unrelated"))) {
                assertThrows(NoSuchElementException.class, () -> ProblemService.getDetail(1, allowed));
            }
            f.monitors.verifyNoInteractions(); f.scopes.verifyNoInteractions(); f.dispatches.verifyNoInteractions();
        }
    }

    static Monitor monitor(int id, String channel) {
        Monitor m = new Monitor(); m.setId(id); m.setScopeType(ScopeType.CHANNEL); m.setScopeId(channel);
        m.setMonitorType(MonitorType.QUEUE_DEPTH); m.setName("Visible monitor"); return m;
    }
    static class Fixture implements AutoCloseable {
        final MockedStatic<AlertEventRepository> events = mockStatic(AlertEventRepository.class);
        final MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class);
        final MockedStatic<ActionDispatchLogRepository> dispatches = mockStatic(ActionDispatchLogRepository.class);
        final MockedStatic<ScopeResolver> scopes = mockStatic(ScopeResolver.class);
        final AlertEvent event = new AlertEvent();
        final Monitor monitor = monitor(1, "allowed");
        Fixture() {
            event.setId(1L); event.setMonitorId(1); event.setChannelId("allowed");
            events.when(() -> AlertEventRepository.getAlertEvent(1L)).thenReturn(event);
            monitors.when(() -> MonitorRepository.getMonitor(1)).thenReturn(monitor);
            dispatches.when(() -> ActionDispatchLogRepository.listActionDispatchLogsForEvent(1L)).thenReturn(List.of());
        }
        @Override public void close() { scopes.close(); dispatches.close(); monitors.close(); events.close(); }
    }
}
