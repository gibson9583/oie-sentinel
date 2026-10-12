package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.QueueDepthEvaluator;
import org.openintegrationengine.plugins.sentinel.server.servlet.SentinelServlet;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class RestrictedMonitorDryRunTest {

    @ParameterizedTest
    @EnumSource(ScopeType.class)
    void everyScopeFiltersBeforeStateEvaluation(ScopeType scope) {
        Monitor monitor = monitor(MonitorType.CHANNEL_STATE, scope);
        try (MockedStatic<ScopeResolver> resolver = mockStatic(ScopeResolver.class);
                MockedStatic<TriggerStateRepository> states = mockStatic(TriggerStateRepository.class)) {
            resolver.when(() -> ScopeResolver.resolveScopedChannels(monitor)).thenReturn(targets());
            resolver.when(() -> ScopeResolver.channelState("allowed")).thenReturn("STARTED");
            resolver.when(() -> ScopeResolver.channelState("forbidden"))
                    .thenThrow(new AssertionError("Unauthorized channel was evaluated"));
            MonitorTestResult result = MonitorService.test(monitor, Set.of("allowed"));
            assertTrue(result.isOk());
            assertEquals("1 channel(s) evaluated", result.getMessage());
            assertEquals(List.of("allowed"), result.getOutcomes().stream().map(ChannelTestOutcome::getChannelId).toList());
            resolver.verify(() -> ScopeResolver.channelState("forbidden"), never());
        }
    }

    @Test
    void activityEvaluationUsesTheSameRestrictionAndHidesForbiddenErrors() {
        Monitor monitor = monitor(MonitorType.QUEUE_DEPTH, ScopeType.ALL);
        try (MockedStatic<ScopeResolver> resolver = mockStatic(ScopeResolver.class);
                MockedStatic<QueueDepthEvaluator> evaluator = mockStatic(QueueDepthEvaluator.class)) {
            resolver.when(() -> ScopeResolver.resolveStartedChannels(monitor)).thenReturn(targets());
            evaluator.when(() -> QueueDepthEvaluator.evaluate(eq(monitor), eq("allowed"), any()))
                    .thenReturn(EvaluationOutcome.ok("{\"queue\":0}"));
            evaluator.when(() -> QueueDepthEvaluator.evaluate(eq(monitor), eq("forbidden"), any()))
                    .thenThrow(new IllegalStateException("secret database/queue detail"));
            MonitorTestResult result = MonitorService.test(monitor, Set.of("allowed"));
            assertTrue(result.isOk());
            assertEquals(1, result.getOutcomes().size());
            evaluator.verify(() -> QueueDepthEvaluator.evaluate(eq(monitor), eq("forbidden"), any()), never());
        }
    }

    @Test
    void emptyAndUnrelatedPermissionsNeverBecomeUnrestricted() {
        Monitor monitor = monitor(MonitorType.CHANNEL_STATE, ScopeType.CHANNEL);
        monitor.setScopeId("forbidden");
        try (MockedStatic<ScopeResolver> resolver = mockStatic(ScopeResolver.class)) {
            resolver.when(() -> ScopeResolver.resolveScopedChannels(monitor))
                    .thenReturn(List.of(new ScopeResolver.ChannelTarget("forbidden", "Secret feed")));
            for (Set<String> allowed : List.of(Set.<String>of(), Set.of("allowed"))) {
                MonitorTestResult result = MonitorService.test(monitor, allowed);
                assertTrue(result.isOk());
                assertTrue(result.getOutcomes().isEmpty());
                assertEquals("No channels in scope", result.getMessage());
            }
            resolver.verify(() -> ScopeResolver.channelState(anyString()), never());
        }
    }

    @Test
    void unrestrictedInternalCallStillEvaluatesAllTargets() {
        Monitor monitor = monitor(MonitorType.CHANNEL_STATE, ScopeType.ALL);
        try (MockedStatic<ScopeResolver> resolver = mockStatic(ScopeResolver.class);
                MockedStatic<TriggerStateRepository> states = mockStatic(TriggerStateRepository.class)) {
            resolver.when(() -> ScopeResolver.resolveScopedChannels(monitor)).thenReturn(targets());
            resolver.when(() -> ScopeResolver.channelState(anyString())).thenReturn("STARTED");
            assertEquals(2, MonitorService.test(monitor).getOutcomes().size());
        }
    }

    @Test
    void servletUsesCurrentRequestPermissionsRatherThanSubmittedScope() {
        RestrictedServlet servlet = mock(RestrictedServlet.class, CALLS_REAL_METHODS);
        try (MockedStatic<ScopeResolver> resolver = mockStatic(ScopeResolver.class);
                MockedStatic<TriggerStateRepository> states = mockStatic(TriggerStateRepository.class)) {
            ChannelInfo allowed = new ChannelInfo(); allowed.setChannelId("allowed");
            ChannelInfo forbidden = new ChannelInfo(); forbidden.setChannelId("forbidden");
            resolver.when(ScopeResolver::listChannels).thenReturn(List.of(allowed, forbidden));
            resolver.when(() -> ScopeResolver.resolveScopedChannels(any(Monitor.class))).thenReturn(targets());
            resolver.when(() -> ScopeResolver.channelState("allowed")).thenReturn("STARTED");
            String body = "{\"monitorType\":\"CHANNEL_STATE\",\"scopeType\":\"ALL\"}";
            String response = servlet.testMonitor(body);
            assertTrue(response.contains("allowed"));
            assertFalse(response.contains("forbidden"));
            assertFalse(response.contains("Secret feed"));

            // A second action from the same stale page can have less permission.
            servlet.allowed = Set.of();
            assertFalse(servlet.testMonitor(body).contains("allowed"));
        }
    }

    public static class RestrictedServlet extends SentinelServlet {
        Set<String> allowed;
        public RestrictedServlet() { super(null, null); }
        @Override protected boolean doesUserHaveChannelRestrictions() { return true; }
        @Override protected Set<String> redactChannelIds(Set<String> ids) {
            Set<String> result = new HashSet<>(ids);
            result.retainAll(allowed == null ? Set.of("allowed") : allowed);
            return result;
        }
    }

    private static Monitor monitor(MonitorType type, ScopeType scope) {
        Monitor monitor = new Monitor(); monitor.setMonitorType(type); monitor.setScopeType(scope);
        monitor.setScopeId("scope"); monitor.setConfigJson("{\"minDurationSeconds\":0}");
        return monitor;
    }

    private static List<ScopeResolver.ChannelTarget> targets() {
        return List.of(new ScopeResolver.ChannelTarget("allowed", "Allowed feed"),
                new ScopeResolver.ChannelTarget("forbidden", "Secret feed"));
    }
}
