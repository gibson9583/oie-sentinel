/* OIE Sentinel — MPL 2.0. */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.fasterxml.jackson.databind.JsonNode;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/** Net aggregate queue growth/shrink estimates from a complete recent sample
 * window. Gauges cannot establish destination age, actual throughput or latency. */
public final class QueueTrendEvaluator {
    private QueueTrendEvaluator() { }
    public static EvaluationOutcome evaluate(Monitor monitor, String channel, Instant now, JsonNode config) {
        int window = config.path("windowSeconds").asInt(300);
        int gap = config.path("maxSampleGapSeconds").asInt(120);
        if (window < 1 || window > 86400 || gap < 1 || gap > 1200)
            return unknown("Invalid queue trend window or gap tolerance", config);
        return evaluateSamples(ActivityRepository.listActivitySamples(channel, now.minusSeconds((long) window + 2L * gap), now), now, config);
    }

    public static EvaluationOutcome evaluateSamples(List<ActivitySample> samples, Instant now, JsonNode config) {
        String mode = config.path("mode").asText("GROWTH").trim().toUpperCase(Locale.ROOT);
        int window = config.path("windowSeconds").asInt(300), gap = config.path("maxSampleGapSeconds").asInt(120);
        long threshold = config.path("threshold").asLong(1000);
        double growth = config.path("growthPerMinute").asDouble(100), maxDecrease = config.path("maxNetDecreasePerMinute").asDouble(0);
        if ((!mode.equals("GROWTH") && !mode.equals("STALL")) || window < 1 || window > 86400 || gap < 1 || gap > 1200
                || threshold < 0 || !Double.isFinite(growth) || growth <= 0 || !Double.isFinite(maxDecrease) || maxDecrease < 0)
            return unknown("Invalid queue trend configuration", config);
        if (samples == null || samples.size() < 2 || samples.size() > 10000) return unknown("Missing or excessive raw samples", config);
        int baseline = -1;
        Instant previous = null;
        for (int i = 0; i < samples.size(); i++) {
            ActivitySample s = samples.get(i);
            if (s == null || s.getSampleTime() == null || s.getQueuedSnapshot() < 0 || s.getSampleTime().isAfter(now)
                    || (previous != null && !s.getSampleTime().isAfter(previous)))
                return unknown("Invalid, duplicate or unordered sample timestamps/depth", config);
            previous = s.getSampleTime();
        }
        Instant boundary = samples.get(samples.size() - 1).getSampleTime().minusSeconds(window);
        for (int i = 0; i < samples.size(); i++) if (!samples.get(i).getSampleTime().isAfter(boundary)) baseline = i;
        if (baseline < 0 || baseline == samples.size() - 1) return unknown("Raw history does not cover the requested window", config);
        var points = new ArrayList<>(samples.subList(baseline, samples.size()));
        ActivitySample first = points.get(0), last = points.get(points.size() - 1);
        if (Duration.between(first.getSampleTime(), boundary).compareTo(Duration.ofSeconds(gap)) > 0
                || Duration.between(last.getSampleTime(), now).compareTo(Duration.ofSeconds(gap)) > 0)
            return unknown("Window boundary or latest sample is stale", config);
        long minimum = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            minimum = Math.min(minimum, points.get(i).getQueuedSnapshot());
            if (i > 0 && Duration.between(points.get(i-1).getSampleTime(), points.get(i).getSampleTime()).compareTo(Duration.ofSeconds(gap)) > 0)
                return unknown("Raw sample gap exceeds tolerance", config);
        }
        double elapsed = Duration.between(first.getSampleTime(), last.getSampleTime()).toMillis()/1000.0;
        if (elapsed <= 0) return unknown("No elapsed observation time", config);
        double netPerMinute = (last.getQueuedSnapshot() - first.getQueuedSnapshot()) * 60.0/elapsed;
        var value = Json.mapper().createObjectNode();
        value.put("mode", mode); value.put("windowSeconds", window); value.put("maxSampleGapSeconds", gap);
        value.put("sampleCount",points.size()); value.put("sampleFrom",first.getSampleTime().toString()); value.put("sampleTo",last.getSampleTime().toString());
        value.put("observedSeconds",elapsed); value.put("queueDepth",last.getQueuedSnapshot()); value.put("minimumObservedDepth",minimum);
        value.put("threshold",threshold); value.put("estimatedNetChangePerMinute",netPerMinute);
        value.put("growthPerMinute",growth); value.put("maxNetDecreasePerMinute",maxDecrease);
        value.put("limitation","Aggregate queue snapshots estimate net change; no destination age, true drain throughput or exact latency.");
        boolean breach = mode.equals("GROWTH")
                ? last.getQueuedSnapshot() >= threshold && netPerMinute >= growth
                : minimum >= threshold && netPerMinute >= -maxDecrease;
        String evidence = Json.write(value);
        return breach ? EvaluationOutcome.breach(evidence, String.format(Locale.ROOT,
                "Queue %s: estimated net change %.2f messages/min across %.1fs of samples (depth %d)",mode.toLowerCase(Locale.ROOT),netPerMinute,elapsed,last.getQueuedSnapshot()))
                : EvaluationOutcome.ok(evidence);
    }

    private static EvaluationOutcome unknown(String reason, JsonNode config) {
        var value = Json.mapper().createObjectNode(); value.put("mode",config.path("mode").asText());
        value.put("reason",reason); value.putNull("estimatedNetChangePerMinute");value.putNull("queueDepth");
        return EvaluationOutcome.insufficientData(Json.write(value));
    }
}
