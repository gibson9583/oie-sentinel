/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import org.openintegrationengine.plugins.sentinel.server.service.DashboardService;
import org.openintegrationengine.plugins.sentinel.server.service.MetricsService;
import org.openintegrationengine.plugins.sentinel.shared.model.LogPipelineStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/** Drops on the plugin's own pipeline, as the dashboard and the metrics endpoint report them. */
class LogPipelineStatusTest {

    @AfterEach
    void cleanup() {
        LogPipeline.get().stop();
        LogPipeline.get().resize(new SentinelSettings().getLogQueueCapacity());
    }

    @Test
    void dropsShowInMetricsAndTheDashboardForUnrestrictedCallersOnly() {
        LogPipeline pipeline = LogPipeline.get();
        pipeline.resize(100);
        pipeline.refresh(List.of(LogMatchCounterTest.monitor(1, "{}")));
        for (int i = 0; i < 105; i++) {
            pipeline.queue().offer(LogQueueTest.event(Level.ERROR, "x", "line " + i));
        }
        try (MockedStatic<MonitorRepository> monitors = mockStatic(MonitorRepository.class);
                MockedStatic<ScopeResolver> scope = mockStatic(ScopeResolver.class);
                MockedStatic<AlertEventRepository> events = mockStatic(AlertEventRepository.class)) {
            events.when(() -> AlertEventRepository.listAlertEvents(any()))
                    .thenReturn(new PagedResult<>(List.of(), 0L, 0, 1));
            events.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt())).thenReturn(List.of());

            String node = "{node=\"" + SentinelLeadership.nodeId() + "\"} ";
            String metrics = MetricsService.render(null);
            assertTrue(metrics.contains("sentinel_log_lines_dropped_total" + node + "5\n"), metrics);
            assertTrue(metrics.contains("sentinel_log_queue_depth" + node + "100\n"), metrics);
            assertTrue(metrics.contains("sentinel_log_queue_capacity" + node + "100\n"), metrics);
            assertTrue(metrics.contains("sentinel_log_matcher_alive" + node + "0\n"), metrics);
            assertTrue(metrics.contains("sentinel_log_appender_attached" + node + "0\n"), metrics);
            assertFalse(MetricsService.render(Set.of("channel")).contains("sentinel_log_"), "server-wide");

            LogPipelineStatus status = DashboardService.build(null).getLogPipeline();
            assertEquals(SentinelLeadership.nodeId(), status.getNodeId());
            assertEquals(100, status.getQueueCapacity());
            assertEquals(100, status.getQueueDepth());
            assertEquals(5, status.getDroppedSinceStart());
            assertFalse(status.isMatcherAlive());
            assertFalse(status.isAppenderAttached());
            assertTrue(status.getNotCountingReason().contains("appender"), status.getNotCountingReason());
            assertNull(DashboardService.build(Set.of("channel")).getLogPipeline());
        }
    }
}
