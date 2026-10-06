/* OIE Sentinel. Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;
class CoverageServiceTest {
    private MockedStatic<ScopeResolver> scopes;private MockedStatic<MonitorRepository> monitors;
    private MockedStatic<TriggerStateRepository> states;private MockedStatic<NodeLeaseRepository> nodes;private MockedStatic<SettingsService> settings;
    private final Instant time=Instant.now().minusSeconds(10);
    @BeforeEach void setup() {
        scopes=mockStatic(ScopeResolver.class);monitors=mockStatic(MonitorRepository.class);states=mockStatic(TriggerStateRepository.class);nodes=mockStatic(NodeLeaseRepository.class);settings=mockStatic(SettingsService.class);
        SentinelSettings config=new SentinelSettings();config.setEvaluatorIntervalSeconds(30);settings.when(SettingsService::get).thenReturn(config);
        scopes.when(()->ScopeResolver.resolveScopedChannelSet(any())).thenAnswer(call->resolution("a","b"));
        scopes.when(()->ScopeResolver.scopeExists(any())).thenReturn(true);
        scopes.when(()->ScopeResolver.resolveStartedChannelSet(any())).thenAnswer(call->resolution("a"));
        nodes.when(NodeLeaseRepository::listActiveDeployedChannelIds).thenReturn(Set.of("b"));
        nodes.when(()->NodeLeaseRepository.listActiveConnectorDeployments("b")).thenReturn(Map.of(0,Map.of("remote",time.minusSeconds(10))));
    }
    @AfterEach void close() {settings.close();nodes.close();states.close();monitors.close();scopes.close();}
    private ScopeResolver.ChannelResolution resolution(String... ids) {var r=new ScopeResolver.ChannelResolution();for(String id:ids)r.targets.add(new ScopeResolver.ChannelTarget(id,"Channel "+id));return r;}
    private Monitor monitor(int id,MonitorType type) {var m=new Monitor();m.setId(id);m.setName("monitor "+id);m.setScopeType(ScopeType.ALL);m.setMonitorType(type);m.setEnabled(true);m.setUpdatedTime(time.minusSeconds(50));m.setConfigJson("{}");return m;}
    private TriggerState state(int monitor,String channel,Integer metadata,TriggerStatus status,Instant evaluated) {var s=new TriggerState();s.setMonitorId(monitor);s.setChannelId(channel);s.setMetadataId(metadata);s.setState(status);s.setLastEvaluatedTime(evaluated);return s;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("rows");}
    @SuppressWarnings("unchecked") private Map<String,Object> finding(Map<String,Object> result,String channel,int monitor) {return ((List<Map<String,Object>>)rows(result).stream().filter(r->channel.equals(r.get("channelId"))).findFirst().orElseThrow().get("monitors")).stream().filter(m->Integer.valueOf(monitor).equals(m.get("monitorId"))).findFirst().orElseThrow();}
    private Map<String,Object> build(){return CoverageService.build(id->true,true);}
    @Test void typeSpecificEligibilityIncludesStoppedStateAndSharedRemoteConnectionInventory() {
        monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(monitor(1,MonitorType.CHANNEL_STATE),monitor(2,MonitorType.QUEUE_DEPTH),monitor(3,MonitorType.CONNECTION_STATUS)));
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenReturn(List.of(state(1,"b",null,TriggerStatus.OK,time)));
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(2)).thenReturn(List.of(state(2,"a",null,TriggerStatus.OK,time)));
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(3)).thenReturn(List.of(state(3,"b",0,TriggerStatus.OK,time)));
        var result=build();assertEquals("HEALTHY",finding(result,"b",1).get("status"));assertEquals("INELIGIBLE",finding(result,"b",2).get("status"));assertEquals("HEALTHY",finding(result,"b",3).get("status"));
        assertEquals("UNKNOWN",finding(result,"a",1).get("status"));
    }
    @Test void missingStaleFutureOrPreConfigEvaluationsNeverEstablishHealth() {
        var m=monitor(1,MonitorType.CHANNEL_STATE);monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(m));
        for(Instant evaluated:Arrays.asList(null,time.minusSeconds(500),Instant.now().plusSeconds(60),m.getUpdatedTime().minusSeconds(1))) {
            states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenReturn(List.of(state(1,"a",null,TriggerStatus.OK,evaluated)));
            assertEquals("UNKNOWN",finding(build(),"a",1).get("status"));
        }
    }
    @Test void currentConnectorSetAndDeploymentIdentityAreRequiredForHealth() {
        monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(monitor(1,MonitorType.CONNECTION_STATUS)));
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenReturn(List.of(state(1,"b",0,TriggerStatus.OK,time)));
        nodes.when(()->NodeLeaseRepository.listActiveConnectorDeployments("b")).thenReturn(Map.of(0,Map.of("remote",time),1,Map.of("remote",time)));
        assertEquals("UNKNOWN",finding(build(),"b",1).get("status"));
        nodes.when(()->NodeLeaseRepository.listActiveConnectorDeployments("b")).thenReturn(Map.of(0,Map.of("remote",time.plusSeconds(1))));
        assertEquals("UNKNOWN",finding(build(),"b",1).get("status"));
    }
    @Test void emptyAndFailedInventoryRemainDistinct() {
        monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(monitor(1,MonitorType.CONNECTION_STATUS)));
        nodes.when(()->NodeLeaseRepository.listActiveConnectorDeployments("b")).thenReturn(Map.of());assertEquals("WARMING_UP",finding(build(),"b",1).get("status"));
        nodes.when(()->NodeLeaseRepository.listActiveConnectorDeployments("b")).thenThrow(new RuntimeException("db unavailable"));assertEquals("UNKNOWN",finding(build(),"b",1).get("status"));
        scopes.when(()->ScopeResolver.resolveScopedChannelSet(any())).thenAnswer(call->resolution());assertEquals("AVAILABLE",build().get("inventoryStatus"));
        scopes.when(()->ScopeResolver.resolveScopedChannelSet(any())).thenThrow(new RuntimeException("offline"));assertEquals("UNKNOWN",build().get("inventoryStatus"));
    }
    @Test void restrictionsApplyBeforeRowsEvidenceCountsAndDiagnostics() {
        var m=monitor(1,MonitorType.CHANNEL_STATE);monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(m));
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenReturn(List.of(state(1,"b",null,TriggerStatus.PROBLEM,time)));
        var result=CoverageService.build("a"::equals,false);assertEquals(1,rows(result).size());assertFalse(Json.write(result).contains("Channel b"));assertFalse(Json.write(result).contains("\"channelId\":\"b\""));
        scopes.when(()->ScopeResolver.scopeExists(m)).thenReturn(false);assertEquals(List.of(),CoverageService.build("a"::equals,false).get("issues"));assertFalse(((List<?>)build().get("issues")).isEmpty());
    }
    @Test void insufficientDataAndPendingBreachAreNotHealthyAndReadFailureRetainsUnknown() {
        monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(monitor(1,MonitorType.CHANNEL_STATE)));
        var s=state(1,"a",null,TriggerStatus.INSUFFICIENT_DATA,time);states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenReturn(List.of(s));assertEquals("WARMING_UP",finding(build(),"a",1).get("status"));
        s.setState(TriggerStatus.OK);s.setConsecutiveBreachCount(1);assertEquals("WARMING_UP",finding(build(),"a",1).get("status"));
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenThrow(new RuntimeException("read failure"));assertEquals("UNKNOWN",finding(build(),"a",1).get("status"));
    }
    @Test void membershipIsResolvedAgainOnEachRead() {
        var m=monitor(1,MonitorType.CHANNEL_STATE);m.setScopeType(ScopeType.GROUP);m.setScopeId("group");monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(m));
        scopes.when(()->ScopeResolver.resolveScopedChannelSet(m)).thenAnswer(call->resolution("a"));assertEquals("CONFIGURED",rows(build()).get(0).get("status"));
        scopes.when(()->ScopeResolver.resolveScopedChannelSet(m)).thenAnswer(call->resolution("b"));assertEquals("NO_COVERAGE",rows(build()).get(0).get("status"));
    }
    @Test void scopeLookupFailureDoesNotClaimNoCoverageAndDisabledHistoryRemainsVisible() {
        var m=monitor(1,MonitorType.CHANNEL_STATE);m.setEnabled(false);monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(m));
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenReturn(List.of(state(1,"a",null,TriggerStatus.OK,time)));
        assertEquals(time,finding(build(),"a",1).get("latestEvaluatedTime"));assertEquals("INELIGIBLE",finding(build(),"a",1).get("status"));
        scopes.when(()->ScopeResolver.scopeExists(m)).thenThrow(new RuntimeException("group inventory failed"));assertEquals("UNKNOWN",rows(build()).get(0).get("status"));
    }
    @Test void staleEvaluationCannotHideAConfirmedOpenProblemAndClosedReferencesAreUnknown() {
        var m=monitor(1,MonitorType.CHANNEL_STATE);monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(m));
        var s=state(1,"a",null,TriggerStatus.INSUFFICIENT_DATA,time.minusSeconds(500));s.setOpenAlertEventId(42L);
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenReturn(List.of(s));
        try(var alerts=mockStatic(AlertEventRepository.class)) {
            var event=new AlertEvent();event.setId(42L);event.setChannelId("a");event.setMonitorId(1);event.setStatus(AlertStatus.PROBLEM);
            alerts.when(()->AlertEventRepository.getAlertEvent(42L)).thenReturn(event);assertEquals("OPEN_PROBLEM",finding(build(),"a",1).get("status"));
            event.setStatus(AlertStatus.RESOLVED);assertEquals("UNKNOWN",finding(build(),"a",1).get("status"));
        }
    }
    @Test void connectionChannelRollupUsesCurrentChannelTrigger() {
        var m=monitor(1,MonitorType.CONNECTION_STATUS);m.setConfigJson("{\"rollup\":\"CHANNEL\"}");monitors.when(()->MonitorRepository.listMonitors(null,null,null,null)).thenReturn(List.of(m));
        states.when(()->TriggerStateRepository.listTriggerStatesByMonitor(1)).thenReturn(List.of(state(1,"b",null,TriggerStatus.OK,time)));
        assertEquals("HEALTHY",finding(build(),"b",1).get("status"));
    }

}
