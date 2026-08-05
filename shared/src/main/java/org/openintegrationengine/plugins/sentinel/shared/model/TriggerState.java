/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * Runtime evaluation state of a single {@code (monitor, channel, metadata_id)}
 * triple, persisted in {@code sentinel_trigger_state}.
 *
 * <p>One row exists per triple a monitor has ever evaluated for a given
 * channel (and, for {@code CONNECTION_STATUS} monitors, per connector within
 * that channel — hence the separate {@link #metadataId}). The evaluator reads
 * the current row, applies the monitor's hysteresis rule, and writes back the
 * new {@link #state}, updating {@link #openAlertEventId} whenever it opens or
 * resolves a {@code sentinel_alert_event} as a side effect of the
 * transition.</p>
 */
public class TriggerState {

    private Integer id;
    private int monitorId;
    private String channelId;
    private Integer metadataId;
    private TriggerStatus state;
    private int consecutiveBreachCount;
    private String lastValueJson;
    private Long openAlertEventId;
    private Instant lastChangeTime;
    private Instant lastEvaluatedTime;

    /**
     * Creates an empty trigger state. Callers populate fields via the setters
     * before handing the instance to {@code TriggerStateRepository}.
     */
    public TriggerState() {
    }

    /**
     * @return the database-assigned id of this row, or {@code null} for an
     *         instance that has not been persisted yet
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
     * @return the id of the {@code sentinel_monitor} this state belongs to
     */
    public int getMonitorId() {
        return monitorId;
    }

    /**
     * @param monitorId the id of the {@code sentinel_monitor} this state
     *                  belongs to
     */
    public void setMonitorId(int monitorId) {
        this.monitorId = monitorId;
    }

    /**
     * @return the OIE channel id (a UUID string) this state was evaluated for
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id (a UUID string) this state was
     *                  evaluated for
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the connector metadata id this state tracks, or {@code null}
     *         for monitor types that evaluate at the whole-channel level
     *         rather than per connector
     */
    public Integer getMetadataId() {
        return metadataId;
    }

    /**
     * @param metadataId the connector metadata id this state tracks; {@code
     *                   null} for monitor types that evaluate at the
     *                   whole-channel level
     */
    public void setMetadataId(Integer metadataId) {
        this.metadataId = metadataId;
    }

    /**
     * @return the current evaluation state
     */
    public TriggerStatus getState() {
        return state;
    }

    /**
     * @param state the current evaluation state
     */
    public void setState(TriggerStatus state) {
        this.state = state;
    }

    /**
     * @return the number of consecutive breaching evaluations observed so
     *         far, used against the owning monitor's
     *         {@code min_consecutive_breaches} hysteresis threshold
     */
    public int getConsecutiveBreachCount() {
        return consecutiveBreachCount;
    }

    /**
     * @param consecutiveBreachCount the number of consecutive breaching
     *                               evaluations observed so far
     */
    public void setConsecutiveBreachCount(int consecutiveBreachCount) {
        this.consecutiveBreachCount = consecutiveBreachCount;
    }

    /**
     * @return the raw JSON snapshot of the value(s) considered at the last
     *         evaluation (e.g. {@code {current, mean, stddev, z, tier,
     *         sampleCount}}), for the alert detail view; {@code null} if not
     *         yet evaluated. Not parsed here — JSON handling is a Service
     *         layer concern.
     */
    public String getLastValueJson() {
        return lastValueJson;
    }

    /**
     * @param lastValueJson the raw JSON snapshot of the value(s) considered
     *                      at the last evaluation; stored/retrieved as-is
     */
    public void setLastValueJson(String lastValueJson) {
        this.lastValueJson = lastValueJson;
    }

    /**
     * @return the id of the currently-open {@code sentinel_alert_event} this
     *         trigger opened, or {@code null} if the trigger is not currently
     *         in {@link TriggerStatus#PROBLEM} (or the problem was resolved)
     */
    public Long getOpenAlertEventId() {
        return openAlertEventId;
    }

    /**
     * @param openAlertEventId the id of the currently-open
     *                         {@code sentinel_alert_event} this trigger
     *                         opened; {@code null} when no problem is open
     */
    public void setOpenAlertEventId(Long openAlertEventId) {
        this.openAlertEventId = openAlertEventId;
    }

    /**
     * @return the timestamp {@link #state} last transitioned to a different
     *         value, or {@code null} if it has never changed
     */
    public Instant getLastChangeTime() {
        return lastChangeTime;
    }

    /**
     * @param lastChangeTime the timestamp {@link #state} last transitioned
     *                       to a different value
     */
    public void setLastChangeTime(Instant lastChangeTime) {
        this.lastChangeTime = lastChangeTime;
    }

    /**
     * @return the timestamp of the most recent evaluation, regardless of
     *         whether it changed {@link #state}; {@code null} if never
     *         evaluated
     */
    public Instant getLastEvaluatedTime() {
        return lastEvaluatedTime;
    }

    /**
     * @param lastEvaluatedTime the timestamp of the most recent evaluation,
     *                          regardless of whether it changed
     *                          {@link #state}
     */
    public void setLastEvaluatedTime(Instant lastEvaluatedTime) {
        this.lastEvaluatedTime = lastEvaluatedTime;
    }
}
