/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.time.Instant;

import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * Display-resolved snapshot of an alert event, handed to every
 * {@link AlertSender} and to {@link ActionConditionMatcher}.
 *
 * <p>Exists so that name resolution happens exactly once per dispatch: the
 * {@code AlertEvent} row stores only ids (monitor id, channel id), but every
 * delivery target — an email subject line, a routed channel message's source
 * map, an SNS notification — wants human-readable names. Resolving them here,
 * at dispatch time, also pins the names an operator sees to what the entities
 * were called <em>when the alert fired</em>, instead of each sender
 * re-resolving (and possibly disagreeing) later in the dispatch loop.</p>
 *
 * <p>Immutable by design: one payload instance fans out to several senders on
 * different code paths within a single evaluator tick, so no sender may
 * mutate what another sender is about to serialize. Serialized to JSON by
 * Jackson (bean getters) in {@code ChannelAlertSender} and
 * {@code SnsAlertSender}; only serialization is ever needed, so there is no
 * no-arg constructor.</p>
 */
public final class AlertPayload {

    private final long alertEventId;
    private final int monitorId;
    private final String monitorName;
    private final MonitorType monitorType;
    private final String channelId;
    private final String channelName;
    private final Integer metadataId;
    private final Severity severity;
    private final String eventType;
    private final String message;
    private final Instant openedTime;
    private final String valueJson;

    /**
     * Package-private so that {@link ActionDispatcher#sendTest(org.openintegrationengine.plugins.sentinel.shared.model.Action)}
     * can build a fully synthetic payload (fake names, no real channel)
     * without routing through {@link #of}, which resolves the channel name
     * from the live engine cache. All production payloads must come from
     * {@link #of} so the resolution rules stay in one place.
     */
    AlertPayload(long alertEventId, int monitorId, String monitorName, MonitorType monitorType,
            String channelId, String channelName, Integer metadataId, Severity severity,
            String eventType, String message, Instant openedTime, String valueJson) {
        this.alertEventId = alertEventId;
        this.monitorId = monitorId;
        this.monitorName = monitorName;
        this.monitorType = monitorType;
        this.channelId = channelId;
        this.channelName = channelName;
        this.metadataId = metadataId;
        this.severity = severity;
        this.eventType = eventType;
        this.message = message;
        this.openedTime = openedTime;
        this.valueJson = valueJson;
    }

    /**
     * Builds the payload for a real alert event, resolving the channel's
     * display name via {@link ScopeResolver#channelName(String)} (which falls
     * back to {@code "(unknown)"} for deleted channels, so senders never have
     * to null-check the name).
     *
     * <p>The monitor may be {@code null} or nameless when it was deleted
     * between the alert opening and this dispatch — the payload then carries
     * {@code "(deleted monitor)"} rather than failing the whole dispatch,
     * because the operator still wants the resolve notification for an alert
     * whose monitor is gone.</p>
     *
     * @param event     the alert event being dispatched; its message, severity
     *                  and details become the payload's
     * @param monitor   the monitor that owns the event, or {@code null} if it
     *                  has been deleted
     * @param eventType which lifecycle edge this dispatch is for:
     *                  {@code "PROBLEM"} or {@code "RESOLVED"}
     * @return an immutable payload ready for every sender
     */
    public static AlertPayload of(AlertEvent event, Monitor monitor, String eventType) {
        return new AlertPayload(
                event.getId() != null ? event.getId() : 0L,
                event.getMonitorId(),
                monitor != null && monitor.getName() != null ? monitor.getName() : "(deleted monitor)",
                monitor != null ? monitor.getMonitorType() : null,
                event.getChannelId(),
                ScopeResolver.channelName(event.getChannelId()),
                event.getMetadataId(),
                event.getSeverity(),
                eventType,
                event.getMessage(),
                event.getOpenedTime(),
                event.getDetailsJson());
    }

    /**
     * @return id of the {@code sentinel_alert_event} row this payload
     *         describes; {@code 0} for synthetic test payloads
     */
    public long getAlertEventId() {
        return alertEventId;
    }

    /**
     * @return id of the monitor that opened the alert; {@code 0} for
     *         synthetic test payloads
     */
    public int getMonitorId() {
        return monitorId;
    }

    /**
     * @return the monitor's display name, or {@code "(deleted monitor)"} if
     *         it no longer exists; never {@code null}
     */
    public String getMonitorName() {
        return monitorName;
    }

    /**
     * @return the monitor's rule type, or {@code null} when the monitor was
     *         deleted or the payload is synthetic
     */
    public MonitorType getMonitorType() {
        return monitorType;
    }

    /**
     * @return id of the channel the alert is about, or {@code null} for
     *         synthetic test payloads
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * @return the channel's display name resolved at dispatch time
     *         ({@code "(unknown)"} for deleted channels); never {@code null}
     */
    public String getChannelName() {
        return channelName;
    }

    /**
     * @return the connector metadata id for CONNECTION_STATUS alerts, or
     *         {@code null} for channel-level alerts
     */
    public Integer getMetadataId() {
        return metadataId;
    }

    /**
     * @return the alert's severity, used both for display and by
     *         {@link ActionConditionMatcher} SEVERITY conditions
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * @return which lifecycle edge this dispatch is for: {@code "PROBLEM"}
     *         when the alert opened, {@code "RESOLVED"} when it cleared
     *         (test sends use {@code "TEST"} so recipients can never mistake
     *         a wiring check for a real alert)
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * @return the human-readable alert message produced by the evaluator
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return when the alert event opened; carried on RESOLVED dispatches too
     *         so a resolve notification can state how long the problem lasted
     */
    public Instant getOpenedTime() {
        return openedTime;
    }

    /**
     * @return the evaluator's machine-readable measurement detail as a raw
     *         JSON string (embedded as a string, not nested JSON, when the
     *         payload itself is serialized); may be {@code null}
     */
    public String getValueJson() {
        return valueJson;
    }
}
