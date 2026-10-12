package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;

/** Real export/import and action services over two isolated in-memory repositories. */
class ActionEscalationImportTest {
    @Test
    void exportsNamesAndNeverSelectsAnUnrelatedDestinationWithTheSameNumericId() {
        for (boolean reverse : List.of(false, true)) {
            try (Store db = new Store()) {
                db.put(action(1, "Primary", 7));
                db.put(action(7, "Pager", null));
                ObjectNode document = ExportImportService.export();
                assertEquals(2, document.path("schemaVersion").asInt());
                JsonNode primary = document.path("actions").get(0);
                assertFalse(primary.has("id"));
                assertFalse(primary.has("escalateToActionId"));
                assertEquals("Pager", primary.path("escalateToActionName").asText());
                if (reverse) reverseActions(document);
                db.rows.clear();
                db.put(action(7, "Unrelated recipient", null));
                db.put(action(42, "Pager", null));

                var result = ExportImportService.importDocument(document, 99);
                assertEquals(1, result.getCreated());
                assertEquals(42, db.named("Primary").getEscalateToActionId());
                assertEquals("Unrelated recipient", db.rows.get(7).getName());
                assertEquals(0, ExportImportService.importDocument(document, 99).getUpdated());
            }
        }
    }

    @Test
    void reverseOrderedNewChainAndRetryAreIdempotent() {
        try (Store db = new Store()) {
            ObjectNode document = document(entry("First", "Second"), entry("Second", "Third"), entry("Third", null));
            assertEquals(3, ExportImportService.importDocument(document, 99).getCreated());
            assertEquals(db.named("Second").getId(), db.named("First").getEscalateToActionId());
            assertEquals(db.named("Third").getId(), db.named("Second").getEscalateToActionId());
            int writes = db.writes;
            reverseActions(document);
            var retry = ExportImportService.importDocument(document, 99);
            assertEquals(3, retry.getSkipped());
            assertTrue(retry.getEntries().stream().allMatch(e -> "Already matches this server".equals(e.getReason())));
            assertEquals(writes, db.writes);
        }
    }

    @Test
    void updatesReferenceAndTreatsRenameAsCreateByNameWithoutMutatingOldTarget() {
        try (Store db = new Store()) {
            db.put(action(10, "Primary", 20));
            db.put(action(20, "Old pager", null));
            ObjectNode document = document(entry("Primary", "New pager"), entry("New pager", null));
            var result = ExportImportService.importDocument(document, 99);
            assertEquals(1, result.getCreated());
            assertEquals(1, result.getUpdated());
            assertEquals(db.named("New pager").getId(), db.named("Primary").getEscalateToActionId());
            assertEquals(20, db.named("Old pager").getId());
        }
    }

    @Test
    void destinationOnlyCaseFoldedTargetAndDisabledTargetKeepTheirMeaning() {
        try (Store db = new Store()) {
            Action target = action(80, "Pager", null);
            target.setEnabled(false);
            db.put(target);
            assertEquals(1, ExportImportService.importDocument(document(entry("Primary", " pager ")), 99).getCreated());
            assertEquals(80, db.named("Primary").getEscalateToActionId());
            assertFalse(db.named("Pager").isEnabled());
        }
    }

    @Test
    void missingOrMalformedNameLeavesExistingActionUntouched() {
        try (Store db = new Store()) {
            db.put(action(10, "Primary", 20));
            db.put(action(20, "Original", null));
            for (JsonNode target : List.of(Json.mapper().getNodeFactory().textNode("Missing"),
                    Json.mapper().getNodeFactory().textNode(" "), Json.mapper().getNodeFactory().numberNode(20),
                    Json.mapper().createObjectNode())) {
                ObjectNode incoming = entry("Primary", null);
                incoming.set("escalateToActionName", target);
                assertEquals(1, ExportImportService.importDocument(document(incoming), 99).getSkipped());
                assertEquals(20, db.named("Primary").getEscalateToActionId());
            }
            assertEquals(0, db.writes);
        }
    }

    @Test
    void unresolvedExportReferenceRemainsAnExplicitRejectionEvenWhenDestinationIdExists() {
        try (Store db = new Store()) {
            db.put(action(10, "Primary", 70));
            ObjectNode document = ExportImportService.export();
            assertEquals(70, document.path("actions").get(0).path("escalateToActionId").asInt());
            db.rows.clear();
            db.put(action(70, "Unrelated", null));
            var result = ExportImportService.importDocument(document, 99);
            assertEquals(1, result.getSkipped());
            assertTrue(result.getEntries().get(0).getReason().contains("cannot be resolved safely"));
            assertNull(db.named("Primary"));
            assertEquals(0, db.writes);
        }
    }

