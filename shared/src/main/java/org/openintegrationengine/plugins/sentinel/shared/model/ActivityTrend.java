/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * One hourly rollup of a channel's activity, persisted in
 * {@code sentinel_channel_activity_trend}.
 *
 * <p>The rollup job aggregates a hour's worth of {@link ActivitySample} rows
 * into one bucket per {@code (channel_id, hour_bucket)} pair, so baseline/
 * anomaly monitor types can compare current activity against a much cheaper
 * history than replaying raw samples. Rebuilding a bucket is an idempotent
 * delete-then-reinsert (see {@code ActivityRepository.replaceActivityTrendForHour}),
 * never an in-place update.</p>
 */
public class ActivityTrend {

    private Integer id;
    private String channelId;
    private Instant hourBucket;
    private long receivedSum;
    private long sentSum;
    private long errorSum;
    private double avgQueued;
    private long minQueued;
    private long maxQueued;

    /**
     * Creates an empty trend bucket. Callers populate fields via the setters
     * before handing the instance to {@code ActivityRepository}.
     */
    public ActivityTrend() {
    }

    /**
     * @return the database-assigned id of this bucket, or {@code null} for
     *         an instance that has not been persisted yet
     */
    public Integer getId() {
        return id;
    }

    /**
     * @param id the database-assigned id; this table's insert is a plain
     *           insert with no generated-key retrieval, so this is normally
     *           only populated when reading a row back from a query
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * @return the OIE channel id (a UUID string) this bucket summarizes
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id (a UUID string) this bucket
     *                  summarizes
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the start of the hour this bucket covers (truncated to the
     *         hour); paired with {@link #getChannelId()} under a UNIQUE
     *         constraint
     */
    public Instant getHourBucket() {
        return hourBucket;
    }

    /**
     * @param hourBucket the start of the hour this bucket covers; callers
     *                   must pass an hour-truncated {@link Instant}
     */
    public void setHourBucket(Instant hourBucket) {
        this.hourBucket = hourBucket;
    }

    /**
     * @return the sum of {@link ActivitySample#getReceivedDelta()} across
     *         every sample folded into this bucket
     */
    public long getReceivedSum() {
        return receivedSum;
    }

    /**
     * @param receivedSum the sum of received-message deltas across every
     *                    sample folded into this bucket
     */
    public void setReceivedSum(long receivedSum) {
        this.receivedSum = receivedSum;
    }

    /**
     * @return the sum of {@link ActivitySample#getSentDelta()} across every
     *         sample folded into this bucket
     */
    public long getSentSum() {
        return sentSum;
    }

    /**
     * @param sentSum the sum of sent-message deltas across every sample
     *                folded into this bucket
     */
    public void setSentSum(long sentSum) {
        this.sentSum = sentSum;
    }

    /**
     * @return the sum of {@link ActivitySample#getErrorDelta()} across every
     *         sample folded into this bucket
     */
    public long getErrorSum() {
        return errorSum;
    }

    /**
     * @param errorSum the sum of errored-message deltas across every sample
     *                 folded into this bucket
     */
    public void setErrorSum(long errorSum) {
        this.errorSum = errorSum;
    }

    /**
     * @return the average of {@link ActivitySample#getQueuedSnapshot()}
     *         across every sample folded into this bucket
     */
    public double getAvgQueued() {
        return avgQueued;
    }

    /**
     * @param avgQueued the average queued-message snapshot across every
     *                  sample folded into this bucket
     */
    public void setAvgQueued(double avgQueued) {
        this.avgQueued = avgQueued;
    }

    /**
     * @return the minimum {@link ActivitySample#getQueuedSnapshot()} observed
     *         across every sample folded into this bucket
     */
    public long getMinQueued() {
        return minQueued;
    }

    /**
     * @param minQueued the minimum queued-message snapshot observed across
     *                  every sample folded into this bucket
     */
    public void setMinQueued(long minQueued) {
        this.minQueued = minQueued;
    }

    /**
     * @return the maximum {@link ActivitySample#getQueuedSnapshot()} observed
     *         across every sample folded into this bucket
     */
    public long getMaxQueued() {
        return maxQueued;
    }

    /**
     * @param maxQueued the maximum queued-message snapshot observed across
     *                  every sample folded into this bucket
     */
    public void setMaxQueued(long maxQueued) {
        this.maxQueued = maxQueued;
    }
}
