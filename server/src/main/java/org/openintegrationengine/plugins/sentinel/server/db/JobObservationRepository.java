/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.mirth.connect.server.util.SqlConfig;

/** Shared evidence writes require the same live fencing epoch as job work. */
public final class JobObservationRepository {
    private JobObservationRepository() { }
    public static void start(String job,String runId,LeaseFence fence) {
        if(fence==null||!fence.isManaged()||!"sentinel-engine".equals(fence.leaseName()))throw new IllegalArgumentException("Managed fence required");
        var params=parameters(job,runId,fence);
        AlertLifecycleTransaction.execute(fence,after->{
            var sql=SqlConfig.getInstance().getSqlSessionManager();
            if(sql.update("Sentinel.startJobObservation",params)==0)sql.insert("Sentinel.insertJobObservation",params);
        });
    }
    public static void finish(String job,String runId,LeaseFence fence,boolean success) {
        if(fence==null||!fence.isManaged()||!"sentinel-engine".equals(fence.leaseName()))throw new IllegalArgumentException("Managed fence required");
        var params=parameters(job,runId,fence);params.put("outcome",success?"SUCCESS":"ERROR");
        AlertLifecycleTransaction.execute(fence,after->{
            if(SqlConfig.getInstance().getSqlSessionManager().update("Sentinel.finishJobObservation",params)!=1)
                throw new IllegalStateException("Job observation was superseded");
        });
    }
    private static Map<String,Object> parameters(String job,String runId,LeaseFence fence) {
        if(!Set.of("collector","evaluator","rollup","prune").contains(job))throw new IllegalArgumentException("Unknown job");
        var params=new HashMap<String,Object>();params.put("job",job);params.put("runId",runId);fence.bind(params);return params;
    }
    public static List<Map<String,Object>> list() {
        List<Map<String,Object>> rows=SqlConfig.getInstance().getSqlSessionManager().selectList("Sentinel.listJobObservations");
        var output=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            var converted=new HashMap<>(row);
            for(String key:List.of("started_time","finished_time","last_success_time"))
                if(converted.get(key) instanceof Timestamp timestamp)converted.put(key,timestamp.toInstant());
            output.add(converted);
        }
        return output;
    }
    public static Map<String,Object> storage(Set<String> channels) {
        var params=new HashMap<String,Object>();params.put("channelIdIn",channels==null?null:new ArrayList<>(channels));
        var sql=SqlConfig.getInstance().getSqlSessionManager();var output=new LinkedHashMap<String,Object>();
        for(String kind:List.of("Samples","Trends","Events","Pending")) {
            Number value=sql.selectOne("Sentinel.countHealth"+kind,params);output.put(kind.toLowerCase(Locale.ROOT),value.longValue());
        }
        Timestamp oldest=sql.selectOne("Sentinel.oldestHealthPending",params);output.put("oldestPendingEdge",oldest==null?null:oldest.toInstant());
        return output;
    }
}
