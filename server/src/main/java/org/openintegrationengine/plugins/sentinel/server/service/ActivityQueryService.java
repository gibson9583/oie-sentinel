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
 *
 * <p><b>Point ceiling.</b> Choosing the source is not by itself a bound: an
 * explicit {@code granularity=RAW} over the 90 day sample-retention ceiling
 * is a quarter of a million points, and {@code HOURLY} over the 3650 day
 * trend-retention ceiling is 87,600 — each carrying four counts and a queue
 * depth, serialized as JSON to anyone holding nothing more than View
 * Monitoring. So no series ever exceeds {@value #MAX_POINTS} points; a range
 * that would is folded into that many equal buckets server-side, and only a
 * range too wide to even read ({@link #MAX_SOURCE_POINTS}) is refused.</p>
 *
 * <p><b>Honest granularity.</b> Because the server may quietly change what it
 * drew — folding raw samples into five minute buckets, or promoting an
 * over-wide {@code RAW} request to the hourly rollup — the {@code
 * granularity} on the response reports the resolution that was
 * <em>actually drawn</em>, never the one that was asked for. A client that
 * tests {@code granularity == "RAW"} to decide whether it is looking at
 * tick resolution must get {@code false} for a folded series, or it will
 * label an averaged queue depth as an instantaneous reading.</p>
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

    /**
     * Ceiling on the points in a channel series. Sized to what a chart can
     * use rather than to what the tables hold: recharts draws a few thousand
     * points comfortably and degrades badly past that, and every point beyond
     * the pixel width of the plot is invisible detail that still costs JSON
     * on the wire and DOM in the browser. Series longer than this are folded
     * into exactly this many buckets rather than truncated — truncation would
     * silently drop one end of the requested range, which is worse than a
     * coarser but complete picture.
     */
    private static final int MAX_POINTS = 2000;

    /**
     * Ceiling on the <em>source</em> rows a request may read in order to
     * produce those points. Folding requires the rows in memory (the
     * repository's list queries have no {@code LIMIT} and no server-side
     * bucketing), so this — not {@link #MAX_POINTS} — is what actually bounds
     * the work a caller holding only View Monitoring can ask the server to
     * do. Sized so the entire hourly retention ceiling stays chartable
     * (3650 days is 87,600 hour buckets); past it there is no source cheap
     * enough to fall back to, so the request is refused.
     */
    private static final int MAX_SOURCE_POINTS = 100_000;

    /**
     * Raw-sample density used to size a range <em>before</em> reading it: the
     * lowest collector interval {@code SettingsService} accepts, matching
     * {@code InactivityEvaluator}'s use of the highest one. The current
     * setting is deliberately not consulted — samples are written at whatever
     * interval was configured when they were collected, so an operator who
     * raised the interval yesterday would make today's estimate understate a
     * decade of denser rows, and understating is the one direction that
     * defeats the point of estimating at all. The floor can only overstate.
     */
    private static final int MIN_COLLECTOR_INTERVAL_SECONDS = 10;

    /** Seconds per hourly rollup bucket — the point spacing of a HOURLY series. */
    private static final int HOURLY_BUCKET_SECONDS = 3600;

    private ActivityQueryService() {
    }

    /**
     * Returns one channel's activity time series, never longer than
     * {@value #MAX_POINTS} points.
     *
     * <p>Three things can happen to a range that would exceed the ceiling,
     * in this order of preference:</p>
     * <ol>
     *   <li><b>Fold.</b> The resolved source is read and its points are
     *       summed into {@value #MAX_POINTS} equal buckets. Preferred because
     *       it keeps the requested source: a folded raw series still carries
     *       real {@code filtered} counts and snapshot-derived queue depths,
     *       which the hourly rollup cannot reproduce.</li>
     *   <li><b>Promote.</b> When the raw rows needed to fold would themselves
     *       exceed {@link #MAX_SOURCE_POINTS}, {@code RAW} falls back to the
     *       hourly rollup, which covers the same range in 1/360th of the
     *       rows, and folds that instead if it is still too long.</li>
     *   <li><b>Refuse.</b> A range too wide to read even hourly is a 400
     *       naming the maximum for that granularity, since no coarser source
     *       exists to fall back to.</li>
     * </ol>
     *
     * @param channelId       the channel to chart
     * @param fromEpochMillis range start (epoch ms), or {@code null} for
     *                        {@code to} minus the default range
     * @param toEpochMillis   range end (epoch ms), or {@code null} for now
     * @param granularity     {@code AUTO} (default when null/blank),
     *                        {@code RAW}, or {@code HOURLY}
     * @return the series, at most {@value #MAX_POINTS} points long;
     *         {@code granularity} on the result is what was actually drawn
     *         and never {@code AUTO}: {@code RAW} or {@code HOURLY} for a
     *         source read one-for-one, or {@code RAW_}/{@code HOURLY_} suffixed
     *         with the ISO-8601 bucket width for a folded series (e.g.
     *         {@code RAW_PT5M2.4S}), so an equality test against {@code "RAW"}
     *         cannot mistake folded points for tick resolution
     * @throws IllegalArgumentException on a missing channel id, an inverted
     *                                  range, an unknown granularity, or a
     *                                  range too wide to read at any
     *                                  available granularity
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

        // Promote and refuse before touching the database. Both decisions turn
        // on how many rows the query would return, and the repository has no
        // count and no LIMIT, so an estimate is the only way to make them
        // without first performing the read they exist to avoid.
        if ("RAW".equals(resolved) && estimatedSourcePoints(resolved, from, to) > MAX_SOURCE_POINTS) {
            log.debug("Promoting RAW to HOURLY for channel {}: a {} day range exceeds {} raw samples",
                    channelId, Duration.between(from, to).toDays(), MAX_SOURCE_POINTS);
            resolved = "HOURLY";
        }
        if (estimatedSourcePoints(resolved, from, to) > MAX_SOURCE_POINTS) {
            throw new IllegalArgumentException("range is too wide to chart: at " + resolved
                    + " granularity the maximum is " + maxRangeDays(resolved) + " days, but "
                    + Duration.between(from, to).toDays() + " were requested");
        }

        List<ActivityPoint> points = "RAW".equals(resolved)
                ? rawPoints(channelId, from, to)
                : hourlyPoints(channelId, from, to);

        // Enforced on the fetched series, not on the estimate: a sparse
        // channel under the ceiling keeps its true granularity, and a denser
        // one than the estimate predicted is still capped.
        String drawn = resolved;
        if (points.size() > MAX_POINTS) {
            drawn = resolved + "_" + Duration.between(from, to).dividedBy(MAX_POINTS);
            points = downsample(points, from, to, MAX_POINTS);
        }

        ChannelActivity activity = new ChannelActivity();
        activity.setChannelId(channelId);
        activity.setGranularity(drawn);
        activity.setPoints(points);
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
     * Upper bound on the rows a series would read, computed from the range
     * width alone so it can be known before the query runs. Raw samples are
     * assumed to arrive at {@value #MIN_COLLECTOR_INTERVAL_SECONDS} second
     * intervals and hourly buckets hourly; gaps in collection only make the
     * real count smaller, which is the harmless direction.
     *
     * <p>The hourly figure bounds what is <em>charted</em>, not quite what is
     * fetched — {@link #hourlyPoints}' query is lower-bounded only and applies
     * {@code to} in Java, so a narrow window with an old {@code from} still
     * reads to the present. That read is bounded anyway: trend retention caps
     * the table at 3650 days of buckets per channel, 87,600 rows, which is
     * why {@link #MAX_SOURCE_POINTS} is sized above it.</p>
     */
    private static long estimatedSourcePoints(String granularity, Instant from, Instant to) {
        long perPointSeconds = "RAW".equals(granularity)
                ? MIN_COLLECTOR_INTERVAL_SECONDS : HOURLY_BUCKET_SECONDS;
        return Duration.between(from, to).getSeconds() / perPointSeconds + 1;
    }

    /**
     * The widest range, in whole days, that {@link #estimatedSourcePoints}
     * will pass at a given granularity — the number the 400 quotes, so the
     * caller is told what to ask for instead of just being told no.
     */
    private static long maxRangeDays(String granularity) {
        long perPointSeconds = "RAW".equals(granularity)
                ? MIN_COLLECTOR_INTERVAL_SECONDS : HOURLY_BUCKET_SECONDS;
        return Math.max(1, Duration.ofSeconds((long) MAX_SOURCE_POINTS * perPointSeconds).toDays());
    }

    /**
     * Folds an over-long series into {@code bucketCount} equal sub-windows of
     * {@code [from, to]}, oldest first — the same equal-sub-window reduction
     * {@link #sparkline} performs, over every metric instead of just
     * received.
     *
     * <p>Counts (received/sent/error/filtered) are summed, since they are
     * per-interval totals and a wider interval is simply their sum. Queue
     * depth is a level rather than a count, so it is averaged and rounded,
     * matching what the hourly rollup does to the same column — a summed
     * queue depth would be a meaningless number that grows with the bucket
     * width.</p>
     *
     * <p>Buckets with no source points are dropped rather than emitted as
     * zeros, unlike the sparkline: a chart series with an explicit zero is
     * asserting that the channel was idle and its queue was empty, which is a
     * different claim from having no reading at all. The one-for-one paths
     * make the same choice by construction — neither invents a row for a
     * period that produced none.</p>
     */
    private static List<ActivityPoint> downsample(List<ActivityPoint> points, Instant from, Instant to,
            int bucketCount) {
        long[] received = new long[bucketCount];
        long[] sent = new long[bucketCount];
        long[] error = new long[bucketCount];
        long[] filtered = new long[bucketCount];
        long[] queuedTotal = new long[bucketCount];
        int[] sourceCount = new int[bucketCount];

        for (ActivityPoint point : points) {
            if (point.getTime() == null) {
                continue;
            }
            int index = bucketIndex(point.getTime(), from, to, bucketCount);
            received[index] += point.getReceived();
            sent[index] += point.getSent();
            error[index] += point.getError();
            filtered[index] += point.getFiltered();
            queuedTotal[index] += point.getQueued();
            sourceCount[index]++;
        }

        long windowMillis = to.toEpochMilli() - from.toEpochMilli();
        List<ActivityPoint> folded = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) {
            if (sourceCount[i] == 0) {
                continue;
            }
            ActivityPoint point = new ActivityPoint();
            // Bucket start, matching hourlyPoints' use of the hour-bucket start.
            point.setTime(from.plusMillis(windowMillis * i / bucketCount));
            point.setReceived(received[i]);
            point.setSent(sent[i]);
            point.setError(error[i]);
            point.setFiltered(filtered[i]);
            point.setQueued(Math.round((double) queuedTotal[i] / sourceCount[i]));
            folded.add(point);
        }
        return folded;
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
            int index = bucketIndex(sample.getSampleTime(), from, to, bucketCount);
            buckets.set(index, buckets.get(index) + sample.getReceivedDelta());
        }
        return buckets;
    }

    /**
     * Which of {@code bucketCount} equal sub-windows of {@code [from, to]} an
     * instant falls in — the one piece of arithmetic {@link #sparkline} and
     * {@link #downsample} share, kept in one place so the two reductions can
     * never disagree about where a bucket boundary is.
     *
     * <p>Clamped to the array bounds: {@code to} itself lands exactly on
     * {@code bucketCount}, and both callers' ranges are inclusive of their
     * upper bound.</p>
     */
    private static int bucketIndex(Instant time, Instant from, Instant to, int bucketCount) {
        long windowMillis = to.toEpochMilli() - from.toEpochMilli();
        if (windowMillis <= 0) {
            return 0;
        }
        long offsetMillis = time.toEpochMilli() - from.toEpochMilli();
        int index = (int) (offsetMillis * bucketCount / windowMillis);
        return Math.max(0, Math.min(bucketCount - 1, index));
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
