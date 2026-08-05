/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.util.List;

/**
 * Compact per-channel activity rollup for the batched
 * {@code GET /activity/summary} endpoint: window totals plus a small
 * received-count sparkline series.
 *
 * <p>Batched into one response for many channels deliberately — the
 * dashboard's channel cards would otherwise issue an activity request per
 * channel (N+1). The sparkline is a bare {@code List<Long>} rather than
 * {@link ActivityPoint}s because the widget only draws relative shape; the
 * client derives timestamps from the requested window and bucket count if it
 * needs them. Response-only; never persisted.</p>
 */
public class ChannelActivitySummary {

    private String channelId;
    private String channelName;
    private long received;
    private long sent;
    private long error;
    private List<Long> sparkline;

    public ChannelActivitySummary() {
    }

    /**
     * @return the OIE channel id (a UUID string) this summary belongs to
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id this summary belongs to
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the channel's display name, resolved when the summary was built
     */
    public String getChannelName() {
        return channelName;
    }

    /**
     * @param channelName the channel's display name
     */
    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    /**
     * @return total messages received in the requested window
     */
    public long getReceived() {
        return received;
    }

    /**
     * @param received total messages received in the requested window
     */
    public void setReceived(long received) {
        this.received = received;
    }

    /**
     * @return total messages sent in the requested window
     */
    public long getSent() {
        return sent;
    }

    /**
     * @param sent total messages sent in the requested window
     */
    public void setSent(long sent) {
        this.sent = sent;
    }

    /**
     * @return total messages errored in the requested window
     */
    public long getError() {
        return error;
    }

    /**
     * @param error total messages errored in the requested window
     */
    public void setError(long error) {
        this.error = error;
    }

    /**
     * @return received-message counts summed into equal sub-windows of the
     *         requested range, oldest first — one value per requested bucket
     */
    public List<Long> getSparkline() {
        return sparkline;
    }

    /**
     * @param sparkline received-message counts per sub-window, oldest first
     */
    public void setSparkline(List<Long> sparkline) {
        this.sparkline = sparkline;
    }
}
