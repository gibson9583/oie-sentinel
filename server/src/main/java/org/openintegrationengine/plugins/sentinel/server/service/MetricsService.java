/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelInfo;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * Renders Sentinel's operational state as a Prometheus exposition document —
 * the entire body behind {@code GET /metrics}, so the servlet stays the thin
 * translate-and-delegate layer every other endpoint keeps it as.
 *
 * <p><b>Scrape budget is the design constraint.</b> Unlike every other read in
 * this plugin, this one runs unattended every 15 seconds for the life of the
 * server, so each series is either free or a single indexed count:</p>
 * <ul>
 * <li>Per-channel throughput reads {@link CollectorState}'s in-memory counter
 * snapshots — zero queries.</li>
 * <li>Heartbeat ages are two {@code volatile} field reads — zero queries.</li>
 * <li>The open-problem breakdown is one {@code COUNT} per severity (five
 * total, exact). Deliberately <i>not</i> reused from {@link DashboardService},
 * whose single scan-and-group-in-Java pass is capped at its scan limit: a
 * severity breakdown that silently plateaus during the exact storm it exists
 * to measure is worse than four extra counts, and a Prometheus series that
 * flattens under load poisons every alert rule built on it.</li>
 * </ul>
 *
 * <p><b>Why the channel totals are honest counters.</b> They are the engine's
 * own cumulative per-channel statistics as observed at the last collector
 * tick — not window sums — which is what earns them the {@code _total} suffix
 * and makes {@code rate()} meaningful. A channel redeploy resets the engine's
 * counters to zero, but Prometheus already models a counter going backwards as
 * a reset, so that is the one discontinuity it handles correctly on its own.
 * Their staleness is bounded by the collector interval (10–300s), which is why
 * they are exposed as counters to be rated rather than as instantaneous
 * gauges. A channel the collector has never sampled emits no series at all:
 * zeros would assert "this channel has processed nothing", which is a
 * different and stronger claim than "we have no reading".</p>
 *
 * <p><b>Channel redaction.</b> {@code visibleChannelIds} follows the same
 * contract as {@link DashboardService#build(Set)}: {@code null} for an
 * unrestricted caller, otherwise the caller's authorized set (possibly empty).
 * Both channel-attributed surfaces honor it — the per-channel series are
 * filtered to the set, and the open-problem counts are constrained by it, so a
 * channel-restricted user scraping {@code /metrics} learns neither the names
 * nor the traffic nor the problem counts of channels the UI hides from them.
 * An empty set short-circuits to no channel series and zero problem counts
 * rather than being forwarded as an empty {@code IN} filter, which the mapper
 * would read as "no filter" and leak every channel.</p>
 *
 * <p><b>Deliberately absent: dispatch success/failure by action type.</b>
 * {@code ActionDispatchLogRepository} exposes only a per-alert-event listing,
 * so counting dispatches by action type would mean either a new mapped
 * statement or an N+1 fan-out over alert events on every scrape. Worse, any
 * count derived from that table is not a counter at all: retention prunes it,
 * so the value would fall as well as rise and {@code rate()} would report
 * fiction. It is omitted rather than approximated, pending an aggregate
 * statement (or an in-memory counter on the dispatcher) that can back a real
 * monotonic series.</p>
 *
 * <p><b>Format.</b> Prometheus text exposition format 0.0.4: one metric family
 * at a time, each preceded by its {@code # HELP} and {@code # TYPE} lines and
 * with all of its samples contiguous, every line LF-terminated. No {@code
 * # EOF} trailer — that belongs to OpenMetrics 1.0, which is a different
 * content type than the one this endpoint declares. Every label value passes
 * through {@link #escapeLabelValue(String)}; channel names are operator-typed
 * free text and a stray quote would otherwise truncate the document into
 * unparseable garbage.</p>
 */
public final class MetricsService {

    /** Open (unresolved) problem count, broken down by severity. Gauge. */
    private static final String OPEN_PROBLEMS = "sentinel_problems_open";

    /** Cumulative messages received per channel. Counter. */
    private static final String CHANNEL_RECEIVED = "sentinel_channel_messages_received_total";

    /** Cumulative messages sent per channel. Counter. */
    private static final String CHANNEL_SENT = "sentinel_channel_messages_sent_total";

    /** Cumulative errored messages per channel. Counter. */
    private static final String CHANNEL_ERRORED = "sentinel_channel_messages_errored_total";

    /** Seconds since the collector job last completed a tick. Gauge. */
    private static final String COLLECTOR_HEARTBEAT = "sentinel_collector_heartbeat_age_seconds";

    /** Seconds since the evaluator job last completed a tick. Gauge. */
    private static final String EVALUATOR_HEARTBEAT = "sentinel_evaluator_heartbeat_age_seconds";

    /**
     * Page size for the per-severity counts. Only the paged result's
     * {@code total()} is read, but the repository pairs a {@code LIMIT} query
     * with the {@code COUNT}, and a zero limit is not portable across every
     * supported vendor — so one row is fetched and discarded.
     */
    private static final int COUNT_ONLY_PAGE_SIZE = 1;

    private MetricsService() {
    }

    /**
     * Renders the full exposition document.
     *
     * @param visibleChannelIds the caller's authorized channel ids, or
     *                          {@code null} when the caller is unrestricted;
     *                          an empty set means "no channels visible"
     * @return the document, LF-terminated; never {@code null}
     */
    public static String render(Set<String> visibleChannelIds) {
        StringBuilder out = new StringBuilder(4096);
        appendOpenProblems(out, visibleChannelIds);
        appendChannelThroughput(out, visibleChannelIds);
        appendHeartbeats(out, Instant.now());
        return out.toString();
    }

    // ========== Series ==========

    /**
     * Open problems per severity, zero-filled across the whole
     * {@link Severity} enum so a scrape always yields the same five series —
     * a severity that drops out of the output when it hits zero would make
     * every rule written against it go stale rather than go green.
     */
    private static void appendOpenProblems(StringBuilder out, Set<String> visibleChannelIds) {
        family(out, OPEN_PROBLEMS, "gauge", "Currently open (unresolved) Sentinel problems, by severity.");

        // An empty authorized set means the caller may see nothing; the
        // filter's channel list must not be forwarded empty (read as "no
        // filter" downstream), so the counts are known to be zero without a
        // query at all.
        boolean nothingVisible = visibleChannelIds != null && visibleChannelIds.isEmpty();
        List<String> channelIdIn = visibleChannelIds != null ? new ArrayList<>(visibleChannelIds) : null;

        for (Severity severity : Severity.values()) {
            long count = nothingVisible ? 0L : countOpenProblems(severity, channelIdIn);
            sample(out, OPEN_PROBLEMS, labels("severity", severity.name()), Long.toString(count));
        }
    }

    /**
     * One exact {@code COUNT} of open problems at a single severity,
     * constrained to the visible channels when the caller is restricted.
     * Severity label values are the enum names verbatim ({@code DISASTER},
     * not {@code critical}) so an operator can carry a value straight from a
     * Grafana panel into {@code /problems?severity=...} without a translation
     * table.
     */
    private static long countOpenProblems(Severity severity, List<String> channelIdIn) {
        AlertEventFilter filter = new AlertEventFilter();
        filter.setStatus(AlertStatus.PROBLEM);
        filter.setSeverityIn(List.of(severity));
        if (channelIdIn != null) {
            filter.setChannelIdIn(channelIdIn);
        }
        filter.setPage(0);
        filter.setPageSize(COUNT_ONLY_PAGE_SIZE);
        return AlertEventRepository.listAlertEvents(filter).total();
    }

    /**
     * The three per-channel throughput counters. Each family is emitted whole
     * before the next begins — the text format requires a family's samples to
     * be contiguous — so the channel snapshot is taken once and walked three
     * times rather than the three families being interleaved per channel.
     */
    private static void appendChannelThroughput(StringBuilder out, Set<String> visibleChannelIds) {
        List<ChannelCounters> channels = channelCounters(visibleChannelIds);

        family(out, CHANNEL_RECEIVED, "counter",
                "Messages received by a channel since it was deployed, as of the last collector tick.");
        for (ChannelCounters channel : channels) {
            sample(out, CHANNEL_RECEIVED, channelLabels(channel), Long.toString(channel.counters().received));
        }

        family(out, CHANNEL_SENT, "counter",
                "Messages sent by a channel since it was deployed, as of the last collector tick.");
        for (ChannelCounters channel : channels) {
            sample(out, CHANNEL_SENT, channelLabels(channel), Long.toString(channel.counters().sent));
        }

        family(out, CHANNEL_ERRORED, "counter",
                "Messages errored on a channel since it was deployed, as of the last collector tick.");
        for (ChannelCounters channel : channels) {
            sample(out, CHANNEL_ERRORED, channelLabels(channel), Long.toString(channel.counters().error));
        }
    }

    /**
     * A channel's identity paired with the cumulative counter reading the
     * collector last observed for it. Snapshotted into a list so the three
     * counter families all describe the same instant and the same channel set
     * — reading {@link CollectorState} once per family would let a collector
     * tick landing mid-render produce a document whose {@code received} and
     * {@code sent} series disagree about which tick they came from.
     */
    private record ChannelCounters(String channelId, String channelName, CollectorState.Counters counters) {
    }

    /**
     * The channels this caller may see that the collector has actually
     * sampled, ordered by channel id so successive scrapes produce a stable
     * document (diffable by hand, and friendlier to any intermediary that
     * caches or compresses it).
     *
     * <p>Channels with no counter snapshot are skipped rather than zero-filled
     * — see the class Javadoc — which also naturally excludes undeployed
     * channels, whose state {@code CollectorState.forgetChannel} evicts.</p>
     */
    private static List<ChannelCounters> channelCounters(Set<String> visibleChannelIds) {
        List<ChannelCounters> channels = new ArrayList<>();
        if (visibleChannelIds != null && visibleChannelIds.isEmpty()) {
            return channels;
        }
        CollectorState state = CollectorState.getInstance();
        for (ChannelInfo channel : ScopeResolver.listChannels()) {
            String channelId = channel.getChannelId();
            if (channelId == null || (visibleChannelIds != null && !visibleChannelIds.contains(channelId))) {
                continue;
            }
            CollectorState.Counters counters = state.getPreviousCounters(channelId);
            if (counters == null) {
                continue;
            }
            channels.add(new ChannelCounters(channelId, channel.getName(), counters));
        }
        channels.sort(Comparator.comparing(ChannelCounters::channelId));
        return channels;
    }

    /**
     * Labels for a channel series: the stable {@code channel_id} that survives
     * a rename, plus the human {@code channel} name the dashboards actually
     * display. Both, because either alone is wrong — keying only on the name
     * silently splits a series when someone renames a channel, and keying only
     * on the uuid makes every panel unreadable.
     */
    private static Map<String, String> channelLabels(ChannelCounters channel) {
        return labels("channel_id", channel.channelId(), "channel", channel.channelName());
    }

    /**
     * Collector and evaluator liveness as an age in seconds — the base unit,
     * per the naming conventions, rather than the raw timestamps the dashboard
     * shows, so an alert reads {@code > 120} instead of doing clock arithmetic
     * against a scrape time.
     *
     * <p>A job that has never run emits its {@code # HELP}/{@code # TYPE}
     * metadata but no sample. Absence is the honest encoding of "no reading",
     * and it is directly alertable with {@code absent()}; any sentinel number
     * (0, -1) would be indistinguishable from a real age and would quietly
     * satisfy a staleness rule that should have fired.</p>
     */
    private static void appendHeartbeats(StringBuilder out, Instant now) {
        CollectorState state = CollectorState.getInstance();

        family(out, COLLECTOR_HEARTBEAT, "gauge",
                "Seconds since the Sentinel activity collector last completed a tick.");
        appendAge(out, COLLECTOR_HEARTBEAT, state.getLastCollectorRun(), now);

        family(out, EVALUATOR_HEARTBEAT, "gauge",
                "Seconds since the Sentinel trigger evaluator last completed a tick.");
        appendAge(out, EVALUATOR_HEARTBEAT, state.getLastEvaluatorRun(), now);
    }

    /**
     * Emits one age sample, or nothing when the job has never run. Clamped at
     * zero so a backwards clock adjustment surfaces as "just ran" rather than
     * as a negative age no dashboard expects.
     */
    private static void appendAge(StringBuilder out, String name, Instant lastRun, Instant now) {
        if (lastRun == null) {
            return;
        }
        double seconds = Math.max(0d, Duration.between(lastRun, now).toMillis() / 1000d);
        sample(out, name, labels(), String.format(Locale.ROOT, "%.3f", seconds));
    }

    // ========== Text format ==========

    /**
     * Writes a family's {@code # HELP} and {@code # TYPE} header. The help
     * text is a hard-coded literal at every call site, single-line and free of
     * backslashes, so it needs no escaping of its own — unlike label values,
     * which carry operator input.
     */
    private static void family(StringBuilder out, String name, String type, String help) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    /**
     * Writes one sample line: {@code name{label="value",...} value}. Labels
     * are emitted in insertion order (and the braces omitted entirely when
     * there are none) so a series' identity is textually stable across
     * scrapes.
     */
    private static void sample(StringBuilder out, String name, Map<String, String> labels, String value) {
        out.append(name);
        if (!labels.isEmpty()) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, String> label : labels.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(label.getKey()).append("=\"")
                        .append(escapeLabelValue(label.getValue())).append('"');
            }
            out.append('}');
        }
        out.append(' ').append(value).append('\n');
    }

    /**
     * Builds an ordered label map from alternating name/value arguments —
     * {@code labels("channel_id", id, "channel", name)}. Insertion-ordered so
     * the rendered label order is deterministic.
     *
     * @param nameValuePairs alternating label names and values; must be even-length
     */
    private static Map<String, String> labels(String... nameValuePairs) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            labels.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return labels;
    }

    /**
     * Escapes a label value per the text exposition format: backslash,
     * double-quote, and line feed, which are the only three sequences the
     * format defines (matching the reference Go implementation — no other
     * character may be escaped, since an undefined escape is itself a parse
     * error).
     *
     * <p>Not cosmetic. Channel names are free text an operator types, and one
     * unescaped quote in one channel name closes the label early and makes
     * every remaining byte of the document unparseable — the whole scrape
     * fails, not just that series. A {@code null} name (a channel resolved
     * from the cache mid-delete) renders as an empty value rather than the
     * literal {@code "null"}.</p>
     */
    private static String escapeLabelValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                default:
                    escaped.append(c);
                    break;
            }
        }
        return escaped.toString();
    }
}
