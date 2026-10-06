package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class QueueTrendEvaluatorTest {
    private final Instant now=Instant.parse("2026-10-06T12:00:00Z");
    private com.fasterxml.jackson.databind.JsonNode config(String mode){return Json.read("{\"mode\":\""+mode+"\",\"threshold\":1000,\"windowSeconds\":300,\"maxSampleGapSeconds\":120,\"growthPerMinute\":100}",com.fasterxml.jackson.databind.JsonNode.class);}
    private ActivitySample sample(int offset,long depth){var s=new ActivitySample();s.setSampleTime(now.plusSeconds(offset));s.setQueuedSnapshot(depth);return s;}
    private List<ActivitySample> series(long first,long last){var samples=new ArrayList<ActivitySample>();for(int i=0;i<=5;i++)samples.add(sample(-300+60*i,first+(last-first)*i/5));return samples;}
    @Test void inclusiveGrowthBoundaryAndCapturedSampleContext(){
        var result=QueueTrendEvaluator.evaluateSamples(series(1000,1500),now,config("GROWTH"));
        assertEquals(EvaluationOutcome.Result.BREACH,result.getResult());var value=Json.read(result.getValueJson(),com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(100,value.path("estimatedNetChangePerMinute").asDouble());assertEquals(300,value.path("observedSeconds").asDouble());assertEquals(6,value.path("sampleCount").asInt());
        assertEquals(EvaluationOutcome.Result.OK,QueueTrendEvaluator.evaluateSamples(series(1000,1495),now,config("GROWTH")).getResult());
        assertEquals(EvaluationOutcome.Result.OK,QueueTrendEvaluator.evaluateSamples(series(0,500),now,config("GROWTH")).getResult());
    }
    @Test void stallUsesWholeWindowDepthAndNetChangeNotSummedGauges(){
        assertEquals(EvaluationOutcome.Result.BREACH,QueueTrendEvaluator.evaluateSamples(series(1000,1000),now,config("STALL")).getResult());
        assertEquals(EvaluationOutcome.Result.BREACH,QueueTrendEvaluator.evaluateSamples(series(1000,1500),now,config("STALL")).getResult());
        assertEquals(EvaluationOutcome.Result.OK,QueueTrendEvaluator.evaluateSamples(series(1200,1000),now,config("STALL")).getResult());
        assertEquals(EvaluationOutcome.Result.OK,QueueTrendEvaluator.evaluateSamples(series(999,1000),now,config("STALL")).getResult());
        var c=(com.fasterxml.jackson.databind.node.ObjectNode)config("STALL");c.put("maxNetDecreasePerMinute",40);
        assertEquals(EvaluationOutcome.Result.BREACH,QueueTrendEvaluator.evaluateSamples(series(1200,1000),now,c).getResult());
    }
    @Test void missingCoverageGapStaleInvalidAndDuplicateStayUnknown(){
        var c=config("GROWTH");var full=series(1000,1500);
        var cases=new ArrayList<List<ActivitySample>>();cases.add(List.of());cases.add(full.subList(1,full.size()));
        cases.add(List.of(sample(-300,1000),sample(0,1500)));cases.add(List.of(sample(-600,1000),sample(-121,1500)));
        cases.add(List.of(sample(-300,1000),sample(-300,1001),sample(0,1500)));
        cases.add(List.of(sample(-300,1000),sample(1,1500)));
        cases.add(List.of(sample(-300,-1),sample(0,1500)));cases.add(Arrays.asList(null,sample(0,1500)));
        var noTime=sample(-300,1000);noTime.setSampleTime(null);cases.add(List.of(noTime,sample(0,1500)));
        for(var samples:cases)assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA,QueueTrendEvaluator.evaluateSamples(samples,now,c).getResult(),samples.toString());
    }
    @Test void freshEnoughLatestStillRequiresActualFullObservedWindow(){
        var samples=new ArrayList<ActivitySample>();for(int i=0;i<=5;i++)samples.add(sample(-420+i*60,1000+i*100));
        var result=QueueTrendEvaluator.evaluateSamples(samples,now,config("GROWTH"));assertEquals(EvaluationOutcome.Result.BREACH,result.getResult());
        assertEquals(300,Json.read(result.getValueJson(),com.fasterxml.jackson.databind.JsonNode.class).path("observedSeconds").asDouble());
        samples.remove(0);assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA,QueueTrendEvaluator.evaluateSamples(samples,now,config("GROWTH")).getResult());
    }
    @Test void modeRoutingReadsBoundedRawWindowAndPropagatesApiFailure(){
        Monitor monitor=new Monitor();monitor.setConfigJson(config("GROWTH").toString());
        try(var repository=mockStatic(ActivityRepository.class)){
            repository.when(()->ActivityRepository.listActivitySamples("c",now.minusSeconds(540),now)).thenReturn(series(1000,1500));
            assertEquals(EvaluationOutcome.Result.BREACH,QueueDepthEvaluator.evaluate(monitor,"c",now).getResult());
            repository.when(()->ActivityRepository.listActivitySamples("c",now.minusSeconds(540),now)).thenThrow(new IllegalStateException("db unavailable"));
            assertThrows(IllegalStateException.class,()->QueueDepthEvaluator.evaluate(monitor,"c",now));
        }
    }
    @Test void integerGaugeDifferenceAvoidsLargeDepthPrecisionLossAndFractionalGapIsUnknown(){
        assertEquals(EvaluationOutcome.Result.BREACH,QueueTrendEvaluator.evaluateSamples(series(Long.MAX_VALUE-500,Long.MAX_VALUE),now,config("GROWTH")).getResult());
        var samples=series(1000,1500);samples.get(1).setSampleTime(now.minusSeconds(180).plusMillis(1));
        assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA,QueueTrendEvaluator.evaluateSamples(samples,now,config("GROWTH")).getResult());
    }
    @Test void invalidConfigurationDoesNotDefaultToHealthy(){
        for(String value:new String[]{"{\"mode\":\"SOMETHING\"}","{\"mode\":\"GROWTH\",\"windowSeconds\":86401}","{\"mode\":\"GROWTH\",\"maxSampleGapSeconds\":0}","{\"mode\":\"GROWTH\",\"growthPerMinute\":0}"})
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA,QueueTrendEvaluator.evaluateSamples(series(1000,1500),now,Json.read(value,com.fasterxml.jackson.databind.JsonNode.class)).getResult());
    }
}
