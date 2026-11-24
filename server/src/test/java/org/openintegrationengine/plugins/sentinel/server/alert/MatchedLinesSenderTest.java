/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.SubjectType;

/**
 * What each transport sends for an alert with and without a Log alert's matches.
 *
 * <p>The channel-alert expectations are the exact output of the senders
 * before matches existed, captured from the 1.2.2 code on the same two
 * payloads (one with a runbook, details and a multi-line message; one per
 * connector with no value). They pin "byte for byte unchanged" for every
 * channel alert: the payload JSON (what the CHANNEL action routes, SNS
 * publishes and the default webhook body sends), the mail subject and body,
 * the webhook template rendering, the routed message's source map and the
 * SNS subject.</p>
 */
class MatchedLinesSenderTest {

    private static final String TEMPLATE = "${monitorName}|${channelName}|${severity}|${status}|${message}"
            + "|${runbookUrl}|${alertEventId}|${valueJson}";

    private static final String INCLUDE_MATCHES = "{\"includeMatches\": true}";

    private static final String LINES = "ERROR 2026-11-21T11:59:01.000Z [node-a] transformer: boom\n"
            + "java.lang.IllegalStateException: at line 2\n"
            + "WARN 2026-11-21T11:59:02.000Z [node-b] db-connector: kept as typed: ${valueJson} ${monitorName}";

    private static final AlertPayload WITH_RUNBOOK = new AlertPayload(42L, 7, "ADT \"inactivity\"",
            MonitorType.INACTIVITY, "c-1", "ADT In", null, Severity.HIGH, "PROBLEM", "No messages for 301s\nsecond line",
            Instant.parse("2026-11-21T12:00:00Z"), "{\"secondsSinceLast\":301}", "https://wiki.example.org/rb");

    private static final AlertPayload PER_CONNECTOR = new AlertPayload(43L, 8, "Conn",
            MonitorType.CONNECTION_STATUS, "c-2", "Lab Out", 2, Severity.WARNING, "RESOLVED", "Connector recovered",
            Instant.parse("2026-11-21T12:05:00Z"), null);

    @Test
    void aChannelAlertWithRunbookAndDetailsIsByteForByteUnchanged() throws Exception {
        assertUnchanged(WITH_RUNBOOK,
                "{\"alertEventId\":42,\"monitorId\":7,\"monitorName\":\"ADT \\\"inactivity\\\"\",\"monitorType\":\"INACTIVITY\",\"channelId\":\"c-1\",\"channelName\":\"ADT In\",\"metadataId\":null,\"severity\":\"HIGH\",\"eventType\":\"PROBLEM\",\"message\":\"No messages for 301s\\nsecond line\",\"openedTime\":\"2026-11-21T12:00:00Z\",\"valueJson\":\"{\\\"secondsSinceLast\\\":301}\",\"runbookUrl\":\"https://wiki.example.org/rb\"}",
                "[Sentinel][HIGH] ADT \"inactivity\" \u2014 ADT In: PROBLEM",
                "ADT \"inactivity\"|ADT In|HIGH|PROBLEM|No messages for 301s second line|https://wiki.example.org/rb|${alertEventId}|${valueJson}",
                "OIE Sentinel notification\n\nMonitor:  ADT \"inactivity\" (INACTIVITY)\nChannel:  ADT In [c-1]\nSeverity: HIGH\nStatus:   PROBLEM\nOpened:   2026-11-21T12:00:00Z\nRunbook:  https://wiki.example.org/rb\n\nNo messages for 301s\nsecond line\n\nDetails:\n{\"secondsSinceLast\":301}\n",
                "OIE Sentinel notification\n\nMonitor:  ADT \"inactivity\" (INACTIVITY)\nChannel:  ADT In [c-1]\nSeverity: HIGH\nStatus:   PROBLEM\nOpened:   2026-11-21T12:00:00Z\nRunbook:  https://wiki.example.org/rb\n\nNo messages for 301s\nsecond line\n",
                "ADT \\\"inactivity\\\"|ADT In|HIGH|PROBLEM|No messages for 301s\\nsecond line|https://wiki.example.org/rb|42|{\"secondsSinceLast\":301}",
                "ADT \"inactivity\"|ADT In|HIGH|PROBLEM|No messages for 301s\nsecond line|https://wiki.example.org/rb|42|{\"secondsSinceLast\":301}",
                "{sentinelAlertEventId=42, sentinelChannelId=c-1, sentinelChannelName=ADT In, sentinelMessage=No messages for 301s\nsecond line, sentinelMonitorName=ADT \"inactivity\", sentinelMonitorType=INACTIVITY, sentinelRunbookUrl=https://wiki.example.org/rb, sentinelSeverity=HIGH, sentinelStatus=PROBLEM}",
                "[Sentinel][HIGH] ADT \"inactivity\" - ADT In: PROBLEM");
    }

