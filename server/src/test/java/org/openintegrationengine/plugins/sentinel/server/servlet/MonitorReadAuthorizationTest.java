package org.openintegrationengine.plugins.sentinel.server.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.model.Channel;
import com.mirth.connect.server.controllers.ChannelController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.service.ActivityQueryService;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Real servlet/service/serialization paths, with only repository and engine inventory boundaries mocked. */
class MonitorReadAuthorizationTest {
    private static final String ALLOWED = "allowed";
    private static final String RESTRICTED = "restricted";
    private static final long FROM = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
    private static final long TO = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli();

    @Test
    void listContainsOnlyWhollyAuthorizedMonitorDefinitions() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonNode result = tree(f.servlet.getMonitors());
            assertEquals(1, result.size());
            assertEquals(1, result.get(0).path("id").asInt());
            assertEquals(ALLOWED, result.get(0).path("scopeId").asText());
            assertFalse(result.toString().contains(RESTRICTED));
        }
    }

    @Test
    void detailReturnsAuthorizedConfigurationAndHidesRestrictedExistence() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(ALLOWED, tree(f.servlet.getMonitor(1)).path("scopeId").asText());
            MirthApiException forbidden = assertStatus(404, () -> f.servlet.getMonitor(2));
            f.rows.remove(f.restrictedMonitor);
            MirthApiException missing = assertStatus(404, () -> f.servlet.getMonitor(2));
            assertEquals(missing.getResponse().getEntity(), forbidden.getResponse().getEntity());
        }
    }

    @Test
    void dashboardConfigurationCountsAndAlertDataUseTheSameAuthorizedChannels() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonNode result = tree(f.servlet.getDashboardSummary());
            assertEquals(1, result.path("monitorsTotal").asInt());
            assertEquals(1, result.path("monitorsEnabled").asInt());
            assertEquals(1, result.path("channelsWatched").asInt());
            assertEquals(1, result.path("openTotal").asInt());
            assertEquals(1, result.path("recentProblems").size());
            assertEquals(ALLOWED, result.path("recentProblems").get(0).path("channelId").asText());
            assertFalse(result.toString().contains(RESTRICTED));
        }
    }

    @Test
    void disabledAuthorizedMonitorsCountOnlyTowardTotal() throws Exception {
        try (Fixture f = new Fixture()) {
            f.allowedMonitor.setEnabled(false);
            JsonNode result = tree(f.servlet.getDashboardSummary());
            assertEquals(1, result.path("monitorsTotal").asInt());
            assertEquals(0, result.path("monitorsEnabled").asInt());
            assertEquals(0, result.path("channelsWatched").asInt());
        }
    }

    @Test
    void watchedChannelCountReappliesRestrictionsIfScopeResolutionChanges() throws Exception {
        try (Fixture f = new Fixture()) {
            f.scopes.when(() -> ScopeResolver.resolveStartedChannels(f.allowedMonitor)).thenReturn(List.of(
                    new ScopeResolver.ChannelTarget(ALLOWED, "Allowed"),
                    new ScopeResolver.ChannelTarget(RESTRICTED, "Restricted")));
            assertEquals(1, tree(f.servlet.getDashboardSummary()).path("channelsWatched").asInt());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"GROUP", "TAG", "ALL"})
    void mixedScopesAreHiddenEvenWhenRestrictedMembersAreStopped(ScopeType scope) throws Exception {
        try (Fixture f = new Fixture()) {
            f.rows.clear();
            Monitor mixed = monitor(3, scope, scope == ScopeType.ALL ? null : "mixed", "Mixed monitor");
            f.rows.add(mixed);
            f.scopes.when(() -> ScopeResolver.strictGroupChannelIds("mixed")).thenReturn(Set.of(ALLOWED, RESTRICTED));
            f.scopes.when(() -> ScopeResolver.strictTagChannelIds("mixed")).thenReturn(Set.of(ALLOWED, RESTRICTED));
            // Runtime resolution deliberately omits the stopped restricted member.
            f.scopes.when(() -> ScopeResolver.resolveStartedChannels(mixed))
                    .thenReturn(List.of(new ScopeResolver.ChannelTarget(ALLOWED, "Allowed")));
            assertTrue(tree(f.servlet.getMonitors()).isEmpty());
            assertStatus(404, () -> f.servlet.getMonitor(3));
            assertStatus(404, () -> f.servlet.getMonitorHistory(3, FROM, TO));
            assertEquals(0, tree(f.servlet.getDashboardSummary()).path("monitorsTotal").asInt());
        }
    }

    @Test
    void zeroAuthorizedChannelsNeverBecomesUnrestrictedAcrossReads() throws Exception {
        try (Fixture f = new Fixture()) {
            f.servlet.allowed = Set.of();
            assertTrue(tree(f.servlet.getMonitors()).isEmpty());
            assertStatus(404, () -> f.servlet.getMonitor(1));
            assertStatus(404, () -> f.servlet.getMonitorHistory(1, FROM, TO));
            JsonNode dashboard = tree(f.servlet.getDashboardSummary());
            assertEquals(0, dashboard.path("monitorsTotal").asInt());
            assertEquals(0, dashboard.path("monitorsEnabled").asInt());
            assertEquals(0, dashboard.path("channelsWatched").asInt());
            assertEquals(0, dashboard.path("openTotal").asInt());
            assertTrue(dashboard.path("recentProblems").isEmpty());
            f.alerts.verify(() -> AlertEventRepository.listAlertEvents(any()), never());
        }
    }

    @Test
    void permissionRevocationAndRetriesRecheckEveryRead() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(1, tree(f.servlet.getMonitors()).size());
            assertEquals(1, tree(f.servlet.getMonitor(1)).path("id").asInt());
            f.servlet.allowed = Set.of();
            for (int attempt = 0; attempt < 2; attempt++) {
                assertTrue(tree(f.servlet.getMonitors()).isEmpty());
                assertStatus(404, () -> f.servlet.getMonitor(1));
                assertStatus(404, () -> f.servlet.getMonitorHistory(1, FROM, TO));
                assertEquals(0, tree(f.servlet.getDashboardSummary()).path("monitorsTotal").asInt());
            }
        }
    }

    @Test
    void hiddenMonitorHistoryIsDeniedBeforeReadingAnyAlertData() {
        try (Fixture f = new Fixture()) {
            assertStatus(404, () -> f.servlet.getMonitorHistory(2, FROM, TO));
            f.alerts.verifyNoInteractions();
        }
    }

    @Test
    void authorizedMonitorHistoryStillUsesChannelFilteredEvents() throws Exception {
        try (Fixture f = new Fixture()) {
            JsonNode result = tree(f.servlet.getMonitorHistory(1, FROM, TO));
            assertEquals(1, result.path("monitorId").asInt());
            assertFalse(result.path("points").isEmpty());
            f.alerts.verify(() -> AlertEventRepository.listAlertEvents(argThat(filter ->
                    Set.copyOf(filter.getChannelIdIn()).equals(Set.of(ALLOWED)))));
            f.alerts.verify(() -> AlertEventRepository.listMonitorHistory(anyInt(), any(), any()), never());
        }
    }

    @Test
    void hiddenSuppressionParentCannotBeEnumeratedThroughAnAllowedChild() throws Exception {
        try (Fixture f = new Fixture()) {
            f.allowedMonitor.setSuppressedByMonitorId(2);
            assertTrue(tree(f.servlet.getMonitors()).isEmpty());
            assertStatus(404, () -> f.servlet.getMonitor(1));
            assertStatus(404, () -> f.servlet.getMonitorHistory(1, FROM, TO));
            assertEquals(0, tree(f.servlet.getDashboardSummary()).path("monitorsTotal").asInt());
        }
    }

    @Test
    void scopeResolutionFailureDoesNotExposePartialMonitorInventory() throws Exception {
        try (Fixture f = new Fixture()) {
            Monitor uncertain = monitor(3, ScopeType.GROUP, "unavailable", "synthetic-secret monitor");
            f.rows.add(uncertain);
            f.scopes.when(() -> ScopeResolver.strictGroupChannelIds("unavailable"))
                    .thenThrow(new IllegalStateException("synthetic-secret membership failure"));
            JsonNode result = tree(f.servlet.getMonitors());
            assertEquals(1, result.size());
            assertFalse(result.toString().contains("synthetic-secret"));
            assertStatus(404, () -> f.servlet.getMonitor(3));
            assertEquals(1, tree(f.servlet.getDashboardSummary()).path("monitorsTotal").asInt());
        }
    }

    @Test
    void authorizationInventoryFailureStopsBeforeRepositoryReads() {
        try (Fixture f = new Fixture()) {
            f.scopes.when(ScopeResolver::listChannels).thenThrow(new IllegalStateException("synthetic-secret inventory failure"));
            assertOpaque500(f.servlet::getMonitors);
            assertOpaque500(() -> f.servlet.getMonitor(1));
            assertOpaque500(f.servlet::getDashboardSummary);
            assertOpaque500(() -> f.servlet.getMonitorHistory(1, FROM, TO));
            f.monitors.verifyNoInteractions();
            f.alerts.verifyNoInteractions();
        }
    }

    @Test
    void repositoryFailureReturnsOpaqueErrorWithoutPartialMonitorData() {
        try (Fixture f = new Fixture()) {
            f.monitors.when(() -> MonitorRepository.listMonitors(null, null, null, null))
                    .thenThrow(new IllegalStateException("synthetic-secret repository failure"));
            assertOpaque500(f.servlet::getMonitors);
        }
    }

    @Test
    void unrestrictedCallerStillReadsAllDefinitionsAndCounts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.servlet.restricted = false;
            assertEquals(2, tree(f.servlet.getMonitors()).size());
            assertEquals(RESTRICTED, tree(f.servlet.getMonitor(2)).path("scopeId").asText());
            JsonNode result = tree(f.servlet.getDashboardSummary());
            assertEquals(2, result.path("monitorsTotal").asInt());
            assertEquals(2, result.path("monitorsEnabled").asInt());
            assertEquals(2, result.path("channelsWatched").asInt());
        }
    }

    @Test
    void activityDenialStillPrecedesQueryAndFollowsCurrentPermissions() throws Exception {
        try (Fixture f = new Fixture(); MockedStatic<ActivityQueryService> activity = mockStatic(ActivityQueryService.class)) {
            assertStatus(403, () -> f.servlet.getChannelActivity(RESTRICTED, null, null, "AUTO"));
            activity.verifyNoInteractions();
            ChannelActivity value = new ChannelActivity(); value.setChannelId(ALLOWED); value.setPoints(List.of());
            activity.when(() -> ActivityQueryService.getChannelActivity(ALLOWED, null, null, "AUTO")).thenReturn(value);
            assertEquals(ALLOWED, tree(f.servlet.getChannelActivity(ALLOWED, null, null, "AUTO")).path("channelId").asText());
            f.servlet.allowed = Set.of();
            assertStatus(403, () -> f.servlet.getChannelActivity(ALLOWED, null, null, "AUTO"));
            activity.verify(() -> ActivityQueryService.getChannelActivity(ALLOWED, null, null, "AUTO"), times(1));
        }
    }

    @Test
    void independentRequestsDoNotShareVisibilityState() throws Exception {
        try (Fixture f = new Fixture()) {
            RestrictedServlet other = servlet(); other.allowed = Set.of(RESTRICTED);
            assertEquals(ALLOWED, tree(f.servlet.getMonitors()).get(0).path("scopeId").asText());
            assertEquals(RESTRICTED, tree(other.getMonitors()).get(0).path("scopeId").asText());
            assertStatus(404, () -> other.getMonitor(1));
            assertEquals(ALLOWED, tree(f.servlet.getMonitors()).get(0).path("scopeId").asText());
        }
    }

    public static class RestrictedServlet extends SentinelServlet {
        Set<String> allowed;
        boolean restricted;
        public RestrictedServlet() { super(null, null); }
        @Override protected boolean doesUserHaveChannelRestrictions() { return restricted; }
        @Override protected boolean isChannelRedacted(String id) { return restricted && !allowed.contains(id); }
        @Override protected int getCurrentUserId() { return 42; }
        @Override protected Set<String> redactChannelIds(Set<String> ids) {
            Set<String> result = new HashSet<>(ids); result.retainAll(allowed); return result;
        }
    }

    private static RestrictedServlet servlet() {
        RestrictedServlet servlet = mock(RestrictedServlet.class, CALLS_REAL_METHODS);
        servlet.allowed = Set.of(ALLOWED); servlet.restricted = true; return servlet;
    }

    private static class Fixture implements AutoCloseable {
        final RestrictedServlet servlet = servlet();
        final MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class);
        final MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class);
        final MockedStatic<ScopeResolver> scopes = mockStatic(ScopeResolver.class);
        final MockedStatic<ChannelController> engine = mockStatic(ChannelController.class);
        final Monitor allowedMonitor = monitor(1, ScopeType.CHANNEL, ALLOWED, "Allowed monitor");
        final Monitor restrictedMonitor = monitor(2, ScopeType.CHANNEL, RESTRICTED, "Restricted monitor");
        final List<Monitor> rows = new ArrayList<>(List.of(allowedMonitor, restrictedMonitor));
        Fixture() {
            monitors.when(() -> MonitorRepository.listMonitors(null, null, null, null)).thenAnswer(call -> new ArrayList<>(rows));
            monitors.when(() -> MonitorRepository.getMonitor(anyInt())).thenAnswer(call ->
                    rows.stream().filter(m -> m.getId().equals(call.getArgument(0))).findFirst().orElse(null));
            ChannelController controller = mock(ChannelController.class);
            engine.when(ChannelController::getInstance).thenReturn(controller);
            Channel allowed = new Channel(); allowed.setId(ALLOWED);
            Channel restricted = new Channel(); restricted.setId(RESTRICTED);
            when(controller.getChannels(null)).thenReturn(List.of(allowed, restricted));
            scopes.when(ScopeResolver::listChannels).thenReturn(List.of(channel(ALLOWED), channel(RESTRICTED)));
            scopes.when(() -> ScopeResolver.resolveStartedChannels(any(Monitor.class))).thenAnswer(call -> {
                Monitor monitor = call.getArgument(0);
                return List.of(new ScopeResolver.ChannelTarget(monitor.getScopeId(), monitor.getName()));
            });
            scopes.when(() -> ScopeResolver.channelName(anyString())).thenAnswer(call -> call.getArgument(0));
            AlertEvent allowedAlert = event(ALLOWED), restrictedAlert = event(RESTRICTED);
            alerts.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt())).thenReturn(List.of(allowedAlert, restrictedAlert));
            alerts.when(() -> AlertEventRepository.listAlertEvents(any())).thenAnswer(call -> {
                AlertEventFilter filter = call.getArgument(0);
                List<AlertEvent> found = List.of(allowedAlert, restrictedAlert).stream()
                        .filter(e -> filter.getChannelIdIn() == null || filter.getChannelIdIn().contains(e.getChannelId())).toList();
                return new PagedResult<>(found, found.size(), 0, 1000);
            });
        }
        @Override public void close() { engine.close(); scopes.close(); alerts.close(); monitors.close(); }
        static ChannelInfo channel(String id) { ChannelInfo value = new ChannelInfo(); value.setChannelId(id); return value; }
        static AlertEvent event(String channel) {
            AlertEvent value = new AlertEvent(); value.setChannelId(channel); value.setSeverity(Severity.WARNING);
            value.setOpenedTime(Instant.parse("2026-10-02T12:00:00Z"));
            value.setStatus(AlertStatus.PROBLEM); value.setMessage("Synthetic " + channel); return value;
        }
    }

    private static Monitor monitor(int id, ScopeType scope, String scopeId, String name) {
        Monitor monitor = new Monitor(); monitor.setId(id); monitor.setName(name);
        monitor.setEnabled(true); monitor.setScopeType(scope); monitor.setScopeId(scopeId);
        monitor.setMonitorType(MonitorType.QUEUE_DEPTH); monitor.setConfigJson("{\"threshold\":7}"); return monitor;
    }
    private static JsonNode tree(String value) throws Exception { return Json.mapper().readTree(value); }
    private static MirthApiException assertStatus(int status, org.junit.jupiter.api.function.Executable request) {
        MirthApiException failure = assertThrows(MirthApiException.class, request);
        assertEquals(status, failure.getResponse().getStatus()); return failure;
    }
    private static void assertOpaque500(org.junit.jupiter.api.function.Executable request) {
        MirthApiException failure = assertStatus(500, request);
        assertFalse(String.valueOf(failure.getResponse().getEntity()).contains("synthetic-secret"));
    }
}
