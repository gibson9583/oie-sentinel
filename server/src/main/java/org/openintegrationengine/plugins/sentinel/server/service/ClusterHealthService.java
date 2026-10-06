/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Independent reads keep leadership/job diagnostics available after collection failure. */
public final class ClusterHealthService {
    private ClusterHealthService() { }
    public record Observation(String state,Object data,String detail) { }
    public static Map<String,Observation> build(Set<String> channels) {
        var output=new LinkedHashMap<String,Observation>();
        var leader=observe(()-> {
            NodeLease lease=NodeLeaseRepository.getNodeLease("sentinel-engine");Instant now=NodeLeaseRepository.getDatabaseTime();
            if(now==null)throw new IllegalStateException("Database clock unavailable");
            var data=new LinkedHashMap<String,Object>();data.put("observedAt",now);
            data.put("state",lease==null?"ABSENT":lease.getExpiresTime()!=null&&lease.getExpiresTime().isAfter(now)?"LIVE":"EXPIRED");
            data.put("nodeId",lease==null?null:lease.getNodeId());data.put("epoch",lease==null?null:lease.getLeaseEpoch());data.put("expiresAt",lease==null?null:lease.getExpiresTime());
            return data;
        });output.put("leader",leader);
        output.put("nodes",observe(()->NodeLeaseRepository.listActiveSentinelNodeIds().stream().sorted().toList()));
        output.put("jobs",observe(()->JobObservationRepository.list()));
        output.put("storage",observe(()->JobObservationRepository.storage(channels)));
        output.put("retention",observe(()->{
            SentinelSettings settings=SettingsService.get();var data=new LinkedHashMap<String,Object>();
            data.put("collectorIntervalSeconds",settings.getCollectorIntervalSeconds());data.put("evaluatorIntervalSeconds",settings.getEvaluatorIntervalSeconds());
            data.put("sampleRetentionDays",settings.getSampleRetentionDays());data.put("trendRetentionDays",settings.getTrendRetentionDays());data.put("resolvedAlertRetentionDays",settings.getResolvedAlertRetentionDays());return data;
        }));
        output.put("estimates",observe(()-> {
            Set<String> deployed=new HashSet<>(NodeLeaseRepository.listActiveDeployedChannelIds());if(channels!=null)deployed.retainAll(channels);
            SentinelSettings settings=SettingsService.get();if(settings.getCollectorIntervalSeconds()<=0)throw new IllegalStateException("Invalid collector interval");
            var data=new LinkedHashMap<String,Object>();data.put("visibleDeployedChannels",deployed.size());
            data.put("rawSampleRows",Math.ceil((double)deployed.size()*settings.getSampleRetentionDays()*86400/settings.getCollectorIntervalSeconds()));
            data.put("hourlyTrendRows",(long)deployed.size()*settings.getTrendRetentionDays()*24);
            data.put("assumption","Row-capacity estimates assume this many channels stay deployed and every scheduled collection/rollup succeeds throughout retention. Failover, missing runs and pruning delays change actual rows. Physical database bytes are unknown.");return data;
        }));
        return output;
    }
    private static Observation observe(Supplier<?> read) {
        try { return new Observation("OBSERVED",read.get(),null); }
        catch(Exception failure) { return new Observation("UNKNOWN",null,"Read unavailable; no health conclusion can be drawn."); }
    }
}
