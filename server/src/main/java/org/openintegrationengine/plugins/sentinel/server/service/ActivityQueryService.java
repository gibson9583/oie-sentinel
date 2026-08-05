/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityPoint;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityTrend;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelActivity;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelActivitySummary;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Read-side queries over the collected activity data: a per-channel time
 * series for charting, and a batched multi-channel totals+sparkline summary
 * for the dashboard cards.
 *
 * <p><b>Granularity.</b> Raw collector samples (one row per collector tick)
 * exist only inside the short sample-retention horizon; the hourly rollup
 * lives for months. {@code AUTO} picks raw samples for ranges up to
 * {@value #AUTO_RAW_MAX_HOURS} hours and the hourly rollup beyond that, so
 * the client can chart any range without knowing where the data lives —
 * short ranges get tick-level detail, long ranges get a bounded point count
 * that a chart can actually draw.</p>
 */
public final class ActivityQueryService {

    private static final Logger log = LoggerFactory.getLogger(ActivityQueryService.class);

    /** AUTO granularity switches from RAW to HOURLY beyond this range width. */
    private static final int AUTO_RAW_MAX_HOURS = 6;

    /** Default charting range when the client sends no bounds. */
    private static final Duration DEFAULT_RANGE = Duration.ofHours(6);

    private static final int DEFAULT_WINDOW_SECONDS = 3600;
    private static final int DEFAULT_BUCKETS = 20;

    /**
     * Ceiling on sparkline buckets: beyond a few hundred points a sparkline
     * is indistinguishable, and the bucket array is allocated per channel.
     */
    private static final int MAX_BUCKETS = 500;

    private ActivityQueryService() {
    }

    /**
     * Returns one channel's activity time series.
     *
     * @param channelId       the channel to chart
     * @param fromEpochMillis range start (epoch ms), or {@code null} for
     *                        {@code to} minus the default range
     * @param toEpochMillis   range end (epoch ms), or {@code null} for now
     * @param granularity     {@code AUTO} (default when null/blank),
     *                        {@code RAW}, or {@code HOURLY}
     * @return the series; {@code granularity} on the result is the resolved
     *         value ({@code RAW} or {@code HOURLY}), never {@code AUTO}, so
     *         the client knows what it is drawing
     * @throws IllegalArgumentException on a missing channel id, an inverted
     *                                  range, or an unknown granularity
     */
    public static ChannelActivity getChannelActivity(String channelId, Long fromEpochMillis,
            Long toEpochMillis, String granularity) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId is required");
        }
        Instant to = toEpochMillis != null ? Instant.ofEpochMilli(toEpochMillis) : Instant.now();
        Instant from = fromEpochMillis != null ? Instant.ofEpochMilli(fromEpochMillis) : to.minus(DEFAULT_RANGE);
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }

        String requested = granularity == null || granularity.isBlank()
                ? "AUTO" : granularity.trim().toUpperCase(Locale.ROOT);
        String resolved;
        switch (requested) {
            case "RAW":
            case "HOURLY":
                resolved = requested;
                break;
            case "AUTO":
                resolved = Duration.between(from, to).compareTo(Duration.ofHours(AUTO_RAW_MAX_HOURS)) <= 0
                        ? "RAW" : "HOURLY";
                break;
            default:
                throw new IllegalArgumentException("granularity must be AUTO, RAW, or HOURLY");
        }

        ChannelActivity activity = new ChannelActivity();
        activity.setChannelId(channelId);
        activity.setGranularity(resolved);
        activity.setPoints("RAW".equals(resolved)
                ? rawPoints(channelId, from, to)
                : hourlyPoints(channelId, from, to));
        return activity;
    }

    /**
     * Returns per-channel totals plus a compact sparkline for a set of
     * channels in one call — batched deliberately so the dashboard's channel
     * cards don't issue N+1 activity requests.
     *
     * @param channelIds    the channels to summarize; {@code null} means
     *                      "all watched channels" (every started channel
     *                      covered by an enabled monitor), while an empty
     *                      list means exactly that — nothing (the servlet's
     *                      channel-restriction redaction can legitimately
     *                      empty the list, and that must not widen back to
     *                      everything)
     * @param windowSeconds how far back to aggregate (non-positive →
     *                      {@value #DEFAULT_WINDOW_SECONDS})
     * @param buckets       sparkline resolution (non-positive →
     *                      {@value #DEFAULT_BUCKETS}, capped at
     *                      {@value #MAX_BUCKETS})
     * @return one summary per channel, in input order; never {@code null}
     */
    public static List<ChannelActivitySummary> getActivitySummary(List<String> channelIds,
            int windowSeconds, int buckets) {
        List<String> targets = channelIds != null ? channelIds : watchedChannelIds();
        int window = windowSeconds > 0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
        int bucketCount = buckets > 0 ? Math.min(buckets, MAX_BUCKETS) : DEFAULT_BUCKETS;

        Instant to = Instant.now();
        Instant from = to.minusSeconds(window);

        List<ChannelActivitySummary> summaries = new ArrayList<>(targets.size());
        for (String channelId : targets) {
            if (channelId == null || channelId.isBlank()) {
                continue;
            }
            summaries.add(summarizeChannel(channelId.trim(), from, to, bucketCount));
        }
        return summaries;
    }

    /**
     * CSV convenience overload for the servlet's raw query parameter. A
     * null/blank CSV maps to {@code null} (all watched channels) per the
     * REST contract.
     */
    public static List<ChannelActivitySummary> getActivitySummary(String channelIdsCsv,
            int windowSeconds, int buckets) {
        List<String> channelIds = null;
        if (channelIdsCsv != null && !channelIdsCsv.isBlank()) {
            channelIds = new ArrayList<>();
            for (String token : channelIdsCsv.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    channelIds.add(trimmed);
                }
            }
        }
        return getActivitySummary(channelIds, windowSeconds, buckets);
    }

    // ========== Series building ==========

    /** Raw collector samples mapped 1:1 to chart points. */
    private static List<ActivityPoint> rawPoints(String channelId, Instant from, Instant to) {
        List<ActivityPoint> points = new ArrayList<>();
        for (ActivitySample sample : ActivityRepository.listActivitySamples(channelId, from, to)) {
            ActivityPoint point = new ActivityPoint();
            point.setTime(sample.getSampleTime());
            point.setReceived(sample.getReceivedDelta());
            point.setSent(sample.getSentDelta());
            point.setError(sample.getErrorDelta());
            point.setFiltered(sample.getFilteredDelta());
            point.setQueued(sample.getQueuedSnapshot());
            points.add(point);
        }
        return points;
    }

    /**
     * Hourly rollup rows mapped to chart points. The repository query is
     * lower-bounded only, so the upper bound is applied here. Two lossy
     * mappings are deliberate: {@code filtered} is 0 because the trend table
     * does not roll that column up (it earns no baseline and no monitor
     * reads it), and {@code queued} is the hour's <em>average</em> queue
     * depth rounded to a long — the closest single number the rollup keeps.
     */
    private static List<ActivityPoint> hourlyPoints(String channelId, Instant from, Instant to) {
        List<ActivityPoint> points = new ArrayList<>();
        for (ActivityTrend trend : ActivityRepository.listActivityTrendSince(channelId, from)) {
            if (trend.getHourBucket() == null || trend.getHourBucket().isAfter(to)) {
                continue;
            }
            ActivityPoint point = new ActivityPoint();
            point.setTime(trend.getHourBucket());
            point.setReceived(trend.getReceivedSum());
            point.setSent(trend.getSentSum());
            point.setError(trend.getErrorSum());
            point.setFiltered(0L);
            point.setQueued(Math.round(trend.getAvgQueued()));
            points.add(point);
        }
        return points;
    }

    /**
     * One channel's totals (a single SQL aggregate — no raw rows pulled for
     * the numbers) plus its sparkline (raw samples reduced into equal
     * sub-windows in Java, oldest first).
     */
    private static ChannelActivitySummary summarizeChannel(String channelId, Instant from, Instant to,
            int bucketCount) {
        ChannelActivitySummary summary = new ChannelActivitySummary();
        summary.setChannelId(channelId);
        summary.setChannelName(ScopeResolver.channelName(channelId));

        ActivityAggregate aggregate = ActivityRepository.sumActivitySamplesForRange(channelId, from, to);
        if (aggregate != null) {
            summary.setReceived(aggregate.getReceivedSum());
            summary.setSent(aggregate.getSentSum());
            summary.setError(aggregate.getErrorSum());
        }

        summary.setSparkline(sparkline(channelId, from, to, bucketCount));
        return summary;
    }

    /**
     * Sums received deltas into {@code bucketCount} equal sub-windows of
     * {@code [from, to]}. Buckets with no samples stay zero rather than
     * being skipped, so the sparkline's x-axis is uniform time — a gap in
     * collection <em>looks like</em> a gap.
     */
    private static List<Long> sparkline(String channelId, Instant from, Instant to, int bucketCount) {
        List<Long> buckets = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            buckets.add(0L);
        }
        long windowMillis = to.toEpochMilli() - from.toEpochMilli();
        if (windowMillis <= 0) {
            return buckets;
        }
        for (ActivitySample sample : ActivityRepository.listActivitySamples(channelId, from, to)) {
            if (sample.getSampleTime() == null) {
                continue;
            }
            long offsetMillis = sample.getSampleTime().toEpochMilli() - from.toEpochMilli();
            int index = (int) (offsetMillis * bucketCount / windowMillis);
            index = Math.max(0, Math.min(bucketCount - 1, index));
            buckets.set(index, buckets.get(index) + sample.getReceivedDelta());
        }
        return buckets;
    }

    /**
     * The default channel set for a summary request with no explicit ids:
     * every started channel covered by an enabled monitor — the same
     * "watched" universe the dashboard's coverage number uses, which is what
     * a dashboard card grid with no explicit selection should show.
     * Insertion-ordered so the card order is stable across refreshes.
     */
    private static List<String> watchedChannelIds() {
        Set<String> watched = new LinkedHashSet<>();
        for (Monitor monitor : MonitorRepository.listMonitors(null, null, Boolean.TRUE, null)) {
            try {
                for (ScopeResolver.ChannelTarget target : ScopeResolver.resolveStartedChannels(monitor)) {
                    watched.add(target.channelId);
                }
            } catch (Exception e) {
                log.warn("Could not resolve scope for monitor {} while listing watched channels",
                        monitor.getId(), e);
            }
        }
        return new ArrayList<>(watched);
    }
}
