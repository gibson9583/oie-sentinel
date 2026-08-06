/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorHistoryPoint;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;

/**
 * The read behind {@code GET /monitors/{id}/history}: how often one monitor has
 * fired, and how long its problems took to clear, as a continuous daily series.
 *
 * <p>Answers the question a monitor's own configuration cannot — "is this rule
 * earning its keep?" A monitor that opened forty problems last week is either
 * watching something genuinely broken or is mistuned, and the difference is
 * visible in the shape of the series rather than in any one alert. Mean time to
 * resolve alongside it separates the two: a high count with a low MTTR is
 * usually a flapping threshold, while a low count with a high MTTR is the
 * monitor doing exactly its job.</p>
 *
 * <h2>Gap filling</h2>
 *
 * <p>{@code AlertEventRepository.listMonitorHistory} groups over the events
 * themselves, so a day with no alerts produces no row at all (see
 * {@link MonitorHistoryPoint}). This service emits one point for every calendar
 * day in the requested range regardless, because the alternative draws a
 * falsehood: a chart fed only the days that had alerts spaces them evenly and
 * turns a quiet fortnight into a straight line between two spikes.</p>
 *
 * <p>A filled day carries {@code alertCount = 0} and {@code avgResolveSeconds =
 * null}, and the asymmetry is the whole point. Zero alerts is a measurement — it
 * happened, and it is worth drawing. An MTTR of zero is not: it would say
 * "every problem that day was resolved instantly", the best possible reading,
 * for a day that had nothing to resolve. Left null, the MTTR series breaks
 * across the gap instead of diving to the floor. The same rule already applies
 * within a populated day, where the repository returns a null average for a day
 * whose alerts are all still open.</p>
 *
 * <h2>Day boundaries</h2>
 *
 * <p>Days are the JVM default zone's, matching how the repository interprets the
 * database's own date truncation ({@link MonitorHistoryPoint#getBucket()}). The
 * filled grid and the aggregated rows are therefore keyed on the same calendar,
 * and every emitted bucket is normalized to local midnight — including rows from
 * the database, so a vendor whose truncation lands somewhere marginally
 * different still snaps onto exactly one point of the series rather than
 * doubling a day.</p>
 */
public final class MonitorHistoryService {

    /** Range charted when the caller supplies no bounds — a month of daily points. */
    private static final int DEFAULT_RANGE_DAYS = 30;

    /**
     * Widest range that may be requested, in days.
     *
     * <p>Unlike the activity series, the cost being bounded here is not the
     * query — the aggregate is one {@code GROUP BY} whose result is at most one
     * row per day that had an alert. It is the gap filling: the response carries
     * a point for every day in the range whether or not anything happened, so an
     * unbounded {@code from} (epoch zero is one keystroke away in a hand-built
     * query string) would materialize twenty thousand mostly-empty points and
     * serialize them to anyone holding View Monitoring. A year and a day covers
     * every honest use — including a year-over-year comparison, which needs the
     * extra day to include both endpoints.</p>
     */
    private static final int MAX_RANGE_DAYS = 366;

    /**
     * How many events the channel-restricted path aggregates in Java.
     *
     * <p>Only that path scans rows at all; see {@link #build}. Sized well above
     * {@code DashboardService}'s scan limit because this query is far narrower
     * (one monitor, a bounded date range, the caller's channels) and runs on an
     * operator opening a chart rather than on every dashboard poll. A monitor
     * that exceeds it inside one range has an alerting problem the chart is
     * about to make obvious anyway — and the response says so rather than
     * quietly understating: see the {@code truncated} flag on the envelope.</p>
     */
    private static final int VISIBLE_EVENT_SCAN_LIMIT = 5000;

    private MonitorHistoryService() {
    }

