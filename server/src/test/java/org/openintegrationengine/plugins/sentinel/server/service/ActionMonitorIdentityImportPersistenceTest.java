/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionConditionMatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/** Real persistence and runtime routing regressions for action imports and monitor-name aliases. */
class ActionMonitorIdentityImportPersistenceTest extends MonitorIdentityDatabaseTestSupport {
    @Test
    void uneditedLegacyExportReplacesDanglingDestinationReferenceAndRetriesWithoutWrites() throws Exception {
        Monitor source = legacyMonitor(" 1 ", "{}", null);
        ActionService.create(action("Recipient", condition("=", source.getId().toString())), 99);
        ObjectNode exported = ExportImportService.export();
        clearRows();
        Monitor destination = MonitorService.create(monitor("1", "{}", null), 99);
        assertNotEquals(source.getId(), destination.getId());
        Action stale = ActionService.create(action("Recipient", condition("=", source.getId().toString())), 99);
        assertFalse(matches(stale, destination.getId()));

        var result = ExportImportService.importDocument(exported, 99);
        Action persisted = ActionRepository.getAction(stale.getId());
        var recipient = result.getEntries().stream().filter(entry -> "Recipient".equals(entry.getName()))
                .findFirst().orElseThrow();
        assertAll(
                () -> assertEquals("UPDATED", recipient.getOutcome(), Json.write(result)),
                () -> assertEquals(condition("=", destination.getId().toString()), persisted.getConditionJson()),
                () -> assertTrue(matches(persisted, destination.getId())));
        assertUnchangedRetry(exported, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "!=", "IN"})
    void paddedFieldAndAliasKeepRuntimeMeaningAndRetryWithoutWrites(String operator) {
        Monitor intended = legacyMonitor("Pager", "{}", null);
        Monitor other = legacyMonitor("Other", "{}", null);
        ObjectNode document = document();
        document.putArray("actions").add(actionEntry("Recipient", condition(operator, " pAgEr ")
                .replace("\"MONITOR\"", "\" MONITOR \"")));
        assertEquals(1, ExportImportService.importDocument(document, 99).getCreated());
        assertRouting(namedAction("Recipient"), operator, intended, other);
        assertUnchangedRetry(document, 1);
    }

    @ParameterizedTest
    @CsvSource({"=,false", "=,true", "!=,false", "!=,true", "IN,false", "IN,true"})
    void numericMonitorNamesRepairDanglingConditionIds(String operator, boolean alias) {
        Monitor deleted = legacyMonitor("Deleted monitor", "{}", null);
        Action existing = ActionService.create(action("Recipient", condition(operator, deleted.getId().toString())), 99);
        MonitorRepository.deleteMonitor(deleted.getId());
        Monitor intended = MonitorService.create(monitor(deleted.getId().toString(), "{}", null), 99);
        Monitor other = legacyMonitor("Other", "{}", null);
        assertNotEquals(deleted.getId(), intended.getId());
        assertEquals("!=".equals(operator), matches(existing, intended.getId()),
                "The old numeric ID has different routing behavior from the intended monitor");
        String reference = alias ? " " + intended.getName() + " " : intended.getName();
        ObjectNode document = document();
        ObjectNode imported = actionEntry("Recipient", condition(operator, reference));
        document.putArray("actions").add(imported);

        var result = ExportImportService.importDocument(document, 99);
        Action persisted = ActionRepository.getAction(existing.getId());
        assertAll(
                () -> assertEquals(1, result.getUpdated(), Json.write(result)),
                () -> assertEquals(condition(operator, intended.getId().toString()), persisted.getConditionJson()),
                () -> assertRouting(persisted, operator, intended, other));
        assertUnchangedRetry(document, 1);
        imported.put("conditionJson", condition(operator, alias ? intended.getName() : " " + intended.getName() + " "));
        assertUnchangedRetry(document, 1);
    }

    @ParameterizedTest
    @CsvSource({"=,false", "=,true", "!=,false", "!=,true", "IN,false", "IN,true"})
    void monitorNamesRepairNoncanonicalStoredIdsWhoseRuntimeMeaningDiffers(String operator, boolean alias) {
        Monitor intended = legacyMonitor("Pager", "{}", null);
        Monitor other = legacyMonitor("Other", "{}", null);
        Action existing = ActionService.create(action("Recipient", condition(operator, "0" + intended.getId())), 99);
        assertEquals("!=".equals(operator), matches(existing, intended.getId()),
                "Runtime monitor matching compares ID strings rather than parsing integers");
        ObjectNode document = document();
        ObjectNode imported = actionEntry("Recipient", condition(operator, alias ? " pAgEr " : "Pager"));
        document.putArray("actions").add(imported);

        var result = ExportImportService.importDocument(document, 99);
        Action persisted = ActionRepository.getAction(existing.getId());
        assertAll(
                () -> assertEquals(1, result.getUpdated(), Json.write(result)),
                () -> assertEquals(condition(operator, intended.getId().toString()), persisted.getConditionJson()),
                () -> assertRouting(persisted, operator, intended, other));
        assertUnchangedRetry(document, 1);
        imported.put("conditionJson", condition(operator, alias ? "Pager" : " pAgEr "));
        assertUnchangedRetry(document, 1);
    }

