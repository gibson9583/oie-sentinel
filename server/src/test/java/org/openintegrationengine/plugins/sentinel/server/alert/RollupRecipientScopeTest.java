package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class RollupRecipientScopeTest {
    @Test
    void cachedStormNeverDisclosesIncidentsOutsideEachRecipientsCurrentPolicy() {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        AlertEvent tenantA = event(1, "tenant-a", now);
        AlertEvent tenantB = event(2, "tenant-b-sensitive", now);
        Monitor monitor = new Monitor(); monitor.setId(7); monitor.setEnabled(true);
        monitor.setMonitorType(MonitorType.INACTIVITY);
        try (var events = mockStatic(AlertEventRepository.class);
                var logs = mockStatic(ActionDispatchLogRepository.class);
                var scopes = mockStatic(ScopeResolver.class);
                var monitors = mockStatic(MonitorRepository.class);
                var policy = mockStatic(NotificationSuppression.class)) {
            events.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt()))
                    .thenReturn(List.of(tenantA, tenantB));
            events.when(() -> AlertEventRepository.getAlertEvent(1L)).thenReturn(tenantA);
            events.when(() -> AlertEventRepository.getAlertEvent(2L)).thenReturn(tenantB);
            monitors.when(() -> MonitorRepository.getMonitor(7)).thenReturn(monitor);
            scopes.when(() -> ScopeResolver.channelName(anyString())).thenAnswer(c -> c.getArgument(0));
            policy.when(() -> NotificationSuppression.decision(any(), any()))
                    .thenReturn(NotificationSuppression.Decision.ALLOW);
            AlertStormControl.reset();
            assertEquals(List.of("tenant-a"), AlertStormControl.ceilingVerdict(action(1, "tenant-a"), now).channels());
            // Same cached storm snapshot, different recipient.
            assertEquals(List.of("tenant-b-sensitive"), AlertStormControl.ceilingVerdict(action(2, "tenant-b-sensitive"), now).channels());
            policy.when(() -> NotificationSuppression.decision(tenantB, now))
                    .thenReturn(NotificationSuppression.Decision.SUPPRESS);
            assertTrue(AlertStormControl.ceilingVerdict(action(3, "tenant-b-sensitive"), now).channels().isEmpty());
            policy.when(() -> NotificationSuppression.decision(tenantA, now))
                    .thenReturn(NotificationSuppression.Decision.UNKNOWN);
            assertTrue(AlertStormControl.ceilingVerdict(action(4, "tenant-a"), now).channels().isEmpty());
            policy.when(() -> NotificationSuppression.decision(tenantA, now))
                    .thenReturn(NotificationSuppression.Decision.ALLOW);
            tenantA.setStatus(AlertStatus.RESOLVED);
            assertTrue(AlertStormControl.ceilingVerdict(action(5, "tenant-a"), now).channels().isEmpty(),
                    "ON_PROBLEM must not summarize an incident that resolved since the cached scan");
        } finally { AlertStormControl.reset(); }
    }

    private static Action action(int id, String channel) {
        Action action = new Action(); action.setId(id); action.setEnabled(true);
        action.setOperationMode(OperationMode.ON_PROBLEM);
        action.setMaxNotificationsPerWindow(0); action.setRollupWindowSeconds(600);
        action.setConditionJson("[{\"field\":\"CHANNEL\",\"operator\":\"=\",\"value\":\"" + channel + "\"}]");
        return action;
    }

    private static AlertEvent event(long id, String channel, Instant now) {
        AlertEvent event = new AlertEvent(); event.setId(id); event.setMonitorId(7);
        event.setChannelId(channel); event.setStatus(AlertStatus.PROBLEM);
        event.setOpenedTime(now.minusSeconds(5)); event.setSeverity(Severity.HIGH);
        return event;
    }
}