    @Test
    void aPerConnectorRecoveryWithNoValueIsByteForByteUnchanged() throws Exception {
        assertUnchanged(PER_CONNECTOR,
                "{\"alertEventId\":43,\"monitorId\":8,\"monitorName\":\"Conn\",\"monitorType\":\"CONNECTION_STATUS\",\"channelId\":\"c-2\",\"channelName\":\"Lab Out\",\"metadataId\":2,\"severity\":\"WARNING\",\"eventType\":\"RESOLVED\",\"message\":\"Connector recovered\",\"openedTime\":\"2026-11-21T12:05:00Z\",\"valueJson\":null,\"runbookUrl\":null}",
                "[Sentinel][WARNING] Conn \u2014 Lab Out: RESOLVED",
                "Conn|Lab Out|WARNING|RESOLVED|Connector recovered||${alertEventId}|${valueJson}",
                "OIE Sentinel notification\n\nMonitor:  Conn (CONNECTION_STATUS)\nChannel:  Lab Out [c-2]\nConnector metadata id: 2\nSeverity: WARNING\nStatus:   RESOLVED\nOpened:   2026-11-21T12:05:00Z\n\nConnector recovered\n",
                "OIE Sentinel notification\n\nMonitor:  Conn (CONNECTION_STATUS)\nChannel:  Lab Out [c-2]\nConnector metadata id: 2\nSeverity: WARNING\nStatus:   RESOLVED\nOpened:   2026-11-21T12:05:00Z\n\nConnector recovered\n",
                "Conn|Lab Out|WARNING|RESOLVED|Connector recovered||43|null",
                "Conn|Lab Out|WARNING|RESOLVED|Connector recovered||43|null",
                "{sentinelAlertEventId=43, sentinelChannelId=c-2, sentinelChannelName=Lab Out, sentinelMessage=Connector recovered, sentinelMonitorName=Conn, sentinelMonitorType=CONNECTION_STATUS, sentinelSeverity=WARNING, sentinelStatus=RESOLVED}",
                "[Sentinel][WARNING] Conn - Lab Out: RESOLVED");
    }

    @Test
    void theMatchesTokenIsEmptyForAnAlertWithoutMatches() throws Exception {
        assertEquals("[]", emailSubject("[${matches}]", WITH_RUNBOOK));
        assertEquals("{\"lines\":\"\"}", webhook("{\"lines\":\"${matches}\"}", WITH_RUNBOOK, true));
        assertNull(WITH_RUNBOOK.getMatches());
        assertFalse(Json.write(WITH_RUNBOOK).contains("matches"));
    }