    @ParameterizedTest
    @CsvSource({"=,false", "=,true", "!=,false", "!=,true", "IN,false", "IN,true"})
    void runtimeEquivalentNumericAndPaddedIdsRemainUnchanged(String operator, boolean numeric) {
        Monitor intended = legacyMonitor("Pager", "{}", null);
        Monitor other = legacyMonitor("Other", "{}", null);
        ArrayNode storedRows = Json.mapper().createArrayNode();
        ObjectNode storedRow = storedRows.addObject().put("field", "MONITOR").put("operator", operator);
        if ("IN".equals(operator)) {
            ArrayNode values = storedRow.putArray("value");
            if (numeric) values.add(intended.getId());
            else values.add(" " + intended.getId() + " ");
        } else if (numeric) {
            storedRow.put("value", intended.getId());
        } else {
            storedRow.put("value", " " + intended.getId() + " ");
        }
        Action existing = ActionService.create(action("Recipient", Json.write(storedRows)), 99);
        assertRouting(existing, operator, intended, other);
        ObjectNode document = ExportImportService.export();
        document.remove("monitors");
        assertUnchangedRetry(document, 1);
        ((ObjectNode) document.path("actions").get(0)).put("conditionJson", condition(operator, " pAgEr "));
        assertUnchangedRetry(document, 1);
        assertRouting(ActionRepository.getAction(existing.getId()), operator, intended, other);
    }

    @Test
    void conditionIdentityRepairKeepsResolvedEscalationAliasAndRetriesWithoutWrites() {
        Monitor intended = legacyMonitor("Pager", "{}", null);
        Action target = ActionService.create(action("Fallback", "[]"), 99);
        String targetBefore = Json.write(ActionRepository.getAction(target.getId()));
        Action stale = action("Recipient", condition("=", "0" + intended.getId()));
        stale.setEscalateToActionId(target.getId());
        Action existing = ActionService.create(stale, 99);
        assertFalse(matches(existing, intended.getId()));
        ObjectNode document = document();
        document.putArray("actions")
                .add(actionEntry("Recipient", condition("=", " pAgEr "))
                        .put("escalateToActionName", " fAlLbAcK "))
                .add(actionEntry("Fallback", "[]"));

        var result = ExportImportService.importDocument(document, 99);
        assertEquals(1, result.getUpdated());
        assertEquals(1, result.getSkipped());
        Action persisted = ActionRepository.getAction(existing.getId());
        assertEquals(condition("=", intended.getId().toString()), persisted.getConditionJson());
        assertEquals(target.getId(), persisted.getEscalateToActionId());
        assertEquals(targetBefore, Json.write(ActionRepository.getAction(target.getId())));
        assertTrue(matches(persisted, intended.getId()));
        assertUnchangedRetry(document, 2);
        reverse(document);
        assertUnchangedRetry(document, 2);
    }

    @ParameterizedTest
    @CsvSource({"=,false", "=,true", "!=,false", "!=,true", "IN,false", "IN,true"})
    void unresolvedNameMatchingStaleIdCannotBlessUnchangedActionOrEscalationDependent(
            String operator, boolean reversed) {
        Monitor deleted = legacyMonitor("Deleted monitor", "{}", null);
        Action existing = ActionService.create(action("Recipient", condition(operator, deleted.getId().toString())), 99);
        MonitorRepository.deleteMonitor(deleted.getId());
        Monitor other = legacyMonitor("Other", "{}", null);
        String before = Json.write(ActionRepository.getAction(existing.getId()));
        ObjectNode document = document();
        document.putArray("actions")
                .add(actionEntry("Recipient", condition(operator, deleted.getId().toString())))
                .add(actionEntry("Escalating recipient", "[]").put("escalateToActionName", "Recipient"))
                .add(actionEntry("Independent recipient", "[]"));
        if (reversed) reverse(document);

        var result = ExportImportService.importDocument(document, 99);
        assertEquals(1, result.getCreated(), "An independent recipient must still import");
        assertEquals(0, result.getUpdated());
        assertEquals(2, result.getSkipped());
        assertUnresolvedRecipient(result);
        assertNull(namedAction("Escalating recipient"));
        assertNotNull(namedAction("Independent recipient"));
        assertEquals(before, Json.write(ActionRepository.getAction(existing.getId())));
        assertEquals("!=".equals(operator), matches(ActionRepository.getAction(existing.getId()), other.getId()));

        String after = snapshot();
        reverse(document);
        var retry = ExportImportService.importDocument(document, 99);
        assertEquals(0, retry.getCreated());
        assertEquals(0, retry.getUpdated());
        assertEquals(3, retry.getSkipped());
        assertUnresolvedRecipient(retry);
        assertEquals(after, snapshot());
    }

