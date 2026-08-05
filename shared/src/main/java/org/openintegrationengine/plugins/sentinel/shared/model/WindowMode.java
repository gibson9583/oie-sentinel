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
 *   <li>{@link #SUPPRESS} — classic maintenance: alerts born while the
 *       window is active are flagged suppressed and never notify.</li>
 *   <li>{@link #ACTIVE} — an alerting schedule: alerts on covered channels
 *       notify only while the window is active; an alert born <em>outside</em>
 *       the window's times is the one that gets suppressed. A channel covered
 *       by several ACTIVE windows alerts when any one of them is active.</li>
 * </ul>
 */
public enum WindowMode {
    SUPPRESS,
    ACTIVE
}
