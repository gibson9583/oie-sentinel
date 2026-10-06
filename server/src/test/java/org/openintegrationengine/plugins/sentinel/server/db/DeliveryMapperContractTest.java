package org.openintegrationengine.plugins.sentinel.server.db;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
class DeliveryMapperContractTest {
    @Test void allFiveVendorsBindAuthorizationOnBothReadsAndKeepHistoricalColumns() throws Exception {
        for(String vendor:List.of("derby","postgres","mysql","oracle","sqlserver")) {
            Configuration cfg=new Configuration();
            try(var in=getClass().getResourceAsStream("/mapper/"+vendor+"-sqlmap.xml")) {
                assertNotNull(in);new XMLMapperBuilder(in,cfg,vendor,cfg.getSqlFragments()).parse();
            }
            Map<String,Object> params=new HashMap<>();params.put("channelIdIn",new ArrayList<>());params.put("limit",25);params.put("offset",0);
            for(String id:List.of("listDeliveries","countDeliveries")) {
                String denied=cfg.getMappedStatement("Sentinel."+id).getBoundSql(params).getSql();assertTrue(denied.contains("1 = 0"),vendor+id);
                params.put("channelIdIn",new ArrayList<>(List.of("channel-secret")));params.put("transport","UNKNOWN");params.put("phase","UNKNOWN");
                String filtered=cfg.getMappedStatement("Sentinel."+id).getBoundSql(params).getSql();assertFalse(filtered.contains("channel-secret"));
                assertTrue(filtered.contains("e.channel_id IN"));assertTrue(filtered.contains("d.action_type_at_attempt IS NULL"));assertTrue(filtered.contains("d.event_phase_at_attempt IS NULL"));params.put("channelIdIn",new ArrayList<>());
            }
            try(var in=getClass().getResourceAsStream("/"+vendor+"-sentinel-v14.sql")) {
                assertNotNull(in);String ddl=new String(in.readAllBytes(),StandardCharsets.UTF_8);
                for(String field:List.of("action_id_at_attempt","action_name_at_attempt","action_type_at_attempt","event_phase_at_attempt","idx_sentinel_dispatch_time"))assertTrue(ddl.contains(field),vendor+field);
                assertFalse(ddl.contains("UPDATE"),"Historical context must remain unknown");
            }
        }
    }
}
