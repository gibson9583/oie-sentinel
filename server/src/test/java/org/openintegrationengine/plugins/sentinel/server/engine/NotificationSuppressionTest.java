/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MaintenanceWindowRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowMode;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowRepeat;

class NotificationSuppressionTest {

    private static final String CHANNEL_ID = "channel-a";
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    private MockedStatic<MaintenanceWindowRepository> windows;
    private MockedStatic<MonitorRepository> monitors;
    private MockedStatic<TriggerStateRepository> states;
    private MockedStatic<AlertEventRepository> alerts;

    @BeforeEach
    void setUp() {
        windows = Mockito.mockStatic(MaintenanceWindowRepository.class);
        windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows)
                .thenReturn(List.of());
        monitors = Mockito.mockStatic(MonitorRepository.class);
        states = Mockito.mockStatic(TriggerStateRepository.class);
        alerts = Mockito.mockStatic(AlertEventRepository.class);
    }

    @AfterEach
    void tearDown() {
        alerts.close();
        states.close();
        monitors.close();
        windows.close();
    }

    @Test
    void explanationNamesCurrentGateAndDoesNotInventHistory() {
        Monitor monitor = monitor(1);
        monitors.when(() -> MonitorRepository.getMonitor(1)).thenReturn(monitor);
        AlertEvent event = new AlertEvent();
        event.setId(99L); event.setMonitorId(1); event.setChannelId(CHANNEL_ID);
        event.setSuppressed(true); // historical latch must not force today's reason
        assertEquals(NotificationSuppression.Decision.ALLOW, NotificationSuppression.explain(event, NOW).decision());
        monitor.setEnabled(false);
        assertEquals("MONITOR_DISABLED", NotificationSuppression.explain(event, NOW).reasons().get(0).code());
        monitor.setEnabled(true);
        MaintenanceWindow window = oneTime(WindowMode.SUPPRESS, NOW.minusSeconds(10), NOW.plusSeconds(10));
        window.setId(42); window.setName("Change window"); window.setTimezone("America/New_York");
        windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows).thenReturn(List.of(window));
        NotificationSuppression.Reason reason = NotificationSuppression.explain(event, NOW).reasons().get(0);
        assertEquals("SUPPRESS_SCHEDULE", reason.code());
        assertEquals(42, reason.scheduleId()); assertEquals("Change window", reason.name());
        assertEquals("America/New_York", reason.timezone()); assertEquals(NOW.plusSeconds(10), reason.nextBoundary());
        assertEquals(NotificationSuppression.Decision.ALLOW, NotificationSuppression.explain(event, NOW.plusSeconds(11)).decision());
        windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows).thenThrow(new RuntimeException("secret SQL"));
        var unknown = NotificationSuppression.explain(event, NOW);
        assertEquals(NotificationSuppression.Decision.UNKNOWN, unknown.decision());
        assertTrue(unknown.reasons().isEmpty()); assertFalse(unknown.lookupError().contains("secret"));
    }

    @Test
    void explanationLinksOpenDependencyAndRefreshesAfterResolution() {
        Monitor child = monitor(1); child.setSuppressedByMonitorId(2);
        monitors.when(() -> MonitorRepository.getMonitor(1)).thenReturn(child);
        monitors.when(() -> MonitorRepository.getMonitor(2)).thenReturn(monitor(2));
        TriggerState state = new TriggerState(); state.setChannelId(CHANNEL_ID); state.setOpenAlertEventId(88L);
        states.when(() -> TriggerStateRepository.listTriggerStatesByMonitor(2)).thenReturn(List.of(state));
        AlertEvent parent = new AlertEvent(); parent.setId(88L); parent.setStatus(AlertStatus.PROBLEM);
        alerts.when(() -> AlertEventRepository.getAlertEvent(88L)).thenReturn(parent);
        AlertEvent event = new AlertEvent(); event.setMonitorId(1); event.setChannelId(CHANNEL_ID);
        assertEquals(88L, NotificationSuppression.explain(event, NOW).reasons().get(0).parentEventId());
        parent.setStatus(AlertStatus.RESOLVED);
        assertEquals(NotificationSuppression.Decision.ALLOW, NotificationSuppression.explain(event, NOW).decision());
    }

    @Test
    void suppressWindowBlocksOnlyWhileItIsActive() {
        MaintenanceWindow window = oneTime(WindowMode.SUPPRESS,
                NOW.minusSeconds(60), NOW.plusSeconds(60));
        windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows)
                .thenReturn(List.of(window));

        assertTrue(NotificationSuppression.isSuppressed(monitor(1), CHANNEL_ID, NOW));
        assertFalse(NotificationSuppression.isSuppressed(
                monitor(1), CHANNEL_ID, NOW.plusSeconds(120)));
    }

    @Test
    void alertingScheduleAllowsOnlyItsActiveTime() {
        MaintenanceWindow window = oneTime(WindowMode.ACTIVE,
                NOW.minusSeconds(60), NOW.plusSeconds(60));
        windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows)
                .thenReturn(List.of(window));

        assertFalse(NotificationSuppression.isSuppressed(monitor(1), CHANNEL_ID, NOW));
        assertTrue(NotificationSuppression.isSuppressed(
                monitor(1), CHANNEL_ID, NOW.plusSeconds(120)));
    }

    @Test
    void anyOpenParentProblemSuppressesAcrossTransientStateAndOldStoredBit() {
        Monitor parent = monitor(2);
        Monitor child = monitor(3);
        child.setSuppressedByMonitorId(2);
        monitors.when(() -> MonitorRepository.getMonitor(2)).thenReturn(parent);

        TriggerState parentState = new TriggerState();
        parentState.setMonitorId(2);
        parentState.setChannelId(CHANNEL_ID);
        parentState.setState(TriggerStatus.INSUFFICIENT_DATA);
        parentState.setOpenAlertEventId(88L);
        states.when(() -> TriggerStateRepository.listTriggerStatesByMonitor(2))
                .thenReturn(List.of(parentState));
        AlertEvent open = new AlertEvent();
        open.setId(88L);
        open.setStatus(AlertStatus.PROBLEM);
        open.setSuppressed(true);
        alerts.when(() -> AlertEventRepository.getAlertEvent(88L)).thenReturn(open);

        assertTrue(NotificationSuppression.isSuppressed(child, CHANNEL_ID, NOW));

        parent.setEnabled(false);
        assertFalse(NotificationSuppression.isSuppressed(child, CHANNEL_ID, NOW),
                "a disabled parent must not leave dependents permanently silent");
    }

    @Test
    void policyLookupFailureFailsThisAttemptClosed() {
        windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows)
                .thenThrow(new RuntimeException("window database unavailable"));

        assertTrue(NotificationSuppression.isSuppressed(monitor(1), CHANNEL_ID, NOW));
    }

    @Test
    void persistedDecisionPreservesLookupUncertainty() {
        windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows)
                .thenThrow(new RuntimeException("window database unavailable"));
        monitors.when(() -> MonitorRepository.getMonitor(1)).thenReturn(monitor(1));
        AlertEvent event = new AlertEvent();
        event.setId(99L);
        event.setMonitorId(1);
        event.setChannelId(CHANNEL_ID);

        assertEquals(NotificationSuppression.Decision.UNKNOWN,
                NotificationSuppression.decision(event, NOW));
    }

    @Test
    void partialGroupResolutionFailsThePolicyDecisionClosed() {
        MaintenanceWindow window = oneTime(WindowMode.SUPPRESS,
                NOW.minusSeconds(60), NOW.plusSeconds(60));
        window.setScopeType(ScopeType.GROUP);
        window.setScopeId("critical-channels");
        windows.when(MaintenanceWindowRepository::listEnabledMaintenanceWindows)
                .thenReturn(List.of(window));

        try (MockedStatic<ScopeResolver> scope = Mockito.mockStatic(ScopeResolver.class)) {
            scope.when(() -> ScopeResolver.strictGroupChannelIds("critical-channels"))
                    .thenThrow(new RuntimeException("one group member unreadable"));

            assertTrue(NotificationSuppression.isSuppressed(monitor(1), CHANNEL_ID, NOW));
        }
    }

    @Test
    void persistedEventUsesTheCurrentEnabledMonitorDefinition() {
        Monitor monitor = monitor(7);
        monitors.when(() -> MonitorRepository.getMonitor(7)).thenReturn(monitor);
        AlertEvent event = new AlertEvent();
        event.setId(99L);
        event.setMonitorId(7);
        event.setChannelId(CHANNEL_ID);

        assertFalse(NotificationSuppression.isSuppressed(event, NOW));

        monitor.setEnabled(false);
        assertTrue(NotificationSuppression.isSuppressed(event, NOW));
    }

    private static Monitor monitor(int id) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setEnabled(true);
        return monitor;
    }

    private static MaintenanceWindow oneTime(WindowMode mode, Instant from, Instant until) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(1);
        window.setName("schedule");
        window.setEnabled(true);
        window.setMode(mode);
        window.setScopeType(ScopeType.ALL);
        window.setRepeatType(WindowRepeat.NONE);
        window.setActiveFrom(from);
        window.setActiveUntil(until);
        return window;
    }
}