    @Test
    void legacyNumericReferencesAreRejectedEvenIfNameAlsoPresentOrIdLooksHarmless() {
        try (Store db = new Store()) {
            db.put(action(7, "Pager", null));
            for (JsonNode id : List.of(Json.mapper().getNodeFactory().numberNode(7),
                    Json.mapper().getNodeFactory().numberNode(0), Json.mapper().getNodeFactory().textNode("7"))) {
                ObjectNode incoming = entry("Primary", "Pager");
                incoming.set("escalateToActionId", id);
                ObjectNode document = document(incoming);
                document.put("schemaVersion", 1);
                assertEquals(1, ExportImportService.importDocument(document, 99).getSkipped());
            }
            assertEquals(0, db.writes);
        }
    }

    @Test
    void legacyWithoutEscalationStillImportsAndExplicitNullClearsReference() {
        try (Store db = new Store()) {
            db.put(action(10, "Primary", 20));
            ObjectNode incoming = entry("Primary", null);
            incoming.remove("escalateToActionName");
            incoming.putNull("escalateToActionId");
            ObjectNode document = document(incoming, entry("Other", null));
            document.put("schemaVersion", 1);
            var result = ExportImportService.importDocument(document, 99);
            assertEquals(1, result.getUpdated());
            assertEquals(1, result.getCreated());
            assertNull(db.named("Primary").getEscalateToActionId());
        }
    }

    @Test
    void selfReferencesAndCyclesLeaveExistingAndNewActionsUntouchedInEitherOrder() {
        for (boolean reverse : List.of(false, true)) {
            try (Store db = new Store()) {
                db.put(action(10, "A", null));
                db.put(action(20, "B", null));
                ObjectNode document = document(entry("A", "B"), entry("B", "A"), entry("Self", "Self"),
                        entry("Dependent", "A"), entry("Independent", null));
                if (reverse) reverseActions(document);
                var result = ExportImportService.importDocument(document, 99);
                assertEquals(4, result.getSkipped());
                assertEquals(1, result.getCreated());
                assertNull(db.named("A").getEscalateToActionId());
                assertNull(db.named("B").getEscalateToActionId());
                assertNull(db.named("Self"));
                assertNull(db.named("Dependent"));
            }
        }
    }

    @Test
    void targetChainsOutsideTheDocumentCannotIntroduceOrExtendCycles() {
        try (Store db = new Store()) {
            db.put(action(10, "A", null));
            db.put(action(20, "B", 10));
            var result = ExportImportService.importDocument(document(entry("A", "B")), 99);
            assertEquals(1, result.getSkipped());
            assertTrue(result.getEntries().get(0).getReason().contains("circular"));
            assertNull(db.named("A").getEscalateToActionId());
            db.put(action(10, "A", 20)); // an existing cycle created outside import
            assertEquals(1, ExportImportService.importDocument(document(entry("New", "A")), 99).getSkipped());
            assertNull(db.named("New"));
            assertEquals(0, db.writes);
        }
    }

    @Test
    void duplicateNamesBlockAllCopiesAndTheirDependentsInsteadOfLastWriteWinning() {
        try (Store db = new Store()) {
            db.put(action(20, "Pager", null));
            var result = ExportImportService.importDocument(document(entry("Primary", "Pager"),
                    entry("Pager", null), entry(" pager ", null)), 99);
            assertEquals(3, result.getSkipped());
            assertEquals(0, db.writes);
            assertNull(db.named("Primary"));
        }
    }

    @Test
    void failedTargetReplacementCannotFallBackToOldDestinationRowAndRetryCanConverge() {
        try (Store db = new Store()) {
            db.put(action(20, "Pager", null));
            ObjectNode invalid = entry("Pager", null);
            invalid.put("configJson", "{}");
            ObjectNode document = document(entry("Primary", "Pager"), invalid, entry("Independent", null));
            var first = ExportImportService.importDocument(document, 99);
            assertEquals(2, first.getSkipped());
            assertEquals(1, first.getCreated());
            assertNull(db.named("Primary"));
            assertEquals("{\"to\":\"pager@example.test\"}", db.named("Pager").getConfigJson());
            invalid.put("configJson", "{\"to\":\"new-pager@example.test\"}");
            var retry = ExportImportService.importDocument(document, 99);
            assertEquals(1, retry.getUpdated());
            assertEquals(1, retry.getCreated());
            assertEquals(20, db.named("Primary").getEscalateToActionId());
        }
    }

    @Test
    void missingSecretsSkipTargetAndDependentRatherThanCreatingAnIncompleteChain() {
        try (Store db = new Store()) {
            Action target = sns(20, "Pager");
            ObjectNode masked = Json.mapper().valueToTree(ActionService.redact(target));
            masked.remove("id");
            masked.remove("escalateToActionId");
            var result = ExportImportService.importDocument(document(entry("Primary", "Pager"), masked), 99);
            assertEquals(2, result.getSkipped());
            assertTrue(result.getEntries().stream().anyMatch(e -> !e.getSecretsRequired().isEmpty()));
            assertEquals(0, db.writes);
        }
    }

