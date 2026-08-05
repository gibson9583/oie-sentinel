/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/** When a {@code sentinel_action} fires relative to an alert event's lifecycle. */
public enum OperationMode {
    ON_PROBLEM,
    ON_RESOLVE,
    BOTH
}
