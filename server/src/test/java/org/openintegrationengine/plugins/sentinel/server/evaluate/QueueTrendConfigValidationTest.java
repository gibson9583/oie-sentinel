package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.service.MonitorService;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class QueueTrendConfigValidationTest {
    private void validate(String raw) throws Exception {
        var method=MonitorService.class.getDeclaredMethod("validateConfig",Monitor.class);method.setAccessible(true);
        var monitor=new Monitor();monitor.setMonitorType(MonitorType.QUEUE_DEPTH);monitor.setConfigJson(raw);
        try{method.invoke(null,monitor);}catch(InvocationTargetException e){if(e.getCause() instanceof IllegalArgumentException invalid)throw invalid;throw e;}
    }
    @Test void savesRejectInvalidModesGapsWindowsAndNonfiniteRates(){
        for(String raw:new String[]{"{\"mode\":\"OTHER\"}","{\"windowSeconds\":1.5}","{\"windowSeconds\":86401}","{\"maxSampleGapSeconds\":0}","{\"maxSampleGapSeconds\":1201}","{\"growthPerMinute\":\"Infinity\"}","{\"growthPerMinute\":\"NaN\"}","{\"maxNetDecreasePerMinute\":-1}"})
            assertThrows(IllegalArgumentException.class,()->validate(raw),raw);
    }
    @Test void oldDepthConfigAndTrendBoundariesRemainAccepted(){
        for(String raw:new String[]{"{}","{\"threshold\":1000,\"minDurationSeconds\":300}","{\"mode\":\"growth\",\"windowSeconds\":86400,\"maxSampleGapSeconds\":1200,\"growthPerMinute\":0.01}","{\"mode\":\"STALL\",\"maxNetDecreasePerMinute\":0}"})
            assertDoesNotThrow(()->validate(raw));
    }
}
