package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;

/** A secret-safe response must retain the independent delivery policy. */
class ActionMetadataRoundTripTest {

    @Test
    void redactionPreservesDeliveryPolicyWithoutChangingStoredCredentials() {
        Action stored = action();
        Action response = ActionService.redact(stored);
        assertNotSame(stored, response);
        assertPolicy(response);
        assertTrue(response.getConfigJson().contains(ActionService.REDACTED));
        assertFalse(response.getConfigJson().contains("encrypted-key"));
        assertTrue(stored.getConfigJson().contains("encrypted-key"));
    }

    @Test
    void listGetUnrelatedEditAndExportKeepTheStoredPolicy() {
        AtomicReference<Action> stored = new AtomicReference<>(action());
        try (MockedStatic<ActionRepository> repository = mockStatic(ActionRepository.class);
                MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class);
                MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
                MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class)) {
            repository.when(() -> ActionRepository.getAction(1)).thenAnswer(i -> stored.get());
            Action target = new Action(); target.setId(7); target.setName("Escalation pager");
            repository.when(() -> ActionRepository.listActions(null)).thenAnswer(i -> List.of(stored.get(), target));
            repository.when(() -> ActionRepository.updateAction(any(Action.class))).thenAnswer(i -> {
                stored.set(i.getArgument(0));
                return null;
            });

            assertPolicy(ActionService.list().get(0));
            Action edited = ActionService.get(1);
            assertPolicy(edited);
            edited.setDescription("Only the description changed");
            assertPolicy(ActionService.update(1, edited, 42));
            assertPolicy(stored.get());
            assertEquals("Only the description changed", stored.get().getDescription());
            assertTrue(stored.get().getConfigJson().contains("encrypted-key"));

            JsonNode node = ExportImportService.export().path("actions").get(0);
            assertEquals("Escalation pager", node.path("escalateToActionName").asText());
            assertFalse(node.has("escalateToActionId"));
            Action exported = Json.mapper().convertValue(node, Action.class);
            assertNull(exported.getEscalateToActionId());
            exported.setEscalateToActionId(7); // the portable name carries the same policy
            assertPolicy(exported);
            assertTrue(exported.getConfigJson().contains(ActionService.REDACTED));
            assertFalse(exported.getConfigJson().contains("encrypted-key"));
        }
    }

    @Test
    void createResponseKeepsPolicyAndNullablePolicyStaysDisabled() {
        try (MockedStatic<ActionRepository> repository = mockStatic(ActionRepository.class);
                MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class);
                MockedStatic<SettingsCrypto> crypto = mockStatic(SettingsCrypto.class)) {
            crypto.when(() -> SettingsCrypto.encrypt("new-key")).thenReturn("encrypted-key");
            repository.when(() -> ActionRepository.insertAction(any(Action.class))).thenAnswer(i -> {
                Action inserted = i.getArgument(0);
                inserted.setId(9);
                assertPolicy(inserted);
                return inserted;
            });
            Action incoming = action();
            incoming.setConfigJson(incoming.getConfigJson().replace("encrypted-key", "new-key"));
            Action created = ActionService.create(incoming, 42);
            assertEquals(9, created.getId());
            assertPolicy(created);
            assertFalse(created.getConfigJson().contains("new-key"));

            Action disabled = ActionService.redact(new Action());
            assertNull(disabled.getMaxNotificationsPerWindow());
            assertNull(disabled.getRollupWindowSeconds());
            assertNull(disabled.getEscalateAfterSeconds());
            assertNull(disabled.getEscalateToActionId());
        }
    }

    private static Action action() {
        Action action = new Action();
        action.setId(1);
        action.setName("Pager");
        action.setEnabled(true);
        action.setActionType(ActionType.SNS);
        action.setOperationMode(OperationMode.BOTH);
        action.setConditionJson("[]");
        action.setRepeatIntervalSeconds(120);
        action.setMaxRepeats(3);
        action.setMaxNotificationsPerWindow(5);
        action.setRollupWindowSeconds(60);
        action.setEscalateAfterSeconds(300);
        action.setEscalateToActionId(7);
        action.setConfigJson("{\"region\":\"us-east-1\",\"topicArn\":\"arn:aws:sns:us-east-1:123:alerts\","
                + "\"authType\":\"STATIC\",\"accessKeyId\":\"key-id\",\"secretAccessKey\":\"encrypted-key\"}");
        return action;
    }

    private static void assertPolicy(Action action) {
        assertAll(() -> assertEquals(120, action.getRepeatIntervalSeconds()),
                () -> assertEquals(3, action.getMaxRepeats()),
                () -> assertEquals(5, action.getMaxNotificationsPerWindow()),
                () -> assertEquals(60, action.getRollupWindowSeconds()),
                () -> assertEquals(300, action.getEscalateAfterSeconds()),
                () -> assertEquals(7, action.getEscalateToActionId()));
    }
}