    @Test
    void aLogAlertWithMatchesShowsThemInEveryTransport() throws Exception {
        AlertPayload payload = logPayload("Log errors", INCLUDE_MATCHES);

        // Payload JSON: the lines as fields, only when present.
        JsonNode json = Json.mapper().readTree(Json.write(payload));
        assertEquals("Log", json.path("channelName").asText());
        assertTrue(json.path("channelId").isNull());
        assertEquals(2, json.path("matches").size());
        JsonNode first = json.path("matches").get(0);
        assertEquals(List.of("time", "node", "level", "logger", "message"), fieldNames(first));
        assertEquals("node-a", first.path("node").asText());
        assertEquals("boom\njava.lang.IllegalStateException: at line 2", first.path("message").asText());

        // Mail: the block after the message and before the details, whatever includeDetails says.
        String body = "OIE Sentinel notification\n\n"
                + "Monitor:  Log errors (LOG_EVENTS)\n"
                + "Channel:  Log\n"
                + "Severity: HIGH\n"
                + "Status:   PROBLEM\n"
                + "Opened:   2026-11-21T12:00:00Z\n"
                + "\n2 matching log lines in the last 300s (at least 2)\n"
                + "\nMatches:\n" + LINES + "\n";
        assertEquals(body + "\nDetails:\n" + payload.getValueJson() + "\n", emailBody(payload, true));
        assertEquals(body, emailBody(payload, false));
        assertEquals("[Sentinel][HIGH] Log errors \u2014 Log: PROBLEM",
                emailSubject(EmailAlertSender.DEFAULT_SUBJECT_TEMPLATE, payload));

        // Webhook: escaped like any scalar; the default body is the payload JSON, matches included.
        JsonNode parsed = Json.mapper().readTree(webhook("{\"text\":\"${channelName}\\n${matches}\"}", payload, true));
        assertEquals("Log\n" + LINES, parsed.path("text").asText());
        Method renderBody = WebhookAlertSender.class.getDeclaredMethod("renderBody", JsonNode.class, List.class,
                AlertPayload.class);
        renderBody.setAccessible(true);
        assertEquals(Json.write(payload), renderBody.invoke(null, Json.mapper().createObjectNode(), List.of(), payload));

        // Routed message: the lines as text in the source map; no channel id key at all.
        Map<String, Object> source = sourceMap(payload);
        assertEquals(LINES, source.get("sentinelMatches"));
        assertEquals("Log", source.get("sentinelChannelName"));
        assertFalse(source.containsKey("sentinelChannelId"));

        assertEquals("[Sentinel][HIGH] Log errors - Log: PROBLEM", snsSubject(payload));
    }

    @Test
    void aLogAlertWithoutMatchesHasNoBlockAndNoProperty() throws Exception {
        AlertPayload payload = AlertPayload.of(logEvent(false), logMonitor("Log errors", INCLUDE_MATCHES), "PROBLEM");
        assertNull(payload.getMatches());
        assertFalse(Json.write(payload).contains("matches"));
        assertFalse(emailBody(payload, true).contains("Matches:"));
        assertFalse(sourceMap(payload).containsKey("sentinelMatches"));
        assertEquals("", emailSubject("${matches}", payload));
    }

    @Test
    void tokensAreSubstitutedInOnePassSoNoSubstitutedValueIsExpanded() throws Exception {
        AlertPayload payload = logPayload("Errors ${message}", INCLUDE_MATCHES);
        String message = "2 matching log lines in the last 300s (at least 2)";
        assertEquals("Errors ${message}|" + message + "|" + AlertSender.singleLine(LINES),
                emailSubject("${monitorName}|${message}|${matches}", payload));
        assertEquals("Errors ${message}|" + message + "|" + LINES,
                webhook("${monitorName}|${message}|${matches}", payload, false));
        JsonNode parsed = Json.mapper().readTree(webhook("{\"m\":\"${monitorName}\",\"lines\":\"${matches}\","
                + "\"value\":${valueJson}}", payload, true));
        assertEquals("Errors ${message}", parsed.path("m").asText());
        assertTrue(parsed.path("lines").asText().endsWith("kept as typed: ${valueJson} ${monitorName}"),
                "a logged token reaches the body as typed, so it cannot inject raw JSON");
        assertEquals(2, parsed.path("value").path("count").asInt());
    }

