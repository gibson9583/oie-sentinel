/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.util.List;

/**
 * One channel's activity time series for charting
 * ({@code GET /channels/{channelId}/activity}).
 *
 * <p>{@link #getGranularity()} echoes back which source the server actually
 * used ({@code RAW} collector samples or the {@code HOURLY} rollup) — the
 * client usually requests {@code AUTO} and needs to know what it got so the
 * chart can label its resolution and interpret {@code queued}/{@code filtered}
 * correctly (see {@link ActivityPoint}). Response-only; never persisted.</p>
 */
public class ChannelActivity {

    private String channelId;
    private String granularity;
    private List<ActivityPoint> points;

    public ChannelActivity() {
    }

    /**
     * @return the OIE channel id (a UUID string) this series belongs to
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id this series belongs to
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the granularity the server resolved to: {@code "RAW"} or
     *         {@code "HOURLY"} (never {@code "AUTO"} — that is a request-side
     *         value only)
     */
    public String getGranularity() {
        return granularity;
    }

    /**
     * @param granularity the granularity the server resolved to
     */
    public void setGranularity(String granularity) {
        this.granularity = granularity;
    }

    /**
     * @return the series points in ascending time order; empty when the range
     *         holds no data
     */
    public List<ActivityPoint> getPoints() {
        return points;
    }

    /**
     * @param points the series points in ascending time order
     */
    public void setPoints(List<ActivityPoint> points) {
        this.points = points;
    }
}
