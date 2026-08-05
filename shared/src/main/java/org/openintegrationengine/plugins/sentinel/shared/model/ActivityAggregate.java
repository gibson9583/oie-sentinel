/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * A single-row aggregate over {@code sentinel_channel_activity_sample} for
 * one channel and time range, produced by
 * {@code ActivityRepository.sumActivitySamplesForRange}.
 *
 * <p>Result-only: there is no corresponding table and no id/channel/time
 * fields — the caller already knows which channel and range it asked for.
 * The underlying query {@code COALESCE}s every aggregate to zero, so an empty
 * range never leaves a field null; callers never need to null-check.</p>
 */
public class ActivityAggregate {

    private long receivedSum;
    private long sentSum;
    private long errorSum;
    private double avgQueued;
    private long minQueued;
    private long maxQueued;

    /**
     * Creates an all-zero aggregate. Callers populate fields via the setters,
     * or receive a populated instance from {@code ActivityRepository}.
     */
    public ActivityAggregate() {
    }

    /**
     * @return the sum of {@code received_delta} across every sample in range
     */
    public long getReceivedSum() {
        return receivedSum;
    }

    /**
     * @param receivedSum the sum of {@code received_delta} across every
     *                    sample in range
     */
    public void setReceivedSum(long receivedSum) {
        this.receivedSum = receivedSum;
    }

    /**
     * @return the sum of {@code sent_delta} across every sample in range
     */
    public long getSentSum() {
        return sentSum;
    }

    /**
     * @param sentSum the sum of {@code sent_delta} across every sample in
     *                range
     */
    public void setSentSum(long sentSum) {
        this.sentSum = sentSum;
    }

    /**
     * @return the sum of {@code error_delta} across every sample in range
     */
    public long getErrorSum() {
        return errorSum;
    }

    /**
     * @param errorSum the sum of {@code error_delta} across every sample in
     *                 range
     */
    public void setErrorSum(long errorSum) {
        this.errorSum = errorSum;
    }

    /**
     * @return the average {@code queued_snapshot} across every sample in
     *         range
     */
    public double getAvgQueued() {
        return avgQueued;
    }

    /**
     * @param avgQueued the average {@code queued_snapshot} across every
     *                  sample in range
     */
    public void setAvgQueued(double avgQueued) {
        this.avgQueued = avgQueued;
    }

    /**
     * @return the minimum {@code queued_snapshot} observed across every
     *         sample in range
     */
    public long getMinQueued() {
        return minQueued;
    }

    /**
     * @param minQueued the minimum {@code queued_snapshot} observed across
     *                  every sample in range
     */
    public void setMinQueued(long minQueued) {
        this.minQueued = minQueued;
    }

    /**
     * @return the maximum {@code queued_snapshot} observed across every
     *         sample in range
     */
    public long getMaxQueued() {
        return maxQueued;
    }

    /**
     * @param maxQueued the maximum {@code queued_snapshot} observed across
     *                  every sample in range
     */
    public void setMaxQueued(long maxQueued) {
        this.maxQueued = maxQueued;
    }
}
