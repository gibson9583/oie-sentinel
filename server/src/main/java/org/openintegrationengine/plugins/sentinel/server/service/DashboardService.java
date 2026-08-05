/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.DashboardSummary;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SeverityCount;
import org.openintegrationengine.plugins.sentinel.shared.model.TopChannel;

/**
 * Assembles the dashboard's entire top fold in one pass — severity counts,
 * acknowledgment backlog, top problem channels, monitor coverage, recent
 * problems, and the collector/evaluator/listener heartbeats.
 *
 * <p>Built server-side as a single aggregate (rather than the client fanning
 * out six requests) so the landing page renders atomically from one snapshot:
 * every number on it describes the same instant, and the payload stays small
 * regardless of table sizes.</p>
 *
 * <p><b>Aggregation bound:</b> the severity/acknowledgment/top-channel
 * numbers are computed over the first {@value #OPEN_EVENT_SCAN_LIMIT} open
 * problems (one page query, no second scan). {@code openTotal} always comes
 * from the query's true COUNT, so the headline number is exact; only the
 * per-severity/per-channel breakdowns would understate on a server drowning
 * in more than {@value #OPEN_EVENT_SCAN_LIMIT} simultaneous open problems —
 * a state in which precise breakdowns are no longer the operator's
 * problem.</p>
 */
