/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.model.Channel;
import com.mirth.connect.server.controllers.ChannelController;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityTrend;

/**
 * Hourly rollup: folds the just-completed hour of raw activity samples into
 * one {@code sentinel_channel_activity_trend} bucket per channel. The trend
 * table is what {@code BaselineResolver} tiers over for anomaly/baseline
 * monitors and what long-range activity charts read — raw samples only live
 * for days, trend buckets for months.
 *
 * <p>Scheduled at five past the hour (cron {@code 0 5 * * * ?}) so the hour
 * being rolled up is fully closed and every collector tick belonging to it
 * has long since been written. The write path is
 * {@link ActivityRepository#replaceActivityTrendForHour(ActivityTrend)} —
 * a transactional delete-then-insert — so re-running the rollup for the
 * same hour (misfired-then-recovered scheduler, manual replay) is
 * idempotent instead of duplicating buckets, without resorting to
 * vendor-specific {@code MERGE}/{@code ON CONFLICT} SQL.</p>
 *
 * <p>Channel iteration goes through the engine's channel cache
 * ({@code getChannels(null)}, no DB hit) rather than a DISTINCT scan of the
 * sample table: simplest correct source of "channels that can have
 * samples". A channel deleted from the engine during the hour loses its
 * final partial bucket — acceptable, since its monitors and charts are gone
 * with it.</p>
 *
 * <p>Leadership-gated, like the collector: every node of a multi-node engine
 * fires this cron, and only the holder of the Sentinel leader lease runs it.
 * The replacement write is idempotent, so a second node's run would not
 * duplicate buckets — but it would have both nodes deleting and re-inserting
 * the same rows at the same instant, which is contention and lock waiting on
 * the engine's operational database for no gain. Which node rolls the hour up
 * does not matter: the samples being folded are already in the database,
 * whoever wrote them.</p>
 *
 * <p>{@code @DisallowConcurrentExecution} for consistency with the interval
 * jobs: a rollup stalled past the next cron fire would otherwise run its
 * delete-then-insert replacement concurrently with itself.</p>
 */
@DisallowConcurrentExecution
public class ActivityRollupJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ActivityRollupJob.class);

    /**
     * Rolls up the previous completed hour for every channel, unless another
     * node holds the Sentinel leader lease (see class Javadoc). Never throws —
     * the schedule must keep firing (see collector job for the rationale);
     * a failed hour is recoverable because a later manual replay of
     * {@code replaceActivityTrendForHour} is idempotent.
     */
    @Override
    public void execute(JobExecutionContext context) {
        if (!SentinelLeadership.isLeader()) {
            return;
        }
        try {
            // Hour bucket = the previous completed hour, truncated on the
            // Instant (UTC-aligned). This MUST match how the evaluators
            // compute their reference hour (now.truncatedTo(HOURS) on the
            // Instant — AnomalyEvaluator/LowVolumeEvaluator): in
            // fractional-offset zones (+05:30 India, +09:30 ACST, ...) a
            // wall-clock truncation would shift every trend bucket 30-45
            // minutes from the evaluated hour, letting BaselineResolver's
            // reference-hour exclusion admit the bucket that overlaps the
            // very hour under test. BaselineResolver's hour-of-day tiering
            // still classifies these buckets via atZone(systemDefault()), so
            // local seasonality is unaffected.
            Instant hourStart = Instant.now().truncatedTo(ChronoUnit.HOURS)
                    .minus(1, ChronoUnit.HOURS);
            // listActivitySamples' bounds are inclusive; back the upper bound
            // off by 1ms so a sample landing exactly on the next hour
            // boundary is counted once (in the next hour), never twice.
            Instant hourEndInclusive = hourStart.plus(1, ChronoUnit.HOURS).minusMillis(1);

            List<Channel> channels = ChannelController.getInstance().getChannels(null);
            if (channels == null) {
                return;
            }

            int rolledUp = 0;
            for (Channel channel : channels) {
                // Per-channel try/catch: one channel's bad rollup must not
                // cost every other channel its trend bucket for the hour.
                try {
                    if (rollupChannel(channel.getId(), hourStart, hourEndInclusive)) {
                        rolledUp++;
                    }
                } catch (Exception e) {
                    log.error("Failed to roll up activity for channel {} hour {}", channel.getId(), hourStart, e);
                }
            }

            if (rolledUp > 0) {
                log.info("Rolled up activity for {} channel(s) into hour bucket {}", rolledUp, hourStart);
            } else {
                log.debug("No channels had activity samples in hour bucket {}", hourStart);
            }
        } catch (Throwable t) {
            log.error("Activity rollup run failed", t);
        }
    }

    /**
     * Computes and replaces one channel's bucket for the hour. Channels with
     * no samples in the hour get no bucket at all (rather than a zero row):
     * "the collector wasn't watching" and "the channel received nothing"
     * are different facts, and {@code BaselineResolver} must not learn a
     * baseline of zeros from hours Sentinel simply wasn't running.
     *
     * @return true if a bucket was written, false if the hour had no samples
     */
    private boolean rollupChannel(String channelId, Instant hourStart, Instant hourEndInclusive) {
        List<ActivitySample> samples = ActivityRepository.listActivitySamples(channelId, hourStart, hourEndInclusive);
        if (samples.isEmpty()) {
            return false;
        }

        long receivedSum = 0L;
        long sentSum = 0L;
        long errorSum = 0L;
        long queuedTotal = 0L;
        long minQueued = Long.MAX_VALUE;
        long maxQueued = Long.MIN_VALUE;

        for (ActivitySample sample : samples) {
            receivedSum += sample.getReceivedDelta();
            sentSum += sample.getSentDelta();
            errorSum += sample.getErrorDelta();

            long queued = sample.getQueuedSnapshot();
            queuedTotal += queued;
            minQueued = Math.min(minQueued, queued);
            maxQueued = Math.max(maxQueued, queued);
        }

        ActivityTrend trend = new ActivityTrend();
        trend.setChannelId(channelId);
        trend.setHourBucket(hourStart);
        trend.setReceivedSum(receivedSum);
        trend.setSentSum(sentSum);
        trend.setErrorSum(errorSum);
        trend.setAvgQueued((double) queuedTotal / samples.size());
        trend.setMinQueued(minQueued);
        trend.setMaxQueued(maxQueued);

        ActivityRepository.replaceActivityTrendForHour(trend);
        return true;
    }
}
