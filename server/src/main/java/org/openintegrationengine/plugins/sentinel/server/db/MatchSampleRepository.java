/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.util.SqlConfig;

/**
 * Persistence for {@code sentinel_match_sample}, the counts table shared by
 * Log and Event monitors.
 *
 * <p>A row without a channel is a node's heartbeat for a monitor: written every
 * flush, zero counts included, so the evaluator can tell "no matches" from "no
 * data". A row with a channel counts matches tied to that channel. Each node
 * writes only its own rows, so the insert takes no leadership fence; the prune
 * is leader-only and fenced, like the activity-sample prune.</p>
 */
public final class MatchSampleRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(MatchSampleRepository.class);

    private MatchSampleRepository() {
    }

    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Writes one flush in a single JDBC batch, all or nothing. A confirmed
     * deadlock victim is rolled back and retried once.
     *
     * @param samples ids are ignored; {@code null} or empty opens no session
     * @throws RepositoryException on persistence failure, not logged here: the
     *         flush job logs it once, with what it keeps
     */
    public static void insertSamples(List<MatchSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>(samples.size());
            for (MatchSample sample : samples) rows.add(toColumnMap(sample));
            BatchInsert.run(stmt("insertMatchSample"), rows, LeaseFence.unmanaged(), "match sample batch");
        } catch (Exception e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * One channel's rows in a range, from every node.
     *
     * @param channelId  the channel
     * @param matchCount the sum of their counts
     */
    public record ChannelSum(String channelId, long matchCount) {
    }

    /**
     * A monitor's heartbeat rows (no channel) in an inclusive range, without
     * their matched lines: what the evaluator needs to count a window to the
     * second and to see which nodes covered it.
     *
     * @return rows ordered by node id, then sample time, with {@code id} and
     *         {@code samplesJson} left {@code null}; never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<MatchSample> listCountRows(int monitorId, Instant from, Instant to) {
        try {
            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listMatchSampleCounts"), range(monitorId, from, to));
            List<MatchSample> samples = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Object sampleTime = row.get("sample_time");
                samples.add(new MatchSample(null, (String) row.get("node_id"), monitorId, null,
                        sampleTime != null ? ((Timestamp) sampleTime).toInstant() : null,
                        toLong(row.get("match_count")), null, isTrue(row.get("complete")),
                        (String) row.get("second_counts")));
            }
            return samples;
        } catch (Exception e) {
            log.error("Failed to list match counts for monitor {} from {} to {}", monitorId, from, to, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Sums a monitor's rows that have a channel, per channel across all nodes,
     * inclusive range.
     *
     * @return one entry per channel with a row in the range, by channel id;
     *         never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<ChannelSum> sumSamplesByChannel(int monitorId, Instant from, Instant to) {
        try {
            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("sumMatchSamplesByChannel"), range(monitorId, from, to));
            List<ChannelSum> sums = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                sums.add(new ChannelSum((String) row.get("channel_id"), toLong(row.get("match_sum"))));
            }
            return sums;
        } catch (Exception e) {
            log.error("Failed to sum match samples by channel for monitor {} from {} to {}", monitorId, from, to, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * One node's newest rows that keep samples, any channel, inclusive range.
     *
     * @param limit the most rows to return
     * @return rows with a non-null {@code samplesJson}, newest first; never
     *         {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<MatchSample> listRecentSamples(int monitorId, String nodeId, Instant from, Instant to,
            int limit) {
        try {
            Map<String, Object> params = range(monitorId, from, to);
            params.put("nodeId", nodeId);
            params.put("limit", limit);
            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listRecentMatchSamples"), params);
            List<MatchSample> samples = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) samples.add(toSample(row));
            return samples;
        } catch (Exception e) {
            log.error("Failed to list recent match samples for monitor {} on node {}", monitorId, nodeId, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Deletes rows older than the cutoff in bounded passes, for the leader's
     * retention job; see {@link ChunkedDelete}.
     *
     * @return the number of rows deleted across all passes
     * @throws RepositoryException on persistence failure
     */
    public static int deleteSamplesOlderThan(Instant cutoff, LeaseFence fence) {
        return ChunkedDelete.run(stmt("deleteMatchSamplesOlderThan"), cutoff, "match samples", fence);
    }

    private static Map<String, Object> range(int monitorId, Instant from, Instant to) {
        Map<String, Object> params = new HashMap<>();
        params.put("monitorId", monitorId);
        params.put("from", toTimestamp(from));
        params.put("to", toTimestamp(to));
        return params;
    }

    private static Map<String, Object> toColumnMap(MatchSample sample) {
        Map<String, Object> params = new HashMap<>();
        params.put("node_id", sample.nodeId());
        params.put("monitor_id", sample.monitorId());
        params.put("channel_id", sample.channelId());
        params.put("sample_time", toTimestamp(sample.sampleTime()));
        params.put("match_count", sample.matchCount());
        params.put("samples_json", sample.samplesJson());
        params.put("complete", sample.complete());
        params.put("second_counts", sample.secondCounts());
        return params;
    }

    private static MatchSample toSample(Map<String, Object> row) {
        // Derby and Oracle return identity and NUMBER columns as BigDecimal.
        Object id = row.get("id");
        Object sampleTime = row.get("sample_time");
        return new MatchSample(id != null ? ((Number) id).longValue() : null, (String) row.get("node_id"),
                ((Number) row.get("monitor_id")).intValue(), (String) row.get("channel_id"),
                sampleTime != null ? ((Timestamp) sampleTime).toInstant() : null, toLong(row.get("match_count")),
                (String) row.get("samples_json"), isTrue(row.get("complete")), (String) row.get("second_counts"));
    }

    /** Oracle's NUMBER(1) and SQL Server's BIT come back as numbers, the others as Boolean. */
    private static boolean isTrue(Object value) {
        return value instanceof Boolean flag ? flag : value instanceof Number number && number.intValue() != 0;
    }

    /** SUM and NUMBER(19) come back as BigDecimal on some vendors. */
    private static long toLong(Object value) {
        return value != null ? ((Number) value).longValue() : 0L;
    }

    private static Timestamp toTimestamp(Instant value) {
        return value != null ? Timestamp.from(value) : null;
    }
}
