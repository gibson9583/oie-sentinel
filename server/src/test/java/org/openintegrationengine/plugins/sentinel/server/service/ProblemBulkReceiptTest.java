package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;

class ProblemBulkReceiptTest {
    private AlertEvent event(long id, AlertStatus status) {
        AlertEvent event = new AlertEvent(); event.setId(id); event.setStatus(status); return event;
    }

    @Test void mixedAcknowledgementReceiptsPreserveOrderAndDeduplicate() {
        try (MockedStatic<AlertEventRepository> db = mockStatic(AlertEventRepository.class);
             MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            AlertEvent open = event(1, AlertStatus.PROBLEM);
            AlertEvent acknowledged = event(2, AlertStatus.PROBLEM); acknowledged.setAcknowledgedBy(7);
            db.when(() -> AlertEventRepository.getAlertEvent(1)).thenReturn(open);
            db.when(() -> AlertEventRepository.getAlertEvent(2)).thenReturn(acknowledged);
            db.when(() -> AlertEventRepository.getAlertEvent(3)).thenReturn(event(3, AlertStatus.RESOLVED));
            db.when(() -> AlertEventRepository.getAlertEvent(5)).thenReturn(event(5, AlertStatus.PROBLEM));
            db.when(() -> AlertEventRepository.acknowledgeAlertEvent(open)).thenReturn(true);
            List<Map<String,Object>> receipts = ProblemService.bulkReceipts(List.of(1L,2L,3L,4L,5L,1L), "note", 7, false, e -> e.getId() != 5);
            assertEquals(List.of("APPLIED","ALREADY_ACKNOWLEDGED","ALREADY_RESOLVED","UNAVAILABLE","UNAVAILABLE"),
                receipts.stream().map(r -> r.get("status")).toList());
            assertEquals(1, ProblemService.appliedCount(receipts));
            db.verify(() -> AlertEventRepository.acknowledgeAlertEvent(open), times(1));
            audit.verify(() -> SentinelAuditLog.problemBulkAcknowledged(7, 1, "note"));
        }
    }

    @Test void readFailureWriteUncertaintyAndConditionalRaceDoNotEraseOtherReceipts() {
        try (MockedStatic<AlertEventRepository> db = mockStatic(AlertEventRepository.class);
             MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            db.when(() -> AlertEventRepository.getAlertEvent(1)).thenThrow(new RuntimeException("read failed"));
            AlertEvent uncertain = event(2, AlertStatus.PROBLEM), race = event(3, AlertStatus.PROBLEM), open = event(4, AlertStatus.PROBLEM);
            db.when(() -> AlertEventRepository.getAlertEvent(2)).thenReturn(uncertain);
            db.when(() -> AlertEventRepository.getAlertEvent(3)).thenReturn(race);
            db.when(() -> AlertEventRepository.getAlertEvent(4)).thenReturn(open);
            db.when(() -> AlertEventRepository.acknowledgeAlertEvent(uncertain)).thenThrow(new RuntimeException("commit response lost"));
            db.when(() -> AlertEventRepository.acknowledgeAlertEvent(race)).thenReturn(false);
            db.when(() -> AlertEventRepository.acknowledgeAlertEvent(open)).thenReturn(true);
            assertEquals(List.of("FAILED","UNKNOWN","NOT_APPLIED","APPLIED"), ProblemService.bulkReceipts(
                List.of(1L,2L,3L,4L), "", 7, false, e -> true).stream().map(r -> r.get("status")).toList());
        }
    }

    @Test void resolveResetsOnlyAppliedTriggerAndDoesNotTouchForbiddenEvent() {
        try (MockedStatic<AlertEventRepository> db = mockStatic(AlertEventRepository.class);
             MockedStatic<TriggerStateRepository> state = mockStatic(TriggerStateRepository.class);
             MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            db.when(() -> AlertEventRepository.getAlertEvent(1)).thenReturn(event(1, AlertStatus.PROBLEM));
            db.when(() -> AlertEventRepository.getAlertEvent(2)).thenReturn(event(2, AlertStatus.PROBLEM));
            db.when(() -> AlertEventRepository.resolveAlertEventManually(any())).thenReturn(true);
            assertEquals(List.of("APPLIED","UNAVAILABLE"), ProblemService.bulkReceipts(List.of(1L,2L), "", 7, true,
                e -> e.getId() == 1).stream().map(r -> r.get("status")).toList());
            db.verify(() -> AlertEventRepository.resolveAlertEventManually(any()), times(1));
            state.verify(() -> TriggerStateRepository.resetTriggerStateForAlert(eq(1L), any()), times(1));
            audit.verify(() -> SentinelAuditLog.problemBulkResolved(7, 1, ""));
        }
    }

    @Test void invalidCommentFailsBeforeAnyReadsOrWrites() {
        try (MockedStatic<AlertEventRepository> db = mockStatic(AlertEventRepository.class)) {
            assertThrows(IllegalArgumentException.class, () -> ProblemService.bulkReceipts(List.of(1L), "x".repeat(1025), 7, false, e -> true));
            db.verifyNoInteractions();
        }
    }
}
