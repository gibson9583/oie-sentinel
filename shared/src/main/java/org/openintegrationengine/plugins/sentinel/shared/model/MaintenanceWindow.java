/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * A scheduled window over the channels identified by
 * {@link #getScopeType()}/{@link #getScopeId()}, in one of two modes
 * ({@link #getMode()}): a classic {@code SUPPRESS} maintenance window
 * (alerts born while the window is active are suppressed) or an
 * {@code ACTIVE} alerting schedule (alerts notify only while the window is
 * active). Windows are one-time or recur weekly/monthly
 * ({@link #getRepeatType()}): one-time windows are bounded by the absolute
 * {@link #getActiveFrom()}/{@link #getActiveUntil()} instants; recurring
 * windows repeat on {@link #getDaysOfWeek()}/{@link #getDaysOfMonth()}
 * between {@link #getStartTime()} and {@link #getEndTime()} on the clock of
 * {@link #getTimezone()} (null = the server's zone), optionally bounded by the
 * absolute instants.
 *
 * <p>Maps to {@code sentinel_maintenance_window}. Scope matching against a
 * specific channel or monitor, and the "is this window active right now"
 * schedule math, happen server-side — this DTO and its repository only carry
 * the row data (plus the response-only {@link #getActiveNow()} flag the
 * service stamps for the UI).</p>
 */
public class MaintenanceWindow {

    private Integer id;
    private String name;
    private ScopeType scopeType;
    private String scopeId;
    private WindowMode mode;
    private WindowRepeat repeatType;
    private String daysOfWeek;
    private String daysOfMonth;
    private String startTime;
    private String endTime;
    private String timezone;
    private Instant activeFrom;
    private Instant activeUntil;
    private boolean enabled;
    private Integer createdBy;
    private Instant createdTime;
    private Boolean activeNow;

    public MaintenanceWindow() {
    }

    /**
     * @return the database-assigned id of this maintenance window, or
     *         {@code null} for a window that has not been persisted yet
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
     * @return this maintenance window's display name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name this maintenance window's display name
     */
    public void setName(String name) {
        this.name = name;
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
     * @return the channel id or channel-group id this window applies to;
     *         {@code null} when {@link #getScopeType()} is {@code ALL}
     */
    public String getScopeId() {
        return scopeId;
    }

    /**
     * @param scopeId the channel id or channel-group id this window applies
     *                to; must be {@code null} when {@link #getScopeType()} is
     *                {@code ALL}
     */
    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    /**
     * @return what being inside this window's active times means for alerts;
     *         {@code null} on rows written before modes existed and treated
     *         as {@link WindowMode#SUPPRESS}
     */
    public WindowMode getMode() {
        return mode;
    }

    /**
     * @param mode what being inside this window's active times means for
     *             alerts
     */
    public void setMode(WindowMode mode) {
        this.mode = mode;
    }

    /**
     * @return how this window recurs; {@code null} on rows written before
     *         recurrence existed and treated as {@link WindowRepeat#NONE}
     */
    public WindowRepeat getRepeatType() {
        return repeatType;
    }

    /**
     * @param repeatType how this window recurs
     */
    public void setRepeatType(WindowRepeat repeatType) {
        this.repeatType = repeatType;
    }

    /**
     * @return for WEEKLY windows, the days this window recurs on as a
     *         comma-separated list of {@code java.time.DayOfWeek} names
     *         (e.g. {@code "MONDAY,FRIDAY"}); {@code null} otherwise
     */
    public String getDaysOfWeek() {
        return daysOfWeek;
    }

    /**
     * @param daysOfWeek comma-separated {@code DayOfWeek} names for WEEKLY
     *                   windows; {@code null} otherwise
     */
    public void setDaysOfWeek(String daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    /**
     * @return for MONTHLY windows, the days of month (1–31) this window
     *         recurs on as a comma-separated list (e.g. {@code "1,15"});
     *         {@code null} otherwise
     */
    public String getDaysOfMonth() {
        return daysOfMonth;
    }

    /**
     * @param daysOfMonth comma-separated days of month (1–31) for MONTHLY
     *                    windows; {@code null} otherwise
     */
    public void setDaysOfMonth(String daysOfMonth) {
        this.daysOfMonth = daysOfMonth;
    }

    /**
     * @return for recurring windows, the daily start time as {@code HH:mm}
     *         on {@link #getTimezone()}'s clock; {@code null} for one-time
     *         windows
     */
    public String getStartTime() {
        return startTime;
    }

    /**
     * @param startTime daily start time ({@code HH:mm}) for recurring
     *                  windows; {@code null} for one-time windows
     */
    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    /**
     * @return for recurring windows, the daily end time as {@code HH:mm} on
     *         {@link #getTimezone()}'s clock — an end at or before the start
     *         wraps past midnight; {@code null} for one-time windows
     */
    public String getEndTime() {
        return endTime;
    }

    /**
     * @param endTime daily end time ({@code HH:mm}) for recurring windows;
     *                {@code null} for one-time windows
     */
    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    /**
     * The zone whose local clock {@link #getStartTime()}/{@link #getEndTime()}
     * and the day-of-week/day-of-month match are read on — an IANA zone id
     * such as {@code "America/New_York"}.
     *
     * <p>Stored per window rather than taken from the server so a 22:00–06:00
     * schedule stays 8 hours across both DST transitions in the zone the
     * on-call rotation actually lives in, instead of becoming 23 or 25 hours
     * because the server sits in another zone (or in UTC, which has no
     * transitions at all). Irrelevant to one-time windows: those are absolute
     * instants, so the service blanks this for them.</p>
     *
     * @return the window's IANA zone id, or {@code null} to evaluate on the
     *         server's own zone — which is what every row written before
     *         schema v3 carries, and is why null must keep meaning exactly
     *         that rather than being backfilled to a guess
     */
    public String getTimezone() {
        return timezone;
    }

    /**
     * @param timezone an IANA zone id (e.g. {@code "America/New_York"}) for
     *                 recurring windows, or {@code null} to evaluate the
     *                 schedule on the server's own zone. An unparseable id is
     *                 rejected at save time and, if one reaches the database
     *                 by another route, falls back to the server zone at
     *                 evaluation ({@code WindowSchedule})
     */
    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    /**
     * @return the instant this window's active range begins; required for
     *         one-time windows, an optional outer bound for recurring ones
     */
    public Instant getActiveFrom() {
        return activeFrom;
    }

    /**
     * @param activeFrom the instant this window's active range begins;
     *                   required for one-time windows, an optional outer
     *                   bound for recurring ones
     */
    public void setActiveFrom(Instant activeFrom) {
        this.activeFrom = activeFrom;
    }

    /**
     * @return the instant this window's active range ends; required for
     *         one-time windows, an optional outer bound for recurring ones
     */
    public Instant getActiveUntil() {
        return activeUntil;
    }

    /**
     * @param activeUntil the instant this window's active range ends;
     *                    required for one-time windows, an optional outer
     *                    bound for recurring ones
     */
    public void setActiveUntil(Instant activeUntil) {
        this.activeUntil = activeUntil;
    }

    /**
     * @return {@code true} if this maintenance window is currently eligible
     *         to suppress alerts; {@code false} windows are ignored even if
     *         the current time falls within their active range
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled whether this maintenance window is currently eligible to
     *                suppress alerts
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return the engine user id that created this maintenance window, or
     *         {@code null} if unknown (e.g. seeded by a migration)
     */
    public Integer getCreatedBy() {
        return createdBy;
    }

    /**
     * @param createdBy the engine user id that created this maintenance
     *                  window
     */
    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    /**
     * @return when this maintenance window was created
     */
    public Instant getCreatedTime() {
        return createdTime;
    }

    /**
     * @param createdTime when this maintenance window was created
     */
    public void setCreatedTime(Instant createdTime) {
        this.createdTime = createdTime;
    }

    /**
     * @return whether this window's schedule covers the moment the response
     *         was built — response-only, stamped by the service for the UI's
     *         Active column and never persisted; {@code null} when not
     *         stamped
     */
    public Boolean getActiveNow() {
        return activeNow;
    }

    /**
     * @param activeNow whether this window is active right now;
     *                  response-only
     */
    public void setActiveNow(Boolean activeNow) {
        this.activeNow = activeNow;
    }
}
