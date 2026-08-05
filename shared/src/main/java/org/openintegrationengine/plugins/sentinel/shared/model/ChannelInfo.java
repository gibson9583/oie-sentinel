/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * A slim view of one engine channel for Sentinel's scope pickers and
 * channel lists ({@code GET /core/channels}).
 *
 * <p>Exists because the engine's own channel endpoints return XStream-shaped
 * payloads with far more detail than the pickers need; this passthrough DTO
 * gives the web UI exactly id, name, and run state as clean JSON. State is a
 * String (the deployed-state name, or {@code "UNDEPLOYED"}) rather than the
 * engine's enum so the shared module carries no engine dependency.
 * Response-only; never persisted.</p>
 */
public class ChannelInfo {

    private String channelId;
    private String name;
    private String state;
    private boolean started;

    public ChannelInfo() {
    }

    /**
     * @return the OIE channel id (a UUID string)
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the channel's display name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name the channel's display name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the deployed-state name (e.g. {@code "STARTED"},
     *         {@code "PAUSED"}, {@code "STOPPED"}), or {@code "UNDEPLOYED"}
     *         when the channel is not currently deployed
     */
    public String getState() {
        return state;
    }

    /**
     * @param state the deployed-state name, or {@code "UNDEPLOYED"}
     */
    public void setState(String state) {
        this.state = state;
    }

    /**
     * @return {@code true} only when the channel is deployed AND started —
     *         the exact condition under which Sentinel's evaluator will watch
     *         it, precomputed so pickers can flag channels a monitor would
     *         currently ignore
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * @param started whether the channel is deployed and started
     */
    public void setStarted(boolean started) {
        this.started = started;
    }
}