    /**
     * Builds one monitor's daily history over a range.
     *
     * <p><b>Channel restrictions.</b> {@code visibleChannelIds} follows the same
     * contract as {@link DashboardService#build(Set)} and
     * {@link MetricsService#render(Set)}: {@code null} for an unrestricted
     * caller, otherwise the caller's authorized set, possibly empty. It is not
     * decoration here. A monitor's history looks like a property of the monitor,
     * but every alert counted into it belongs to a channel, and a monitor scoped
     * to a group, a tag, or all channels aggregates across channels the caller
     * may not be entrusted with. Serving the raw aggregate to a restricted
     * caller would hand them a per-day alert count and a resolve time for
     * exactly the channels {@code /problems}, {@code /dashboard/summary} and the
     * activity endpoints redact — anonymized, but not much: a spike on a day
     * where that caller saw no problems of their own is a direct statement that
     * something they cannot see broke, and its MTTR is a statement about how the
     * team handled it.</p>
     *
     * <p>So a restricted caller's series is computed from channel-filtered
     * events instead of from the SQL aggregate, which has no channel predicate.
     * That is the same rule {@code /dashboard/summary} applies — constrain every
     * channel-attributed element to the authorized set, leave non-channel facts
     * alone — implemented one layer up because the aggregation cannot be pushed
     * into a statement that does not take the filter. The monitor's
     * <em>existence</em> is deliberately not hidden: monitor definitions are not
     * channel-filtered anywhere in this plugin (an unknown id still 404s, a known
     * one still resolves), so this method narrows the data and never the
     * lookup.</p>
     *
     * <p>An empty authorized set short-circuits to an all-zero series rather
     * than to an empty {@code IN} filter, which the mapper reads as "no filter"
     * and would leak every channel — the same trap the servlet's other endpoints
     * guard against.</p>
     *
     * @param monitorId         the monitor to chart
     * @param fromEpochMillis   range start (epoch ms), or {@code null} for
     *                          {@code to} minus {@value #DEFAULT_RANGE_DAYS} days
     * @param toEpochMillis     range end (epoch ms), or {@code null} for now
     * @param visibleChannelIds the caller's authorized channel ids, or
     *                          {@code null} when the caller is unrestricted
     * @return {@code {monitorId, from, to, bucket, truncated, points}}, where
     *         {@code points} is one {@link MonitorHistoryPoint} per calendar day
     *         in the range, oldest first, with no gaps
     * @throws java.util.NoSuchElementException if no monitor has that id
     * @throws IllegalArgumentException         if the range is inverted or wider
     *                                          than {@value #MAX_RANGE_DAYS} days
     */
    public static Map<String, Object> build(int monitorId, Long fromEpochMillis, Long toEpochMillis,
            Set<String> visibleChannelIds) {
        // Existence check first: a chart for a monitor that is not there is a
        // 404, not an empty series that reads as "this monitor never fires".
        MonitorService.get(monitorId);

        Instant to = toEpochMillis != null ? Instant.ofEpochMilli(toEpochMillis) : Instant.now();
        Instant from = fromEpochMillis != null
                ? Instant.ofEpochMilli(fromEpochMillis)
                : to.minus(Duration.ofDays(DEFAULT_RANGE_DAYS));
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate firstDay = from.atZone(zone).toLocalDate();
        LocalDate lastDay = to.atZone(zone).toLocalDate();
        long days = ChronoUnit.DAYS.between(firstDay, lastDay) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("range is too wide to chart: the maximum is "
                    + MAX_RANGE_DAYS + " days, but " + days + " were requested");
        }

