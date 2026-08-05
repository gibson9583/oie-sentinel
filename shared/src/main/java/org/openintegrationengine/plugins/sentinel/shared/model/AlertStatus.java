/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/** Lifecycle status of a {@code sentinel_alert_event} row (the "Problems" list). */
public enum AlertStatus {
    PROBLEM,
    RESOLVED
}
