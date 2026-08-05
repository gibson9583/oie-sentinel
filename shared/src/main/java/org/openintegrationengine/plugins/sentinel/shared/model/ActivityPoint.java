/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * One point in a channel's activity time series ({@link ChannelActivity}):
 * message counts observed at (raw granularity) or aggregated into (hourly
 * granularity) a moment in time.
 *
 * <p>The same shape serves both granularities so the web UI's chart
 * component doesn't branch on where the data came from. At RAW granularity
 * the counts are per-collector-interval deltas and {@code queued} is a
 * point-in-time snapshot; at HOURLY they are hour sums and {@code queued} is
 * the hour's average (with {@code filtered} always 0, since the hourly
 * rollup does not carry it). Response-only; never persisted.</p>
 */
public class ActivityPoint {

    private Instant time;
    private long received;
    private long sent;
    private long error;
    private long filtered;
    private long queued;

    public ActivityPoint() {
    }

    /**
     * @return the moment this point represents: the sample time at RAW
     *         granularity, the hour-bucket start at HOURLY
     */
    public Instant getTime() {
        return time;
    }

    /**
     * @param time the moment this point represents
     */
    public void setTime(Instant time) {
        this.time = time;
    }

    /**
     * @return messages received in this point's interval
     */
    public long getReceived() {
        return received;
    }

    /**
     * @param received messages received in this point's interval
     */
    public void setReceived(long received) {
        this.received = received;
    }

    /**
     * @return messages sent in this point's interval
     */
    public long getSent() {
        return sent;
    }

    /**
     * @param sent messages sent in this point's interval
     */
    public void setSent(long sent) {
        this.sent = sent;
    }

    /**
     * @return messages errored in this point's interval
     */
    public long getError() {
        return error;
    }

    /**
     * @param error messages errored in this point's interval
     */
    public void setError(long error) {
        this.error = error;
    }

    /**
     * @return messages filtered in this point's interval; always 0 at HOURLY
     *         granularity because the rollup table does not carry filtered
     *         counts
     */
    public long getFiltered() {
        return filtered;
    }

    /**
     * @param filtered messages filtered in this point's interval
     */
    public void setFiltered(long filtered) {
        this.filtered = filtered;
    }

    /**
     * @return queue depth: a point-in-time snapshot at RAW granularity, the
     *         hour's average (rounded) at HOURLY
     */
    public long getQueued() {
        return queued;
    }

    /**
     * @param queued queue depth for this point
     */
    public void setQueued(long queued) {
        this.queued = queued;
    }
}
