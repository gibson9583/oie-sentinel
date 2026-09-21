/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * What being "inside" a {@link MaintenanceWindow}'s active times means for
 * alerts on the channels it covers. Persisted as the literal enum name
 * ({@code window_mode} VARCHAR(16)).
 *
 * <ul>
 *   <li>{@link #SUPPRESS} — classic maintenance: notification decisions made
 *       while the window is active are suppressed. A still-open problem is
 *       eligible again after the window ends.</li>
 *   <li>{@link #ACTIVE} — an alerting schedule: alerts on covered channels
 *       notify only while the window is active. A problem born outside the
 *       window is reconsidered on entry if it remains open. A channel covered
 *       by several ACTIVE windows alerts when any one of them is active.</li>
 * </ul>
 */
public enum WindowMode {
    SUPPRESS,
    ACTIVE
}
