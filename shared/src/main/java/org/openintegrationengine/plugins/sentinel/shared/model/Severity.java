/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * Zabbix's five-level severity scale, ascending. Persisted as the literal
 * enum name (VARCHAR(16)) in {@code sentinel_monitor.severity} and
 * {@code sentinel_alert_event.severity} — do not reorder or rename existing
 * constants without a migration, and keep {@link #ordinal()} meaningful
 * since callers compare severities by ordinal (e.g. "severity &gt;= HIGH").
 */
public enum Severity {
    INFORMATION,
    WARNING,
    AVERAGE,
    HIGH,
    DISASTER
}
