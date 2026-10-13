package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;

class RecoveryNotificationBudgetTest {
    @Test
    void recoveryOfAnOldIncidentConsumesBudgetAcrossRefreshAndRestart() {
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        Action action = new Action();
        action.setId(9);
        action.setMaxNotificationsPerWindow(1);
        action.setRollupWindowSeconds(600);
        ActionDispatchLog recovery = row(now.minusSeconds(10), "[SENTINEL_RESOLVED]");
        try (var events = mockStatic(AlertEventRepository.class);
                var logs = mockStatic(ActionDispatchLogRepository.class)) {
            // The incident is absent from recent-event display results: it
            // can be arbitrarily old or displaced by newer incidents.
            events.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt())).thenReturn(List.of());
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsInRange(any(), any()))
                    .thenReturn(List.of(recovery));
            AlertStormControl.reset();
            assertEquals(AlertStormControl.CeilingOutcome.ROLLUP,
                    AlertStormControl.ceilingVerdict(action, now).outcome());
            ActionDispatchLog rollup = row(now, "[sentinel:rollup] [SENTINEL_RESOLVED]");
            logs.when(() -> ActionDispatchLogRepository.listActionDispatchLogsInRange(any(), any()))
                    .thenReturn(List.of(recovery, rollup));
            assertEquals(AlertStormControl.CeilingOutcome.SILENT,
                    AlertStormControl.ceilingVerdict(action, now.plusSeconds(3)).outcome());
            AlertStormControl.reset();
            assertEquals(AlertStormControl.CeilingOutcome.SILENT,
                    AlertStormControl.ceilingVerdict(action, now.plusSeconds(4)).outcome());
            assertEquals(AlertStormControl.CeilingOutcome.SEND,
                    AlertStormControl.ceilingVerdict(action, now.plusSeconds(601)).outcome());
        } finally {
            AlertStormControl.reset();
        }
    }

    private static ActionDispatchLog row(Instant time, String marker) {
        ActionDispatchLog row = new ActionDispatchLog();
        row.setAlertEventId(1L);
        row.setActionId(9);
        row.setDispatchTime(time);
        row.setErrorMessage(marker);
        row.setSuccess(true);
        return row;
    }
}
