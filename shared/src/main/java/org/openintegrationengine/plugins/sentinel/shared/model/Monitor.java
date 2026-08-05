/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * A monitor definition: a rule Sentinel evaluates on a schedule against one
 * channel, one channel group, or every deployed channel, raising/clearing
 * alert events (see {@code sentinel_alert_event}) as its condition
 * transitions.
 *
 * <p>Maps to {@code sentinel_monitor}. {@link #getConfigJson()} holds the
 * type-specific rule parameters (threshold, duration, baseline window, etc.)
 * as a raw JSON string; this DTO does not parse it — that is the Service
 * layer's job.</p>
 */
public class Monitor {

    private Integer id;
    private String name;
    private String description;
    private MonitorType monitorType;
    private ScopeType scopeType;
    private String scopeId;
    private boolean enabled;
    private Severity severity;
    private String configJson;
    private int minConsecutiveBreaches;
    private Integer suppressedByMonitorId;
    private Integer createdBy;
    private Instant createdTime;
    private Integer updatedBy;
    private Instant updatedTime;

    public Monitor() {
    }

    /**
     * @return the database-assigned id of this monitor, or {@code null} for
     *         a monitor that has not been persisted yet
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
     * @return the monitor's display name (must be unique across all monitors)
     */
    public String getName() {
        return name;
    }

    /**
     * @param name the monitor's display name; must be unique across all
     *             monitors, enforced by a UNIQUE constraint on
     *             {@code sentinel_monitor.name}
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
     * @return which rule type this monitor evaluates; determines how
     *         {@link #getConfigJson()} is interpreted
     */
    public MonitorType getMonitorType() {
        return monitorType;
    }

    /**
     * @param monitorType which rule type this monitor evaluates
     */
    public void setMonitorType(MonitorType monitorType) {
        this.monitorType = monitorType;
    }

    /**
     * @return what {@link #getScopeId()} refers to (a single channel, a
     *         channel group, or all channels)
     */
    public ScopeType getScopeType() {
        return scopeType;
    }

    /**
     * @param scopeType what {@link #getScopeId()} refers to
     */
    public void setScopeType(ScopeType scopeType) {
        this.scopeType = scopeType;
    }

    /**
     * @return the channel id or channel-group id this monitor applies to;
     *         {@code null} when {@link #getScopeType()} is {@code ALL}
     */
    public String getScopeId() {
        return scopeId;
    }

    /**
     * @param scopeId the channel id or channel-group id this monitor applies
     *                to; must be {@code null} when {@link #getScopeType()} is
     *                {@code ALL}
     */
    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    /**
     * @return {@code true} if this monitor is currently evaluated by the
     *         scheduler; {@code false} monitors are skipped
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled whether this monitor is currently evaluated by the
     *                scheduler
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return the severity assigned to alert events this monitor raises
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * @param severity the severity assigned to alert events this monitor
     *                 raises
     */
    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    /**
     * @return the type-specific rule parameters as a raw JSON string; not
     *         parsed at this layer
     */
    public String getConfigJson() {
        return configJson;
    }

    /**
     * @param configJson the type-specific rule parameters as a raw JSON
     *                   string; not validated at this layer
     */
    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    /**
     * @return how many consecutive evaluation breaches are required before
     *         this monitor opens an alert event
     */
    public int getMinConsecutiveBreaches() {
        return minConsecutiveBreaches;
    }

    /**
     * @param minConsecutiveBreaches how many consecutive evaluation breaches
     *                               are required before this monitor opens
     *                               an alert event
     */
    public void setMinConsecutiveBreaches(int minConsecutiveBreaches) {
        this.minConsecutiveBreaches = minConsecutiveBreaches;
    }

    /**
     * @return the id of another monitor whose open state suppresses this
     *         monitor's alerting, or {@code null} if this monitor is not
     *         suppressed by another
     */
    public Integer getSuppressedByMonitorId() {
        return suppressedByMonitorId;
    }

    /**
     * @param suppressedByMonitorId the id of another monitor whose open state
     *                              suppresses this monitor's alerting, or
     *                              {@code null} for none
     */
    public void setSuppressedByMonitorId(Integer suppressedByMonitorId) {
        this.suppressedByMonitorId = suppressedByMonitorId;
    }

    /**
     * @return the engine user id that created this monitor, or {@code null}
     *         if unknown (e.g. seeded by a migration)
     */
    public Integer getCreatedBy() {
        return createdBy;
    }

    /**
     * @param createdBy the engine user id that created this monitor
     */
    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    /**
     * @return when this monitor was created
     */
    public Instant getCreatedTime() {
        return createdTime;
    }

    /**
     * @param createdTime when this monitor was created
     */
    public void setCreatedTime(Instant createdTime) {
        this.createdTime = createdTime;
    }

    /**
     * @return the engine user id that last updated this monitor, or
     *         {@code null} if it has never been updated
     */
    public Integer getUpdatedBy() {
        return updatedBy;
    }

    /**
     * @param updatedBy the engine user id that last updated this monitor
     */
    public void setUpdatedBy(Integer updatedBy) {
        this.updatedBy = updatedBy;
    }

    /**
     * @return when this monitor was last updated, or {@code null} if it has
     *         never been updated
     */
    public Instant getUpdatedTime() {
        return updatedTime;
    }

    /**
     * @param updatedTime when this monitor was last updated
     */
    public void setUpdatedTime(Instant updatedTime) {
        this.updatedTime = updatedTime;
    }
}
