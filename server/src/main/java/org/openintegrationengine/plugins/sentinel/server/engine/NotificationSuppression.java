/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MaintenanceWindowRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowMode;

/**
 * The action-time notification policy shared by the evaluator and dispatcher.
 * Windows and monitor dependencies are deliberately read afresh for every
 * notification decision: an alert row can outlive many schedule boundaries,
 * monitor edits, parent problems, restarts, and dispatch-queue delays.
 */
public final class NotificationSuppression {

    /** A current policy result, preserving lookup uncertainty for one-shot edges. */
    public enum Decision {
        ALLOW,
        SUPPRESS,
        UNKNOWN
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationSuppression.class);

    private NotificationSuppression() {
    }

    /**
     * Re-evaluates a persisted event against the monitor definition and
     * suppression state that exist now. Any lookup failure fails closed for
     * this attempt; a still-open event is reconsidered on the next evaluator
     * tick, so policy uncertainty delays a notification rather than leaking
     * one through a maintenance window or losing it permanently.
     */
    public static boolean isSuppressed(AlertEvent event, Instant now) {
        return decision(event, now) != Decision.ALLOW;
    }

    /** A concrete current policy gate; historical decisions are not reconstructed. */
    public record Reason(String code, Integer scheduleId, String name, String timezone,
            Long parentEventId, Instant nextBoundary) { }

    /** Read-only current policy. Reasons describe the first decisive policy gate,
     * not historical routing, flap control, storm control, or delivery certainty. */
    public record Explanation(Decision decision, List<Reason> reasons, String lookupError) { }

    public static Decision decision(AlertEvent event, Instant now) {
        return explain(event, now).decision();
    }

    public static Explanation explain(AlertEvent event, Instant now) {
        if (event == null || event.getChannelId() == null || now == null) {
            return new Explanation(Decision.UNKNOWN, List.of(), "Policy inputs are unavailable");
        }
        try {
            return explain(MonitorRepository.getMonitor(event.getMonitorId()), event.getChannelId(), now);
        } catch (Throwable t) {
            log.error("Failed to read notification policy for event {}", event.getId(), t);
            return new Explanation(Decision.UNKNOWN, List.of(), "Current policy lookup failed; refresh to retry");
        }
    }

    private static Explanation explain(Monitor monitor, String channelId, Instant now) {
        if (monitor == null || !monitor.isEnabled()) {
            return new Explanation(Decision.SUPPRESS, List.of(new Reason(
                    monitor == null ? "MONITOR_MISSING" : "MONITOR_DISABLED", null,
                    monitor == null ? null : monitor.getName(), null, null, null)), null);
        }
        List<Reason> windows = windowReasons(channelId, now);
        if (!windows.isEmpty()) {
            return new Explanation(Decision.SUPPRESS, windows, null);
        }
        Reason dependency = openDependency(monitor, channelId);
        if (dependency != null) {
            return new Explanation(Decision.SUPPRESS, List.of(dependency), null);
        }
        return new Explanation(Decision.ALLOW, List.of(), null);
    }

    /** Creation-time snapshot stored on a new alert; dispatch always rechecks. */
    static boolean isSuppressed(Monitor monitor, String channelId, Instant now) {
        if (monitor == null || channelId == null || now == null || !monitor.isEnabled()) {
            return true;
        }
        try {
            return explain(monitor, channelId, now).decision() != Decision.ALLOW;
        } catch (Throwable t) {
            log.error("Failed to evaluate notification suppression for monitor {} channel {}; "
                            + "recording the alert as suppressed so a later tick can retry",
                    monitor.getId(), channelId, t);
            return true;
        }
    }

    /**
     * A covering SUPPRESS window blocks while active. Covering ACTIVE windows
     * are the inverse: if any exist, at least one must be active. A malformed
     * schedule follows WindowSchedule's fail-open rule, but a repository or
     * scope-resolution failure is caught by the caller and fails this attempt
     * closed.
     */
    private static List<Reason> windowReasons(String channelId, Instant now) {
        List<Reason> inactive = new ArrayList<>();
        boolean hasAlertingSchedule = false;
        boolean insideAlertingSchedule = false;
        for (MaintenanceWindow window : MaintenanceWindowRepository.listEnabledMaintenanceWindows()) {
            if (!window.isEnabled() || !windowCoversChannel(window, channelId)) {
                continue;
            }
            Boolean activeNow = WindowSchedule.isActiveNow(window, now);
            if (window.getMode() == WindowMode.ACTIVE) {
                hasAlertingSchedule = true;
                inactive.add(scheduleReason("OUTSIDE_ALERTING_SCHEDULE", window, now));
                if (activeNow == null || activeNow) {
                    insideAlertingSchedule = true;
                }
            } else if (Boolean.TRUE.equals(activeNow)) {
                return List.of(scheduleReason("SUPPRESS_SCHEDULE", window, now));
            }
        }
        return hasAlertingSchedule && !insideAlertingSchedule ? inactive : List.of();
    }

    private static Reason scheduleReason(String code, MaintenanceWindow window, Instant now) {
        // Only absolute one-time boundaries are exposed: recurring DST boundaries
        // need a dedicated schedule calculator, not a guessed clock duration.
        Instant next = null;
        if (window.getRepeatType() == null || window.getRepeatType()
                == org.openintegrationengine.plugins.sentinel.shared.model.WindowRepeat.NONE) {
            if (window.getActiveFrom() != null && window.getActiveFrom().isAfter(now)) {
                next = window.getActiveFrom();
            } else if (window.getActiveUntil() != null && window.getActiveUntil().isAfter(now)) {
                next = window.getActiveUntil();
            }
        }
        String zone = window.getTimezone();
        if (zone == null || zone.isBlank()) zone = ZoneId.systemDefault().getId();
        return new Reason(code, window.getId(), window.getName(), zone, null, next);
    }

    private static boolean windowCoversChannel(MaintenanceWindow window, String channelId) {
        ScopeType scope = window.getScopeType();
        if (scope == ScopeType.ALL) {
            return true;
        }
        if (scope == ScopeType.CHANNEL && channelId.equals(window.getScopeId())) {
            return true;
        }
        if (scope == ScopeType.GROUP
                && ScopeResolver.strictGroupChannelIds(window.getScopeId()).contains(channelId)) {
            return true;
        }
        return scope == ScopeType.TAG
                && ScopeResolver.strictTagChannelIds(window.getScopeId()).contains(channelId);
    }

    /**
     * An enabled parent monitor with any still-open alert on the same channel
     * suppresses its dependent. The trigger's transient state and the parent
     * event's old stored suppression bit are intentionally irrelevant: the
     * open alert is the durable problem fact, making chained dependencies and
     * INSUFFICIENT_DATA gaps independent of evaluation order.
     */
    private static Reason openDependency(Monitor monitor, String channelId) {
        Integer parentId = monitor.getSuppressedByMonitorId();
        if (parentId == null) {
            return null;
        }
        Monitor parent = MonitorRepository.getMonitor(parentId);
        if (parent == null || !parent.isEnabled()) {
            return null;
        }
        for (TriggerState parentState : TriggerStateRepository.listTriggerStatesByMonitor(parentId)) {
            if (!channelId.equals(parentState.getChannelId())
                    || parentState.getOpenAlertEventId() == null) {
                continue;
            }
            AlertEvent open = AlertEventRepository.getAlertEvent(parentState.getOpenAlertEventId());
            if (open != null && open.getStatus() == AlertStatus.PROBLEM) {
                return new Reason("DEPENDENCY_OPEN", null, parent.getName(), null, open.getId(), null);
            }
        }
        return null;
    }
}
