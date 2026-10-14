/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionConditionMatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/** Preserve IN members ignored by runtime without hiding real unresolved monitor names. */
class ActionMonitorIgnoredValuesImportPersistenceTest extends MonitorIdentityDatabaseTestSupport {
    static Stream<Arguments> ignoredMembersAndOrders() {
        return Stream.of("null", "{}", "[]", "\"\"", "\"  \"")
                .flatMap(value -> Stream.of(false, true).map(reversed -> Arguments.of(value, reversed)));
    }

    @ParameterizedTest
    @MethodSource("ignoredMembersAndOrders")
    void unchangedTargetAllowsEscalationInEitherOrder(String ignoredJson, boolean reversed) throws Exception {
        Monitor intended = MonitorService.create(monitor("Pager", "{}", null), 99);
        Monitor other = MonitorService.create(monitor("Other", "{}", null), 99);
        ArrayNode values = Json.mapper().createArrayNode();
        JsonNode ignored = Json.mapper().readTree(ignoredJson);
        if (reversed) values.add(intended.getId()).add(ignored);
        else values.add(ignored).add(intended.getId());
        Action target = ActionService.create(action("Recipient", inCondition(values)), 99);
        assertRouting(target, intended, other);
        String targetBefore = Json.write(ActionRepository.getAction(target.getId()));
        ObjectNode document = ExportImportService.export();
        document.remove("monitors");
        ((ArrayNode) document.path("actions")).add(actionEntry("Escalating", "[]")
                .put("escalateToActionName", "Recipient"));
        if (reversed) reverse(document);

        var result = ExportImportService.importDocument(document, 99);
        assertEquals(1, result.getCreated(), Json.write(result));
        assertEquals(0, result.getUpdated(), Json.write(result));
        assertEquals("Already matches this server", entry(result, "Recipient").getReason());
        assertEquals(target.getId(), namedAction("Escalating").getEscalateToActionId());
        assertEquals(targetBefore, Json.write(ActionRepository.getAction(target.getId())));
        assertRouting(ActionRepository.getAction(target.getId()), intended, other);
        reverse(document);
        assertUnchanged(document, 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ignoredMembersDoNotHideMissingMonitorNames(boolean reversed) throws Exception {
        Monitor intended = MonitorService.create(monitor("Pager", "{}", null), 99);
        Monitor other = MonitorService.create(monitor("Other", "{}", null), 99);
        Action target = ActionService.create(action("Recipient", condition("IN", intended.getId().toString())), 99);
        String targetBefore = Json.write(ActionRepository.getAction(target.getId()));
        ArrayNode values = (ArrayNode) Json.mapper().readTree("[null,{},[],\"\",\"  \",\"Pager\",\"Missing\"]");
        ObjectNode document = document();
        document.putArray("actions")
                .add(actionEntry("Recipient", inCondition(values)))
                .add(actionEntry("Escalating", "[]").put("escalateToActionName", "Recipient"))
                .add(actionEntry("Independent", "[]"));
        if (reversed) reverse(document);

        var result = ExportImportService.importDocument(document, 99);
        assertEquals(1, result.getCreated(), Json.write(result));
        assertEquals(0, result.getUpdated());
        assertEquals(2, result.getSkipped());
        assertMissingTarget(result);
        assertNotNull(namedAction("Independent"));
        assertNull(namedAction("Escalating"));
        assertEquals(targetBefore, Json.write(ActionRepository.getAction(target.getId())));
        assertRouting(ActionRepository.getAction(target.getId()), intended, other);

        String beforeRetry = snapshot();
        reverse(document);
        var retry = ExportImportService.importDocument(document, 99);
        assertEquals(0, retry.getCreated());
        assertEquals(0, retry.getUpdated());
        assertEquals(3, retry.getSkipped());
        assertMissingTarget(retry);
        assertEquals(beforeRetry, snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allIgnoredMembersNeverWidenConditionOnCreateUpdateOrRetry(boolean reversed) throws Exception {
        Monitor monitor = MonitorService.create(monitor("Pager", "{}", null), 99);
        ArrayNode values = (ArrayNode) Json.mapper().readTree(reversed
                ? "[\"  \",\"\",[],{},null]" : "[null,{},[],\"\",\"  \"]");
        String condition = inCondition(values);
        ObjectNode document = document();
        ObjectNode imported = actionEntry("Recipient", condition);
        document.putArray("actions").add(imported);
        assertEquals(1, ExportImportService.importDocument(document, 99).getCreated());
        assertEquals(condition, namedAction("Recipient").getConditionJson());
        assertFalse(matches(namedAction("Recipient"), monitor.getId()));
        assertUnchanged(document, 1);

        imported.put("description", "Changed policy");
        assertEquals(1, ExportImportService.importDocument(document, 99).getUpdated());
        assertEquals(condition, namedAction("Recipient").getConditionJson());
        assertFalse(matches(namedAction("Recipient"), monitor.getId()));
        assertUnchanged(document, 1);
        ObjectNode exported = ExportImportService.export();
        exported.remove("monitors");
        assertUnchanged(exported, 1);
    }

    private static String inCondition(ArrayNode values) {
        ObjectNode row = Json.mapper().createObjectNode().put("field", " MONITOR ").put("operator", "IN");
        row.set("value", values);
        return Json.write(Json.mapper().createArrayNode().add(row));
    }

    private static ExportImportService.ImportEntry entry(ExportImportService.ImportResult result, String name) {
        return result.getEntries().stream().filter(entry -> name.equals(entry.getName())).findFirst().orElseThrow();
    }

    private static void assertMissingTarget(ExportImportService.ImportResult result) {
        var recipient = entry(result, "Recipient");
        assertEquals("SKIPPED", recipient.getOutcome());
        assertTrue(recipient.getReason().contains("do not exist on this server: Missing"), recipient.getReason());
        assertTrue(entry(result, "Escalating").getReason().contains("referenced entry was skipped"));
    }

    private static void assertUnchanged(ObjectNode document, int entries) {
        String before = snapshot();
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(0, result.getCreated(), Json.write(result));
        assertEquals(0, result.getUpdated(), Json.write(result));
        assertEquals(entries, result.getSkipped(), Json.write(result));
        assertTrue(result.getEntries().stream().allMatch(entry -> "Already matches this server".equals(entry.getReason())),
                Json.write(result));
        assertEquals(before, snapshot());
    }

    private static void assertRouting(Action action, Monitor intended, Monitor other) {
        assertTrue(matches(action, intended.getId()));
        assertFalse(matches(action, other.getId()));
    }

    private static boolean matches(Action action, int monitorId) {
        AlertPayload payload = mock(AlertPayload.class);
        when(payload.getMonitorId()).thenReturn(monitorId);
        return ActionConditionMatcher.matches(action, payload);
    }
}
