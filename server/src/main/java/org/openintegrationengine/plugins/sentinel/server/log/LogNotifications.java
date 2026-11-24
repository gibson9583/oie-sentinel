/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertLifecycleTransaction;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertKind;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * Log notifications as {@code sentinel_alert_event} rows of kind NOTIFICATION.
 *
 * <p>A notification is written already closed (RESOLVED, resolved when it
 * opened) with its opened edge pending and no resolution edge. Closed, it is
 * never open anywhere and cannot be acknowledged or resolved; pending, the
 * leader's outbox pass delivers it once, after a restart or a leader change
 * too, and the dispatcher never sends a clear for it.</p>
 */
public final class LogNotifications {

    /** The payload's event type, {@code ${status}} in templates. */
    public static final String EVENT_TYPE = "NOTIFICATION";

    private LogNotifications() {
    }

    /** One closed, undelivered notification row, not yet inserted. */
    public static AlertEvent notification(Monitor monitor, String message, String detailsJson, Instant at) {
        AlertEvent event = new AlertEvent();
        event.setMonitorId(monitor.getId());
        event.setKind(AlertKind.NOTIFICATION);
        event.setSubjectType(SubjectType.SERVER);
        event.setSeverity(monitor.getSeverity());
        event.setStatus(AlertStatus.RESOLVED);
        event.setMessage(message);
        event.setOpenedTime(at);
        event.setResolvedTime(at);
        event.setDetailsJson(detailsJson);
        event.setProblemPending(true);
        return event;
    }

    /**
     * One notification per occurrence a node kept, opened at the line's own
     * time, plus one summary for the occurrences past the cap. The message
     * names the level and logger only; the text travels in {@code matches},
     * and only when the monitor includes them.
     */
    static List<AlertEvent> forOccurrences(Monitor monitor, LogEventsConfig config, LogMatchCounter.Drained drained,
            String nodeId, Instant now) {
        List<AlertEvent> events = new ArrayList<>();
        for (LogMatchCounter.MatchedLine line : drained.occurrences()) {
            ObjectNode details = Json.mapper().createObjectNode().put("condition", "EACH").put("count", 1);
            details.put("node", nodeId);
            if (config.includeMatches()) {
                details.set("matches", LogMatchFlushJob.toJson(List.of(line), nodeId));
            }
            events.add(notification(monitor, cut("Matching " + line.level() + " log line from " + line.logger()),
                    Json.write(details), Instant.ofEpochMilli(line.timeMillis())));
        }
        if (drained.overflow() > 0) {
            ObjectNode details = Json.mapper().createObjectNode().put("condition", "EACH")
                    .put("count", drained.overflow()).put("node", nodeId).put("notSentIndividually", drained.overflow());
            events.add(notification(monitor, drained.overflow() + " more matching log lines on " + nodeId
                    + " were not sent one by one (limit " + LogMatchCounter.MAX_OCCURRENCES + " per collector run)",
                    Json.write(details), now));
        }
        return events;
    }

    /** All or nothing, without a leadership fence: each node writes only its own new rows. */
    static void insert(List<AlertEvent> events) {
        if (!events.isEmpty()) {
            AlertLifecycleTransaction.execute(afterCommit -> events.forEach(AlertEventRepository::insertAlertEvent));
        }
    }

    /** The message column is VARCHAR(1024). */
    private static String cut(String message) {
        return message.length() <= 1024 ? message : LogQueue.cut(message, 1021) + "...";
    }
}
