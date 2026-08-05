/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.model.ServerEvent.Level;
import com.mirth.connect.model.ServerEvent.Outcome;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.EventController;

import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/**
 * Curated, human-readable audit events for Sentinel's human-initiated
 * mutations, dispatched into the engine's System Events log.
 *
 * <p>This is the second of two audit layers. The first is free: every
 * mutating {@code @MirthOperation} keeps {@code auditable = true}, so the
 * engine's {@code AuthorizationController} already records who called what
 * with which parameters. This class adds what that generic dump cannot: a
 * readable "what actually happened" phrase plus resolved entity context
 * (names, not just ids) — the same two-layer split the RBAC plugin uses.
 * Scope is deliberately limited to human actions; the automated alert
 * open/resolve lifecycle already has its own durable trail in
 * {@code sentinel_alert_event}/{@code sentinel_action_dispatch_log}, and
 * routing every evaluator tick through the human audit log would be
 * noise.</p>
 *
 * <p><b>Channel attribution.</b> {@code ServerEvent} has no usable channel
 * fields ({@code setChannelName} does not exist; {@code setChannelId} writes
 * a dead field with no backing column). The engine's own auditor instead
 * stores an attribute literally named {@code "channel"} whose value is the
 * {@code Channel.toAuditString()} form ({@code Channel[id=...,name=...]} —
 * NOT {@code toString()}, which omits the id) that {@code getChannelId()}/
 * {@code getChannelName()} parse back out — so this class does exactly that
 * for channel-scoped events, plus explicit {@code "Channel ID"}/
 * {@code "Channel Name"} attributes so a human reading the attributes table
 * doesn't have to parse the composite string. When the channel no longer
 * exists, only {@code "Channel ID"} is written.</p>
 *
 * <p><b>Never throws.</b> Auditing is an observer, not a participant: a
 * failed event dispatch must never roll back or mask the mutation it
 * describes, so every public method traps everything and logs a warning
 * instead.</p>
 */
public final class SentinelAuditLog {

    private static final Logger log = LoggerFactory.getLogger(SentinelAuditLog.class);

    private SentinelAuditLog() {
    }

    // ========== Monitors ==========

    /** Records that a user created a monitor. */
    public static void monitorCreated(int userId, Monitor monitor) {
        dispatchMonitorEvent(userId, "Created Sentinel monitor", monitor);
    }

    /** Records that a user updated a monitor's definition. */
    public static void monitorUpdated(int userId, Monitor monitor) {
        dispatchMonitorEvent(userId, "Updated Sentinel monitor", monitor);
    }

    /** Records that a user deleted a monitor. */
    public static void monitorDeleted(int userId, Monitor monitor) {
        dispatchMonitorEvent(userId, "Deleted Sentinel monitor", monitor);
    }

    /**
     * Records that a user flipped a monitor's enabled flag. A separate verb
     * from "updated" because enable/disable is the operational action a NOC
     * shift performs — it deserves an unambiguous phrase in the log.
     */
    public static void monitorEnabledChanged(int userId, Monitor monitor) {
        dispatchMonitorEvent(userId,
                (monitor.isEnabled() ? "Enabled" : "Disabled") + " Sentinel monitor", monitor);
    }

