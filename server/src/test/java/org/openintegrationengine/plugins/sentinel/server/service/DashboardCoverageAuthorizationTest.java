/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class DashboardCoverageAuthorizationTest {
    @Test
    void restrictedCoverageMustNotRevealAnAllScopedMonitorWhenItsOwnCoverageIsUnchanged() {
        Monitor own = monitor(1, ScopeType.CHANNEL, "allowed");
        Monitor hiddenGlobal = monitor(2, ScopeType.ALL, null);
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class)) {
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null)).thenReturn(List.of(own));
            Map<String,Object> before = DashboardService.coverage(Set.of("allowed"));
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null)).thenReturn(List.of(own, hiddenGlobal));
            Map<String,Object> after = DashboardService.coverage(Set.of("allowed"));
            assertEquals(before, after, "The caller's only authorized channel stays monitored, but coversAll reveals a hidden global definition");
        }
    }

    @Test
    void emptyAuthorizationNeverGetsGlobalCoverageOrForeignChannelIds() {
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class)) {
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null)).thenReturn(List.of(
                    monitor(1, ScopeType.ALL, null), monitor(2, ScopeType.CHANNEL, "restricted")));
            assertEquals(Map.of("coversAll", false, "channelIds", List.of()), DashboardService.coverage(Set.of()));
        }
    }

    @Test
    void unrestrictedCoveragePreservesGlobalAllSemantics() {
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class)) {
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null))
                    .thenReturn(List.of(monitor(1, ScopeType.ALL, null)));
            assertEquals(true, DashboardService.coverage(null).get("coversAll"));
        }
    }

    @Test
    void allScopeMaterializesOnlyCurrentAuthorizedIdsAndStopsContributingWhenDisabled() {
        Monitor global = monitor(1, ScopeType.ALL, null);
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class)) {
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null)).thenReturn(List.of(global));
            assertEquals(Map.of("coversAll", false, "channelIds", List.of("allowed", "alsoAllowed")),
                    DashboardService.coverage(Set.of("alsoAllowed", "allowed")));
            assertEquals(Map.of("coversAll", false, "channelIds", List.of("allowed")),
                    DashboardService.coverage(Set.of("allowed")));
            global.setEnabled(false);
            assertEquals(Map.of("coversAll", false, "channelIds", List.of()),
                    DashboardService.coverage(Set.of("allowed")));
        }
    }

    private static Monitor monitor(int id, ScopeType type, String scope) {
        Monitor monitor = new Monitor(); monitor.setId(id); monitor.setScopeType(type);
        monitor.setScopeId(scope); monitor.setEnabled(true); return monitor;
    }
}
