/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * What a problem or trigger state is about. Persisted as the literal enum
 * name (VARCHAR(16)) in the {@code subject_type} column of
 * {@code sentinel_alert_event} and {@code sentinel_trigger_state}.
 *
 * <p>{@code CHANNEL} rows carry a channel id, and a connector metadata id
 * when the monitor evaluates per connector. {@code SERVER} rows carry
 * neither: the monitor watches the engine as a whole, the way
 * {@link MonitorType#LOG_EVENTS} watches the engine's log. Rows written
 * before this column existed are {@code CHANNEL}; a {@code null} read from
 * the database means the same, so a partially applied migration cannot turn
 * a channel problem into a server one.</p>
 */
public enum SubjectType {
    CHANNEL,
    SERVER;

    /**
     * @param value a stored or deserialized subject type, possibly {@code null}
     * @return {@code value}, or {@link #CHANNEL} when it is {@code null}
     */
    public static SubjectType orChannel(SubjectType value) {
        return value == null ? CHANNEL : value;
    }
}
