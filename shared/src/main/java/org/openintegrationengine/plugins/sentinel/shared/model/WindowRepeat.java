/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * How a {@link MaintenanceWindow} recurs. Persisted as the literal enum name
 * ({@code repeat_type} VARCHAR(16)).
 *
 * <ul>
 *   <li>{@link #NONE} — a one-time window: active between its absolute
 *       {@code activeFrom}/{@code activeUntil} instants (both required).</li>
 *   <li>{@link #WEEKLY} — recurs on the window's days of week between its
 *       start and end times (server-local clock); {@code activeFrom}/
 *       {@code activeUntil} become optional outer bounds.</li>
 *   <li>{@link #MONTHLY} — recurs on the window's days of month between its
 *       start and end times; bounds optional as with WEEKLY.</li>
 * </ul>
 */
public enum WindowRepeat {
    NONE,
    WEEKLY,
    MONTHLY
}
