/* OIE Sentinel — MPL 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.evaluate.*;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Bounded metric-only replay over retained raw samples. Historical eligibility,
 * schedules, dependencies, opening/recovery progress and delivery are unknown. */
public final class ActivityReplayService {
    private ActivityReplayService() { }
    public static Map<String, Object> replay(ActivityReplayRequest request, Instant now, Set<String> authorized) {
        if (request == null || request.getChannelId() == null || request.getChannelId().isBlank())
            throw new IllegalArgumentException("Replay channel is required");
        if (authorized != null && !authorized.contains(request.getChannelId()))
            throw new NoSuchElementException("Replay channel not found");
        Config config = validate(request, now);
        var samples = ActivityRepository.listActivitySamplesBounded(request.getChannelId(),
                config.from.minusSeconds((long)config.window + config.gap), config.to, 10001);
        if (samples.size() > 10000) throw new IllegalArgumentException("More than 10000 raw samples: narrow the range or condition window");
        return replaySamples(request, config, samples);
    }
    public static Map<String, Object> replaySamples(ActivityReplayRequest request, Instant now, List<ActivitySample> samples) {
        return replaySamples(request, validate(request, now), samples);
    }
    private record Config(Instant from, Instant to, int step, int gap, int window, JsonNode rule) { }
    private static Config validate(ActivityReplayRequest r, Instant now) {
        if (r == null || r.getMonitor() == null || r.getChannelId() == null || r.getChannelId().isBlank()
                || r.getFrom() == null || r.getTo() == null) throw new IllegalArgumentException("Candidate monitor, channel and replay bounds are required");
        Instant from = Instant.ofEpochMilli(r.getFrom()), to = Instant.ofEpochMilli(r.getTo());
        if (!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(1)) > 0 || to.isAfter(now))
            throw new IllegalArgumentException("Replay needs a past range of at most 24 hours with from before to");
        int step = r.getStepSeconds() == null ? 300 : r.getStepSeconds(), gap = r.getMaxSampleGapSeconds() == null ? 120 : r.getMaxSampleGapSeconds();
        if (step < 60 || step > 3600 || gap < 1 || gap > 1200) throw new IllegalArgumentException("Evaluation step must be 60..3600s and sample gap 1..1200s");
        if (Duration.between(from,to).toMillis()/(step*1000L)+1 > 1000) throw new IllegalArgumentException("More than 1000 evaluation instants: increase step or narrow range");
        JsonNode rule = Json.read(r.getMonitor().getConfigJson() == null ? "{}" : r.getMonitor().getConfigJson(), JsonNode.class);
        if (!rule.isObject()) throw new IllegalArgumentException("Candidate config must be a JSON object");
        if (r.getMonitor().getMonitorType() != MonitorType.ERROR_RATE && r.getMonitor().getMonitorType() != MonitorType.LOW_VOLUME)
            throw new IllegalArgumentException("Replay supports Error rate and fixed Low volume only");
        if (r.getMonitor().getMonitorType() == MonitorType.LOW_VOLUME && !rule.path("compareTo").asText("FIXED").equalsIgnoreCase("FIXED"))
            throw new IllegalArgumentException("Historical baseline-relative Low volume is not replayable");
        int window = integer(rule,"windowSeconds",3600,1,86400);
        if (r.getMonitor().getMonitorType() == MonitorType.ERROR_RATE) {
            integerLong(rule,"minMessages");
            double threshold = rule.path("thresholdPercent").asDouble(10);
            if (!Double.isFinite(threshold) || threshold < 0 || threshold > 100
                    || rule.hasNonNull("thresholdPercent") && !rule.get("thresholdPercent").isNumber())
                throw new IllegalArgumentException("thresholdPercent must be a finite number between 0 and 100");
        } else integerLong(rule,"minCount");
        return new Config(from,to,step,gap,window,rule);
    }
    private static int integer(JsonNode rule,String key,int fallback,int min,int max) {
        JsonNode n=rule.get(key);if(n==null || n.isNull())return fallback;
        if(!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue()<min || n.intValue()>max)throw new IllegalArgumentException(key+" must be a whole number between "+min+" and "+max);
        return n.intValue();
    }
    private static void integerLong(JsonNode rule,String key) {
        JsonNode n=rule.get(key);if(n==null || n.isNull())return;
        if(!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue()<0)throw new IllegalArgumentException(key+" must be a nonnegative whole number");
    }
    private static Map<String,Object> replaySamples(ActivityReplayRequest r,Config c,List<ActivitySample> samples) {
        if (samples == null || samples.size()>10000) throw new IllegalArgumentException("Raw sample cap exceeded or missing result");
        Instant previous=null;
        for (ActivitySample s:samples) {
            if (s==null || s.getSampleTime()==null || previous!=null && !s.getSampleTime().isAfter(previous)
                    || s.getSampleTime().isAfter(c.to) || s.getReceivedDelta()<0 || s.getErrorDelta()<0)
                throw new IllegalArgumentException("Raw samples have invalid/duplicate/order timestamps or negative deltas; replay is unavailable");
            previous=s.getSampleTime();
        }
        var points=new ArrayList<Map<String,Object>>();var runs=new ArrayList<Map<String,Object>>();
        int breaches=0,unknown=0;
        for(Instant at=c.from;!at.isAfter(c.to);at=at.plusSeconds(c.step)) {
            EvaluationOutcome outcome=compare(r.getMonitor().getMonitorType(),c,samples,at);
            var point=new LinkedHashMap<String,Object>();point.put("at",at);point.put("status",outcome.getResult());point.put("valueJson",outcome.getValueJson());points.add(point);
            if(outcome.getResult()==EvaluationOutcome.Result.BREACH)breaches++;
            if(outcome.getResult()==EvaluationOutcome.Result.INSUFFICIENT_DATA)unknown++;
            var last=runs.isEmpty()?null:runs.get(runs.size()-1);
            if(last!=null && last.get("status")==outcome.getResult()){last.put("to",at);last.put("evaluations",(int)last.get("evaluations")+1);}
            else{var run=new LinkedHashMap<String,Object>();run.put("from",at);run.put("to",at);run.put("status",outcome.getResult());run.put("evaluations",1);runs.add(run);}
        }
        var result=new LinkedHashMap<String,Object>();result.put("channelId",r.getChannelId());result.put("from",c.from);result.put("to",c.to);
        result.put("stepSeconds",c.step);result.put("windowSeconds",c.window);result.put("rawSampleCount",samples.size());
        result.put("rawFrom",samples.isEmpty()?null:samples.get(0).getSampleTime());result.put("rawTo",samples.isEmpty()?null:samples.get(samples.size()-1).getSampleTime());
        result.put("evaluationCount",points.size());result.put("breachCount",breaches);result.put("unknownCount",unknown);result.put("runs",runs);result.put("points",points);
        result.put("comparisonOnly",true);result.put("historicalDeploymentKnown",false);result.put("historicalSchedulesKnown",false);
        result.put("limitation","Sampled metric comparisons only, assuming runtime eligibility. Historical deployment, schedules, dependencies, trigger hysteresis and delivery are not replayed. Raw retention gaps are unknown; hourly rollups are never substituted.");return result;
    }
    private static EvaluationOutcome compare(MonitorType type,Config c,List<ActivitySample> samples,Instant at) {
        Instant start=at.minusSeconds(c.window);ActivitySample baseline=null,latest=null;Instant prior=null;long received=0,errors=0;
        for(ActivitySample s:samples){Instant time=s.getSampleTime();if(time.isAfter(at))break;if(!time.isAfter(start))baseline=s;}
        if(baseline==null || Duration.between(baseline.getSampleTime(),start).compareTo(Duration.ofSeconds(c.gap))>0)return unknown("Raw history does not cover window start");
        try {
            for(ActivitySample s:samples){Instant time=s.getSampleTime();if(time.isBefore(baseline.getSampleTime()))continue;if(time.isAfter(at))break;
                if(prior!=null && Duration.between(prior,time).compareTo(Duration.ofSeconds(c.gap))>0)return unknown("Raw sample gap exceeds tolerance");
                prior=time;latest=s;if(!time.isBefore(start)){received=Math.addExact(received,s.getReceivedDelta());errors=Math.addExact(errors,s.getErrorDelta());}}
        }catch(ArithmeticException overflow){return unknown("Window counter sum overflow");}
        if(latest==null || Duration.between(latest.getSampleTime(),at).compareTo(Duration.ofSeconds(c.gap))>0)return unknown("Latest raw sample is stale");
        return type==MonitorType.ERROR_RATE?ErrorRateEvaluator.evaluateCounts(c.rule,c.window,received,errors):LowVolumeEvaluator.evaluateFixed(c.rule,c.window,received);
    }
    private static EvaluationOutcome unknown(String reason){return EvaluationOutcome.insufficientData(Json.write(Map.of("reason",reason)));}
}
