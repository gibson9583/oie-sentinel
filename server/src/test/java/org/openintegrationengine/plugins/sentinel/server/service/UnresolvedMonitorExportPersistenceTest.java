/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionConditionMatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/** Source-side reference diagnostics must survive export without becoming destination monitor names. */
class UnresolvedMonitorExportPersistenceTest extends MonitorIdentityDatabaseTestSupport {
    @ParameterizedTest
    @CsvSource({"=,false", "=,true", "!=,false", "!=,true", "IN,false", "IN,true"})
    void uneditedDanglingExportCannotBindAnUnrelatedNumericName(String operator, boolean differentServer)
            throws Exception {
        Monitor deleted = MonitorService.create(monitor("Deleted", "{}", null), 99);
        Action original = ActionService.create(action("Recipient", condition(operator, deleted.getId().toString())), 99);
        MonitorRepository.deleteMonitor(deleted.getId());
        Monitor unrelated = MonitorService.create(monitor(deleted.getId().toString(), "{}", null), 99);
        assertNotEquals(deleted.getId(), unrelated.getId());
        boolean originalRouting = matches(original, unrelated.getId());

        ObjectNode exported = ExportImportService.export();
        ObjectNode recipient = exportedAction(exported, "Recipient");
        assertEquals(3, exported.path("schemaVersion").asInt(), "Older readers must reject the diagnostic format");
        assertDiagnostic(recipient, deleted.getId().toString());
        assertEquals(original.getConditionJson(), recipient.path("conditionJson").asText());

        if (differentServer) {
            clearRows();
            Monitor destination = MonitorService.create(monitor(deleted.getId().toString(), "{}", null), 99);
            assertNotEquals(unrelated.getId(), destination.getId());
        }
        String before = snapshot();
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = ExportImportService.importDocument(exported, 99);
            assertCollisionSkip(result, "Recipient");
            assertEquals(0, result.getCreated());
            assertEquals(0, result.getUpdated());
            assertEquals(before, snapshot(), "An unresolved source reference must not alter destination rows");
            if (differentServer) {
                assertNull(namedAction("Recipient"));
            } else {
                Action persisted = ActionRepository.getAction(original.getId());
                assertEquals(original.getConditionJson(), persisted.getConditionJson());
                assertEquals(originalRouting, matches(persisted, unrelated.getId()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "+"})
    void noncanonicalSourceIdsRemainUnresolvedInsteadOfChangingRouting(String prefix) {
        Monitor intended = MonitorService.create(monitor("Pager", "{}", null), 99);
        String noncanonicalId = prefix + intended.getId();
        Action original = ActionService.create(action("Recipient", condition("=", noncanonicalId)), 99);
        assertFalse(matches(original, intended.getId()), "The runtime compares textual IDs exactly");
        ObjectNode exported = ExportImportService.export();
        ObjectNode recipient = exportedAction(exported, "Recipient");
        assertDiagnostic(recipient, noncanonicalId);
        assertEquals(original.getConditionJson(), recipient.path("conditionJson").asText());
        String before = snapshot();

        for (int attempt = 0; attempt < 2; attempt++) {
            var result = ExportImportService.importDocument(exported, 99);
            assertCollisionSkip(result, "Recipient");
            assertEquals(before, snapshot());
            assertFalse(matches(ActionRepository.getAction(original.getId()), intended.getId()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8})
    void legacyNameThatTrimsToBlankCannotEraseAWorkingMonitorCondition(int character) {
        Monitor legacy = legacyMonitor(String.valueOf((char) character), "{}", null);
        Action original = ActionService.create(action("Recipient", condition("=", legacy.getId().toString())), 99);
        assertTrue(matches(original, legacy.getId()));
        ObjectNode exported = ExportImportService.export();
        ObjectNode recipient = exportedAction(exported, "Recipient");
        assertDiagnostic(recipient, legacy.getId().toString());
        assertEquals(original.getConditionJson(), recipient.path("conditionJson").asText());
        ((ArrayNode) exported.path("actions")).add(actionEntry("Escalating recipient", "[]")
                .put("escalateToActionName", "Recipient"));
        String before = snapshot();

        for (int attempt = 0; attempt < 2; attempt++) {
            var result = ExportImportService.importDocument(exported, 99);
            assertCollisionSkip(result, "Recipient");
            assertCollisionSkip(result, "Escalating recipient");
            assertNull(namedAction("Escalating recipient"));
            assertEquals(before, snapshot());
            assertTrue(matches(ActionRepository.getAction(original.getId()), legacy.getId()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void explicitNumericMonitorNamesAndAliasesStillImportInEverySupportedFormat(int schemaVersion) {
        Monitor intended = MonitorService.create(monitor("007", "{}", null), 99);
        Action stale = ActionService.create(action("Recipient", condition("IN", "0" + intended.getId())), 99);
        assertFalse(matches(stale, intended.getId()));
        ObjectNode imported = document().put("schemaVersion", schemaVersion);
        imported.putArray("actions").add(actionEntry("Recipient", condition("IN", " 007 ")));

        var first = ExportImportService.importDocument(imported, 99);
        assertEquals(1, first.getUpdated());
        Action persisted = ActionRepository.getAction(stale.getId());
        assertEquals(condition("IN", intended.getId().toString()), persisted.getConditionJson());
        assertTrue(matches(persisted, intended.getId()));
        assertFalse(exportedAction(ExportImportService.export(), "Recipient").has("unresolvedMonitorIds"));
        String beforeRetry = snapshot();
        var retry = ExportImportService.importDocument(imported, 99);
        assertEquals(0, retry.getUpdated());
        assertEquals("Already matches this server", retry.getEntries().get(0).getReason());
        assertEquals(beforeRetry, snapshot());
    }

    @Test
    void unresolvedExportBlocksEscalationDependentsWhileIndependentEntriesImportAndRetry() {
        Monitor deleted = MonitorService.create(monitor("Deleted", "{}", null), 99);
        Action original = ActionService.create(action("Recipient", condition("=", deleted.getId().toString())), 99);
        MonitorRepository.deleteMonitor(deleted.getId());
        MonitorService.create(monitor(deleted.getId().toString(), "{}", null), 99);
        ObjectNode exported = ExportImportService.export();
        ArrayNode actions = (ArrayNode) exported.path("actions");
        actions.add(actionEntry("Escalating recipient", "[]").put("escalateToActionName", "Recipient"));
        actions.add(actionEntry("Independent recipient", "[]"));
        reverse(exported);
        String originalRecipient = Json.write(ActionRepository.getAction(original.getId()));

        var first = ExportImportService.importDocument(exported, 99);
        assertEquals(1, first.getCreated());
        assertEquals(0, first.getUpdated());
        assertCollisionSkip(first, "Recipient");
        assertCollisionSkip(first, "Escalating recipient");
        assertNull(namedAction("Escalating recipient"));
        assertNotNull(namedAction("Independent recipient"));
        assertEquals(originalRecipient, Json.write(ActionRepository.getAction(original.getId())));

        String beforeRetry = snapshot();
        reverse(exported);
        var retry = ExportImportService.importDocument(exported, 99);
        assertEquals(0, retry.getCreated());
        assertEquals(0, retry.getUpdated());
        assertCollisionSkip(retry, "Recipient");
        assertCollisionSkip(retry, "Escalating recipient");
        assertEquals(beforeRetry, snapshot());
    }

    @Test
    void explicitRepairOfMixedConditionAllowsNumericNamesAfterRemovingDiagnostic() {
        Monitor retained = MonitorService.create(monitor("Pager", "{}", null), 99);
        Monitor deleted = MonitorService.create(monitor("Deleted", "{}", null), 99);
        Action original = ActionService.create(action("Recipient",
                condition("IN", retained.getId().toString(), deleted.getId().toString())), 99);
        MonitorRepository.deleteMonitor(deleted.getId());
        Monitor replacement = MonitorService.create(monitor(deleted.getId().toString(), "{}", null), 99);
        assertTrue(matches(original, retained.getId()));
        assertFalse(matches(original, replacement.getId()));
        ObjectNode exported = ExportImportService.export();
        ObjectNode recipient = exportedAction(exported, "Recipient");
        assertDiagnostic(recipient, deleted.getId().toString());
        assertEquals(condition("IN", retained.getName(), deleted.getId().toString()),
                recipient.path("conditionJson").asText());
        String before = snapshot();
        assertCollisionSkip(ExportImportService.importDocument(exported, 99), "Recipient");
        assertEquals(before, snapshot());

        recipient.put("conditionJson", condition("IN", " pAgEr ", " " + replacement.getName() + " "));
        recipient.remove("unresolvedMonitorIds");
        var repaired = ExportImportService.importDocument(exported, 99);
        assertEquals(1, repaired.getUpdated());
        Action persisted = ActionRepository.getAction(original.getId());
        assertTrue(matches(persisted, retained.getId()));
        assertTrue(matches(persisted, replacement.getId()));
        assertEquals(condition("IN", retained.getId().toString(), replacement.getId().toString()),
                persisted.getConditionJson());
        String beforeRetry = snapshot();
        var retry = ExportImportService.importDocument(exported, 99);
        assertEquals(0, retry.getUpdated());
        assertEquals(beforeRetry, snapshot());
    }

    @Test
    void malformedOrEmptyDiagnosticCannotBypassImportRejection() throws Exception {
        ObjectNode imported = document().put("schemaVersion", 3);
        ArrayNode actions = imported.putArray("actions");
        String[] diagnostics = {"null", "[]", "{}", "\"1\""};
        for (int i = 0; i < diagnostics.length; i++) {
            ObjectNode recipient = actionEntry("Recipient " + i, "[]");
            recipient.set("unresolvedMonitorIds", Json.mapper().readTree(diagnostics[i]));
            actions.add(recipient);
        }
        String before = snapshot();
        var result = ExportImportService.importDocument(imported, 99);
        assertEquals(0, result.getCreated());
        assertEquals(diagnostics.length, result.getSkipped());
        for (int i = 0; i < diagnostics.length; i++) assertCollisionSkip(result, "Recipient " + i);
        assertEquals(before, snapshot());
    }

    private static ObjectNode exportedAction(ObjectNode exported, String name) {
        for (JsonNode entry : exported.path("actions")) {
            if (name.equals(entry.path("name").asText())) return (ObjectNode) entry;
        }
        throw new AssertionError("No exported action named " + name);
    }

    private static void assertDiagnostic(ObjectNode action, String expectedReference) {
        assertEquals(Json.mapper().createArrayNode().add(expectedReference), action.path("unresolvedMonitorIds"));
    }

    private static boolean matches(Action action, int monitorId) {
        AlertPayload payload = mock(AlertPayload.class);
        when(payload.getMonitorId()).thenReturn(monitorId);
        return ActionConditionMatcher.matches(action, payload);
    }
}