        boolean truncated = false;
        Map<LocalDate, MonitorHistoryPoint> byDay;
        if (visibleChannelIds == null) {
            byDay = aggregatedHistory(monitorId, from, to, zone);
        } else if (visibleChannelIds.isEmpty()) {
            byDay = new TreeMap<>();
        } else {
            PagedResult<AlertEvent> page = visibleEvents(monitorId, from, to, visibleChannelIds);
            truncated = page.total() > page.items().size();
            byDay = foldEvents(page.items(), zone);
        }

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("monitorId", monitorId);
        history.put("from", from);
        history.put("to", to);
        history.put("bucket", "DAY");
        history.put("truncated", truncated);
        history.put("points", fillGaps(byDay, firstDay, lastDay, zone));
        return history;
    }

    // ========== Sources ==========

    /**
     * The unrestricted path: the database's own {@code GROUP BY}, re-keyed onto
     * the local-day grid. Preferred wherever it is usable because its cost is
     * the number of days that had alerts rather than the number of alerts.
     */
    private static Map<LocalDate, MonitorHistoryPoint> aggregatedHistory(int monitorId, Instant from,
            Instant to, ZoneId zone) {
        Map<LocalDate, MonitorHistoryPoint> byDay = new TreeMap<>();
        for (MonitorHistoryPoint point : AlertEventRepository.listMonitorHistory(monitorId, from, to)) {
            if (point.getBucket() == null) {
                continue;
            }
            byDay.put(point.getBucket().atZone(zone).toLocalDate(), point);
        }
        return byDay;
    }

    /**
     * The channel-restricted path: the caller's own alert events for this
     * monitor, newest first.
     *
     * <p>Newest first matters when the scan limit bites. Truncating a
     * descending page loses the oldest days, which the gap filling then draws as
     * zero — indistinguishable from quiet, but at the far edge of the chart,
     * where an operator is least likely to be reading a precise number. Ascending
     * would instead hollow out the last few days, which is where they are
     * certainly looking. Neither is honest on its own, which is why the response
     * also carries {@code truncated}.</p>
     */
    private static PagedResult<AlertEvent> visibleEvents(int monitorId, Instant from, Instant to,
            Set<String> visibleChannelIds) {
        AlertEventFilter filter = new AlertEventFilter();
        filter.setMonitorId(monitorId);
        filter.setChannelIdIn(new ArrayList<>(visibleChannelIds));
        filter.setFrom(from);
        filter.setTo(to);
        filter.setSortColumn("opened_time");
        filter.setSortDir("DESC");
        filter.setPage(0);
        filter.setPageSize(VISIBLE_EVENT_SCAN_LIMIT);
        return AlertEventRepository.listAlertEvents(filter);
    }

    /**
     * Reproduces the SQL aggregate in Java over an event list: count by the day
     * the alert <em>opened</em>, and average the open-to-resolve elapsed seconds
     * of that day's resolved alerts only.
     *
     * <p>Both halves match {@link MonitorHistoryPoint}'s documented semantics
     * deliberately, so the restricted and unrestricted paths differ in which
     * events they see and in nothing else. An alert that opens on one day and
     * resolves on the next counts once, on the day it opened, and contributes
     * its full duration to that day. A still-open alert counts but contributes
     * no duration — averaging it in as zero would drag MTTR down precisely while
     * an incident is running.</p>
     */
    private static Map<LocalDate, MonitorHistoryPoint> foldEvents(List<AlertEvent> events, ZoneId zone) {
        Map<LocalDate, MonitorHistoryPoint> byDay = new TreeMap<>();
        Map<LocalDate, long[]> resolved = new TreeMap<>(); // [total seconds, resolved count]

        for (AlertEvent event : events) {
            if (event.getOpenedTime() == null) {
                continue;
            }
            LocalDate day = event.getOpenedTime().atZone(zone).toLocalDate();
            MonitorHistoryPoint point = byDay.computeIfAbsent(day, d -> {
                MonitorHistoryPoint fresh = new MonitorHistoryPoint();
                fresh.setAlertCount(0L);
                return fresh;
            });
            point.setAlertCount(point.getAlertCount() + 1);

            if (event.getResolvedTime() != null) {
                long[] totals = resolved.computeIfAbsent(day, d -> new long[2]);
                totals[0] += Duration.between(event.getOpenedTime(), event.getResolvedTime()).getSeconds();
                totals[1]++;
            }
        }

        for (Map.Entry<LocalDate, long[]> entry : resolved.entrySet()) {
            long[] totals = entry.getValue();
            if (totals[1] > 0) {
                byDay.get(entry.getKey()).setAvgResolveSeconds((double) totals[0] / totals[1]);
            }
        }
        return byDay;
    }

    // ========== Gap filling ==========

    /**
     * Expands the sparse per-day map into one point per calendar day from
     * {@code firstDay} to {@code lastDay} inclusive, oldest first.
     *
     * <p>Every emitted point's bucket is {@code day.atStartOfDay(zone)}, so the
     * x-axis is uniform time by construction and a database row that landed a
     * few hours off local midnight is normalized onto the same instant its
     * filled neighbours use. Days with no data get a fresh zero-count,
     * null-MTTR point rather than a shared instance — the points are mutable
     * beans about to be serialized, and one shared object aliased across a
     * hundred quiet days is a defect waiting for the first caller that touches
     * one.</p>
     */
    private static List<MonitorHistoryPoint> fillGaps(Map<LocalDate, MonitorHistoryPoint> byDay,
            LocalDate firstDay, LocalDate lastDay, ZoneId zone) {
        List<MonitorHistoryPoint> points = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            MonitorHistoryPoint point = byDay.get(day);
            if (point == null) {
                point = new MonitorHistoryPoint();
                point.setAlertCount(0L);
                // avgResolveSeconds stays null: see the class Javadoc for why a
                // day with nothing to resolve must not report resolving fast.
            }
            point.setBucket(day.atStartOfDay(zone).toInstant());
            points.add(point);
        }
        return points;
    }
}
