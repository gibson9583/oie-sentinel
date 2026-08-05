/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * Runtime evaluation state of a {@code (monitor, channel, metadata_id)}
 * triple, persisted in {@code sentinel_trigger_state.state}.
 */
public enum TriggerStatus {
    /**
     * Not enough historical data to evaluate yet (e.g. a brand-new ANOMALY
     * monitor still below its minimum baseline sample count). Never itself
     * triggers a notification; only the transition INTO or OUT OF
     * {@link #PROBLEM} does.
     */
    INSUFFICIENT_DATA,
    OK,
    PROBLEM
}
