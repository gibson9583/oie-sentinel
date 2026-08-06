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

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.util.SqlConfig;

import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityTrend;

/**
 * Persistence for {@code sentinel_channel_activity_sample} and
 * {@code sentinel_channel_activity_trend} — raw per-poll activity samples and
 * their hourly rollups. One repository owns both tables because the
 * collector and rollup jobs always use them together (write a sample, later
 * fold a window of samples into a trend bucket).
 *
 * <p>Stateless: every method reaches {@link SqlConfig#getInstance()} directly,
 * so unlike {@code RbacRepository} this class carries no constructor-time
 * setup and needs no singleton lifecycle — plain static methods are
 * sufficient. Most statements here are a single MyBatis call and use
 * auto-commit. Two methods are not: {@link #replaceActivityTrendForHour(ActivityTrend)}
 * is a multi-statement sequence (delete-then-reinsert) and opens a
 * manual-commit session so a mid-flight failure leaves the prior bucket in
 * place rather than a deleted-but-not-reinserted gap, and
 * {@link #insertActivitySamples(List)} opens a manual-commit
 * {@link ExecutorType#BATCH} session so a whole collector tick's rows land in
 * one round trip, all or nothing.</p>
 */
public final class ActivityRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(ActivityRepository.class);

    private ActivityRepository() {
    }

    /** Qualifies a mapped-statement id with the plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    // ========== Activity Sample ==========

    /**
     * Inserts a single activity sample. Plain insert — {@code
     * sentinel_channel_activity_sample} rows are never updated after
     * creation, so no generated-key retrieval is needed.
     *
     * <p>The collector — the only high-frequency writer — goes through
     * {@link #insertActivitySamples(List)} instead, so this method has no
     * caller in the plugin today. It is retained as the repository's
     * single-row entry point: it is the cheaper shape for a genuinely single
     * sample, where opening a batch session costs more than it saves, and it
     * shares the same mapped statement, so it cannot drift from the batch
     * path.</p>
     *
     * @param sample the sample to persist; its {@code id} field is ignored
     * @throws RepositoryException on persistence failure
     */
    public static void insertActivitySample(ActivitySample sample) {
        try {
            Map<String, Object> params = toSampleColumnMap(sample);
            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertActivitySample"), params);
        } catch (Exception e) {
            log.error("Failed to insert activity sample for channel {} at {}",
                    sample.getChannelId(), sample.getSampleTime(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Inserts a whole collector tick's activity samples in one batched round
     * trip. This is the plugin's hot write path — one row per deployed
     * channel per tick — so the per-row cost is the number that matters: at
     * the 30-second default with 200 channels, the single-row path above was
     * 200 statements every 30 seconds against the same database the engine
     * uses for message data.
     *
     * <p><b>Why a batch executor and not a multi-row insert.</b> MyBatis
     * offers both, and the alternative would have been a {@code <foreach>}
     * multi-row {@code VALUES} statement written five times in
     * {@code package/resources/mapper}. The {@link ExecutorType#BATCH}
     * session won on four counts:</p>
     *
     * <ul>
     *   <li><i>The session infrastructure already supports it.</i> Batch
     *   access is not awkward through {@code SqlSessionManager}: its
     *   {@code openSession(ExecutorType, boolean)} delegates straight to the
     *   underlying session factory, the same call shape as the manual-commit
     *   {@code openSession(false)} this class already uses in
     *   {@link #replaceActivityTrendForHour(ActivityTrend)}. The rest of the
     *   repository layer calls {@code getSqlSessionManager()} statement
     *   methods directly only because every one of those is a single round
     *   trip with nothing to batch or roll back.</li>
     *
     *   <li><i>One statement, five vendors.</i> This re-executes the existing
     *   single-row {@code insertActivitySample} verbatim, so the cross-vendor
     *   mapper contract gains nothing new to keep in sync. A multi-row
     *   variant would need three dialects: Oracle has no multi-row
     *   {@code VALUES} clause at all and would require
     *   {@code INSERT ALL ... SELECT * FROM dual}.</li>
     *
     *   <li><i>No row or parameter ceiling.</i> A multi-row {@code VALUES}
     *   list would need per-vendor chunking to stay legal — SQL Server caps a
     *   table value constructor at 1000 rows and a request at 2100
     *   parameters, which is only 300 rows at this table's 7 bound columns
     *   and is cleared on the first tick of a 200+ channel server;
     *   PostgreSQL's extended-query protocol caps a statement at 65535 bind
     *   parameters. A JDBC batch re-executes one 7-parameter statement per
     *   row, so the only bound is the deployed channel count.</li>
     *
     *   <li><i>All-or-nothing.</i> The batch commits as one manual-commit
     *   transaction, which is exactly the guarantee
     *   {@code ActivityCollectorJob} needs to decide whether to advance its
     *   previous-counters snapshots.</li>
     * </ul>
     *
     * <p>Returns no row count deliberately: JDBC drivers may report
     * {@code Statement.SUCCESS_NO_INFO} for batched statements, so a count
     * here would be unreliable, and the collector has no use for one.</p>
     *
     * @param samples the samples to persist, in any order; each sample's
     *                {@code id} field is ignored. A {@code null} or empty
     *                list is a no-op — a tick with no deployed channels must
     *                not open a session
     * @throws RepositoryException on persistence failure. The transaction is
     *                             rolled back, so no sample in the batch is
     *                             persisted; callers must treat the whole
     *                             tick as unwritten
     */
    public static void insertActivitySamples(List<ActivitySample> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }

        SqlSession session = null;
        try {
            session = SqlConfig.getInstance().getSqlSessionManager().openSession(ExecutorType.BATCH, false);

            for (ActivitySample sample : samples) {
                session.insert(stmt("insertActivitySample"), toSampleColumnMap(sample));
            }

            // Flushes the accumulated batch and commits it; under BATCH
            // nothing has reached the driver before this point.
            session.commit();
        } catch (Exception e) {
            log.error("Failed to insert batch of {} activity samples", samples.size(), e);
            throw new RepositoryException(e);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Lists raw activity samples for a channel within a time range, ordered
     * by sample time.
     *
     * @param channelId the OIE channel id (a UUID string) to look up
     * @param from      inclusive lower bound on {@code sample_time}
     * @param to        inclusive upper bound on {@code sample_time}
     * @return matching samples ordered by {@code sample_time}; never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<ActivitySample> listActivitySamples(String channelId, Instant from, Instant to) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("channelId", channelId);
            params.put("from", toTimestamp(from));
            params.put("to", toTimestamp(to));

            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listActivitySamples"), params);

            List<ActivitySample> samples = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                samples.add(buildActivitySample(row));
            }
            return samples;
        } catch (Exception e) {
            log.error("Failed to list activity samples for channel {} from {} to {}", channelId, from, to, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Aggregates activity samples for a channel within a time range: sums of
     * received/sent/error deltas plus average/min/max queued-message
     * snapshot. Used by baseline/anomaly monitor types instead of pulling
     * every raw sample into Java.
     *
     * @param channelId the OIE channel id (a UUID string) to look up
     * @param from      inclusive lower bound on {@code sample_time}
     * @param to        inclusive upper bound on {@code sample_time}
     * @return the aggregate over the matching samples; every field is zero
     *         (never {@code null}) when no samples fall in range, since the
     *         underlying query {@code COALESCE}s each aggregate function
     * @throws RepositoryException on persistence failure
     */
    public static ActivityAggregate sumActivitySamplesForRange(String channelId, Instant from, Instant to) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("channelId", channelId);
            params.put("from", toTimestamp(from));
            params.put("to", toTimestamp(to));

            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("sumActivitySamplesForRange"), params);

            return buildActivityAggregate(row);
        } catch (Exception e) {
            log.error("Failed to sum activity samples for channel {} from {} to {}", channelId, from, to, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Deletes activity samples older than a cutoff. Used by the retention
     * job to keep the raw-sample table bounded once its data has been folded
     * into {@code sentinel_channel_activity_trend}.
     *
     * @param cutoff samples with {@code sample_time} before this instant are
     *               removed
     * @return the number of rows deleted
     * @throws RepositoryException on persistence failure
     */
    public static int deleteActivitySamplesOlderThan(Instant cutoff) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("cutoff", toTimestamp(cutoff));
            return SqlConfig.getInstance().getSqlSessionManager().delete(stmt("deleteActivitySamplesOlderThan"), params);
        } catch (Exception e) {
            log.error("Failed to delete activity samples older than {}", cutoff, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Activity Trend ==========

    /**
     * Replaces the hourly trend bucket for {@code (channel_id, hour_bucket)}
     * with a freshly computed one, as a delete-then-reinsert pair run inside
     * a single transaction. This is the idempotent upsert the rollup job
     * uses instead of a vendor-specific {@code MERGE}/{@code ON CONFLICT}: if
     * the rollup for an hour needs to be recomputed (late-arriving samples,
     * a corrected window), calling this again with the new totals cleanly
     * replaces the old bucket.
     *
     * @param trend the new bucket contents; {@link ActivityTrend#getChannelId()}
     *              and {@link ActivityTrend#getHourBucket()} identify which
     *              bucket is replaced. {@code id} is ignored — the insert is
     *              a plain insert with no generated-key retrieval
     * @throws RepositoryException on persistence failure; the transaction is
     *                             rolled back so a failed reinsert never
     *                             leaves the bucket merely deleted
     */
    public static void replaceActivityTrendForHour(ActivityTrend trend) {
        SqlSession session = null;
        try {
            session = SqlConfig.getInstance().getSqlSessionManager().openSession(false);

            Map<String, Object> deleteParams = new HashMap<>();
            deleteParams.put("channelId", trend.getChannelId());
            deleteParams.put("hourBucket", toTimestamp(trend.getHourBucket()));
            session.delete(stmt("deleteActivityTrendForHour"), deleteParams);

            Map<String, Object> insertParams = toTrendColumnMap(trend);
            session.insert(stmt("insertActivityTrend"), insertParams);

            session.commit();
        } catch (Exception e) {
            log.error("Failed to replace activity trend for channel {} hour {}",
                    trend.getChannelId(), trend.getHourBucket(), e);
            throw new RepositoryException(e);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Lists hourly trend buckets for a channel from a starting hour forward,
     * ordered ascending. Deliberately not filtered by hour-of-day/weekend
     * here — that bucketing happens in Java, where date functions are
     * portable across vendors.
     *
     * @param channelId       the OIE channel id (a UUID string) to look up
     * @param sinceHourBucket inclusive lower bound on {@code hour_bucket}
     * @return matching buckets ordered by {@code hour_bucket} ascending;
     *         never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<ActivityTrend> listActivityTrendSince(String channelId, Instant sinceHourBucket) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("channelId", channelId);
            params.put("sinceHourBucket", toTimestamp(sinceHourBucket));

            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listActivityTrendSince"), params);

            List<ActivityTrend> trends = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                trends.add(buildActivityTrend(row));
            }
            return trends;
        } catch (Exception e) {
            log.error("Failed to list activity trend for channel {} since {}", channelId, sinceHourBucket, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Deletes trend buckets older than a cutoff.
     *
     * @param cutoff buckets with {@code hour_bucket} before this instant are
     *               removed
     * @return the number of rows deleted
     * @throws RepositoryException on persistence failure
     */
    public static int deleteActivityTrendOlderThan(Instant cutoff) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("cutoff", toTimestamp(cutoff));
            return SqlConfig.getInstance().getSqlSessionManager().delete(stmt("deleteActivityTrendOlderThan"), params);
        } catch (Exception e) {
            log.error("Failed to delete activity trend older than {}", cutoff, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO Conversion ==========

    /**
     * Builds the column map for {@code insertActivitySample} (every
     * {@code sentinel_channel_activity_sample} column except {@code id}),
     * keyed by column name to match the mapped statement's {@code
     * #{column_name}} bind variables.
     */
    private static Map<String, Object> toSampleColumnMap(ActivitySample sample) {
        Map<String, Object> params = new HashMap<>();
        params.put("channel_id", sample.getChannelId());
        params.put("sample_time", toTimestamp(sample.getSampleTime()));
        params.put("received_delta", sample.getReceivedDelta());
        params.put("sent_delta", sample.getSentDelta());
        params.put("error_delta", sample.getErrorDelta());
        params.put("filtered_delta", sample.getFilteredDelta());
        params.put("queued_snapshot", sample.getQueuedSnapshot());
        return params;
    }

    private static ActivitySample buildActivitySample(Map<String, Object> row) {
        ActivitySample sample = new ActivitySample();
        sample.setId(toLong(row.get("id")));
        sample.setChannelId((String) row.get("channel_id"));
        sample.setSampleTime(toInstant(row.get("sample_time")));

        Long receivedDelta = toLong(row.get("received_delta"));
        sample.setReceivedDelta(receivedDelta != null ? receivedDelta : 0L);

        Long sentDelta = toLong(row.get("sent_delta"));
        sample.setSentDelta(sentDelta != null ? sentDelta : 0L);

        Long errorDelta = toLong(row.get("error_delta"));
        sample.setErrorDelta(errorDelta != null ? errorDelta : 0L);

        Long filteredDelta = toLong(row.get("filtered_delta"));
        sample.setFilteredDelta(filteredDelta != null ? filteredDelta : 0L);

        Long queuedSnapshot = toLong(row.get("queued_snapshot"));
        sample.setQueuedSnapshot(queuedSnapshot != null ? queuedSnapshot : 0L);

        return sample;
    }

    /**
     * Builds the column map for {@code insertActivityTrend} (every
     * {@code sentinel_channel_activity_trend} column except {@code id}),
     * keyed by column name to match the mapped statement's {@code
     * #{column_name}} bind variables.
     */
    private static Map<String, Object> toTrendColumnMap(ActivityTrend trend) {
        Map<String, Object> params = new HashMap<>();
        params.put("channel_id", trend.getChannelId());
        params.put("hour_bucket", toTimestamp(trend.getHourBucket()));
        params.put("received_sum", trend.getReceivedSum());
        params.put("sent_sum", trend.getSentSum());
        params.put("error_sum", trend.getErrorSum());
        params.put("avg_queued", trend.getAvgQueued());
        params.put("min_queued", trend.getMinQueued());
        params.put("max_queued", trend.getMaxQueued());
        return params;
    }

    private static ActivityTrend buildActivityTrend(Map<String, Object> row) {
        ActivityTrend trend = new ActivityTrend();
        trend.setId(toInteger(row.get("id")));
        trend.setChannelId((String) row.get("channel_id"));
        trend.setHourBucket(toInstant(row.get("hour_bucket")));

        Long receivedSum = toLong(row.get("received_sum"));
        trend.setReceivedSum(receivedSum != null ? receivedSum : 0L);

        Long sentSum = toLong(row.get("sent_sum"));
        trend.setSentSum(sentSum != null ? sentSum : 0L);

        Long errorSum = toLong(row.get("error_sum"));
        trend.setErrorSum(errorSum != null ? errorSum : 0L);

        trend.setAvgQueued(toDouble(row.get("avg_queued")));

        Long minQueued = toLong(row.get("min_queued"));
        trend.setMinQueued(minQueued != null ? minQueued : 0L);

        Long maxQueued = toLong(row.get("max_queued"));
        trend.setMaxQueued(maxQueued != null ? maxQueued : 0L);

        return trend;
    }

    /**
     * Builds an {@link ActivityAggregate} from the single row {@code
     * sumActivitySamplesForRange} returns. The underlying query {@code
     * COALESCE}s every aggregate function to 0, so {@code row} is expected
     * to always be non-null with every column populated; the null check is
     * defensive only.
     */
    private static ActivityAggregate buildActivityAggregate(Map<String, Object> row) {
        ActivityAggregate aggregate = new ActivityAggregate();
        if (row == null) {
            return aggregate;
        }

        Long receivedSum = toLong(row.get("received_sum"));
        aggregate.setReceivedSum(receivedSum != null ? receivedSum : 0L);

        Long sentSum = toLong(row.get("sent_sum"));
        aggregate.setSentSum(sentSum != null ? sentSum : 0L);

        Long errorSum = toLong(row.get("error_sum"));
        aggregate.setErrorSum(errorSum != null ? errorSum : 0L);

        aggregate.setAvgQueued(toDouble(row.get("avg_queued")));

        Long minQueued = toLong(row.get("min_queued"));
        aggregate.setMinQueued(minQueued != null ? minQueued : 0L);

        Long maxQueued = toLong(row.get("max_queued"));
        aggregate.setMaxQueued(maxQueued != null ? maxQueued : 0L);

        return aggregate;
    }

    /** Converts an {@link Instant} to the {@link Timestamp} MyBatis/JDBC expects as a bound parameter. */
    private static Timestamp toTimestamp(Instant value) {
        return value != null ? Timestamp.from(value) : null;
    }

    /** Converts the {@link Timestamp} JDBC hands back from a SELECT to an {@link Instant}. */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        return ((Timestamp) value).toInstant();
    }

    /**
     * Coerces a MyBatis-returned numeric column to a {@link Long}. Different
     * JDBC drivers return different {@link Number} subtypes for BIGINT
     * columns (e.g. Derby may hand back a {@link java.math.BigDecimal}), so a
     * direct {@code (Long)} cast can throw {@link ClassCastException}.
     * Handles any {@link Number}.
     *
     * @param value the raw value from a params map or result row
     * @return the value as a {@code Long}, or {@code null} if it is null
     */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(value.toString().trim());
    }

    /**
     * Coerces a MyBatis-returned key or id to an {@link Integer}. Most drivers
     * return an {@code Integer}, but Derby hands back identity/generated keys as
     * a {@link java.math.BigDecimal}, so a direct {@code (Integer)} cast throws
     * {@link ClassCastException}. Handles any {@link Number}.
     *
     * @param value the raw value from a params map or result row
     * @return the value as an {@code Integer}, or {@code null} if it is null
     */
    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(value.toString().trim());
    }

    /**
     * Coerces a MyBatis-returned numeric column to a primitive {@code
     * double}, defaulting to {@code 0.0} for {@code null} (the {@code
     * avg_queued} column is always {@code COALESCE}d server-side, but this
     * stays defensive rather than risking a {@link NullPointerException} on
     * unboxing).
     */
    private static double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(value.toString().trim());
    }
}
