/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/** Real database regressions for portable name resolution, partial imports and recipient identity. */
class MonitorIdentityImportPersistenceTest extends MonitorIdentityDatabaseTestSupport {
    @Test
    void exportPreservesEveryLegacyRowAndTheExactReferencesToItsId() throws Exception {
        Monitor intended = legacyMonitor(" Pager", "{\"noDataForSeconds\":60}", null);
        legacyMonitor("Pager", "{\"noDataForSeconds\":120}", null);
        legacyMonitor("PAGER", "{\"noDataForSeconds\":180}", null);
        legacyMonitor("Child", "{}", intended.getId());
        ActionService.create(action("Recipient", condition("=", intended.getId().toString())), 99);

        ObjectNode exported = ExportImportService.export();
        assertEquals(4, exported.path("monitors").size(), "Export must enumerate IDs, not normalized names");
        assertEquals(Set.of(" Pager", "Pager", "PAGER", "Child"),
                StreamSupport.stream(exported.path("monitors").spliterator(), false)
                        .map(node -> node.path("name").asText()).collect(Collectors.toSet()));
        for (JsonNode node : exported.path("monitors")) assertFalse(node.has("id"));
        JsonNode child = StreamSupport.stream(exported.path("monitors").spliterator(), false)
                .filter(node -> "Child".equals(node.path("name").asText())).findFirst().orElseThrow();
        assertEquals(" Pager", child.path("suppressedByMonitorName").asText());
        assertEquals(" Pager", Json.mapper().readTree(exported.path("actions").get(0)
                .path("conditionJson").asText()).get(0).path("value").asText());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoringCollidingExportRejectsDependentsAndKeepsIndependentEntries(boolean reversed) throws Exception {
        Monitor intended = legacyMonitor(" Pager", "{\"noDataForSeconds\":60}", null);
        legacyMonitor("Pager", "{\"noDataForSeconds\":120}", null);
        legacyMonitor("Child", "{}", intended.getId());
        legacyMonitor("Independent", "{}", null);
        ActionService.create(action("Recipient", condition("=", intended.getId().toString())), 99);
        ActionService.create(action("Independent recipient", "[]"), 99);
        ObjectNode exported = ExportImportService.export();
        if (reversed) reverse(exported);
        clearRows();
        Monitor unrelated = legacyMonitor("Unrelated target", "{}", null);

        var first = ExportImportService.importDocument(exported, 99);
        assertEquals(2, first.getCreated(), "Only the independent monitor and action may be restored");
        assertEquals(4, first.getSkipped());
        assertNull(namedMonitor("Pager"));
        assertNull(namedMonitor(" Pager"));
        assertNull(namedMonitor("Child"));
        assertNull(namedAction("Recipient"));
        assertNotNull(namedMonitor("Independent"));
        assertNotNull(namedAction("Independent recipient"));
        assertEquals("Unrelated target", MonitorRepository.getMonitor(unrelated.getId()).getName());
        String beforeRetry = snapshot();
        reverse(exported);
        var retry = ExportImportService.importDocument(exported, 99);
        assertEquals(0, retry.getCreated());
        assertEquals(0, retry.getUpdated());
        assertEquals(beforeRetry, snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateDocumentNamesCannotOverwriteExistingMonitorOrBindNewDependents(boolean reversed) {
        Monitor existing = legacyMonitor("Pager", "{\"noDataForSeconds\":300}", null);
        String original = Json.write(MonitorRepository.getMonitor(existing.getId()));
        ObjectNode document = document();
        document.putArray("monitors")
                .add(monitorEntry("Pager", "{\"noDataForSeconds\":60}", null))
                .add(monitorEntry(" pager ", "{\"noDataForSeconds\":120}", null))
                .add(monitorEntry("Child", "{}", "PAGER"))
                .add(monitorEntry("Independent", "{}", null));
        document.putArray("actions").add(actionEntry("Recipient", condition("IN", "Pager", "Independent")));
        if (reversed) reverse(document);
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(1, result.getCreated());
        assertEquals(4, result.getSkipped());
        assertEquals(0, result.getUpdated());
        assertEquals(original, Json.write(MonitorRepository.getMonitor(existing.getId())));
        assertNull(namedMonitor("Child"));
        assertNull(namedAction("Recipient"));
        String beforeRetry = snapshot();
        reverse(document);
        ExportImportService.importDocument(document, 99);
        assertEquals(beforeRetry, snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousDestinationRejectsOwnUpdateSuppressionAndActionButAllowsIndependentWork(boolean reversed) {
        Monitor intended = legacyMonitor(" Pager", "{\"noDataForSeconds\":60}", null);
        legacyMonitor("Pager", "{\"noDataForSeconds\":120}", null);
        Monitor child = legacyMonitor("Child", "{}", intended.getId());
        Action recipient = ActionService.create(action("Recipient", condition("=", intended.getId().toString())), 99);
        String oldMonitors = Json.write(MonitorRepository.listMonitors(null, null, null, null));
        String oldRecipient = Json.write(ActionRepository.getAction(recipient.getId()));
        ObjectNode document = document();
        document.putArray("monitors")
                .add(monitorEntry("pager", "{\"noDataForSeconds\":999}", null))
                .add(monitorEntry("Child", "{\"noDataForSeconds\":999}", "PAGER"));
        ObjectNode changedAction = actionEntry("Recipient", condition("=", "Pager"));
        changedAction.put("configJson", "{\"to\":\"wrong@example.test\"}");
        document.putArray("actions").add(changedAction).add(actionEntry("Independent recipient", "[]"));
        if (reversed) reverse(document);
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(1, result.getCreated());
        assertEquals(3, result.getSkipped());
        assertEquals(0, result.getUpdated());
        assertEquals(oldMonitors, Json.write(MonitorRepository.listMonitors(null, null, null, null)));
        assertEquals(oldRecipient, Json.write(ActionRepository.getAction(recipient.getId())));
        assertEquals(intended.getId(), MonitorRepository.getMonitor(child.getId()).getSuppressedByMonitorId());
        String beforeRetry = snapshot();
        reverse(document);
        ExportImportService.importDocument(document, 99);
        assertEquals(beforeRetry, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "!=", "IN"})
    void ambiguousActionConditionsAreRejectedEvenOnUnchangedFastPath(String operator) throws Exception {
        Monitor intended = legacyMonitor(" Pager", "{}", null);
        legacyMonitor("Pager", "{}", null);
        Monitor independent = legacyMonitor("Independent", "{}", null);
        String stored = "IN".equals(operator)
                ? condition(operator, independent.getId().toString(), intended.getId().toString())
                : condition(operator, intended.getId().toString());
        Action existing = ActionService.create(action("Recipient", stored), 99);
        ObjectNode exported = ExportImportService.export();
        exported.remove("monitors");
        String before = snapshot();
        var result = ExportImportService.importDocument(exported, 99);
        assertEquals(1, result.getSkipped());
        assertCollisionSkip(result, "Recipient");
        assertEquals(before, snapshot());
        assertEquals(stored, ActionRepository.getAction(existing.getId()).getConditionJson());

        ObjectNode changed = (ObjectNode) exported.path("actions").get(0);
        changed.put("description", "Cannot make an ambiguous negative or mixed condition safe");
        var changedResult = ExportImportService.importDocument(exported, 99);
        assertCollisionSkip(changedResult, "Recipient");
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void collisionRejectionPropagatesThroughSuppressionChainInsteadOfUsingOldRows(boolean reversed) {
        Monitor intended = legacyMonitor(" Pager", "{}", null);
        legacyMonitor("Pager", "{}", null);
        Monitor middle = legacyMonitor("Middle", "{}", intended.getId());
        Monitor leaf = legacyMonitor("Leaf", "{}", middle.getId());
        ActionService.create(action("Recipient", condition("=", leaf.getId().toString())), 99);
        String before = snapshot();
        ObjectNode document = document();
        document.putArray("monitors")
                .add(monitorEntry("Middle", "{\"noDataForSeconds\":60}", "Pager"))
                .add(monitorEntry("Leaf", "{\"noDataForSeconds\":60}", "Middle"));
        document.putArray("actions").add(actionEntry("Recipient", condition("=", "Leaf")));
        if (reversed) reverse(document);
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(3, result.getSkipped());
        assertEquals(0, result.getUpdated());
        assertEquals(before, snapshot());
        assertCollisionSkip(result, "Middle");
        assertCollisionSkip(result, "Leaf");
        assertCollisionSkip(result, "Recipient");
        reverse(document);
        ExportImportService.importDocument(document, 99);
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uniqueNamesRoundTripToNewIdsAndRetryWithoutChangesInEitherOrder(boolean reversed) throws Exception {
        Monitor sourceParent = MonitorService.create(monitor("Parent", "{}", null), 99);
        Monitor sourceChild = MonitorService.create(monitor("Child", "{}", sourceParent.getId()), 99);
        ActionService.create(action("Recipient", condition("IN", sourceParent.getId().toString(),
                sourceChild.getId().toString())), 99);
        ObjectNode exported = ExportImportService.export();
        if (reversed) reverse(exported);
        clearRows();
        Monitor unrelated = legacyMonitor("Unrelated", "{}", null);
        var first = ExportImportService.importDocument(exported, 99);
        assertEquals(3, first.getCreated());
        assertEquals(0, first.getSkipped());
        Monitor parent = namedMonitor("Parent");
        Monitor child = namedMonitor("Child");
        assertNotEquals(sourceParent.getId(), parent.getId());
        assertNotEquals(sourceChild.getId(), child.getId());
        assertEquals(parent.getId(), child.getSuppressedByMonitorId());
        JsonNode resolved = Json.mapper().readTree(namedAction("Recipient").getConditionJson()).get(0).path("value");
        assertEquals(List.of(parent.getId().toString(), child.getId().toString()),
                StreamSupport.stream(resolved.spliterator(), false).map(JsonNode::asText).toList());
        assertEquals("Unrelated", MonitorRepository.getMonitor(unrelated.getId()).getName());
        String beforeRetry = snapshot();
        reverse(exported);
        var retry = ExportImportService.importDocument(exported, 99);
        assertEquals(3, retry.getSkipped());
        assertTrue(retry.getEntries().stream().allMatch(e -> "Already matches this server".equals(e.getReason())));
        assertEquals(beforeRetry, snapshot());
    }

    @Test
    void whitespaceLegacyNameAndActionReferenceNormalizeOnceThenExactExportRetryIsUnchanged() throws Exception {
        Monitor legacy = legacyMonitor(" Queue", "{}", null);
        ActionService.create(action("Recipient", condition("=", legacy.getId().toString())), 99);
        ObjectNode exported = ExportImportService.export();
        clearRows();
        assertEquals(2, ExportImportService.importDocument(exported, 99).getCreated());
        Monitor restored = namedMonitor("Queue");
        assertNotNull(restored);
        assertEquals(condition("=", restored.getId().toString()), namedAction("Recipient").getConditionJson());
        String afterFirst = snapshot();
        for (int attempt = 0; attempt < 2; attempt++) {
            var retry = ExportImportService.importDocument(exported, 99);
            assertEquals(0, retry.getUpdated(), "Retry must not rewrite the action after trimming its monitor name");
            assertEquals(2, retry.getSkipped());
            assertTrue(retry.getEntries().stream().allMatch(e -> "Already matches this server".equals(e.getReason())));
            assertEquals(afterFirst, snapshot(), "Authorship and updatedTime must remain unchanged");
        }
    }

    @Test
    void parentAndActionReferenceAliasesAreIdempotentAcrossRepeatedImports() {
        MonitorService.create(monitor("Parent", "{}", null), 99);
        ObjectNode document = document();
        document.putArray("monitors").add(monitorEntry(" Child ", "{}", " pArEnT "));
        document.putArray("actions").add(actionEntry("Recipient", condition("IN", " cHiLd ", " PARENT ")));
        assertEquals(2, ExportImportService.importDocument(document, 99).getCreated());
        assertNotNull(namedMonitor("Parent"));
        assertNotNull(namedMonitor("Child"), "Imported monitor names must be trimmed before saving");
        assertEquals(namedMonitor("Parent").getId(), namedMonitor("Child").getSuppressedByMonitorId());
        String afterFirst = snapshot();
        for (int attempt = 0; attempt < 2; attempt++) {
            var retry = ExportImportService.importDocument(document, 99);
            assertEquals(0, retry.getUpdated());
            assertEquals(2, retry.getSkipped());
            assertTrue(retry.getEntries().stream().allMatch(e -> "Already matches this server".equals(e.getReason())));
            assertEquals(afterFirst, snapshot());
        }
    }

    @Test
    void whitespacePaddedMonitorConditionFieldStillMapsSourceIdsToDestinationIds() throws Exception {
        Monitor source = legacyMonitor("Source monitor", "{}", null);
        String padded = condition("=", source.getId().toString()).replace("\"MONITOR\"", "\" MONITOR \"");
        ActionService.create(action("Recipient", padded), 99);
        ObjectNode exported = ExportImportService.export();
        assertEquals("Source monitor", Json.mapper().readTree(exported.path("actions").get(0)
                .path("conditionJson").asText()).get(0).path("value").asText());
        clearRows();
        legacyMonitor("Unrelated", "{}", null);
        assertEquals(2, ExportImportService.importDocument(exported, 99).getCreated());
        Monitor destination = namedMonitor("Source monitor");
        assertNotEquals(source.getId(), destination.getId());
        assertEquals(destination.getId().toString(), Json.mapper().readTree(namedAction("Recipient")
                .getConditionJson()).get(0).path("value").asText());
    }

    @Test
    void whitespacePaddedMonitorConditionFieldCannotBypassAmbiguityGuard() {
        Monitor intended = legacyMonitor(" Pager", "{}", null);
        legacyMonitor("Pager", "{}", null);
        ActionService.create(action("Recipient", condition("!=", intended.getId().toString())
                .replace("\"MONITOR\"", "\" MONITOR \"")), 99);
        ObjectNode exported = ExportImportService.export();
        exported.remove("monitors");
        String before = snapshot();
        var result = ExportImportService.importDocument(exported, 99);
        assertCollisionSkip(result, "Recipient");
        assertEquals(before, snapshot());
    }

    @Test
    void numericLookingUnresolvedNameCannotBorrowDestinationIdDuringUnchangedComparison() {
        Monitor actual = legacyMonitor("Actual", "{}", null);
        ActionService.create(action("Recipient", condition("=", actual.getId().toString())), 99);
        ObjectNode document = document();
        document.putArray("actions").add(actionEntry("Recipient", condition("=", actual.getId().toString())));
        String before = snapshot();
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(1, result.getSkipped());
        assertCollisionSkip(result, "Recipient");
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedActionWithAmbiguousMonitorCannotAuthorizeEscalationDependents(boolean reversed) {
        Monitor intended = legacyMonitor(" Pager", "{}", null);
        legacyMonitor("Pager", "{}", null);
        ActionService.create(action("Recipient", condition("=", intended.getId().toString())), 99);
        ObjectNode exported = ExportImportService.export();
        exported.remove("monitors");
        ((com.fasterxml.jackson.databind.node.ArrayNode) exported.path("actions"))
                .add(actionEntry("Escalating recipient", "[]").put("escalateToActionName", "Recipient"));
        if (reversed) reverse(exported);
        String before = snapshot();
        var result = ExportImportService.importDocument(exported, 99);
        assertEquals(2, result.getSkipped());
        assertEquals(0, result.getCreated());
        assertCollisionSkip(result, "Recipient");
        assertNull(namedAction("Escalating recipient"));
        assertEquals(before, snapshot());
    }
}
