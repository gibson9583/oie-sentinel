/* OIE Sentinel. Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Read-only configured membership, evaluator eligibility and persisted evaluation evidence. */
public final class CoverageService {
    private CoverageService() { }
    private static final class EmptyConnectorInventory extends RuntimeException { }
    public static Map<String,Object> build(Predicate<String> visible, boolean unrestricted) {
        Instant now=Instant.now();
        long freshnessSeconds=Math.max(120L,3L*SettingsService.get().getEvaluatorIntervalSeconds());
        Monitor inventory=new Monitor();inventory.setScopeType(ScopeType.ALL);
        ScopeResolver.ChannelResolution channels;
        try {channels=ScopeResolver.resolveScopedChannelSet(inventory);}
        catch(Exception e) {return Map.of("rows",List.of(),"inventoryStatus","UNKNOWN","issues",List.of("Channel inventory unavailable"));}
        Map<String,Map<String,Object>> rows=new LinkedHashMap<>();
        for(ScopeResolver.ChannelTarget channel:channels.targets) if(visible.test(channel.channelId)) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("channelId",channel.channelId);row.put("channelName",channel.channelName);
            row.put("monitors",new ArrayList<Map<String,Object>>());row.put("scopeUnknown",false);rows.put(channel.channelId,row);
        }
        for(String channelId:channels.unresolvedChannelIds) if(visible.test(channelId) && !rows.containsKey(channelId)) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("channelId",channelId);row.put("channelName","(name unavailable)");
            row.put("monitors",new ArrayList<Map<String,Object>>());row.put("scopeUnknown",true);rows.put(channelId,row);
        }
        List<String> issues=new ArrayList<>();
        for(Monitor monitor:MonitorRepository.listMonitors(null,null,null,null)) {
            ScopeResolver.ChannelResolution scoped;
            try {
                if(!ScopeResolver.scopeExists(monitor)) {
                    if(unrestricted) issues.add("Monitor #"+monitor.getId()+" ("+monitor.getName()+"): scope no longer resolves");
                    continue;
                }
                scoped=ScopeResolver.resolveScopedChannelSet(monitor);
            } catch(Exception e) {
                // Unknown membership cannot justify "no coverage" or "healthy" on any visible channel.
                rows.values().forEach(row->row.put("scopeUnknown",true));
                if(unrestricted) issues.add("Monitor #"+monitor.getId()+": scope inventory unavailable");
                continue;
            }
            Set<String> configured=new HashSet<>();
            scoped.targets.forEach(t->configured.add(t.channelId));configured.addAll(scoped.unresolvedChannelIds);
            configured.retainAll(rows.keySet());
            if(configured.isEmpty()) continue;
            Set<String> eligible=new HashSet<>(),runtimeUnknown=new HashSet<>(scoped.unresolvedChannelIds);
            try {
                if(monitor.getMonitorType()==MonitorType.CHANNEL_STATE) eligible.addAll(configured);
                else if(monitor.getMonitorType()==MonitorType.CONNECTION_STATUS) eligible.addAll(NodeLeaseRepository.listActiveDeployedChannelIds());
                else {
                    ScopeResolver.ChannelResolution runtime=ScopeResolver.resolveStartedChannelSet(monitor);
                    runtime.targets.forEach(t->eligible.add(t.channelId));runtimeUnknown.addAll(runtime.unresolvedChannelIds);
                }
            } catch(Exception e) {runtimeUnknown.addAll(configured);}
            List<TriggerState> persisted;
            boolean stateReadFailed=false;
            try {persisted=TriggerStateRepository.listTriggerStatesByMonitor(monitor.getId());}
            catch(Exception e) {persisted=List.of();stateReadFailed=true;}
            for(String channelId:configured) {
                List<TriggerState> states=persisted.stream().filter(s->channelId.equals(s.getChannelId())).toList();
                Map<String,Object> finding=new LinkedHashMap<>();
                finding.put("monitorId",monitor.getId());finding.put("monitorName",monitor.getName());finding.put("monitorType",monitor.getMonitorType());
                finding.put("enabled",monitor.isEnabled());finding.put("eligible",eligible.contains(channelId) && !runtimeUnknown.contains(channelId));
                String status;String reason;
                List<Map<String,Object>> observations=new ArrayList<>();
                for(TriggerState state:states) {
                    Map<String,Object> observation=new LinkedHashMap<>();observation.put("metadataId",state.getMetadataId());
                    observation.put("state",state.getState());observation.put("lastEvaluatedTime",state.getLastEvaluatedTime());
                    observation.put("consecutiveBreaches",state.getConsecutiveBreachCount());observation.put("current",false);observations.add(observation);
                }
                if(monitor.getMonitorType()==null) {status="UNKNOWN";reason="Monitor type unavailable";}
                else if(!monitor.isEnabled()) {status="INELIGIBLE";reason="Monitor disabled";}
                else if(runtimeUnknown.contains(channelId)) {status="UNKNOWN";reason="Runtime inventory unavailable";}
                else if(!eligible.contains(channelId)) {status="INELIGIBLE";reason=monitor.getMonitorType()==MonitorType.CONNECTION_STATUS?"Not in active deployment inventory":"Channel is not started";}
                else if(stateReadFailed) {status="UNKNOWN";reason="Evaluation state unavailable";}
                else {
                    try {
                        Set<Integer> expected=new HashSet<>();Instant deployment=null;
                        if(monitor.getMonitorType()==MonitorType.CONNECTION_STATUS) {
                            Map<Integer,Map<String,Instant>> deployments=NodeLeaseRepository.listActiveConnectorDeployments(channelId);
                            if(deployments.isEmpty()) throw new EmptyConnectorInventory();
                            for(Map<String,Instant> nodes:deployments.values()) for(Instant time:nodes.values())
                                if(time!=null && (deployment==null || time.isAfter(deployment))) deployment=time;
                            String rollup=Json.mapper().readTree(monitor.getConfigJson()==null?"{}":monitor.getConfigJson()).path("rollup").asText("CONNECTOR");
                            if("CHANNEL".equals(rollup)) expected.add(null);
                            else if("CONNECTOR".equals(rollup)) expected.addAll(deployments.keySet());
                            else throw new IllegalStateException("Unsupported connection rollup");
                        } else expected.add(null);
                        List<TriggerState> current=states.stream().filter(s->expected.contains(s.getMetadataId())).toList();
                        status="HEALTHY";reason="All current triggers recently evaluated OK";
                        if(current.size()!=expected.size()) {status="UNKNOWN";reason="Current trigger evaluation missing";}
                        for(TriggerState state:current) {
                            Map<String,Object> observation=observations.get(states.indexOf(state));observation.put("current",true);
                            boolean fresh=state.getLastEvaluatedTime()!=null && !state.getLastEvaluatedTime().isAfter(now)
                                && !state.getLastEvaluatedTime().isBefore(now.minusSeconds(freshnessSeconds))
                                && (monitor.getUpdatedTime()==null || !state.getLastEvaluatedTime().isBefore(monitor.getUpdatedTime()))
                                && (deployment==null || !state.getLastEvaluatedTime().isBefore(deployment));
                            AlertEvent event=state.getOpenAlertEventId()==null?null:AlertEventRepository.getAlertEvent(state.getOpenAlertEventId());
                            if(event!=null && event.getStatus()==AlertStatus.PROBLEM && channelId.equals(event.getChannelId())
                                && event.getMonitorId()==monitor.getId()) {
                                observation.put("problemId",event.getId());status="OPEN_PROBLEM";
                                reason=fresh?"An open problem is recorded":"An open problem is recorded; evaluation is stale";
                            } else if(!fresh || state.getState()==null) {
                                if(!"OPEN_PROBLEM".equals(status)){status="UNKNOWN";reason="Evaluation missing, stale or predates configuration/deployment";}
                            } else if(state.getState()==TriggerStatus.PROBLEM || state.getOpenAlertEventId()!=null) {
                                if(!"OPEN_PROBLEM".equals(status)) {status="UNKNOWN";reason="Trigger/problem lifecycle needs reconciliation";}
                            } else if(state.getState()==TriggerStatus.INSUFFICIENT_DATA || state.getConsecutiveBreachCount()>0) {
                                if("HEALTHY".equals(status)) {status="WARMING_UP";reason="Insufficient data or breach confirmation in progress";}
                            }
                        }
                    } catch(EmptyConnectorInventory e) {status="WARMING_UP";reason="Live connector inventory is empty; insufficient data";}
                    catch(Exception e) {status="UNKNOWN";reason="Current evaluation or connector inventory unavailable";}
                }
                finding.put("latestEvaluatedTime",states.stream().map(TriggerState::getLastEvaluatedTime).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
                finding.put("recordedStates",states.stream().map(TriggerState::getState).filter(Objects::nonNull).distinct().toList());
                finding.put("status",status);finding.put("reason",reason);finding.put("evaluations",observations);
                @SuppressWarnings("unchecked") List<Map<String,Object>> findings=(List<Map<String,Object>>)rows.get(channelId).get("monitors");findings.add(finding);
            }
        }
        for(Map<String,Object> row:rows.values()) {
            @SuppressWarnings("unchecked") List<Map<String,Object>> findings=(List<Map<String,Object>>)row.get("monitors");
            row.put("status", Boolean.TRUE.equals(row.get("scopeUnknown"))?"UNKNOWN":findings.isEmpty()?"NO_COVERAGE":"CONFIGURED");
        }
        return Map.of("rows",new ArrayList<>(rows.values()),"inventoryStatus",channels.unresolvedChannelIds.isEmpty()?"AVAILABLE":"PARTIAL",
            "issues",issues,"evaluatedAt",now,"freshnessSeconds",freshnessSeconds);
    }
}