public final class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /** How many open problems the breakdown aggregation scans (see class Javadoc). */
    private static final int OPEN_EVENT_SCAN_LIMIT = 1000;

    /** How many recent problems the dashboard's activity feed shows. */
    private static final int RECENT_PROBLEM_LIMIT = 10;

    /** How many top problem channels the dashboard highlights. */
    private static final int TOP_CHANNEL_LIMIT = 5;

    private DashboardService() {
    }

    /**
     * Builds the full dashboard summary from the current database and
     * in-memory collector state.
     *
     * <p><b>Channel restrictions:</b> {@code visibleChannelIds} is
     * {@code null} for unrestricted callers (no filtering). For a
     * channel-restricted caller the servlet passes their authorized set, and
     * every channel-attributed element — the open-problem scan feeding the
     * severity/acknowledgment/top-channel numbers, and the recent-problems
     * feed — is constrained to it. Otherwise the landing page would hand a
     * restricted viewer exactly the channel names and alert messages that
     * {@code /problems} and the activity endpoints redact. Monitor counts
     * and the channels-watched total remain global: they are configuration/
     * coverage scalars carrying no per-channel data.</p>
     *
     * @param visibleChannelIds the caller's authorized channel ids, or
     *                          {@code null} when the caller is unrestricted;
     *                          an empty set means "no channels visible"
     * @return the populated summary; never {@code null}
     */
    public static DashboardSummary build(Set<String> visibleChannelIds) {
        DashboardSummary summary = new DashboardSummary();

        PagedResult<AlertEvent> open = openProblems(visibleChannelIds);
        List<AlertEvent> openItems = open.items();

        summary.setOpenTotal((int) open.total());
        summary.setOpenBySeverity(countBySeverity(openItems));
        summary.setUnacknowledged(countUnacknowledged(openItems));
        summary.setTopChannels(topChannels(openItems));

        List<Monitor> monitors = MonitorRepository.listMonitors(null, null, null, null);
        summary.setMonitorsTotal(monitors.size());
        int enabled = 0;
        for (Monitor monitor : monitors) {
            if (monitor.isEnabled()) {
                enabled++;
            }
        }
        summary.setMonitorsEnabled(enabled);
        summary.setChannelsWatched(countWatchedChannels(monitors));

        summary.setRecentProblems(recentProblems(visibleChannelIds));

        CollectorState collectorState = CollectorState.getInstance();
        summary.setLastCollectorRun(collectorState.getLastCollectorRun());
        summary.setLastEvaluatorRun(collectorState.getLastEvaluatorRun());
        summary.setLastConnectorEvent(collectorState.getLastConnectorEvent());
        return summary;
    }

    /**
     * One page query for open problems, newest first, constrained to the
     * visible channel set when one applies. An empty visible set
     * short-circuits to an empty page — forwarding an empty {@code IN}
     * filter would mean "no filter" to the mapper and leak every channel.
     */
    private static PagedResult<AlertEvent> openProblems(Set<String> visibleChannelIds) {
        if (visibleChannelIds != null && visibleChannelIds.isEmpty()) {
            return new PagedResult<>(List.of(), 0L, 0, OPEN_EVENT_SCAN_LIMIT);
        }
        AlertEventFilter filter = new AlertEventFilter();
        filter.setStatus(AlertStatus.PROBLEM);
        if (visibleChannelIds != null) {
            filter.setChannelIdIn(new ArrayList<>(visibleChannelIds));
        }
        filter.setPage(0);
        filter.setPageSize(OPEN_EVENT_SCAN_LIMIT);
        return AlertEventRepository.listAlertEvents(filter);
    }

    /**
     * The recent-problems feed, post-filtered to the visible channel set.
     * Post-filtering (rather than a channel-aware query) can under-fill the
     * feed below {@value #RECENT_PROBLEM_LIMIT} entries for restricted
     * callers — accepted: the repository's recent query has no channel
     * filter in its contract, and a sparse feed is correct where a full one
     * would leak.
     */
    private static List<AlertEvent> recentProblems(Set<String> visibleChannelIds) {
        List<AlertEvent> recent = AlertEventRepository.listRecentAlertEvents(RECENT_PROBLEM_LIMIT);
        if (visibleChannelIds == null) {
            return recent;
        }
        List<AlertEvent> visible = new ArrayList<>();
        for (AlertEvent event : recent) {
            if (event.getChannelId() != null && visibleChannelIds.contains(event.getChannelId())) {
                visible.add(event);
            }
        }
        return visible;
    }

    /**
     * Counts open problems per severity, zero-filling every severity so the
     * dashboard's severity strip always renders all five tiles in enum order
     * — the client never has to know the severity universe.
     */
    private static List<SeverityCount> countBySeverity(List<AlertEvent> openItems) {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
        for (AlertEvent event : openItems) {
            if (event.getSeverity() != null) {
                counts.merge(event.getSeverity(), 1, Integer::sum);
            }
        }
        List<SeverityCount> result = new ArrayList<>(counts.size());
        for (Severity severity : Severity.values()) {
            SeverityCount count = new SeverityCount();
            count.setSeverity(severity);
            count.setCount(counts.get(severity));
            result.add(count);
        }
        return result;
    }

    /** Counts open problems no human has acknowledged yet — the "needs eyes" number. */
    private static int countUnacknowledged(List<AlertEvent> openItems) {
        int unacknowledged = 0;
        for (AlertEvent event : openItems) {
            if (event.getAcknowledgedBy() == null) {
                unacknowledged++;
            }
        }
        return unacknowledged;
    }

    /**
     * Ranks channels by open-problem count (ties broken by worse max
     * severity, so the more urgent channel wins the last slot) and keeps the
     * top {@value #TOP_CHANNEL_LIMIT}. Severity comparison is by enum
     * ordinal — the {@code Severity} declaration order is ascending badness
     * by contract.
     */
    private static List<TopChannel> topChannels(List<AlertEvent> openItems) {
        Map<String, TopChannel> byChannel = new HashMap<>();
        for (AlertEvent event : openItems) {
            String channelId = event.getChannelId();
            if (channelId == null) {
                continue;
            }
            TopChannel entry = byChannel.get(channelId);
            if (entry == null) {
                entry = new TopChannel();
                entry.setChannelId(channelId);
                entry.setChannelName(ScopeResolver.channelName(channelId));
                entry.setOpenCount(0);
                byChannel.put(channelId, entry);
            }
            entry.setOpenCount(entry.getOpenCount() + 1);
            if (event.getSeverity() != null && (entry.getMaxSeverity() == null
                    || event.getSeverity().ordinal() > entry.getMaxSeverity().ordinal())) {
                entry.setMaxSeverity(event.getSeverity());
            }
        }

        List<TopChannel> ranked = new ArrayList<>(byChannel.values());
        ranked.sort((a, b) -> {
            int byCount = Integer.compare(b.getOpenCount(), a.getOpenCount());
            if (byCount != 0) {
                return byCount;
            }
            int aSeverity = a.getMaxSeverity() != null ? a.getMaxSeverity().ordinal() : -1;
            int bSeverity = b.getMaxSeverity() != null ? b.getMaxSeverity().ordinal() : -1;
            return Integer.compare(bSeverity, aSeverity);
        });
        return ranked.size() > TOP_CHANNEL_LIMIT
                ? new ArrayList<>(ranked.subList(0, TOP_CHANNEL_LIMIT))
                : ranked;
    }

    /**
     * Counts the distinct started channels covered by at least one enabled
     * monitor — the dashboard's "coverage" number, answering "how much of my
     * running interface estate is actually being watched?". Each monitor's
     * scope resolution is individually guarded: one monitor pointing at a
     * deleted group must cost its own contribution, not the whole
     * dashboard.
     */
    private static int countWatchedChannels(List<Monitor> monitors) {
        Set<String> watched = new HashSet<>();
        for (Monitor monitor : monitors) {
            if (!monitor.isEnabled()) {
                continue;
            }
            try {
                for (ScopeResolver.ChannelTarget target : ScopeResolver.resolveStartedChannels(monitor)) {
                    watched.add(target.channelId);
                }
            } catch (Exception e) {
                log.warn("Could not resolve scope for monitor {} while counting watched channels",
                        monitor.getId(), e);
            }
        }
        return watched.size();
    }
}
