/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * Whether an alert event is a problem, which stays open until it clears, or a
 * one-off notification with nothing to clear. Stored as the enum name in
 * {@code sentinel_alert_event.event_kind}.
 */
public enum AlertKind {
    PROBLEM,
    NOTIFICATION;

    /**
     * @param value a stored or deserialized kind, possibly {@code null}
     * @return {@code value}, or {@link #PROBLEM} when it is {@code null}
     */
    public static AlertKind orProblem(AlertKind value) {
        return value == null ? PROBLEM : value;
    }

    /**
     * @param stored an {@code event_kind} column value, possibly {@code null}
     * @return the kind it names, or {@link #PROBLEM} when it is {@code null}
     */
    public static AlertKind fromStored(Object stored) {
        return orProblem(stored == null ? null : valueOf(stored.toString().trim()));
    }
}
