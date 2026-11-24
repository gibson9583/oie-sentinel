/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ProblemSubject;
import org.openintegrationengine.plugins.sentinel.server.log.LogEventsConfig;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorKind;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;

/**
 * The matched log lines a Log alert carries under {@code "matches"} in its
 * value JSON, rendered the one way every transport shows them. No channel
 * evaluator writes that key, so every channel alert parses to {@code null}
 * and its output is unchanged.
 *
 * <p>Size: at most 10 lines per evaluation (one per each-occurrence
 * notification), each message and logger cut to 1,000 characters.</p>
 */
public final class MatchedLines {

    static final String KEY = "matches";

    private MatchedLines() {
    }

    /** One matched line in the shape the flush job stores. */
    @JsonPropertyOrder({"time", "node", "level", "logger", "message"})
    public record Line(String time, String node, String level, String logger, String message) {
    }

    /**
     * Never throws: a damaged value must cost the lines, not the notification.
     *
     * @return the lines, oldest first, or {@code null} when there are none
     */
    static List<Line> parse(String valueJson) {
        if (valueJson == null || valueJson.isBlank()) {
            return null;
        }
        try {
            JsonNode lines = Json.mapper().readTree(valueJson).path(KEY);
            if (!lines.isArray() || lines.isEmpty()) {
                return null;
            }
            List<Line> parsed = new ArrayList<>(lines.size());
            for (JsonNode line : lines) {
                parsed.add(new Line(text(line, "time"), text(line, "node"), text(line, "level"),
                        text(line, "logger"), text(line, "message")));
            }
            return List.copyOf(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The details a transport or a reader gets. A Log alert keeps its matches
     * only while its monitor exists and has {@code includeMatches} on now; the
     * stored row keeps them either way. A channel alert's details are returned
     * as stored.
     *
     * @param monitor the alert's monitor now, or {@code null} when it was deleted
     */
    public static String visibleDetails(AlertEvent event, Monitor monitor) {
        MonitorType type = monitor != null ? monitor.getMonitorType() : null;
        if (ProblemSubject.of(event).isServer() && !(type != null && type.kind() == MonitorKind.LOG
                && LogEventsConfig.includesMatches(monitor.getConfigJson()))) {
            return strip(event.getDetailsJson());
        }
        return event.getDetailsJson();
    }

    /** {@link #visibleDetails} for each Log alert of a page, reading each monitor once; channel alerts are untouched. */
    public static void applyVisibleDetails(List<AlertEvent> events) {
        Map<Integer, Monitor> monitors = new HashMap<>();
        for (AlertEvent event : events) {
            if (!ProblemSubject.of(event).isServer()) {
                continue;
            }
            Monitor monitor = monitors.containsKey(event.getMonitorId()) ? monitors.get(event.getMonitorId())
                    : MonitorRepository.getMonitor(event.getMonitorId());
            monitors.put(event.getMonitorId(), monitor);
            event.setDetailsJson(visibleDetails(event, monitor));
        }
    }

    /** @return the value JSON without its matches, unchanged when it has none or is not an object */
    static String strip(String valueJson) {
        if (valueJson == null || !valueJson.contains("\"" + KEY + "\"")) {
            return valueJson;
        }
        try {
            JsonNode value = Json.mapper().readTree(valueJson);
            if (!(value instanceof ObjectNode object) || !object.has(KEY)) {
                return valueJson;
            }
            object.remove(KEY);
            return Json.write(object);
        } catch (Exception e) {
            return null;
        }
    }

    /** Laid out like the engine's log, {@code LEVEL time [node] logger: message}, one entry per line. */
    static String text(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line.level()).append(' ').append(line.time())
                    .append(" [").append(line.node()).append("] ")
                    .append(line.logger()).append(": ").append(line.message());
        }
        return text.toString();
    }

    /** A missing or JSON-null field reads as empty, not "null". */
    private static String text(JsonNode line, String field) {
        JsonNode value = line.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
