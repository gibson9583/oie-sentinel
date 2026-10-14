/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/** A rejected replacement must not authorize consumers of its old persisted definition. */
class RejectedMonitorDependencyImportPersistenceTest extends MonitorIdentityDatabaseTestSupport {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedReplacementBlocksTransitiveChildrenActionsAndEscalationsInEitherOrder(boolean reversed) {
        Monitor parent = MonitorService.create(monitor("Parent", "{\"noDataForSeconds\":300}", null), 99);
        Monitor child = MonitorService.create(monitor("Child", "{}", parent.getId()), 99);
        Monitor leaf = MonitorService.create(monitor("Leaf", "{}", child.getId()), 99);
        ActionService.create(action("Recipient", condition("=", leaf.getId().toString())), 99);
        String originalMonitors = Json.write(MonitorService.list());
        String originalAction = Json.write(namedAction("Recipient"));
        ObjectNode document = document();
        document.putArray("monitors")
                .add(monitorEntry("Parent", "{\"noDataForSeconds\":-1}", null))
                .add(monitorEntry("Child", "{\"noDataForSeconds\":60}", "Parent"))
                .add(monitorEntry("Leaf", "{\"noDataForSeconds\":60}", "Child"));
        document.putArray("actions")
                .add(actionEntry("Recipient", condition("=", "Leaf")))
                .add(actionEntry("Direct parent", condition("=", "Parent")))
                .add(actionEntry("Escalating", "[]").put("escalateToActionName", "Recipient"))
                .add(actionEntry("Independent", "[]"));
        if (reversed) reverse(document);
        var first = ExportImportService.importDocument(document, 99);
        assertEquals(1, first.getCreated(), Json.write(first));
        assertEquals(0, first.getUpdated());
        assertEquals(6, first.getSkipped());
        for (String name : List.of("Parent", "Child", "Leaf", "Recipient", "Direct parent", "Escalating")) {
            assertCollisionSkip(first, name);
        }
        assertEquals(originalMonitors, Json.write(MonitorService.list()));
        assertEquals(originalAction, Json.write(namedAction("Recipient")));
        assertNull(namedAction("Direct parent"));
        assertNull(namedAction("Escalating"));
        assertNotNull(namedAction("Independent"));
        String beforeRetry = snapshot();
        reverse(document);
        var retry = ExportImportService.importDocument(document, 99);
        assertEquals(0, retry.getCreated());
        assertEquals(0, retry.getUpdated());
        assertEquals(beforeRetry, snapshot());
    }

