/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * A single connector state transition, persisted in
 * {@code sentinel_connector_status_event}.
 *
 * <p>One row is written every time a channel's connector fires a connection
 * status change (e.g. IDLE -&gt; POLLING, CONNECTED -&gt; FAILED). {@link
 * #previousState} and {@link #newState} hold the engine's own {@code
 * ConnectionStatusEventType.name()} values as plain strings rather than an
 * enum owned by this plugin — that type belongs to the OIE engine, not
 * Sentinel, and storing it as a string avoids a hard compile-time dependency
 * on the engine's enum surviving unchanged across versions. A {@code
 * CONNECTION_STATUS} monitor reads this history (via {@code
 * ConnectorStatusRepository}) to detect connectors stuck in a failed or
 * unexpected state.</p>
 */
public class ConnectorStatusEvent {

    private Long id;
    private String channelId;
    private int metadataId;
    private String previousState;
    private String newState;
    private Instant changedTime;

    /**
     * Creates an empty connector status event. Callers populate fields via
     * the setters before handing the instance to {@code
     * ConnectorStatusRepository}.
     */
    public ConnectorStatusEvent() {
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
     * @return the OIE channel id (a UUID string) this event was raised on
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @param channelId the OIE channel id (a UUID string) this event was
     *                  raised on
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * @return the connector metadata id within the channel that raised this
     *         event
     */
    public int getMetadataId() {
        return metadataId;
    }

    /**
     * @param metadataId the connector metadata id within the channel that
     *                   raised this event
     */
    public void setMetadataId(int metadataId) {
        this.metadataId = metadataId;
    }

    /**
     * @return the connector's status before this transition, as the engine's
     *         {@code ConnectionStatusEventType.name()} string, or {@code
     *         null} if this is the first observed state for the connector
     */
    public String getPreviousState() {
        return previousState;
    }

    /**
     * @param previousState the connector's status before this transition, as
     *                      the engine's {@code ConnectionStatusEventType.name()}
     *                      string; {@code null} if this is the first observed
     *                      state for the connector
     */
    public void setPreviousState(String previousState) {
        this.previousState = previousState;
    }

    /**
     * @return the connector's status after this transition, as the engine's
     *         {@code ConnectionStatusEventType.name()} string
     */
    public String getNewState() {
        return newState;
    }

    /**
     * @param newState the connector's status after this transition, as the
     *                 engine's {@code ConnectionStatusEventType.name()} string
     */
    public void setNewState(String newState) {
        this.newState = newState;
    }

    /**
     * @return the timestamp this transition was observed
     */
    public Instant getChangedTime() {
        return changedTime;
    }

    /**
     * @param changedTime the timestamp this transition was observed
     */
    public void setChangedTime(Instant changedTime) {
        this.changedTime = changedTime;
    }
}
