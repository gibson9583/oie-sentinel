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
     * Reports what was actually drawn, which is not always what was asked
     * for: the service promotes {@code RAW} to {@code HOURLY} over wide
     * ranges and folds either source into at most {@code MAX_POINTS} buckets
     * (see {@code ActivityQueryService}). Clients must label the chart from
     * this value rather than the requested one, and must not test it for
     * equality with {@code "RAW"} — a folded series carries a compound
     * label.
     *
     * @return the resolved granularity — {@code "RAW"} or {@code "HOURLY"}
     *         when the samples were read one-for-one, otherwise
     *         {@code "<SOURCE>_<ISO-8601 bucket width>"} such as
     *         {@code "HOURLY_PT1H4M48S"} when the series was downsampled.
     *         Never {@code "AUTO"} — that is a request-side value only.
     */
    public String getGranularity() {
        return granularity;
    }

    /**
     * @param granularity the granularity the server resolved to; see
     *                    {@link #getGranularity()} for the compound
     *                    downsampled form
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