    @Test
    void unchangedMaskedTargetSatisfiesDependencyWithoutExposingOrRewritingSecrets() {
        try (Store db = new Store()) {
            db.put(sns(20, "Pager"));
            ObjectNode document = ExportImportService.export();
            ((ArrayNode) document.get("actions")).insert(0, entry("Primary", "Pager"));
            var result = ExportImportService.importDocument(document, 99);
            assertEquals(1, result.getCreated());
            assertEquals(1, result.getSkipped());
            assertEquals(1, db.writes);
            assertEquals(20, db.named("Primary").getEscalateToActionId());
            assertTrue(db.named("Pager").getConfigJson().contains("stored-secret"));
            assertFalse(document.toString().contains("stored-secret"));
        }
    }

    @Test
    void monitorConditionsAndEscalationReferencesBothResolveDestinationIds() {
        try (Store db = new Store()) {
            Monitor monitor = new Monitor(); monitor.setId(1); monitor.setName("Down");
            db.monitorRows.add(monitor);
            Action source = action(10, "Primary", 20);
            source.setConditionJson("[{\"field\":\"MONITOR\",\"operator\":\"IN\",\"value\":\"1\"}]");
            db.put(source); db.put(action(20, "Pager", null));
            ObjectNode document = ExportImportService.export();
            document.remove("monitors");
            monitor.setId(101);
            db.rows.clear(); db.put(action(202, "Pager", null));
            assertEquals(1, ExportImportService.importDocument(document, 99).getCreated());
            assertEquals(202, db.named("Primary").getEscalateToActionId());
            assertTrue(db.named("Primary").getConditionJson().contains("101"));
        }
    }

    private static ObjectNode entry(String name, String target) {
        ObjectNode node = Json.mapper().valueToTree(action(null, name, null));
        node.remove("id"); node.remove("escalateToActionId");
        if (target == null) node.putNull("escalateToActionName"); else node.put("escalateToActionName", target);
        return node;
    }

    private static ObjectNode document(ObjectNode... entries) {
        ObjectNode node = Json.mapper().createObjectNode().put("schemaVersion", 2);
        ArrayNode actions = node.putArray("actions");
        for (ObjectNode entry : entries) actions.add(entry);
        return node;
    }

    private static void reverseActions(ObjectNode document) {
        List<JsonNode> entries = new ArrayList<>();
        document.path("actions").forEach(entries::add);
        ArrayNode reversed = document.putArray("actions");
        for (int i = entries.size() - 1; i >= 0; i--) reversed.add(entries.get(i));
    }

    private static Action action(Integer id, String name, Integer target) {
        Action action = new Action();
        action.setId(id); action.setName(name); action.setEnabled(true);
        action.setActionType(ActionType.EMAIL); action.setOperationMode(OperationMode.BOTH);
        action.setConditionJson("[]"); action.setConfigJson("{\"to\":\"pager@example.test\"}");
        action.setEscalateAfterSeconds(300); action.setEscalateToActionId(target);
        return action;
    }

    private static Action sns(int id, String name) {
        Action target = action(id, name, null); target.setActionType(ActionType.SNS);
        target.setConfigJson("{\"region\":\"us-east-1\",\"topicArn\":\"arn:aws:sns:us-east-1:123:alerts\","
                + "\"authType\":\"STATIC\",\"accessKeyId\":\"stored-key\",\"secretAccessKey\":\"stored-secret\"}");
        return target;
    }

    private static final class Store implements AutoCloseable {
        final Map<Integer, Action> rows = new LinkedHashMap<>();
        final List<Monitor> monitorRows = new ArrayList<>();
        final MockedStatic<ActionRepository> repository = mockStatic(ActionRepository.class);
        final MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class);
        final MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
        final MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class);
        int nextId = 1000;
        int writes;
        Store() {
            repository.when(() -> ActionRepository.listActions(null)).thenAnswer(i -> new ArrayList<>(rows.values()));
            repository.when(() -> ActionRepository.getAction(anyInt())).thenAnswer(i -> rows.get(i.getArgument(0)));
            repository.when(() -> ActionRepository.insertAction(any(Action.class))).thenAnswer(i -> {
                Action action = i.getArgument(0); action.setId(nextId++); put(action); writes++; return action;
            });
            repository.when(() -> ActionRepository.updateAction(any(Action.class))).thenAnswer(i -> {
                put(i.getArgument(0)); writes++; return null;
            });
            monitors.when(MonitorService::list).thenAnswer(i -> monitorRows);
        }
        void put(Action action) { rows.put(action.getId(), action); }
        Action named(String name) { return rows.values().stream().filter(a -> name.equals(a.getName())).findFirst().orElse(null); }
        public void close() { windows.close(); monitors.close(); audit.close(); repository.close(); }
    }
}
