/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Evaluates QUEUE_DEPTH monitors: breach when a channel's queued-message count
 * has stood at or above a configured threshold for at least a configured
 * duration.
 *
 * <p>Config JSON: {@code {threshold, minDurationSeconds}}.</p>
 *
 * <h2>A gauge, not a counter</h2>
 *
 * <p>Every other activity monitor sums deltas: {@code received_delta} and
 * {@code error_delta} are the traffic that happened <em>between</em> two
 * collector ticks, so "how much arrived in the last hour" is genuinely the sum
 * of the rows in that hour. {@code queued_snapshot} is a different kind of
 * number entirely — it is the depth of the queue at the instant the sample was
 * taken, a level rather than a flow. Summing it is meaningless: a channel
 * sitting at a steady, perfectly healthy depth of 5 would "sum" to 600 over an
 * hour of 30-second ticks, and the same channel sampled twice as often would
 * "sum" to 1200 without anything about it having changed. The current depth is
 * therefore read from the <em>latest</em> sample, and the sample series is used
 * only to establish how long that depth has persisted.</p>
 *
 * <h2>Establishing duration from the sample series</h2>
 *
 * <p>{@code minDurationSeconds} means the depth must have been at or above the
 * threshold continuously for that long — a queue that spikes for one tick
 * while a destination reconnects is normal, one that has been deep for twenty
 * minutes is a backlog. {@link ConnectionStatusEvaluator} answers the same
 * question from {@code CollectorState}'s in-memory since-stamp, but there is
 * no such stamp for queue depth and this evaluator deliberately does not add
 * one: an in-memory stamp is lost on plugin restart, and a backlog is exactly
 * the condition that outlives a restart.</p>
 *
 * <p>Instead the duration is reconstructed from the timestamped rows already
 * in {@code sentinel_channel_activity_sample}: one window of samples is read
 * with the existing {@code listActivitySamples} query and walked backwards
 * from the newest, extending the "above threshold" run until a sample below
 * the threshold ends it. The run's first sample time is the earliest moment
 * the depth is known to have been high, and the sustained duration is measured
 * from there to {@code now}. That makes the reading survive a restart at the
 * cost of granularity: the crossing can only be located to the collector tick
 * that observed it, so the reported duration is a lower bound, never an
 * overstatement. The window read is {@code minDurationSeconds} plus one
 * collector interval of slack, which is all that is needed — a run that fills
 * the whole window already clears any threshold the config can express, so
 * reading further back could not change the verdict.</p>
 *
 * <p>The collector samples deployed-but-paused channels too (zero-delta rows
 * kept precisely for queue-depth continuity), so the series does not gap when
 * a channel is paused mid-backlog. What it does gap on is the collector not
 * running at all, and a stale latest sample is treated as
 * {@code INSUFFICIENT_DATA} rather than as a current reading: a gauge is only
 * as good as its last reading, and alerting (or, worse, resolving) on a depth
 * measured ten minutes ago would be reporting history as if it were the
 * present.</p>
 *
 * <p>Note that this evaluator applies no "started throughout the window"
 * gate, unlike {@link InactivityEvaluator} and {@link LowVolumeEvaluator}.
 * Those two infer a breach from an <em>absence</em> of traffic, which an
 * unwatched window fakes perfectly. A queue depth is a present-tense fact
 * about a channel that is deployed and started right now; a channel restarted
 * two minutes ago with 50,000 messages still queued has a real problem, and
 * suppressing it until the restart aged out of the window would silence the
 * monitor at the one moment it is most useful.</p>
 *
 * <p>Value JSON shape: {@code {"queueDepth", "threshold", "sustainedSeconds",
 * "minDurationSeconds", "sampleTimeIso"}} — the latter two null-free, and
 * {@code queueDepth}/{@code sampleTimeIso} JSON {@code null} when no usable
 * sample exists, keeping one stable shape for the UI.</p>
 */
