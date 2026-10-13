package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.server.controllers.ChannelController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class RestrictedMonitorReadTest {
    private static final Set<String> ALLOWED = Set.of("allowed");

    @Test
    void channelDefinitionsDoNotDependOnDeploymentOrEvaluationState() {
        try (MockedStatic<ScopeResolver> resolver = mockStatic(ScopeResolver.class)) {
            Monitor monitor = monitor(1, ScopeType.CHANNEL, "allowed");
            monitor.setEnabled(false);
            assertTrue(MonitorVisibility.isVisible(monitor, ALLOWED));
            monitor.setScopeId("forbidden");
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            assertFalse(MonitorVisibility.isVisible(monitor, Set.of()));
            assertTrue(MonitorVisibility.isVisible(monitor, null));
            for (String malformed : Arrays.asList(null, "", " ")) {
                monitor.setScopeId(malformed);
                assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            }
            monitor.setScopeType(null);
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            resolver.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"GROUP", "TAG"})
    void groupAndTagRequireCompleteNonemptyMembershipEveryRequest(ScopeType type) {
        Monitor monitor = monitor(1, type, "scope");
        try (MockedStatic<ScopeResolver> resolver = mockStatic(ScopeResolver.class)) {
            org.mockito.stubbing.OngoingStubbing<Set<String>> membership = type == ScopeType.GROUP
                    ? resolver.when(() -> ScopeResolver.strictGroupChannelIds("scope"))
                    : resolver.when(() -> ScopeResolver.strictTagChannelIds("scope"));
            membership.thenReturn(Set.of("allowed"))
                    .thenReturn(Set.of("allowed", "dangling-forbidden"))
                    .thenReturn(Set.of())
                    .thenReturn(new HashSet<>(Arrays.asList("allowed", null)))
                    .thenReturn(Set.of("allowed", " "))
                    .thenThrow(new IllegalStateException("partial membership is unavailable"));
            assertTrue(MonitorVisibility.isVisible(monitor, ALLOWED));
            for (int attempt = 0; attempt < 5; attempt++) {
                assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            }
            resolver.verify(() -> ScopeResolver.resolveScopedChannels(any()), never());
            resolver.verify(() -> ScopeResolver.resolveStartedChannels(any()), never());
        }
    }

    @Test
    void groupReadNeverDropsAnUnreadableOrDanglingMember() {
        ChannelController controller = mock(ChannelController.class);
        ChannelGroup group = mock(ChannelGroup.class);
        Channel allowed = channel("allowed");
        Channel other = channel("deleted-forbidden");
        when(group.getId()).thenReturn("group");
        when(group.getChannels()).thenReturn(List.of(allowed, other));
        when(controller.getChannelGroups(null)).thenReturn(List.of(group));
        try (MockedStatic<ChannelController> channels = mockStatic(ChannelController.class)) {
            channels.when(ChannelController::getInstance).thenReturn(controller);
            Monitor monitor = monitor(1, ScopeType.GROUP, "group");
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            when(other.getId()).thenThrow(new IllegalStateException("member unreadable"));
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            verify(controller, never()).getChannelById(anyString());
        }
    }

    @Test
    void allRequiresACompleteNonemptyInventoryRegardlessOfChannelState() {
        ChannelController controller = mock(ChannelController.class);
        Channel allowed = channel("allowed");
        Channel other = channel("undeployed-forbidden");
        Monitor monitor = monitor(1, ScopeType.ALL, null);
        try (MockedStatic<ChannelController> channels = mockStatic(ChannelController.class)) {
            channels.when(ChannelController::getInstance).thenReturn(controller);
            when(controller.getChannels(null)).thenReturn(List.of(allowed));
            assertTrue(MonitorVisibility.isVisible(monitor, ALLOWED));
            when(controller.getChannels(null)).thenReturn(List.of(allowed, other));
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            when(other.getId()).thenThrow(new IllegalStateException("partial inventory"));
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            doReturn(null).when(other).getId();
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            doReturn(" ").when(other).getId();
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            when(controller.getChannels(null)).thenReturn(Arrays.asList(allowed, null));
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            when(controller.getChannels(null)).thenReturn(null);
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            when(controller.getChannels(null)).thenReturn(List.of());
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            when(controller.getChannels(null)).thenThrow(new IllegalStateException("inventory unavailable"));
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            assertTrue(MonitorVisibility.isVisible(monitor, null));
            monitor.setScopeId("malformed-all-scope");
            assertFalse(MonitorVisibility.isVisible(monitor, ALLOWED));
            verify(allowed, never()).getName();
        }
    }

    @Test
    void listAndDetailFilterBeforeReturningDefinitionsAndRecheckRevocation() {
        Monitor allowed = monitor(1, ScopeType.CHANNEL, "allowed");
        Monitor forbidden = monitor(2, ScopeType.CHANNEL, "forbidden");
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class)) {
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null))
                    .thenReturn(List.of(allowed, forbidden));
            rows.when(() -> MonitorRepository.getMonitor(1)).thenReturn(allowed);
            rows.when(() -> MonitorRepository.getMonitor(2)).thenReturn(forbidden);
            assertEquals(List.of(allowed), MonitorService.list(ALLOWED));
            assertSame(allowed, MonitorService.get(1, ALLOWED));
            assertEquals("No monitor with id 2", assertThrows(NoSuchElementException.class,
                    () -> MonitorService.get(2, ALLOWED)).getMessage());
            rows.when(() -> MonitorRepository.getMonitor(2)).thenReturn(null);
            assertEquals("No monitor with id 2", assertThrows(NoSuchElementException.class,
                    () -> MonitorService.get(2, ALLOWED)).getMessage());
            assertTrue(MonitorService.list(Set.of()).isEmpty());
            assertThrows(NoSuchElementException.class, () -> MonitorService.get(1, Set.of()));
            allowed.setScopeId("forbidden");
            assertTrue(MonitorService.list(ALLOWED).isEmpty());
            assertThrows(NoSuchElementException.class, () -> MonitorService.get(1, ALLOWED));
            assertEquals(List.of(allowed, forbidden), MonitorService.list());
            assertSame(allowed, MonitorService.get(1, null));
        }
    }

    @Test
    void hiddenMissingAndCyclicSuppressionChainsHideTheWholeDefinitionWithoutMutation() {
        Monitor child = monitor(1, ScopeType.CHANNEL, "allowed");
        Monitor parent = monitor(2, ScopeType.CHANNEL, "allowed");
        Monitor grandparent = monitor(3, ScopeType.CHANNEL, "forbidden");
        child.setSuppressedByMonitorId(2);
        parent.setSuppressedByMonitorId(3);
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class)) {
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null))
                    .thenReturn(List.of(child, parent, grandparent));
            rows.when(() -> MonitorRepository.getMonitor(1)).thenReturn(child);
            rows.when(() -> MonitorRepository.getMonitor(2)).thenReturn(parent);
            rows.when(() -> MonitorRepository.getMonitor(3)).thenReturn(grandparent);
            assertTrue(MonitorService.list(ALLOWED).isEmpty());
            assertThrows(NoSuchElementException.class, () -> MonitorService.get(1, ALLOWED));
            assertEquals(2, child.getSuppressedByMonitorId());
            assertEquals(3, parent.getSuppressedByMonitorId());
            grandparent.setScopeId("allowed");
            assertEquals(List.of(child, parent, grandparent), MonitorService.list(ALLOWED));
            assertSame(child, MonitorService.get(1, ALLOWED));
            grandparent.setSuppressedByMonitorId(1);
            assertTrue(MonitorService.list(ALLOWED).isEmpty());
            assertThrows(NoSuchElementException.class, () -> MonitorService.get(1, ALLOWED));
            grandparent.setSuppressedByMonitorId(99);
            assertTrue(MonitorService.list(ALLOWED).isEmpty());
            assertThrows(NoSuchElementException.class, () -> MonitorService.get(1, ALLOWED));
            assertSame(child, MonitorService.get(1));
            assertEquals(List.of(child, parent, grandparent), MonitorService.list(null));
        }
    }

    @Test
    void dashboardCountsOnlyReadableDefinitionsAndIntersectsChangedCoverage() {
        Monitor allowed = monitor(1, ScopeType.GROUP, "group");
        Monitor disabled = monitor(2, ScopeType.CHANNEL, "allowed");
        disabled.setEnabled(false);
        Monitor forbidden = monitor(3, ScopeType.CHANNEL, "forbidden");
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class);
                MockedStatic<ScopeResolver> resolver = mockStatic(ScopeResolver.class);
                MockedStatic<AlertEventRepository> events = mockStatic(AlertEventRepository.class)) {
            rows.when(() -> MonitorRepository.listMonitors(null, null, null, null))
                    .thenReturn(List.of(allowed, disabled, forbidden));
            resolver.when(() -> ScopeResolver.strictGroupChannelIds("group")).thenReturn(ALLOWED);
            // A newly added member after definition authorization must not enter coverage.
            resolver.when(() -> ScopeResolver.resolveStartedChannels(allowed)).thenReturn(List.of(
                    new ScopeResolver.ChannelTarget("allowed", "Allowed"),
                    new ScopeResolver.ChannelTarget("forbidden", "Forbidden")));
            events.when(() -> AlertEventRepository.listAlertEvents(any())).thenAnswer(invocation -> {
                AlertEventFilter filter = invocation.getArgument(0);
                assertEquals(ALLOWED, new HashSet<>(filter.getChannelIdIn()));
                return new PagedResult<>(List.of(), 0L, 0, 1000);
            });
            events.when(() -> AlertEventRepository.listRecentAlertEvents(10)).thenReturn(List.of());
            DashboardSummary dashboard = DashboardService.build(ALLOWED);
            assertEquals(2, dashboard.getMonitorsTotal());
            assertEquals(1, dashboard.getMonitorsEnabled());
            assertEquals(1, dashboard.getChannelsWatched());
            resolver.verify(() -> ScopeResolver.resolveStartedChannels(forbidden), never());
            resolver.verify(() -> ScopeResolver.resolveStartedChannels(disabled), never());
            DashboardSummary empty = DashboardService.build(Set.of());
            assertEquals(0, empty.getMonitorsTotal());
            assertEquals(0, empty.getMonitorsEnabled());
            assertEquals(0, empty.getChannelsWatched());
            assertEquals(0, empty.getOpenTotal());
        }
    }

    @Test
    void historyChecksDefinitionBeforeAnyEventQueryIncludingEmptyAndInvalidRequests() {
        Monitor monitor = monitor(1, ScopeType.CHANNEL, "forbidden");
        try (MockedStatic<MonitorRepository> rows = mockStatic(MonitorRepository.class);
                MockedStatic<AlertEventRepository> events = mockStatic(AlertEventRepository.class)) {
            rows.when(() -> MonitorRepository.getMonitor(1)).thenReturn(monitor);
            for (Set<String> authorized : List.of(ALLOWED, Set.<String>of())) {
                assertThrows(NoSuchElementException.class,
                        () -> MonitorHistoryService.build(1, 10L, 0L, authorized));
            }
            assertThrows(NoSuchElementException.class,
                    () -> MonitorHistoryService.build(99, null, null, ALLOWED));
            events.verifyNoInteractions();
            monitor.setScopeId("allowed");
            events.when(() -> AlertEventRepository.listAlertEvents(any())).thenAnswer(invocation -> {
                AlertEventFilter filter = invocation.getArgument(0);
                assertEquals(ALLOWED, new HashSet<>(filter.getChannelIdIn()));
                assertEquals(1, filter.getMonitorId());
                return new PagedResult<>(List.of(), 0L, 0, 5000);
            });
            long end = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli();
            assertEquals(1, MonitorHistoryService.build(1, end - 86_400_000L, end, ALLOWED).get("monitorId"));
            events.verify(() -> AlertEventRepository.listMonitorHistory(anyInt(), any(), any()), never());
        }
    }

    private static Monitor monitor(int id, ScopeType type, String scopeId) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setName("Monitor " + id);
        monitor.setScopeType(type);
        monitor.setScopeId(scopeId);
        monitor.setEnabled(true);
        monitor.setMonitorType(MonitorType.CHANNEL_STATE);
        monitor.setConfigJson("{\"states\":[\"STOPPED\"]}");
        return monitor;
    }

    private static Channel channel(String id) {
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(id);
        return channel;
    }
}