    @Test
    void matchesTravelOnlyWhileTheCurrentMonitorIncludesThem() throws Exception {
        AlertEvent event = logEvent(true);
        AlertPayload on = AlertPayload.of(event, logMonitor("Log errors", INCLUDE_MATCHES), "PROBLEM");
        assertEquals(2, on.getMatches().size());
        assertTrue(Json.mapper().readTree(on.getValueJson()).has("matches"));

        for (Monitor monitor : Arrays.asList(logMonitor("Log errors", "{}"),
                logMonitor("Log errors", "{\"includeMatches\": false}"), null)) {
            AlertPayload off = AlertPayload.of(event, monitor, "PROBLEM");
            assertNull(off.getMatches());
            JsonNode value = Json.mapper().readTree(off.getValueJson());
            assertFalse(value.has("matches"));
            assertEquals(2, value.path("count").asInt(), "the rest of the value is kept");
            assertFalse(Json.write(off).contains("boom"), "no trace of a line anywhere in the payload");
        }
    }

    @Test
    void anEmptyOrUnreadableValueCostsTheLinesNotTheNotification() {
        assertNull(MatchedLines.parse("{\"matches\":[]}"));
        assertNull(MatchedLines.parse("{\"matches\":\"not an array\"}"));
        assertNull(MatchedLines.parse("not json"));
        assertNull(MatchedLines.parse(null));
        assertEquals("", MatchedLines.text(null));
        List<MatchedLines.Line> partial = MatchedLines.parse("{\"matches\":[{\"message\":\"only a message\"}]}");
        assertEquals("  [] : only a message", MatchedLines.text(partial),
                "missing fields read as empty, never as \"null\"");
    }

    @Test
    void theLargestLineSetStillFitsSnsMessageLimit() throws Exception {
        // Ten lines, each with a message and a logger at the capture's 1,000-character cut,
        // made of characters JSON escapes to six bytes, so the payload document is as large as it gets.
        String worst = "\u0001".repeat(1000);
        ArrayNode lines = Json.mapper().createArrayNode();
        for (int i = 0; i < 10; i++) {
            lines.addObject().put("time", "2026-11-21T11:59:0" + i + ".000Z").put("node", "node-" + i)
                    .put("level", "ERROR").put("logger", worst + "...").put("message", worst + "...");
        }
        ObjectNode value = Json.mapper().createObjectNode().put("count", 999999).put("threshold", 1)
                .put("windowSeconds", 300);
        value.putObject("perNode").put("node-0", 999999);
        value.set("matches", lines);
        AlertPayload payload = new AlertPayload(1L, 1, "m".repeat(255), MonitorType.LOG_EVENTS, null, "Log", null,
                Severity.DISASTER, "PROBLEM", "x".repeat(1024), Instant.parse("2026-11-21T12:00:00Z"),
                Json.write(value), "https://wiki.example.org/" + "r".repeat(990));
        int full = Json.write(payload).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(full > SnsAlertSender.MAX_MESSAGE_BYTES, "the case the guard exists for: " + full + " bytes");

        String message = SnsAlertSender.buildMessage(payload);
        int bytes = message.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= SnsAlertSender.MAX_MESSAGE_BYTES, "SNS rejects messages over 256 KB; this one is " + bytes);
        JsonNode sent = Json.mapper().readTree(message);
        assertFalse(sent.has("matches"));
        assertEquals(10, Json.mapper().readTree(sent.path("valueJson").asText()).path("matches").size(),
                "the lines still travel inside valueJson");

