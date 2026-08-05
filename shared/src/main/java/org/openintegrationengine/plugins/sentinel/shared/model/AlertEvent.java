/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * One raised or resolved problem instance for a monitor: the "Problems" list
 * item shown in the admin UI.
 *
 * <p>Maps to {@code sentinel_alert_event}. A monitor opens a new row when its
 * trigger transitions into breach ({@code status = PROBLEM}) and closes it
 * ({@code status = RESOLVED}, {@link #getResolvedTime()} populated) when the
 * condition clears. {@link #getDetailsJson()} holds monitor-type-specific
 * evaluation context (the observed value, threshold, baseline, etc.) as a raw
 * JSON string; this DTO does not parse it — that is the Service layer's
 * job.</p>
 */
public class AlertEvent {

    private Long id;
    private int monitorId;
    private String channelId;
    private Integer metadataId;
    private Severity severity;
    private AlertStatus status;
    private String message;
    private Instant openedTime;
    private Instant resolvedTime;
    private Integer acknowledgedBy;
    private Instant acknowledgedTime;
    private String ackComment;
    private String detailsJson;
    private boolean suppressed;

    public AlertEvent() {
    }

    /**
     * @return the database-assigned id of this alert event, or {@code null}
     *         for an event that has not been persisted yet
     */
    public Long getId() {
        return id;
    }

    /**
     * @param id the database-assigned id; typically set by the repository
     *           after insert
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * @return the id of the {@code sentinel_monitor} that raised this event
     */
    public int getMonitorId() {
        return monitorId;
    }

    /**
     * @param monitorId the id of the {@code sentinel_monitor} that raised
     *                  this event
     */
    public void setMonitorId(int monitorId) {
        this.monitorId = monitorId;
    }

    /**
     * @return the OIE channel id (a UUID string) this event was raised
     *         against
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id (a UUID string) this event was
     *                  raised against
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the connector metadata id this event pertains to, or
     *         {@code null} for a monitor type that evaluates at the
     *         whole-channel level (e.g. {@code CONNECTION_STATUS} events are
     *         per-connector, others are not)
     */
    public Integer getMetadataId() {
        return metadataId;
    }

    /**
     * @param metadataId the connector metadata id this event pertains to, or
     *                   {@code null} for a whole-channel-level event
     */
    public void setMetadataId(Integer metadataId) {
        this.metadataId = metadataId;
    }

    /**
     * @return the severity carried over from the raising monitor at the time
     *         this event opened
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * @param severity the severity carried over from the raising monitor at
     *                 the time this event opened
     */
    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    /**
     * @return the current lifecycle status of this event
     */
    public AlertStatus getStatus() {
        return status;
    }

    /**
     * @param status the current lifecycle status of this event
     */
    public void setStatus(AlertStatus status) {
        this.status = status;
    }

    /**
     * @return a human-readable summary of the condition that raised this
     *         event
     */
    public String getMessage() {
        return message;
    }

    /**
     * @param message a human-readable summary of the condition that raised
     *                this event
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * @return when this event was raised
     */
    public Instant getOpenedTime() {
        return openedTime;
    }

    /**
     * @param openedTime when this event was raised
     */
    public void setOpenedTime(Instant openedTime) {
        this.openedTime = openedTime;
    }

    /**
     * @return when this event's condition cleared, or {@code null} while it
     *         is still open ({@code status = PROBLEM})
     */
    public Instant getResolvedTime() {
        return resolvedTime;
    }

    /**
     * @param resolvedTime when this event's condition cleared, or
     *                     {@code null} while it is still open
     */
    public void setResolvedTime(Instant resolvedTime) {
        this.resolvedTime = resolvedTime;
    }

    /**
     * @return the engine user id that acknowledged this event, or
     *         {@code null} if it has not been acknowledged
     */
    public Integer getAcknowledgedBy() {
        return acknowledgedBy;
    }

    /**
     * @param acknowledgedBy the engine user id that acknowledged this event,
     *                       or {@code null} to clear the acknowledgment
     */
    public void setAcknowledgedBy(Integer acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    /**
     * @return when this event was acknowledged, or {@code null} if it has not
     *         been acknowledged
     */
    public Instant getAcknowledgedTime() {
        return acknowledgedTime;
    }

    /**
     * @param acknowledgedTime when this event was acknowledged, or
     *                         {@code null} to clear the acknowledgment
     */
    public void setAcknowledgedTime(Instant acknowledgedTime) {
        this.acknowledgedTime = acknowledgedTime;
    }

    /**
     * @return the free-text comment left by whoever acknowledged this event,
     *         or {@code null}
     */
    public String getAckComment() {
        return ackComment;
    }

    /**
     * @param ackComment the free-text comment left by whoever acknowledged
     *                   this event, or {@code null}
     */
    public void setAckComment(String ackComment) {
        this.ackComment = ackComment;
    }

    /**
     * @return monitor-type-specific evaluation context (observed value,
     *         threshold, baseline, etc.) as a raw JSON string, or
     *         {@code null}; not parsed at this layer
     */
    public String getDetailsJson() {
        return detailsJson;
    }

    /**
     * @param detailsJson monitor-type-specific evaluation context as a raw
     *                    JSON string; not validated at this layer
     */
    public void setDetailsJson(String detailsJson) {
        this.detailsJson = detailsJson;
    }

    /**
     * @return {@code true} if this event was suppressed by another monitor's
     *         open state (via {@code sentinel_monitor.suppressed_by_monitor_id})
     *         and should be hidden from default views/action dispatch
     */
    public boolean isSuppressed() {
        return suppressed;
    }

    /**
     * @param suppressed whether this event was suppressed by another
     *                   monitor's open state
     */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
    }
}
