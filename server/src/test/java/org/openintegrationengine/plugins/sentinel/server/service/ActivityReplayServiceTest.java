package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.evaluate.*;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ActivityReplayServiceTest {
    private final Instant now=Instant.parse("2026-10-06T12:00:00Z");
    private ActivityReplayRequest request(MonitorType type,String config){var r=new ActivityReplayRequest();var m=new Monitor();m.setMonitorType(type);m.setConfigJson(config);r.setMonitor(m);r.setChannelId("c");r.setFrom(now.minusSeconds(600).toEpochMilli());r.setTo(now.toEpochMilli());r.setStepSeconds(60);return r;}
    private List<ActivitySample> samples(long received,long errors){var list=new ArrayList<ActivitySample>();for(int i=-900;i<=0;i+=60){var s=new ActivitySample();s.setChannelId("c");s.setSampleTime(now.plusSeconds(i));s.setReceivedDelta(received);s.setErrorDelta(errors);list.add(s);}return list;}
    private int count(Map<String,Object> result,String key){return (int)result.get(key);}
    @Test void errorReplayUsesLiveMathInclusiveThresholdAndCapturedRawContext(){
        var r=request(MonitorType.ERROR_RATE,"{\"windowSeconds\":300,\"thresholdPercent\":5,\"minMessages\":20}");
        var result=ActivityReplayService.replaySamples(r,now,samples(100,5));assertEquals(11,count(result,"evaluationCount"));assertEquals(11,count(result,"breachCount"));assertEquals(0,count(result,"unknownCount"));
        assertEquals(false,result.get("historicalDeploymentKnown"));assertEquals(false,result.get("historicalSchedulesKnown"));assertEquals(true,result.get("comparisonOnly"));
        var points=(List<?>)result.get("points");var value=((Map<?,?>)points.get(0)).get("valueJson");
        assertEquals(ErrorRateEvaluator.evaluateCounts(Json.read(r.getMonitor().getConfigJson(),com.fasterxml.jackson.databind.JsonNode.class),300,600,30).getValueJson(),value);
        assertEquals(1,((List<?>)result.get("runs")).size());assertEquals(16,result.get("rawSampleCount"));
    }
    @Test void fixedLowVolumePreservesStrictEqualityAndDoesNotInventDeploymentContext(){
        var r=request(MonitorType.LOW_VOLUME,"{\"windowSeconds\":300,\"compareTo\":\"FIXED\",\"minCount\":6}");
        assertEquals(0,count(ActivityReplayService.replaySamples(r,now,samples(1,0)),"breachCount"));
        r.getMonitor().setConfigJson("{\"windowSeconds\":300,\"minCount\":7}");assertEquals(11,count(ActivityReplayService.replaySamples(r,now,samples(1,0)),"breachCount"));
        assertEquals(11,count(ActivityReplayService.replaySamples(r,now,samples(0,0)),"breachCount"));
    }
    @Test void absentRawCoverageIsUnknownAndNoHourlyFallbackIsCalled(){
        var r=request(MonitorType.LOW_VOLUME,"{\"windowSeconds\":300}");
        var result=ActivityReplayService.replaySamples(r,now,List.of());assertEquals(11,count(result,"unknownCount"));assertEquals(0,count(result,"breachCount"));assertNull(result.get("rawFrom"));
        var broken=new ArrayList<>(samples(1,0));broken.removeIf(s->s.getSampleTime().isAfter(now.minusSeconds(700))&&s.getSampleTime().isBefore(now.minusSeconds(400)));
        assertTrue(count(ActivityReplayService.replaySamples(r,now,broken),"unknownCount")>0);
    }
    @Test void messageFloorZeroTrafficAndOverflowStayUnknown(){
        var r=request(MonitorType.ERROR_RATE,"{\"windowSeconds\":300,\"minMessages\":20}");
        assertEquals(11,count(ActivityReplayService.replaySamples(r,now,samples(1,0)),"unknownCount"));
        assertEquals(11,count(ActivityReplayService.replaySamples(r,now,samples(0,0)),"unknownCount"));
        assertEquals(11,count(ActivityReplayService.replaySamples(r,now,samples(Long.MAX_VALUE,1)),"unknownCount"));
    }
    @Test void rejectsNonReplayableRulesFutureRangesBadConfigurationAndCaps(){
        for(var type:List.of(MonitorType.ANOMALY,MonitorType.CHANNEL_STATE,MonitorType.INACTIVITY,MonitorType.CONNECTION_STATUS,MonitorType.QUEUE_DEPTH))
            assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(request(type,"{}"),now,List.of()));
        for(String config:List.of("{\"compareTo\":\"BASELINE_RELATIVE\"}","{\"windowSeconds\":1.5}","{\"windowSeconds\":86401}","{\"minCount\":-1}"))
            assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(request(MonitorType.LOW_VOLUME,config),now,List.of()));
        var r=request(MonitorType.ERROR_RATE,"{}");r.setTo(now.plusSeconds(1).toEpochMilli());assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(r,now,List.of()));
        r.setTo(now.toEpochMilli());r.setFrom(now.minusSeconds(86400).toEpochMilli());assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(r,now,List.of()));
        r.setStepSeconds(300);r.setFrom(now.minusSeconds(86401).toEpochMilli());assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(r,now,List.of()));
    }
    @Test void duplicateUnsortedNegativeOrNullRawDataCannotProduceHealthyReplay(){
        var r=request(MonitorType.ERROR_RATE,"{}");var full=samples(100,5);
        assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(r,now,Arrays.asList((ActivitySample)null)));
        assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(r,now,List.of(full.get(0),full.get(0))));
        assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(r,now,List.of(full.get(1),full.get(0))));
        full.get(0).setReceivedDelta(-1);assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replaySamples(r,now,full));
    }
    @Test void authorizationPrecedesRawReadsAndReplayNeverTouchesLifecycleOrDispatch(){
        var r=request(MonitorType.ERROR_RATE,"{\"windowSeconds\":300}");
        try(var activity=mockStatic(ActivityRepository.class);var triggers=mockStatic(TriggerStateRepository.class);var alerts=mockStatic(AlertEventRepository.class);var dispatch=mockStatic(ActionDispatcher.class)){
            assertThrows(NoSuchElementException.class,()->ActivityReplayService.replay(r,now,Set.of("other")));
            assertThrows(NoSuchElementException.class,()->ActivityReplayService.replay(r,now,Set.of()));activity.verifyNoInteractions();
            activity.when(()->ActivityRepository.listActivitySamplesBounded("c",now.minusSeconds(1020),now,10001)).thenReturn(samples(100,5));
            assertEquals(11,count(ActivityReplayService.replay(r,now,Set.of("c")),"evaluationCount"));
            activity.verify(()->ActivityRepository.listActivitySamplesBounded("c",now.minusSeconds(1020),now,10001));activity.verifyNoMoreInteractions();
            triggers.verifyNoInteractions();alerts.verifyNoInteractions();dispatch.verifyNoInteractions();
        }
    }
    @Test void apiFailurePropagatesAndRetainedRawCapRejectsPartialInputs(){
        var r=request(MonitorType.ERROR_RATE,"{\"windowSeconds\":300}");
        try(var activity=mockStatic(ActivityRepository.class)){
            activity.when(()->ActivityRepository.listActivitySamplesBounded("c",now.minusSeconds(1020),now,10001)).thenThrow(new IllegalStateException("db unavailable"));
            assertThrows(IllegalStateException.class,()->ActivityReplayService.replay(r,now,null));
            activity.when(()->ActivityRepository.listActivitySamplesBounded("c",now.minusSeconds(1020),now,10001)).thenReturn(Collections.nCopies(10001,new ActivitySample()));
            assertThrows(IllegalArgumentException.class,()->ActivityReplayService.replay(r,now,null));
        }
    }
    @Test void coverageToleranceIsInclusiveButFractionalExcessAndStaleTailAreUnknown() {
        var r = request(MonitorType.LOW_VOLUME, "{\"windowSeconds\":300}");
        r.setFrom(now.minusSeconds(60).toEpochMilli());
        var full = samples(1, 0);
        full.removeIf(sample -> sample.getSampleTime().equals(now.minusSeconds(60)));
        assertEquals(0, count(ActivityReplayService.replaySamples(r, now, full), "unknownCount"));
        full.get(full.size() - 1).setSampleTime(now.plusMillis(1));
        assertThrows(IllegalArgumentException.class, () -> ActivityReplayService.replaySamples(r, now, full));
        full.get(full.size() - 1).setSampleTime(now.minusSeconds(120).minusMillis(1));
        full.sort(Comparator.comparing(ActivitySample::getSampleTime));
        full.removeIf(sample -> sample.getSampleTime().equals(now.minusSeconds(120)));
        assertTrue(count(ActivityReplayService.replaySamples(r, now, full), "unknownCount") > 0);
    }
    @Test void malformedThresholdsAndBoundsCannotCompareAndManagePermissionIsDeclared() throws Exception {
        for (String config : List.of("{\"thresholdPercent\":101}", "{\"thresholdPercent\":\"5\"}", "{\"minMessages\":1.5}")) {
            assertThrows(IllegalArgumentException.class, () -> ActivityReplayService.replaySamples(
                    request(MonitorType.ERROR_RATE, config), now, List.of()));
        }
        var r = request(MonitorType.ERROR_RATE, "{}");
        r.setStepSeconds(59);
        assertThrows(IllegalArgumentException.class, () -> ActivityReplayService.replaySamples(r, now, List.of()));
        r.setStepSeconds(60); r.setMaxSampleGapSeconds(1201);
        assertThrows(IllegalArgumentException.class, () -> ActivityReplayService.replaySamples(r, now, List.of()));
        var operation = org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface.class
                .getMethod("replayActivity", String.class)
                .getAnnotation(com.mirth.connect.client.core.api.MirthOperation.class);
        assertEquals(org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface.PERMISSION_MANAGE, operation.permission());
    }

}
