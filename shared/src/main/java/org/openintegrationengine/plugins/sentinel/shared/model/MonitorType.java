/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * The monitor rule types Sentinel evaluates. Persisted as the literal
 * enum name (VARCHAR(32)) in {@code sentinel_monitor.monitor_type}. Each
 * value pairs with its own {@code config_json} shape — see the
 * {@code org.openintegrationengine.plugins.sentinel.shared.model.config}
 * package for the corresponding config POJO.
 */
public enum MonitorType {
    /** Alerts when a channel has received no messages for a configured duration. */
    INACTIVITY,
    /** Alerts when received-message volume falls below a fixed or baseline-relative threshold. */
    LOW_VOLUME,
    /** Alerts when a metric deviates from its historical hour-of-day baseline beyond a z-score threshold. */
    ANOMALY,
    /** Alerts when a channel's connector reports a configured connection state for too long. */
    CONNECTION_STATUS,
    /** Alerts when the share of errored messages over a window reaches a configured percentage. */
    ERROR_RATE,
    /**
     * Alerts when a channel's queued-message count stands at or above a
     * configured depth for a configured duration. Unlike the other activity
     * types this one reads an instantaneous gauge, not summed deltas — see
     * {@code QueueDepthEvaluator}.
     */
    QUEUE_DEPTH
}