    /** Shared body for the monitor verbs: phrase, entity fields, channel attribution. */
    private static void dispatchMonitorEvent(int userId, String actionPhrase, Monitor monitor) {
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Action", actionPhrase);
            attributes.put("Monitor", valueOrUnknown(monitor.getName()));
            attributes.put("Monitor ID", String.valueOf(monitor.getId()));
            if (monitor.getMonitorType() != null) {
                attributes.put("Monitor Type", monitor.getMonitorType().name());
            }
            if (monitor.getScopeType() != null) {
                attributes.put("Scope", monitor.getScopeType().name());
            }
            if (monitor.getSeverity() != null) {
                attributes.put("Severity", monitor.getSeverity().name());
            }
            attributes.put("Enabled", String.valueOf(monitor.isEnabled()));
            if (monitor.getScopeType() == ScopeType.CHANNEL) {
                addChannelAttributes(attributes, monitor.getScopeId());
            } else if (monitor.getScopeType() == ScopeType.GROUP && monitor.getScopeId() != null) {
                attributes.put("Group ID", monitor.getScopeId());
            }
            dispatch(userId, attributes);
        } catch (Exception e) {
            log.warn("Failed to audit '{}' for monitor {}", actionPhrase, monitor.getId(), e);
        }
    }

    // ========== Actions ==========

    /** Records that a user created a notification action. */
    public static void actionCreated(int userId, Action action) {
        dispatchActionEvent(userId, "Created Sentinel action", action);
    }

    /** Records that a user updated a notification action. */
    public static void actionUpdated(int userId, Action action) {
        dispatchActionEvent(userId, "Updated Sentinel action", action);
    }

    /** Records that a user deleted a notification action. */
    public static void actionDeleted(int userId, Action action) {
        dispatchActionEvent(userId, "Deleted Sentinel action", action);
    }

    /**
     * Shared body for the action verbs. Only identity/shape fields are
     * recorded — never the config JSON, which can carry an SNS secret. The
     * entity-name key is "Alert Action" (not "Action") because "Action" is
     * already this log's what-happened phrase key.
     */
    private static void dispatchActionEvent(int userId, String actionPhrase, Action action) {
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Action", actionPhrase);
            attributes.put("Alert Action", valueOrUnknown(action.getName()));
            attributes.put("Alert Action ID", String.valueOf(action.getId()));
            if (action.getActionType() != null) {
                attributes.put("Type", action.getActionType().name());
            }
            if (action.getOperationMode() != null) {
                attributes.put("Operation Mode", action.getOperationMode().name());
            }
            attributes.put("Enabled", String.valueOf(action.isEnabled()));
            dispatch(userId, attributes);
        } catch (Exception e) {
            log.warn("Failed to audit '{}' for action {}", actionPhrase, action.getId(), e);
        }
    }

    // ========== Maintenance windows ==========

    /** Records that a user created a maintenance window. */
    public static void windowCreated(int userId, MaintenanceWindow window) {
        dispatchWindowEvent(userId, "Created Sentinel maintenance window", window);
    }

    /** Records that a user updated a maintenance window. */
    public static void windowUpdated(int userId, MaintenanceWindow window) {
        dispatchWindowEvent(userId, "Updated Sentinel maintenance window", window);
    }

    /** Records that a user deleted a maintenance window. */
    public static void windowDeleted(int userId, MaintenanceWindow window) {
        dispatchWindowEvent(userId, "Deleted Sentinel maintenance window", window);
    }

    /**
     * Records that a user activated a window immediately ("start suppressing
     * now"). A distinct verb because an instant activation during an incident
     * is precisely the action a post-mortem will want to find.
     */
    public static void windowActivated(int userId, MaintenanceWindow window) {
        dispatchWindowEvent(userId, "Activated Sentinel maintenance window now", window);
    }

    /** Shared body for the window verbs: phrase, entity fields, channel attribution. */
    private static void dispatchWindowEvent(int userId, String actionPhrase, MaintenanceWindow window) {
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Action", actionPhrase);
            attributes.put("Maintenance Window", valueOrUnknown(window.getName()));
            attributes.put("Maintenance Window ID", String.valueOf(window.getId()));
            if (window.getScopeType() != null) {
                attributes.put("Scope", window.getScopeType().name());
            }
            if (window.getActiveFrom() != null) {
                attributes.put("Active From", window.getActiveFrom().toString());
            }
            if (window.getActiveUntil() != null) {
                attributes.put("Active Until", window.getActiveUntil().toString());
            }
            attributes.put("Enabled", String.valueOf(window.isEnabled()));
            if (window.getScopeType() == ScopeType.CHANNEL) {
                addChannelAttributes(attributes, window.getScopeId());
            } else if (window.getScopeType() == ScopeType.GROUP && window.getScopeId() != null) {
                attributes.put("Group ID", window.getScopeId());
            }
            dispatch(userId, attributes);
        } catch (Exception e) {
            log.warn("Failed to audit '{}' for maintenance window {}", actionPhrase, window.getId(), e);
        }
    }

    // ========== Problems ==========

    /** Records that a user acknowledged an open problem. */
    public static void problemAcknowledged(int userId, AlertEvent event, String comment) {
        dispatchProblemEvent(userId, "Acknowledged Sentinel problem", event, comment);
    }

    /** Records that a user manually resolved a problem. */
    public static void problemResolved(int userId, AlertEvent event, String comment) {
        dispatchProblemEvent(userId, "Manually resolved Sentinel problem", event, comment);
    }

    /**
     * Records a bulk acknowledgment as one event with a count rather than one
     * event per problem — a 50-row multi-select must not flood the System
     * Events log with 50 near-identical entries.
     */
    public static void problemBulkAcknowledged(int userId, int count, String comment) {
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Action", "Bulk-acknowledged Sentinel problems");
            attributes.put("Count", String.valueOf(count));
            if (comment != null && !comment.isBlank()) {
                attributes.put("Comment", comment);
            }
            dispatch(userId, attributes);
        } catch (Exception e) {
            log.warn("Failed to audit bulk acknowledgment of {} problems", count, e);
        }
    }

    /** Shared body for the single-problem verbs: phrase, event fields, channel attribution. */
    private static void dispatchProblemEvent(int userId, String actionPhrase, AlertEvent event, String comment) {
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Action", actionPhrase);
            attributes.put("Problem ID", String.valueOf(event.getId()));
            attributes.put("Monitor ID", String.valueOf(event.getMonitorId()));
            if (event.getSeverity() != null) {
                attributes.put("Severity", event.getSeverity().name());
            }
            if (event.getMessage() != null) {
                attributes.put("Message", event.getMessage());
            }
            if (comment != null && !comment.isBlank()) {
                attributes.put("Comment", comment);
            }
            addChannelAttributes(attributes, event.getChannelId());
            dispatch(userId, attributes);
        } catch (Exception e) {
            log.warn("Failed to audit '{}' for problem {}", actionPhrase, event.getId(), e);
        }
    }

    // ========== Settings ==========

    /**
     * Records a settings update with every applied value — the full
     * before-less snapshot is small (five integers) and makes the log entry
     * self-contained.
     */
    public static void settingsUpdated(int userId, SentinelSettings settings) {
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Action", "Updated Sentinel settings");
            attributes.put("Collector Interval (s)", String.valueOf(settings.getCollectorIntervalSeconds()));
            attributes.put("Evaluator Interval (s)", String.valueOf(settings.getEvaluatorIntervalSeconds()));
            attributes.put("Sample Retention (days)", String.valueOf(settings.getSampleRetentionDays()));
            attributes.put("Trend Retention (days)", String.valueOf(settings.getTrendRetentionDays()));
            attributes.put("Resolved Alert Retention (days)", String.valueOf(settings.getResolvedAlertRetentionDays()));
            dispatch(userId, attributes);
        } catch (Exception e) {
            log.warn("Failed to audit Sentinel settings update", e);
        }
    }

    // ========== Plumbing ==========

    /**
     * Adds channel attribution per the engine auditor's convention: the
     * composite {@code "channel"} attribute (which makes
     * {@code ServerEvent.getChannelId()}/{@code getChannelName()} work) plus
     * readable {@code "Channel ID"}/{@code "Channel Name"} attributes. Falls
     * back to {@code "Channel ID"} alone when the channel cannot be resolved
     * — an audit row about a since-deleted channel is still worth writing.
     */
    private static void addChannelAttributes(Map<String, String> attributes, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        attributes.put("Channel ID", channelId);
        try {
            Channel channel = ChannelController.getInstance().getChannelById(channelId);
            if (channel != null) {
                attributes.put("Channel Name", channel.getName());
                // toAuditString(), not toString(): only the audit form carries
                // the id ("Channel[id=...,name=...]"), which is what
                // ServerEvent.getChannelId()'s "id="/","-based parsing — and
                // the engine auditor this mirrors — actually requires.
                attributes.put("channel", channel.toAuditString());
            }
        } catch (Exception e) {
            log.debug("Could not resolve channel {} for audit attribution", channelId, e);
        }
    }

    /**
     * Builds and dispatches the {@link ServerEvent}. {@code dispatchEvent}
     * (async, fire-and-forget onto the event listeners' queues) is used
     * rather than {@code insertEvent} (synchronous DB write) so auditing
     * never adds a database round trip to the request path — the same choice
     * the engine's own auditor makes.
     */
    private static void dispatch(int userId, Map<String, String> attributes) {
        ServerEvent event = new ServerEvent(
                ConfigurationController.getInstance().getServerId(),
                SentinelServletInterface.PLUGIN_POINT,
                Level.INFORMATION, Outcome.SUCCESS, attributes);
        event.setUserId(userId);
        EventController.getInstance().dispatchEvent(event);
    }

    /** Guards against a half-built entity making an attribute value "null". */
    private static String valueOrUnknown(String value) {
        return value != null && !value.isBlank() ? value : "(unknown)";
    }
}
