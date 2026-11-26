/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.mirth.connect.server.controllers.ConfigurationController;

import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.LeaseFence;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.log.LogNotifications;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * The evaluator tick waits until the engine has finished starting. The
 * plugin's scheduler fires its first tick inside the plugin's start(), before
 * the engine deploys its channels; a Channel action sent then finds no channel
 * to route to, and that failed send completes the pending notification for
 * good. A skipped tick touches nothing, so the next one finds the
 * notification still pending.
 */
class TriggerEvaluatorJobStartupGateTest {

    private ConfigurationController configuration;
    private MockedStatic<ConfigurationController> configurations;
    private MockedStatic<SentinelLeadership> leadership;
    private MockedStatic<MonitorRepository> monitors;
    private MockedStatic<AlertEventRepository> alerts;
    private MockedStatic<ActionDispatcher> dispatcher;
    private AlertEvent pending;

    @BeforeEach
    void setUp() {
        configuration = mock(ConfigurationController.class);
        configurations = mockStatic(ConfigurationController.class);
        configurations.when(ConfigurationController::getInstance).thenReturn(configuration);
        leadership = mockStatic(SentinelLeadership.class);
        leadership.when(SentinelLeadership::captureFence).thenReturn(LeaseFence.unmanaged());
        leadership.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);
        Monitor monitor = new Monitor();
        monitor.setId(8);
        monitor.setName("log monitor");
        monitor.setMonitorType(MonitorType.LOG_EVENTS);
        monitor.setSeverity(Severity.HIGH);
        monitor.setEnabled(true);
        pending = LogNotifications.notification(monitor, "Matching ERROR log line from transformer",
                "{\"condition\":\"EACH\",\"count\":1}", Instant.parse("2026-11-23T12:00:00Z"));
        pending.setId(81L);
        monitors = mockStatic(MonitorRepository.class);
        monitors.when(() -> MonitorRepository.getMonitor(anyInt())).thenReturn(monitor);
        alerts = mockStatic(AlertEventRepository.class);
        alerts.when(AlertEventRepository::listPendingProblemAlertEvents).thenReturn(List.of(pending));
        dispatcher = mockStatic(ActionDispatcher.class);
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
        alerts.close();
        monitors.close();
        leadership.close();
        configurations.close();
    }

    private void engineStatus(int status) {
        when(configuration.getStatus(false)).thenReturn(status);
        when(configuration.getStatus(true)).thenReturn(status);
        when(configuration.getStatus()).thenReturn(status);
    }

    private void assertTickDidNothing() {
        monitors.verify(() -> MonitorRepository.listMonitors(any(), any(), any(), any()), never());
        alerts.verify(AlertEventRepository::listPendingProblemAlertEvents, never());
        alerts.verify(AlertEventRepository::listPendingResolvedAlertEvents, never());
        dispatcher.verify(() -> ActionDispatcher.onAlertOpened(any(), any()), never());
    }

    @ParameterizedTest(name = "engine status {0}")
    @ValueSource(ints = { ConfigurationController.STATUS_ENGINE_STARTING,
            ConfigurationController.STATUS_INITIAL_DEPLOY, ConfigurationController.STATUS_UNAVAILABLE })
    void aTickWhileTheEngineIsNotStartedEvaluatesNothingAndSendsNothing(int status) {
        engineStatus(status);

        new TriggerEvaluatorJob().execute(null);

        assertTickDidNothing();
    }

    @Test
    void aTickOnAStartedEngineEvaluatesAndSendsThePendingNotification() {
        engineStatus(ConfigurationController.STATUS_OK);

        new TriggerEvaluatorJob().execute(null);

        monitors.verify(() -> MonitorRepository.listMonitors(null, null, Boolean.TRUE, null));
        alerts.verify(AlertEventRepository::listPendingResolvedAlertEvents);
        dispatcher.verify(() -> ActionDispatcher.onAlertOpened(eq(pending), any()));
    }

    @Test
    void theTickAfterTheInitialDeploySendsWhatTheSkippedTickLeftPending() {
        engineStatus(ConfigurationController.STATUS_INITIAL_DEPLOY);
        new TriggerEvaluatorJob().execute(null);
        assertTickDidNothing();

        engineStatus(ConfigurationController.STATUS_OK);
        new TriggerEvaluatorJob().execute(null);
        dispatcher.verify(() -> ActionDispatcher.onAlertOpened(eq(pending), any()));
    }

    @Test
    void aStatusReadThatThrowsSkipsTheTickWithoutThrowing() {
        when(configuration.getStatus(false)).thenThrow(new IllegalStateException("no engine"));
        when(configuration.getStatus(true)).thenThrow(new IllegalStateException("no engine"));
        when(configuration.getStatus()).thenThrow(new IllegalStateException("no engine"));

        new TriggerEvaluatorJob().execute(null);

        assertTickDidNothing();
    }
}
