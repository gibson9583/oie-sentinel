package org.openintegrationengine.plugins.sentinel.server.db;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
class HealthMapperContractTest {
    @Test void everyVendorUsesDatabaseClockRunIdentityAndAuthorizationBeforeCounts() throws Exception {
        for(String vendor:List.of("derby","postgres","mysql","oracle","sqlserver")) {
            Configuration cfg=new Configuration();
            try(var in=getClass().getResourceAsStream("/mapper/"+vendor+"-sqlmap.xml")) {assertNotNull(in);new XMLMapperBuilder(in,cfg,vendor,cfg.getSqlFragments()).parse();}
            var params=new HashMap<String,Object>();params.put("channelIdIn",new ArrayList<>());params.put("outcome","SUCCESS");
            for(String id:List.of("countHealthSamples","countHealthTrends","countHealthEvents","countHealthPending","oldestHealthPending")) {
                assertTrue(cfg.getMappedStatement("Sentinel."+id).getBoundSql(params).getSql().contains("1=0"),vendor+id);
            }
            String completion=cfg.getMappedStatement("Sentinel.finishJobObservation").getBoundSql(params).getSql();assertTrue(completion.contains("run_id=?"));assertTrue(completion.contains("lease_epoch=?"));assertTrue(completion.contains("last_success_time="));
            params.put("outcome","ERROR");assertFalse(cfg.getMappedStatement("Sentinel.finishJobObservation").getBoundSql(params).getSql().contains("last_success_time="));
            try(var in=getClass().getResourceAsStream("/"+vendor+"-sentinel-v15.sql")) {assertNotNull(in);String ddl=new String(in.readAllBytes(),StandardCharsets.UTF_8);assertTrue(ddl.contains("pk_sentinel_job_observation"));assertTrue(ddl.contains("last_success_epoch"));}
        }
    }
}
