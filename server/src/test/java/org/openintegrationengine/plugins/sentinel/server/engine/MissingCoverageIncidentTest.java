package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class MissingCoverageIncidentTest {
    @Test
    void unknownMeasurementRetainsExistingIncidentWithoutRecoveryOrNewAlert() throws Exception {
        Monitor monitor = new Monitor(); monitor.setId(7);
        TriggerState state = new TriggerState(); state.setMonitorId(7); state.setChannelId("channel");
        state.setState(TriggerStatus.PROBLEM); state.setOpenAlertEventId(91L);
        var apply = TriggerEvaluatorJob.class.getDeclaredMethod("applyOutcomeInTransaction",
                Monitor.class, String.class, Integer.class, EvaluationOutcome.class, Instant.class, List.class);
        apply.setAccessible(true);
        try (var states = mockStatic(TriggerStateRepository.class); var alerts = mockStatic(AlertEventRepository.class)) {
            states.when(() -> TriggerStateRepository.getTriggerState(7, "channel", null)).thenReturn(state);
            apply.invoke(null, monitor, "channel", null, EvaluationOutcome.insufficientData("{}"),
                    Instant.parse("2026-01-15T12:00:00Z"), new ArrayList<Runnable>());
            assertEquals(TriggerStatus.INSUFFICIENT_DATA, state.getState());
            assertEquals(91L, state.getOpenAlertEventId());
            states.verify(() -> TriggerStateRepository.updateTriggerState(state));
            alerts.verify(() -> AlertEventRepository.getAlertEvent(91L));
            alerts.verifyNoMoreInteractions();
        }
    }
}