public final class QueueDepthEvaluator {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthEvaluator.class);

    /**
     * Default queue depth that counts as a backlog. Deliberately generous: a
     * queue is a buffer and is supposed to hold messages, so the seed value
     * has to be well clear of normal burst absorption or the monitor teaches
     * operators to ignore it.
     */
    private static final long DEFAULT_THRESHOLD = 1000L;

    /**
     * Default duration the depth must hold before it counts as a breach. Five
     * minutes distinguishes a backlog from the burst a destination clears on
     * its next reconnect.
     */
    private static final long DEFAULT_MIN_DURATION_SECONDS = 300L;

    /**
     * Slack added to the read window, and the age past which the newest sample
     * is considered stale. Sized to the maximum collector interval
     * SettingsService accepts (600s): with any legal collector configuration a
     * running collector must have left a sample within this many seconds, so
     * anything older means collection stopped rather than that the queue is
     * quiet.
     */
    private static final int COLLECTOR_SLACK_SECONDS = 600;

    private QueueDepthEvaluator() {
    }

    /**
     * Evaluates one channel against a QUEUE_DEPTH monitor.
     *
     * @param monitor   the monitor whose {@code configJson} supplies the depth
     *                  threshold and minimum duration
     * @param channelId the OIE channel id (a UUID string) to evaluate
     * @param now       the evaluation instant (passed in so every trigger in a
     *                  tick judges the same window)
     * @return BREACH when the latest depth is at or above {@code threshold}
     *         and has been for at least {@code minDurationSeconds}, OK when
     *         the latest depth is below the threshold or has not held long
     *         enough, INSUFFICIENT_DATA when there is no sample in the read
     *         window or the newest one is too old to describe the present
     */
    public static EvaluationOutcome evaluate(Monitor monitor, String channelId, Instant now) {
        JsonNode config = parseConfig(monitor.getConfigJson());
        long threshold = Math.max(0L, config.path("threshold").asLong(DEFAULT_THRESHOLD));
        long minDurationSeconds = Math.max(0L,
                config.path("minDurationSeconds").asLong(DEFAULT_MIN_DURATION_SECONDS));

        // Only as far back as the verdict can depend on — see the class Javadoc.
        Instant from = now.minusSeconds(minDurationSeconds + COLLECTOR_SLACK_SECONDS);
        List<ActivitySample> samples = ActivityRepository.listActivitySamples(channelId, from, now);

        ActivitySample latest = newestUsable(samples);
        if (latest == null) {
            return EvaluationOutcome.insufficientData(buildValueJson(
                    null, threshold, 0L, minDurationSeconds, null));
        }
        // A gauge is only as current as its last reading: a sample older than
        // any legal collector interval means collection stopped, not that the
        // depth is what it was then.
        if (Duration.between(latest.getSampleTime(), now).getSeconds() > COLLECTOR_SLACK_SECONDS) {
            return EvaluationOutcome.insufficientData(buildValueJson(
                    null, threshold, 0L, minDurationSeconds, latest.getSampleTime()));
        }

        long depth = latest.getQueuedSnapshot();
        if (depth < threshold) {
            return EvaluationOutcome.ok(buildValueJson(
                    depth, threshold, 0L, minDurationSeconds, latest.getSampleTime()));
        }

        long sustainedSeconds = sustainedSeconds(samples, threshold, now);
        String valueJson = buildValueJson(depth, threshold, sustainedSeconds,
                minDurationSeconds, latest.getSampleTime());

        if (sustainedSeconds >= minDurationSeconds) {
            return EvaluationOutcome.breach(valueJson, String.format(
                    "Queue depth %d has been at or above %d for %ds",
                    depth, threshold, sustainedSeconds));
        }
        // Deep but not yet for long enough: a real OK, not a data gap. The
        // depth was measured and the condition (depth AND duration) does not
        // hold, so an open problem from an earlier backlog is entitled to
        // resolve here.
        return EvaluationOutcome.ok(valueJson);
    }

    /**
     * Returns the newest sample carrying a usable timestamp, or {@code null}
     * when the list holds none. The query orders by {@code sample_time}
     * ascending, so this is the last element; the scan backwards exists only
     * because a null timestamp cannot be compared against {@code now} and must
     * be skipped rather than NPE mid-tick.
     */
    private static ActivitySample newestUsable(List<ActivitySample> samples) {
        for (int i = samples.size() - 1; i >= 0; i--) {
            ActivitySample sample = samples.get(i);
            if (sample.getSampleTime() != null) {
                return sample;
            }
        }
        return null;
    }

    /**
     * Measures how long the depth has stood at or above the threshold, by
     * walking the ascending series backwards from the newest sample and
     * extending the run until a sample below the threshold ends it. The
     * duration runs from the first sample of that run to {@code now}, so it is
     * a lower bound on the true time above the threshold: the crossing itself
     * happened at some unobserved moment between that sample and the one
     * before it.
     *
     * <p>When the run reaches the start of the read window the answer is
     * truncated at roughly {@code minDurationSeconds + COLLECTOR_SLACK_SECONDS},
     * which is deliberate — that is already past any threshold the caller
     * could have asked about, so a longer read could only produce a bigger
     * number for the same verdict.</p>
     *
     * @param samples   the read window, ascending by sample time; the caller
     *                  has established that the newest sample is at or above
     *                  the threshold
     * @param threshold the configured depth threshold
     * @param now       the evaluation instant
     * @return seconds the depth is known to have been at or above the
     *         threshold, never negative
     */
    private static long sustainedSeconds(List<ActivitySample> samples, long threshold, Instant now) {
        Instant since = null;
        for (int i = samples.size() - 1; i >= 0; i--) {
            ActivitySample sample = samples.get(i);
            if (sample.getSampleTime() == null) {
                continue; // unusable row; neither extends nor breaks the run
            }
            if (sample.getQueuedSnapshot() < threshold) {
                break;
            }
            since = sample.getSampleTime();
        }
        if (since == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(since, now).getSeconds());
    }

    /**
     * Value JSON per the contract shape: {@code {queueDepth, threshold,
     * sustainedSeconds, minDurationSeconds, sampleTimeIso}}. {@code queueDepth}
     * and {@code sampleTimeIso} are boxed so the INSUFFICIENT_DATA cases render
     * as JSON null rather than as a depth of zero — "we have no reading" and
     * "the queue is empty" are opposite conclusions and must not share a
     * representation.
     */
    private static String buildValueJson(Long depth, long threshold, long sustainedSeconds,
            long minDurationSeconds, Instant sampleTime) {
        ObjectNode node = Json.mapper().createObjectNode();
        if (depth != null) {
            node.put("queueDepth", depth);
        } else {
            node.putNull("queueDepth");
        }
        node.put("threshold", threshold);
        node.put("sustainedSeconds", sustainedSeconds);
        node.put("minDurationSeconds", minDurationSeconds);
        if (sampleTime != null) {
            node.put("sampleTimeIso", sampleTime.toString());
        } else {
            node.putNull("sampleTimeIso");
        }
        return Json.write(node);
    }

    /**
     * Parses the monitor's config JSON, degrading to an empty object (all
     * defaults) on null/blank/malformed input — a broken config on one
     * monitor must produce a defaulted evaluation plus a warning, never an
     * aborted tick. MonitorService validates configs at save time, so this
     * path is defensive.
     */
    private static JsonNode parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Json.mapper().createObjectNode();
        }
        try {
            return Json.mapper().readTree(configJson);
        } catch (Exception e) {
            log.warn("Unparsable QUEUE_DEPTH config '{}'; using defaults", configJson, e);
            return Json.mapper().createObjectNode();
        }
    }
}
