package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import com.fasterxml.jackson.databind.node.*;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ImportReviewServiceTest {
    MockedStatic<ConfigurationImportLock> importLock;
    MockedStatic<MonitorRepository> monitorRows;
    MockedStatic<ActionRepository> actionRows;
    MockedStatic<MaintenanceWindowRepository> windowRows;
    MockedStatic<MonitorService> monitors;
    MockedStatic<ActionService> actions;
    MockedStatic<MaintenanceWindowService> windows;
    MockedStatic<ScopeResolver> scopes;
    List<Monitor> storedMonitors = new ArrayList<>();
    List<Action> storedActions = new ArrayList<>();
    int writes;

    @BeforeEach void setup() {
        importLock = mockStatic(ConfigurationImportLock.class);
        importLock.when(() -> ConfigurationImportLock.withLock(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        monitorRows = mockStatic(MonitorRepository.class);
        actionRows = mockStatic(ActionRepository.class);
        windowRows = mockStatic(MaintenanceWindowRepository.class);
        monitorRows.when(() -> MonitorRepository.listMonitors(null, null, null, null)).thenReturn(List.of());
        actionRows.when(() -> ActionRepository.listActions(null)).thenAnswer(call -> new ArrayList<>(storedActions));
        windowRows.when(MaintenanceWindowRepository::listMaintenanceWindows).thenReturn(List.of());
        monitors = mockStatic(MonitorService.class, CALLS_REAL_METHODS);
        actions = mockStatic(ActionService.class, CALLS_REAL_METHODS);
        windows = mockStatic(MaintenanceWindowService.class, CALLS_REAL_METHODS);
        scopes = mockStatic(ScopeResolver.class);
        monitors.when(MonitorService::list).thenAnswer(call -> new ArrayList<>(storedMonitors));
        actions.when(ActionService::list).thenAnswer(call -> storedActions.stream().map(ActionService::redact).toList());
        windows.when(MaintenanceWindowService::list).thenReturn(List.of());
        ChannelInfo target = new ChannelInfo(); target.setChannelId("target"); target.setName("Target");
        scopes.when(ScopeResolver::listChannels).thenReturn(List.of(target));
        scopes.when(ScopeResolver::listGroups).thenReturn(List.of());
        scopes.when(ScopeResolver::listTags).thenReturn(List.of());
        monitors.when(() -> MonitorService.create(any(), anyInt())).thenAnswer(call -> {
            Monitor value = call.getArgument(0); value.setId(storedMonitors.size()+1); value.setCreatedTime(Instant.now());
            storedMonitors.add(value); writes++; return value;
        });
        actions.when(() -> ActionService.create(any(), anyInt())).thenAnswer(call -> {
            Action value = call.getArgument(0); value.setId(storedActions.size()+1); value.setCreatedTime(Instant.now());
            storedActions.add(value); writes++; return value;
        });
    }
    @AfterEach void close() { scopes.close(); windows.close(); actions.close(); monitors.close(); windowRows.close(); actionRows.close(); monitorRows.close(); importLock.close(); }

    ObjectNode request(String document) { ObjectNode result = Json.mapper().createObjectNode(); result.set("document", Json.read(document, ObjectNode.class)); return result; }
    String monitor(String name, String parent, String scope) {
        return "{\"name\":\""+name+"\",\"monitorType\":\"INACTIVITY\",\"severity\":\"HIGH\",\"scopeType\":\""+(scope==null?"ALL":"CHANNEL")+"\",\"scopeId\":"+(scope==null?"null":"\""+scope+"\"")+",\"enabled\":true,\"configJson\":\"{}\",\"minConsecutiveBreaches\":2,\"suppressedByMonitorName\":"+(parent==null?"null":"\""+parent+"\"")+"}";
    }
    void bind(ObjectNode request, ImportReviewService.Preview preview) {
        request.put("targetFingerprint", preview.targetFingerprint()); request.put("planHash", preview.planHash());
    }

    @Test void previewIsReadOnlyMapsReferencesAndDependencyOrderThenRerunIsUnchanged() {
        ObjectNode request=request("{\"schemaVersion\":1,\"monitors\":["+monitor("Child","Parent","source")+","+monitor("Parent",null,null)+"]}");
        request.set("mappings", Json.read("{\"CHANNEL\":{\"source\":\"target\"}}",ObjectNode.class));
        var preview=ImportReviewService.preview(request);
        assertEquals(0,writes); assertEquals(2,preview.plan().getCreated());
        assertEquals("source",preview.remappings().get(0).source()); assertEquals("target",preview.remappings().get(0).target());
        assertFalse(Json.write(preview).contains("suppressedByMonitorId"));
        bind(request,preview);
        var applied=ImportReviewService.apply(request,10);
        assertFalse(applied.stale()); assertEquals(2,applied.receipt().getCreated()); assertEquals(2,writes);
        assertEquals("Parent",storedMonitors.get(0).getName());
        assertEquals(storedMonitors.get(0).getId(),storedMonitors.get(1).getSuppressedByMonitorId());
        assertEquals("target",storedMonitors.get(1).getScopeId());
        var again=ImportReviewService.preview(request); assertEquals(2,again.plan().getSkipped()); assertEquals(0,again.plan().getUpdated());
        bind(request,again); ImportReviewService.apply(request,10); assertEquals(2,writes);
    }
    @Test void cyclesMissingScopesAndDuplicateNamesAreShownBeforeWrites() {
        ObjectNode request=request("{\"schemaVersion\":1,\"monitors\":["+monitor("A","B",null)+","+monitor("B","A",null)+","+monitor("Missing",null,"gone")+","+monitor("Duplicate",null,null)+","+monitor(" duplicate ",null,null)+"]}");
        var preview=ImportReviewService.preview(request); assertEquals(5,preview.plan().getSkipped()); assertEquals(0,writes);
        assertTrue(Json.write(preview).contains("circular")); assertTrue(Json.write(preview).contains("Unresolved CHANNEL")); assertTrue(Json.write(preview).contains("Duplicate name"));
        bind(request,preview); assertEquals(0,ImportReviewService.apply(request,10).receipt().getCreated()); assertEquals(0,writes);
    }
    @Test void targetAndInputChangesCannotApplyAnOldPreview() {
        ObjectNode request=request("{\"schemaVersion\":1,\"monitors\":["+monitor("A",null,null)+"]}");
        var preview=ImportReviewService.preview(request); bind(request,preview);
        Monitor concurrent=Json.read(monitor("Concurrent",null,null),Monitor.class); concurrent.setId(50); storedMonitors.add(concurrent);
        assertTrue(ImportReviewService.apply(request,10).stale()); assertEquals(0,writes);
        bind(request,ImportReviewService.preview(request)); ((ObjectNode)request.path("document").path("monitors").get(0)).put("description","Changed after preview");
        assertTrue(ImportReviewService.apply(request,10).stale()); assertEquals(0,writes);
    }
    @Test void missingSecretsNeverCreateAnUncredentialedActionAndConfigValuesNeverEnterDiffs() {
        Action webhook=new Action(); webhook.setName("Hook"); webhook.setActionType(ActionType.WEBHOOK); webhook.setOperationMode(OperationMode.ON_PROBLEM);
        webhook.setConfigJson("{\"url\":\"https://example.com\",\"headers\":{\"Authorization\":\""+ActionService.REDACTED+"\"}}");
        ObjectNode request=request("{\"schemaVersion\":1}"); ((ObjectNode)request.get("document")).putArray("actions").add(Json.mapper().valueToTree(webhook));
        var preview=ImportReviewService.preview(request); assertEquals(1,preview.plan().getSkipped()); assertEquals(List.of("headers.Authorization"),preview.plan().getEntries().get(0).getSecretsRequired());
        webhook.setConfigJson("{\"url\":\"https://example.com\",\"headers\":{\"Authorization\":\"never-emit-secret\"},\"body\":\"also-secret\"}");
        ((ObjectNode)request.get("document")).set("actions",Json.mapper().valueToTree(List.of(webhook)));
        preview=ImportReviewService.preview(request); assertEquals(1,preview.plan().getCreated());
        assertFalse(Json.write(preview).contains("never-emit-secret")); assertFalse(Json.write(preview).contains("also-secret")); assertEquals(0,writes);
    }
    @Test void partialApplyPreservesReceiptsAndReportsAmbiguousCompletion() {
        ObjectNode request=request("{\"schemaVersion\":1,\"monitors\":["+monitor("A",null,null)+","+monitor("B",null,null)+"]}");
        var preview=ImportReviewService.preview(request); bind(request,preview);
        monitors.when(() -> MonitorService.create(argThat(value -> value.getName().equals("B")), anyInt())).thenThrow(new RuntimeException("SQL/credential details"));
        var applied=ImportReviewService.apply(request,10); assertFalse(applied.stale());
        assertEquals(1,applied.receipt().getCreated()); assertEquals(1,applied.receipt().getUncertain()); assertEquals(2,applied.receipt().getEntries().size());
        assertFalse(Json.write(applied).contains("SQL/credential")); assertEquals(1,writes);
        var reconciled=ImportReviewService.preview(request); assertEquals(1,reconciled.plan().getSkipped()); assertEquals(1,reconciled.plan().getCreated());
    }
    @Test void mapsGroupTagConditionsAndReportsSafeNumericConfigDiffs() {
        ChannelGroupInfo group = new ChannelGroupInfo(); group.setId("target-group"); group.setName("Group"); group.setChannelIds(List.of("target"));
        TagInfo tag = new TagInfo(); tag.setId("target-tag"); tag.setName("Tag"); tag.setChannelIds(List.of("target"));
        scopes.when(ScopeResolver::listGroups).thenReturn(List.of(group)); scopes.when(ScopeResolver::listTags).thenReturn(List.of(tag));
        ObjectNode request=request("{\"schemaVersion\":1,\"monitors\":["+monitor("A",null,null)+"]}");
        ObjectNode item=(ObjectNode)request.path("document").path("monitors").get(0);
        item.put("scopeType","GROUP"); item.put("scopeId","source-group"); item.put("configJson","{\"noDataForSeconds\":300,\"privateValue\":\"never-diff\"}");
        Action action=new Action(); action.setName("Mail"); action.setActionType(ActionType.EMAIL); action.setOperationMode(OperationMode.BOTH); action.setConfigJson("{\"to\":\"team@example.com\"}");
        action.setConditionJson("[{\"field\":\"CHANNEL_TAG\",\"operator\":\"IN\",\"value\":[\"source-tag\"]}]");
        ((ObjectNode)request.get("document")).putArray("actions").add(Json.mapper().valueToTree(action));
        request.set("mappings",Json.read("{\"GROUP\":{\"source-group\":\"target-group\"},\"TAG\":{\"source-tag\":\"target-tag\"}}",ObjectNode.class));
        var preview=ImportReviewService.preview(request);
        assertEquals(2,preview.remappings().size()); assertEquals(2,preview.plan().getCreated());
        assertTrue(preview.plan().getEntries().get(0).getFieldDiffs().containsKey("configJson.noDataForSeconds"));
        assertFalse(Json.write(preview).contains("never-diff"));
        bind(request,preview); ImportReviewService.apply(request,10);
        assertEquals("target-group",storedMonitors.get(0).getScopeId()); assertTrue(storedActions.get(0).getConditionJson().contains("target-tag"));
    }

    @Test void credentialRotationInvalidatesPreviewEvenWithAnUnchangedAuditTimestamp() {
        Action existing=new Action(); existing.setId(7); existing.setName("Mail"); existing.setActionType(ActionType.EMAIL); existing.setOperationMode(OperationMode.BOTH); existing.setConfigJson("{\"to\":\"team@example.com\",\"secretAccessKey\":\"stored-first\"}"); storedActions.add(existing);
        ObjectNode request=request("{\"schemaVersion\":1,\"monitors\":["+monitor("A",null,null)+"]}");
        var preview=ImportReviewService.preview(request); bind(request,preview);
        existing.setConfigJson("{\"to\":\"team@example.com\",\"secretAccessKey\":\"stored-second\"}");
        var stale=ImportReviewService.apply(request,10); assertTrue(stale.stale()); assertEquals(0,writes);
        assertFalse(Json.write(stale).contains("stored-second")); assertFalse(Json.write(stale).contains("stored-first"));
    }

    @Test void malformedDocumentIsRejectedBeforeInventoryOrImportOwnership() {
        ObjectNode bad=request("{\"schemaVersion\":1,\"actions\":{}}");
        assertThrows(IllegalArgumentException.class,()->ImportReviewService.preview(bad));
        assertThrows(IllegalArgumentException.class,()->ImportReviewService.apply(bad,10));
        assertThrows(IllegalArgumentException.class,()->ExportImportService.importDocument(bad.get("document"),10));
        importLock.verifyNoInteractions(); scopes.verifyNoInteractions(); assertEquals(0,writes);
    }

    @Test void failedInventoryReadNeverLooksLikeAnEmptyValidatedTarget() {
        ObjectNode request=request("{\"schemaVersion\":1,\"monitors\":[]}");
        scopes.when(ScopeResolver::listChannels).thenThrow(new RuntimeException("inventory unavailable"));
        assertThrows(RuntimeException.class,()->ImportReviewService.preview(request)); assertEquals(0,writes);
    }
}
