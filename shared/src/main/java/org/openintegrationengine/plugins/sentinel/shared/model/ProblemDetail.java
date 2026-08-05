/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.util.List;

/**
 * One alert event with all of its display context resolved server-side: the
 * raising monitor's name and type (which survive monitor deletion as literals
 * here), human-readable channel/connector names, and the action dispatch
 * history.
 *
 * <p>Assembled by the Problem service for {@code GET /problems/{id}} so the
 * web UI's detail drawer renders from a single round trip instead of joining
 * monitors, channels, and dispatch logs client-side. Never persisted — this
 * is a response-only composite.</p>
 */
public class ProblemDetail {

    private AlertEvent event;
    private String monitorName;
    private MonitorType monitorType;
    private String channelName;
    private String connectorName;
    private List<ActionDispatchLog> dispatches;

    public ProblemDetail() {
    }

    /**
     * @return the underlying alert event row
     */
    public AlertEvent getEvent() {
        return event;
    }

    /**
     * @param event the underlying alert event row
     */
    public void setEvent(AlertEvent event) {
        this.event = event;
    }

    /**
     * @return display name of the monitor that raised the event, or a
     *         placeholder (e.g. "(deleted monitor)") if the monitor no
     *         longer exists
     */
    public String getMonitorName() {
        return monitorName;
    }

    /**
     * @param monitorName display name of the raising monitor, or a
     *                    placeholder when it has been deleted
     */
    public void setMonitorName(String monitorName) {
        this.monitorName = monitorName;
    }

    /**
     * @return rule type of the raising monitor, or {@code null} if the
     *         monitor has been deleted
     */
    public MonitorType getMonitorType() {
        return monitorType;
    }

    /**
     * @param monitorType rule type of the raising monitor; {@code null} when
     *                    the monitor has been deleted
     */
    public void setMonitorType(MonitorType monitorType) {
        this.monitorType = monitorType;
    }

    /**
     * @return display name of the channel the event was raised against,
     *         resolved at read time (falls back to a placeholder for
     *         since-deleted channels)
     */
    public String getChannelName() {
        return channelName;
    }

    /**
     * @param channelName display name of the affected channel
     */
    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    /**
     * @return display name of the affected connector for connector-scoped
     *         events (CONNECTION_STATUS monitors), or {@code null} for
     *         channel-level events
     */
    public String getConnectorName() {
        return connectorName;
    }

    /**
     * @param connectorName display name of the affected connector;
     *                      {@code null} for channel-level events
     */
    public void setConnectorName(String connectorName) {
        this.connectorName = connectorName;
    }

    /**
     * @return every action delivery attempt made for this event, oldest
     *         first; empty if nothing has been dispatched
     */
    public List<ActionDispatchLog> getDispatches() {
        return dispatches;
    }

    /**
     * @param dispatches the action delivery attempts made for this event
     */
    public void setDispatches(List<ActionDispatchLog> dispatches) {
        this.dispatches = dispatches;
    }
}
