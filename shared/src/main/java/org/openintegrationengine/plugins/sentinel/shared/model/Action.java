/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * An alerting action: a delivery rule that fires when an alert event's
 * lifecycle matches its {@link #getOperationMode()}, provided its own
 * {@link #getConditionJson()} filter (severity, monitor type, etc.) matches
 * the event too.
 *
 * <p>Maps to {@code sentinel_action}. {@link #getConditionJson()} holds the
 * filter expression and {@link #getConfigJson()} holds the delivery-specific
 * parameters (recipient address, channel id, SNS topic ARN, etc.), both as
 * raw JSON strings; this DTO does not parse either — that is the Service
 * layer's job.</p>
 */
public class Action {

    private Integer id;
    private String name;
    private String description;
    private boolean enabled;
    private ActionType actionType;
    private String conditionJson;
    private OperationMode operationMode;
    private Integer repeatIntervalSeconds;
    private Integer maxRepeats;
    private Integer maxNotificationsPerWindow;
    private Integer rollupWindowSeconds;
    private Integer escalateAfterSeconds;
    private Integer escalateToActionId;
    private String configJson;
    private Integer createdBy;
    private Instant createdTime;
    private Integer updatedBy;
    private Instant updatedTime;

    public Action() {
    }

    /**
     * @return the database-assigned id of this action, or {@code null} for
     *         an action that has not been persisted yet
     */
    public Integer getId() {
        return id;
    }

    /**
     * @param id the database-assigned id; typically set by the repository
     *           after insert
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * @return the action's display name (must be unique across all actions)
     */
    public String getName() {
        return name;
    }

    /**
     * @param name the action's display name; must be unique across all
     *             actions, enforced by a UNIQUE constraint on
     *             {@code sentinel_action.name}
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return free-text description shown in the admin UI; may be {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * @param description free-text description shown in the admin UI; may be
     *                    {@code null} or empty
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * @return {@code true} if this action currently fires when its condition
     *         matches; {@code false} actions are skipped
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled whether this action currently fires when its condition
     *                matches
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return which delivery mechanism this action uses
     */
    public ActionType getActionType() {
        return actionType;
    }

    /**
     * @param actionType which delivery mechanism this action uses
     */
    public void setActionType(ActionType actionType) {
        this.actionType = actionType;
    }

    /**
     * @return the filter expression (severity, monitor type, etc.) that
     *         decides whether this action fires for a given alert event, as
     *         a raw JSON string; not parsed at this layer
     */
    public String getConditionJson() {
        return conditionJson;
    }

    /**
     * @param conditionJson the filter expression that decides whether this
     *                      action fires for a given alert event, as a raw
     *                      JSON string; not validated at this layer
     */
    public void setConditionJson(String conditionJson) {
        this.conditionJson = conditionJson;
    }

    /**
     * @return when this action fires relative to an alert event's lifecycle
     */
    public OperationMode getOperationMode() {
        return operationMode;
    }

    /**
     * @param operationMode when this action fires relative to an alert
     *                      event's lifecycle
     */
    public void setOperationMode(OperationMode operationMode) {
        this.operationMode = operationMode;
    }

    /**
     * @return how often, in seconds, this action re-fires while an alert
     *         event stays open, or {@code null} if it fires only once
     */
    public Integer getRepeatIntervalSeconds() {
        return repeatIntervalSeconds;
    }

    /**
     * @param repeatIntervalSeconds how often, in seconds, this action
     *                              re-fires while an alert event stays open;
     *                              {@code null} to fire only once
     */
    public void setRepeatIntervalSeconds(Integer repeatIntervalSeconds) {
        this.repeatIntervalSeconds = repeatIntervalSeconds;
    }

    /**
     * @return the maximum number of repeat firings for a single alert event,
     *         or {@code null} for no limit
     */
    public Integer getMaxRepeats() {
        return maxRepeats;
    }

    /**
     * @param maxRepeats the maximum number of repeat firings for a single
     *                   alert event; {@code null} for no limit
     */
    public void setMaxRepeats(Integer maxRepeats) {
        this.maxRepeats = maxRepeats;
    }

    /**
     * The storm-control ceiling: how many individual notifications this action
     * may send within one {@link #getRollupWindowSeconds()} window before it
     * stops sending them one at a time.
     *
     * <p>Distinct from {@link #getMaxRepeats()}, which bounds re-notification
     * of a <em>single</em> still-open problem. This bounds notifications across
     * <em>all</em> problems the action matches, which is the case that actually
     * pages people at three in the morning: one shared dependency fails, thirty
     * channels open a problem each, and thirty separate notifications go out.
     * Past the ceiling the individual sends are suppressed in favour of a
     * single rollup notification naming the affected channels.</p>
     *
     * @return the maximum individual notifications per rollup window, or
     *         {@code null} for no ceiling (the pre-v4 behavior, and the value
     *         every row upgraded to v4 carries)
     */
    public Integer getMaxNotificationsPerWindow() {
        return maxNotificationsPerWindow;
    }

    /**
     * @param maxNotificationsPerWindow the maximum individual notifications per
     *                                  rollup window; {@code null} for no
     *                                  ceiling. Meaningful only alongside
     *                                  {@link #setRollupWindowSeconds(Integer)}
     */
    public void setMaxNotificationsPerWindow(Integer maxNotificationsPerWindow) {
        this.maxNotificationsPerWindow = maxNotificationsPerWindow;
    }

    /**
     * The width of the window over which {@link #getMaxNotificationsPerWindow()}
     * is counted, and equally the period a rollup notification summarizes.
     *
     * <p>The two are a pair: a ceiling with no window has nothing to count
     * against, and a window with no ceiling never trips. Both are nullable and
     * independently settable because they are separate columns, so consumers
     * must treat "only one is set" as "storm control is not configured" rather
     * than assuming a default for the missing half.</p>
     *
     * @return the rollup window in seconds, or {@code null} when storm control
     *         is not configured
     */
    public Integer getRollupWindowSeconds() {
        return rollupWindowSeconds;
    }

    /**
     * @param rollupWindowSeconds the rollup window in seconds; {@code null}
     *                            when storm control is not configured
     */
    public void setRollupWindowSeconds(Integer rollupWindowSeconds) {
        this.rollupWindowSeconds = rollupWindowSeconds;
    }

    /**
     * How long a problem may stay open, in seconds, before it escalates to
     * {@link #getEscalateToActionId()}.
     *
     * <p>Where {@link #getRepeatIntervalSeconds()} re-notifies the same target
     * indefinitely, this hands the problem to a different one — the shift lead,
     * a louder channel, a wider distribution list — on the theory that a
     * problem nobody has closed in an hour is a problem the first target is not
     * acting on.</p>
     *
     * @return seconds a problem must remain open before escalating, or
     *         {@code null} for no escalation
     */
    public Integer getEscalateAfterSeconds() {
        return escalateAfterSeconds;
    }

    /**
     * @param escalateAfterSeconds seconds a problem must remain open before
     *                             escalating; {@code null} for no escalation
     */
    public void setEscalateAfterSeconds(Integer escalateAfterSeconds) {
        this.escalateAfterSeconds = escalateAfterSeconds;
    }

    /**
     * The id of the action a still-open problem escalates to once
     * {@link #getEscalateAfterSeconds()} has elapsed.
     *
     * <p><b>This reference is not enforced by a foreign key and must be
     * resolved defensively.</b> The column deliberately carries no FK to
     * {@code sentinel_action(id)}: a hard constraint would make an action
     * undeletable for as long as any other action escalated to it, and turn an
     * ordinary "delete this obsolete action" into a constraint-violation error
     * that names an unrelated row. ({@code ON DELETE SET NULL} would avoid the
     * error but silently rewrite the escalating action's configuration, which
     * is worse — an operator would discover the escalation had evaporated only
     * when it failed to fire.)</p>
     *
     * <p>The consequence is that this id may point at a row that no longer
     * exists. Every consumer must look the target up, tolerate {@code null},
     * and treat a missing target as "the escalation chain ends here" rather
     * than as an error. The same applies to cycles: nothing prevents two
     * actions escalating to each other, so a chain walker needs its own visited
     * set or depth bound.</p>
     *
     * @return the id of the action to escalate to, or {@code null} for no
     *         escalation target; a non-null value is not a guarantee that the
     *         action still exists
     */
    public Integer getEscalateToActionId() {
        return escalateToActionId;
    }

    /**
     * @param escalateToActionId the id of the action to escalate to;
     *                           {@code null} for none. Not validated here and
     *                           not constrained by the database — see
     *                           {@link #getEscalateToActionId()}
     */
    public void setEscalateToActionId(Integer escalateToActionId) {
        this.escalateToActionId = escalateToActionId;
    }

    /**
     * @return the delivery-specific parameters (recipient address, channel
     *         id, SNS topic ARN, etc.) as a raw JSON string; not parsed at
     *         this layer
     */
    public String getConfigJson() {
        return configJson;
    }

    /**
     * @param configJson the delivery-specific parameters as a raw JSON
     *                   string; not validated at this layer
     */
    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    /**
     * @return the engine user id that created this action, or {@code null}
     *         if unknown (e.g. seeded by a migration)
     */
    public Integer getCreatedBy() {
        return createdBy;
    }

    /**
     * @param createdBy the engine user id that created this action
     */
    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    /**
     * @return when this action was created
     */
    public Instant getCreatedTime() {
        return createdTime;
    }

    /**
     * @param createdTime when this action was created
     */
    public void setCreatedTime(Instant createdTime) {
        this.createdTime = createdTime;
    }

    /**
     * @return the engine user id that last updated this action, or
     *         {@code null} if it has never been updated
     */
    public Integer getUpdatedBy() {
        return updatedBy;
    }

    /**
     * @param updatedBy the engine user id that last updated this action
     */
    public void setUpdatedBy(Integer updatedBy) {
        this.updatedBy = updatedBy;
    }

    /**
     * @return when this action was last updated, or {@code null} if it has
     *         never been updated
     */
    public Instant getUpdatedTime() {
        return updatedTime;
    }

    /**
     * @param updatedTime when this action was last updated
     */
    public void setUpdatedTime(Instant updatedTime) {
        this.updatedTime = updatedTime;
    }
}
