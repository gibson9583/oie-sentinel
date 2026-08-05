/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * One raw activity measurement for a channel, persisted in
 * {@code sentinel_channel_activity_sample}.
 *
 * <p>The collector job polls each deployed channel's connector statistics on
 * a fixed interval and writes one row per poll, capturing the delta since the
 * previous sample (not a running total) plus a point-in-time queue depth
 * snapshot. {@code sentinel_channel_activity_trend} later rolls these raw
 * samples up into hourly buckets; this DTO is the pre-rollup granularity used
 * by both the rollup job and any raw-window query (e.g. "last 15 minutes").</p>
 */
public class ActivitySample {

    private Long id;
    private String channelId;
    private Instant sampleTime;
    private long receivedDelta;
    private long sentDelta;
    private long errorDelta;
    private long filteredDelta;
    private long queuedSnapshot;

    /**
     * Creates an empty sample. Callers populate fields via the setters before
     * handing the instance to {@code ActivityRepository}.
     */
    public ActivitySample() {
    }

    /**
     * @return the database-assigned id of this sample, or {@code null} for an
     *         instance that has not been persisted yet
     */
    public Long getId() {
        return id;
    }

    /**
     * @param id the database-assigned id; this table's insert is a plain
     *           insert with no generated-key retrieval, so this is normally
     *           only populated when reading a row back from a query
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * @return the OIE channel id (a UUID string) this sample was collected for
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id (a UUID string) this sample was
     *                  collected for
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return when this sample was collected
     */
    public Instant getSampleTime() {
        return sampleTime;
    }

    /**
     * @param sampleTime when this sample was collected
     */
    public void setSampleTime(Instant sampleTime) {
        this.sampleTime = sampleTime;
    }

    /**
     * @return the number of messages received since the previous sample (a
     *         delta, not a running total)
     */
    public long getReceivedDelta() {
        return receivedDelta;
    }

    /**
     * @param receivedDelta the number of messages received since the
     *                      previous sample
     */
    public void setReceivedDelta(long receivedDelta) {
        this.receivedDelta = receivedDelta;
    }

    /**
     * @return the number of messages sent since the previous sample (a
     *         delta, not a running total)
     */
    public long getSentDelta() {
        return sentDelta;
    }

    /**
     * @param sentDelta the number of messages sent since the previous sample
     */
    public void setSentDelta(long sentDelta) {
        this.sentDelta = sentDelta;
    }

    /**
     * @return the number of messages errored since the previous sample (a
     *         delta, not a running total)
     */
    public long getErrorDelta() {
        return errorDelta;
    }

    /**
     * @param errorDelta the number of messages errored since the previous
     *                   sample
     */
    public void setErrorDelta(long errorDelta) {
        this.errorDelta = errorDelta;
    }

    /**
     * @return the number of messages filtered since the previous sample (a
     *         delta, not a running total)
     */
    public long getFilteredDelta() {
        return filteredDelta;
    }

    /**
     * @param filteredDelta the number of messages filtered since the
     *                      previous sample
     */
    public void setFilteredDelta(long filteredDelta) {
        this.filteredDelta = filteredDelta;
    }

    /**
     * @return the channel's queued-message count at the moment this sample
     *         was taken (a point-in-time snapshot, not a delta)
     */
    public long getQueuedSnapshot() {
        return queuedSnapshot;
    }

    /**
     * @param queuedSnapshot the channel's queued-message count at the moment
     *                       this sample was taken
     */
    public void setQueuedSnapshot(long queuedSnapshot) {
        this.queuedSnapshot = queuedSnapshot;
    }
}