        // Ordinary lines, and every alert without lines, publish exactly the payload JSON.
        AlertPayload ordinary = logPayload("Log errors", INCLUDE_MATCHES);
        assertEquals(Json.write(ordinary), SnsAlertSender.buildMessage(ordinary));
        assertEquals(Json.write(WITH_RUNBOOK), SnsAlertSender.buildMessage(WITH_RUNBOOK));
    }

    // ========== helpers ==========

    private static void assertUnchanged(AlertPayload payload, String json, String subjectDefault, String subjectAll,
            String bodyDetails, String bodyNoDetails, String webhookJson, String webhookPlain, String sourceMap,
            String snsSubject) throws Exception {
        assertEquals(json, Json.write(payload));
        assertEquals(subjectDefault, emailSubject(EmailAlertSender.DEFAULT_SUBJECT_TEMPLATE, payload));
        assertEquals(subjectAll, emailSubject(TEMPLATE, payload));
        assertEquals(bodyDetails, emailBody(payload, true));
        assertEquals(bodyNoDetails, emailBody(payload, false));
        assertEquals(webhookJson, webhook(TEMPLATE, payload, true));
        assertEquals(webhookPlain, webhook(TEMPLATE, payload, false));
        assertEquals(sourceMap, new TreeMap<>(sourceMap(payload)).toString());
        assertEquals(snsSubject, snsSubject(payload));
        // The rewording copies the dispatcher makes serialize the same way.
        assertEquals(json, Json.write(payload.copy(payload.getEventType(), payload.getMessage())));
    }

    private static AlertPayload logPayload(String monitorName, String config) {
        return AlertPayload.of(logEvent(true), logMonitor(monitorName, config), "PROBLEM");
    }

    private static AlertEvent logEvent(boolean withMatches) {
        ObjectNode value = Json.mapper().createObjectNode().put("count", 2).put("threshold", 2).put("windowSeconds", 300);
        value.putObject("perNode").put("node-a", 1).put("node-b", 1);
        if (withMatches) {
            ArrayNode lines = value.putArray("matches");
            lines.addObject().put("time", "2026-11-21T11:59:01.000Z").put("node", "node-a").put("level", "ERROR")
                    .put("logger", "transformer").put("message", "boom\njava.lang.IllegalStateException: at line 2");
            lines.addObject().put("time", "2026-11-21T11:59:02.000Z").put("node", "node-b").put("level", "WARN")
                    .put("logger", "db-connector").put("message", "kept as typed: ${valueJson} ${monitorName}");
        }
        AlertEvent event = new AlertEvent();
        event.setId(9L);
        event.setMonitorId(5);
        event.setSubjectType(SubjectType.SERVER);
        event.setSeverity(Severity.HIGH);
        event.setMessage("2 matching log lines in the last 300s (at least 2)");
        event.setOpenedTime(Instant.parse("2026-11-21T12:00:00Z"));
        event.setDetailsJson(Json.write(value));
        return event;
    }

    private static Monitor logMonitor(String name, String config) {
        Monitor monitor = new Monitor();
        monitor.setId(5);
        monitor.setName(name);
        monitor.setMonitorType(MonitorType.LOG_EVENTS);
        monitor.setConfigJson(config);
        return monitor;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String emailSubject(String template, AlertPayload payload) throws Exception {
        Method render = EmailAlertSender.class.getDeclaredMethod("render", String.class, AlertPayload.class);
        render.setAccessible(true);
        return (String) render.invoke(null, template, payload);
    }

    private static String emailBody(AlertPayload payload, boolean includeDetails) throws Exception {
        Method body = EmailAlertSender.class.getDeclaredMethod("buildBody", AlertPayload.class, boolean.class);
        body.setAccessible(true);
        return (String) body.invoke(null, payload, includeDetails);
    }

    private static String webhook(String template, AlertPayload payload, boolean escapeJson) throws Exception {
        Method render = WebhookAlertSender.class.getDeclaredMethod("render", String.class, AlertPayload.class,
                boolean.class);
        render.setAccessible(true);
        return (String) render.invoke(null, template, payload, escapeJson);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceMap(AlertPayload payload) throws Exception {
        Method map = ChannelAlertSender.class.getDeclaredMethod("sourceMap", AlertPayload.class);
        map.setAccessible(true);
        return (Map<String, Object>) map.invoke(null, payload);
    }

    private static String snsSubject(AlertPayload payload) throws Exception {
        Method subject = SnsAlertSender.class.getDeclaredMethod("buildSubject", AlertPayload.class);
        subject.setAccessible(true);
        return (String) subject.invoke(null, payload);
    }
}