    @ParameterizedTest
    @CsvSource({"definition,false", "definition,true", "missing,false", "missing,true", "cycle,false", "cycle,true"})
    void everyRejectedMonitorEntryIsUnavailableToLaterActionReferences(String failure, boolean reversed) {
        Monitor parent = MonitorService.create(monitor("Parent", "{}", null), 99);
        ObjectNode document = document();
        ObjectNode incoming = monitorEntry("Parent", "{}", null);
        if ("definition".equals(failure)) incoming.put("monitorType", "NOT_A_TYPE");
        if ("missing".equals(failure)) incoming.put("suppressedByMonitorName", "Missing");
        if ("cycle".equals(failure)) incoming.put("suppressedByMonitorName", "Parent");
        document.putArray("monitors").add(incoming).add(monitorEntry("Child", "{}", "Parent"));
        document.putArray("actions").add(actionEntry("Recipient", condition("=", "Parent")));
        if (reversed) reverse(document);
        String before = snapshot();
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(3, result.getSkipped(), Json.write(result));
        assertEquals(before, snapshot());
        assertEquals(parent.getId(), namedMonitor("Parent").getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void correctedRetryCreatesDependentsAndThenConverges(boolean reversed) {
        Monitor parent = MonitorService.create(monitor("Parent", "{}", null), 99);
        ObjectNode document = document();
        ObjectNode incoming = monitorEntry("Parent", "{\"noDataForSeconds\":-1}", null);
        document.putArray("monitors").add(incoming).add(monitorEntry("Child", "{}", "Parent"));
        document.putArray("actions").add(actionEntry("Recipient", condition("IN", "Child", "Parent")));
        if (reversed) reverse(document);
        assertEquals(3, ExportImportService.importDocument(document, 99).getSkipped());
        incoming.put("configJson", "{\"noDataForSeconds\":60}");
        var repaired = ExportImportService.importDocument(document, 99);
        assertEquals(2, repaired.getCreated());
        assertEquals(1, repaired.getUpdated());
        assertEquals(parent.getId(), namedMonitor("Child").getSuppressedByMonitorId());
        assertEquals(condition("IN", namedMonitor("Child").getId().toString(), parent.getId().toString()),
                namedAction("Recipient").getConditionJson());
        assertUnchanged(document, 3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedOrDestinationOnlyParentRemainsUsable(boolean included) {
        Monitor parent = MonitorService.create(monitor("Parent", "{}", null), 99);
        ObjectNode document = document();
        var monitors = document.putArray("monitors").add(monitorEntry("Child", "{}", " PARENT "));
        if (included) monitors.add(monitorEntry("Parent", "{}", null));
        document.putArray("actions").add(actionEntry("Recipient", condition("=", "Parent")));
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(2, result.getCreated());
        assertEquals(parent.getId(), namedMonitor("Child").getSuppressedByMonitorId());
        assertUnchanged(document, included ? 3 : 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulRewireWaitsForDocumentParentInsteadOfRejectingOldCycle(boolean reversed) {
        Monitor second = MonitorService.create(monitor("Second", "{}", null), 99);
        Monitor first = MonitorService.create(monitor("First", "{}", second.getId()), 99);
        ObjectNode document = document();
        document.putArray("monitors")
                .add(monitorEntry("First", "{}", null))
                .add(monitorEntry("Second", "{}", "First"));
        if (reversed) reverse(document);
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(2, result.getUpdated(), Json.write(result));
        assertNull(namedMonitor("First").getSuppressedByMonitorId());
        assertEquals(first.getId(), namedMonitor("Second").getSuppressedByMonitorId());
        assertUnchanged(document, 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void destinationOnlySuppressionBridgeCannotReuseRejectedReplacement(boolean reversed) {
        Monitor parent = MonitorService.create(monitor("Parent", "{}", null), 99);
        Monitor bridge = MonitorService.create(monitor("Bridge", "{}", parent.getId()), 99);
        ActionService.create(action("Recipient", condition("=", bridge.getId().toString())), 99);
        ObjectNode document = document();
        document.putArray("monitors")
                .add(monitorEntry("Parent", "{\"noDataForSeconds\":-1}", null))
                .add(monitorEntry("Child", "{}", "Bridge"));
        document.putArray("actions").add(actionEntry("Recipient", condition("=", "Bridge")))
                .add(actionEntry("Escalating", "[]").put("escalateToActionName", "Recipient"));
        if (reversed) reverse(document);
        String before = snapshot();
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(4, result.getSkipped(), Json.write(result));
        assertCollisionSkip(result, "Recipient");
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void destinationOnlySuppressionBridgeWaitsForSuccessfulRewire(boolean reversed) {
        Monitor last = MonitorService.create(monitor("Last", "{}", null), 99);
        Monitor first = MonitorService.create(monitor("First", "{}", last.getId()), 99);
        Monitor bridge = MonitorService.create(monitor("Bridge", "{}", first.getId()), 99);
        ObjectNode document = document();
        document.putArray("monitors").add(monitorEntry("First", "{}", null))
                .add(monitorEntry("Last", "{}", "Bridge"));
        if (reversed) reverse(document);
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(2, result.getUpdated(), Json.write(result));
        assertNull(namedMonitor("First").getSuppressedByMonitorId());
        assertEquals(bridge.getId(), namedMonitor("Last").getSuppressedByMonitorId());
        assertUnchanged(document, 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removingRejectedParentRepairsChildAndKeepsItsDependentsAvailable(boolean reversed) {
        Monitor parent = MonitorService.create(monitor("Parent", "{}", null), 99);
        Monitor child = MonitorService.create(monitor("Child", "{}", parent.getId()), 99);
        Monitor bridge = MonitorService.create(monitor("Bridge", "{}", child.getId()), 99);
        ObjectNode document = document();
        document.putArray("monitors")
                .add(monitorEntry("Parent", "{\"noDataForSeconds\":-1}", null))
                .add(monitorEntry("Child", "{}", null))
                .add(monitorEntry("Leaf", "{}", "Bridge"));
        document.putArray("actions").add(actionEntry("Recipient", condition("=", "Bridge")));
        if (reversed) reverse(document);
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(1, result.getSkipped(), Json.write(result));
        assertEquals(1, result.getUpdated());
        assertEquals(2, result.getCreated());
        assertNull(namedMonitor("Child").getSuppressedByMonitorId());
        assertEquals(bridge.getId(), namedMonitor("Leaf").getSuppressedByMonitorId());
        assertEquals(condition("=", bridge.getId().toString()), namedAction("Recipient").getConditionJson());
        String before = snapshot();
        reverse(document);
        var retry = ExportImportService.importDocument(document, 99);
        assertEquals(0, retry.getCreated());
        assertEquals(0, retry.getUpdated());
        assertEquals(before, snapshot());
    }

    @Test
    void destinationOnlyLegacyAncestorCollisionDoesNotRebindAnExistingId() {
        Monitor first = legacyMonitor("Parent", "{}", null);
        legacyMonitor(" Parent ", "{}", null);
        Monitor bridge = MonitorService.create(monitor("Bridge", "{}", first.getId()), 99);
        ObjectNode document = document();
        document.putArray("monitors").add(monitorEntry("Child", "{}", "Bridge"));
        document.putArray("actions").add(actionEntry("Recipient", condition("=", "Bridge")));
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(2, result.getCreated(), Json.write(result));
        assertEquals(bridge.getId(), namedMonitor("Child").getSuppressedByMonitorId());
        assertUnchanged(document, 2);
    }

    @ParameterizedTest
    @CsvSource({"destination,false", "destination,true", "document,false", "document,true"})
    void rejectedAmbiguousDocumentAncestorAlsoBlocksDestinationOnlyBridge(String collision, boolean reversed) {
        Monitor parent = legacyMonitor("Parent", "{}", null);
        if ("destination".equals(collision)) legacyMonitor(" Parent ", "{}", null);
        Monitor bridge = MonitorService.create(monitor("Bridge", "{}", parent.getId()), 99);
        ActionService.create(action("Recipient", condition("=", bridge.getId().toString())), 99);
        ObjectNode document = document();
        var monitors = document.putArray("monitors").add(monitorEntry("Parent", "{}", null))
                .add(monitorEntry("Child", "{}", "Bridge"));
        if ("document".equals(collision)) monitors.add(monitorEntry(" PARENT ", "{}", null));
        document.putArray("actions").add(actionEntry("Recipient", condition("=", "Bridge")))
                .add(actionEntry("Escalating", "[]").put("escalateToActionName", "Recipient"));
        if (reversed) reverse(document);
        String before = snapshot();
        var result = ExportImportService.importDocument(document, 99);
        assertEquals("document".equals(collision) ? 5 : 4, result.getSkipped(), Json.write(result));
        assertCollisionSkip(result, "Recipient");
        assertCollisionSkip(result, "Child");
        assertEquals(before, snapshot());
    }

    @Test
    void unexpectedPersistenceFailurePreservesEarlierIndependentWorkAndCanBeRetried() {
        Monitor parent = MonitorService.create(monitor("Parent", "{}", null), 99);
        ObjectNode document = document();
        document.putArray("monitors")
                .add(monitorEntry("Independent", "{}", null))
                .add(monitorEntry("Parent", "{\"noDataForSeconds\":60}", null))
                .add(monitorEntry("Child", "{}", "Parent"));
        document.putArray("actions").add(actionEntry("Recipient", condition("=", "Parent")));
        try (MockedStatic<MonitorRepository> repository = mockStatic(MonitorRepository.class, CALLS_REAL_METHODS)) {
            repository.when(() -> MonitorRepository.updateMonitor(any(Monitor.class)))
                    .thenThrow(new IllegalStateException("injected database failure"));
            assertThrows(IllegalStateException.class, () -> ExportImportService.importDocument(document, 99));
        }
        assertNotNull(namedMonitor("Independent"));
        assertEquals("{}", namedMonitor("Parent").getConfigJson());
        assertNull(namedMonitor("Child"));
        assertNull(namedAction("Recipient"));
        var retry = ExportImportService.importDocument(document, 99);
        assertEquals(2, retry.getCreated());
        assertEquals(1, retry.getUpdated());
        assertEquals(parent.getId(), namedMonitor("Child").getSuppressedByMonitorId());
        assertUnchanged(document, 4);
    }

    private static void assertUnchanged(ObjectNode document, int entries) {
        String before = snapshot();
        reverse(document);
        var result = ExportImportService.importDocument(document, 99);
        assertEquals(entries, result.getSkipped(), Json.write(result));
        assertTrue(result.getEntries().stream().allMatch(e -> "Already matches this server".equals(e.getReason())));
        assertEquals(before, snapshot());
    }
}