    @Test
    void aliasUnchangedAndUpdateKeepStoredWebhookSecretsAndClassifyThem() {
        try (MockedStatic<SettingsCrypto> crypto = syntheticCrypto()) {
            Monitor intended = legacyMonitor("Pager", "{}", null);
            Action existing = ActionService.create(webhook(condition("=", intended.getId().toString())), 99);
            String stored = ActionRepository.getAction(existing.getId()).getConfigJson();
            ObjectNode document = ExportImportService.export();
            document.remove("monitors");
            ObjectNode imported = (ObjectNode) document.path("actions").get(0);
            imported.put("conditionJson", condition("=", " pAgEr "));

            var unchanged = ExportImportService.importDocument(document, 99);
            assertEquals("Already matches this server", unchanged.getEntries().get(0).getReason());
            assertKeptSecret(unchanged);
            assertEquals(stored, ActionRepository.getAction(existing.getId()).getConfigJson());
            imported.put("description", "Changed policy");
            var updated = ExportImportService.importDocument(document, 99);
            assertEquals(1, updated.getUpdated());
            assertKeptSecret(updated);
            assertEquals(stored, ActionRepository.getAction(existing.getId()).getConfigJson());
            assertTrue(matches(ActionRepository.getAction(existing.getId()), intended.getId()));
            assertUnchangedRetry(document, 1);
        }
    }

    @Test
    void repairingConditionIdentityKeepsStoredWebhookSecretAndClassifiesIt() {
        try (MockedStatic<SettingsCrypto> crypto = syntheticCrypto()) {
            Monitor intended = legacyMonitor("Pager", "{}", null);
            Action existing = ActionService.create(webhook(condition("=", "0" + intended.getId())), 99);
            String stored = ActionRepository.getAction(existing.getId()).getConfigJson();
            assertFalse(matches(existing, intended.getId()));
            ObjectNode document = ExportImportService.export();
            document.remove("monitors");
            ObjectNode repaired = (ObjectNode) document.path("actions").get(0);
            repaired.put("conditionJson", condition("=", " pAgEr "));
            // This is an explicit repair, not an unedited unresolved export.
            repaired.remove("unresolvedMonitorIds");

            var result = ExportImportService.importDocument(document, 99);
            assertEquals(1, result.getUpdated());
            assertKeptSecret(result);
            Action persisted = ActionRepository.getAction(existing.getId());
            assertEquals(stored, persisted.getConfigJson());
            assertEquals(condition("=", intended.getId().toString()), persisted.getConditionJson());
            assertTrue(matches(persisted, intended.getId()));
            assertUnchangedRetry(document, 1);
        }
    }

    private static void assertUnresolvedRecipient(ExportImportService.ImportResult result) {
        var recipient = result.getEntries().stream().filter(entry -> "Recipient".equals(entry.getName()))
                .findFirst().orElseThrow();
        assertEquals("SKIPPED", recipient.getOutcome());
        assertTrue(recipient.getReason().contains("do not exist on this server"), recipient.getReason());
        var dependent = result.getEntries().stream().filter(entry -> "Escalating recipient".equals(entry.getName()))
                .findFirst().orElseThrow();
        assertEquals("SKIPPED", dependent.getOutcome());
        assertTrue(dependent.getReason().contains("referenced entry was skipped"), dependent.getReason());
    }

    private static void assertUnchangedRetry(ObjectNode document, int expectedEntries) {
        String before = snapshot();
        var retry = ExportImportService.importDocument(document, 99);
        assertEquals(0, retry.getCreated());
        assertEquals(0, retry.getUpdated());
        assertEquals(expectedEntries, retry.getSkipped());
        assertTrue(retry.getEntries().stream().allMatch(entry -> "Already matches this server".equals(entry.getReason())),
                Json.write(retry));
        assertEquals(before, snapshot(), "Retries must preserve conditions, secrets and audit stamps");
    }

    private static void assertRouting(Action action, String operator, Monitor intended, Monitor other) {
        assertEquals(!"!=".equals(operator), matches(action, intended.getId()));
        assertEquals("!=".equals(operator), matches(action, other.getId()));
    }

    private static void assertKeptSecret(ExportImportService.ImportResult result) {
        assertEquals(List.of("url"), result.getEntries().get(0).getSecretsKept());
        assertTrue(result.getEntries().get(0).getSecretsRequired().isEmpty());
        assertFalse(Json.write(result).contains("TEST_SECRET"));
    }

    private static Action webhook(String condition) {
        Action action = action("Recipient", condition);
        action.setActionType(ActionType.WEBHOOK);
        action.setConfigJson("{\"url\":\"https://203.0.113.10/hook/TEST_SECRET\"}");
        return action;
    }

    private static MockedStatic<SettingsCrypto> syntheticCrypto() {
        MockedStatic<SettingsCrypto> crypto = mockStatic(SettingsCrypto.class);
        crypto.when(() -> SettingsCrypto.encrypt(anyString())).thenAnswer(i -> "cipher:" + i.getArgument(0));
        crypto.when(() -> SettingsCrypto.decrypt(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).substring(7));
        return crypto;
    }

    private static boolean matches(Action action, int monitorId) {
        AlertPayload payload = mock(AlertPayload.class);
        when(payload.getMonitorId()).thenReturn(monitorId);
        return ActionConditionMatcher.matches(action, payload);
    }
}
