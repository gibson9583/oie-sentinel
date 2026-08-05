/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * The dry-run evaluation result for one target channel inside a
 * {@link MonitorTestResult}.
 *
 * <p>{@link #getStatus()} is a String rather than an enum because it carries
 * either an evaluation result name ({@code BREACH}, {@code OK},
 * {@code INSUFFICIENT_DATA}) or the literal {@code "ERROR"} when the
 * evaluator itself threw — a union no single shared enum models, and the web
 * UI only needs it for badge text anyway. Response-only; never persisted.</p>
 */
public class ChannelTestOutcome {

    private String channelId;
    private String channelName;
    private String status;
    private String valueSummary;

    public ChannelTestOutcome() {
    }

    /**
     * @return the OIE channel id (a UUID string) this outcome is for
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id this outcome is for
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the channel's display name, resolved at evaluation time
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
     * @return the evaluation result name ({@code BREACH}, {@code OK},
     *         {@code INSUFFICIENT_DATA}) or {@code "ERROR"} if the evaluator
     *         threw for this channel
     */
    public String getStatus() {
        return status;
    }

    /**
     * @param status the evaluation result name, or {@code "ERROR"} when the
     *               evaluator threw
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * @return human-readable detail of what the evaluator observed (the
     *         breach message, or the observed-value JSON when there is no
     *         message)
     */
    public String getValueSummary() {
        return valueSummary;
    }

    /**
     * @param valueSummary human-readable detail of what the evaluator observed
     */
    public void setValueSummary(String valueSummary) {
        this.valueSummary = valueSummary;
    }
}
