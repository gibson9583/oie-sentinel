/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * A single delivery attempt of a {@link Action} against an alert event,
 * persisted in {@code sentinel_action_dispatch_log}.
 *
 * <p>One row is written every time the dispatch job attempts to fire an
 * action for an alert event (initial fire, or a repeat under the action's
 * {@link OperationMode}), whether or not the delivery succeeded. This is a
 * write-once audit trail — rows are never updated, only inserted and later
 * read back for the alert event's history.</p>
 */
public class ActionDispatchLog {

    private Long id;
    private long alertEventId;
    private Integer actionId;
    private Instant dispatchTime;
    private boolean success;
    private String errorMessage;

    /**
     * Creates an empty dispatch log entry. Callers populate fields via the
     * setters before handing the instance to {@code ActionDispatchLogRepository}.
     */
    public ActionDispatchLog() {
    }

    /**
     * @return the database-assigned id of this row, or {@code null} for an
     *         instance that has not been persisted yet
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
     * @return id of the {@code sentinel_alert_event} this dispatch attempt
     *         was made for
     */
    public long getAlertEventId() {
        return alertEventId;
    }

    /**
     * @param alertEventId id of the {@code sentinel_alert_event} this
     *                     dispatch attempt was made for
     */
    public void setAlertEventId(long alertEventId) {
        this.alertEventId = alertEventId;
    }

    /**
     * @return id of the {@code sentinel_action} that was dispatched, or
     *         {@code null} if the action has since been deleted (the FK is
     *         {@code ON DELETE SET NULL}, so the log entry outlives the
     *         action it once referenced)
     */
    public Integer getActionId() {
        return actionId;
    }

    /**
     * @param actionId id of the {@code sentinel_action} that was dispatched;
     *                 {@code null} once the action has been deleted
     */
    public void setActionId(Integer actionId) {
        this.actionId = actionId;
    }

    /**
     * @return when this dispatch attempt was made
     */
    public Instant getDispatchTime() {
        return dispatchTime;
    }

    /**
     * @param dispatchTime when this dispatch attempt was made
     */
    public void setDispatchTime(Instant dispatchTime) {
        this.dispatchTime = dispatchTime;
    }

    /**
     * @return {@code true} if the delivery succeeded; {@code false} if it
     *         failed (see {@link #getErrorMessage()} for detail)
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @param success whether the delivery succeeded
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * @return detail on why the delivery failed, or {@code null} on success
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * @param errorMessage detail on why the delivery failed; {@code null} on
     *                     success
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
