/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * One row in the dashboard's "top problem channels" widget: a channel ranked
 * by how many open alert events it currently has, with the worst severity
 * among them for badge coloring.
 *
 * <p>The channel name is resolved server-side at build time so the widget
 * needs no follow-up lookups; it may lag a rename by one refresh, which is
 * acceptable for a dashboard. Response-only; never persisted.</p>
 */
public class TopChannel {

    private String channelId;
    private String channelName;
    private int openCount;
    private Severity maxSeverity;

    public TopChannel() {
    }

    /**
     * @return the OIE channel id (a UUID string) this row ranks
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id this row ranks
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
     * @return number of currently open alert events against this channel —
     *         the ranking key
     */
    public int getOpenCount() {
        return openCount;
    }

    /**
     * @param openCount number of currently open alert events against this channel
     */
    public void setOpenCount(int openCount) {
        this.openCount = openCount;
    }

    /**
     * @return the highest {@link Severity} (by ordinal) among this channel's
     *         open events, used for the row's severity badge
     */
    public Severity getMaxSeverity() {
        return maxSeverity;
    }

    /**
     * @param maxSeverity the highest severity among this channel's open events
     */
    public void setMaxSeverity(Severity maxSeverity) {
        this.maxSeverity = maxSeverity;
    }
}
