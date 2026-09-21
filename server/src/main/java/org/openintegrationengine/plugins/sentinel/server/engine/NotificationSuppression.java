/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;

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

    /**
     * Re-evaluates policy without collapsing lookup failure into an actual
     * suppressing rule. Repeating problem paths treat UNKNOWN as suppressed;
     * the one-shot resolve path can instead fall back to its durable snapshot
     * rather than permanently losing a recovery because a read failed.
     */
    public static Decision decision(AlertEvent event, Instant now) {
        if (event == null || event.getChannelId() == null || now == null) {
            return Decision.UNKNOWN;
        }
        try {
            Monitor monitor = MonitorRepository.getMonitor(event.getMonitorId());
            if (monitor == null || !monitor.isEnabled()) {
                return Decision.SUPPRESS;
            }
            return suppressedByWindows(event.getChannelId(), now)
                            || dependencyOpen(monitor, event.getChannelId())
                    ? Decision.SUPPRESS : Decision.ALLOW;
        } catch (Throwable t) {
            log.error("Failed to re-evaluate notification suppression for alert event {} and monitor {}; "
                            + "suppressing this attempt so a later tick can retry",
                    event.getId(), event.getMonitorId(), t);
            return Decision.UNKNOWN;
        }
    }

    /** Creation-time snapshot stored on a new alert; dispatch always rechecks. */
    static boolean isSuppressed(Monitor monitor, String channelId, Instant now) {
        if (monitor == null || channelId == null || now == null || !monitor.isEnabled()) {
            return true;
        }
        try {
            return suppressedByWindows(channelId, now) || dependencyOpen(monitor, channelId);
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
    private static boolean suppressedByWindows(String channelId, Instant now) {
        boolean hasAlertingSchedule = false;
        boolean insideAlertingSchedule = false;
        for (MaintenanceWindow window : MaintenanceWindowRepository.listEnabledMaintenanceWindows()) {
            if (!window.isEnabled() || !windowCoversChannel(window, channelId)) {
                continue;
            }
            Boolean activeNow = WindowSchedule.isActiveNow(window, now);
            if (window.getMode() == WindowMode.ACTIVE) {
                hasAlertingSchedule = true;
                if (activeNow == null || activeNow) {
                    insideAlertingSchedule = true;
                }
            } else if (Boolean.TRUE.equals(activeNow)) {
                return true;
            }
        }
        return hasAlertingSchedule && !insideAlertingSchedule;
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
    private static boolean dependencyOpen(Monitor monitor, String channelId) {
        Integer parentId = monitor.getSuppressedByMonitorId();
        if (parentId == null) {
            return false;
        }
        Monitor parent = MonitorRepository.getMonitor(parentId);
        if (parent == null || !parent.isEnabled()) {
            return false;
        }
        for (TriggerState parentState : TriggerStateRepository.listTriggerStatesByMonitor(parentId)) {
            if (!channelId.equals(parentState.getChannelId())
                    || parentState.getOpenAlertEventId() == null) {
                continue;
            }
            AlertEvent open = AlertEventRepository.getAlertEvent(parentState.getOpenAlertEventId());
            if (open != null && open.getStatus() == AlertStatus.PROBLEM) {
                return true;
            }
        }
        return false;
    }
}
