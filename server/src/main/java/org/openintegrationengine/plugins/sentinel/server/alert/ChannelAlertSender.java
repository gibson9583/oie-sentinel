/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.mirth.connect.server.userutil.RawMessage;
import com.mirth.connect.server.userutil.VMRouter;
import com.mirth.connect.userutil.Response;
import com.mirth.connect.userutil.Status;

import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;

/**
 * Delivers alert notifications by routing a message into another OIE channel
 * — the escape hatch that lets an operator build arbitrary alert handling
 * (paging gateways, ticket systems, custom formats) with ordinary channel
 * tooling instead of waiting for Sentinel to grow a dedicated transport.
 *
 * <p>The message body is the full {@link AlertPayload} as JSON, and the same
 * facts are duplicated into flat {@code sentinel*} source-map keys so a
 * filter or transformer can branch on severity or monitor without parsing
 * the body first. Uses {@code com.mirth.connect.server.userutil.VMRouter} /
 * {@code RawMessage} — the same classes channel scripts use — rather than
 * calling {@code EngineController.dispatchRawMessage} directly, because
 * VMRouter already handles deployed-channel resolution and converts the
 * userutil RawMessage for us.</p>
 *
 * <p>Action {@code configJson} shape: {@code {channelId}}.</p>
 */
public final class ChannelAlertSender implements AlertSender {

    /**
     * Routes one payload message to the configured target channel.
     *
     * <p>{@code VMRouter} never throws — it catches {@code Throwable}
     * internally and returns an ERROR {@code Response} instead — so failure
     * detection here is by inspecting the returned Response, then converting
     * ERROR into an exception because the {@link AlertSender} contract is
     * throw-on-failure. A {@code null} Response is success: it just means the
     * target channel's source connector is not configured to return a
     * response.</p>
     */
    @Override
    public void send(Action action, AlertEvent event, AlertPayload payload) throws Exception {
        JsonNode config = readConfig(action);
        String channelId = text(config, "channelId");
        if (channelId == null) {
            // Validated at save time by ActionService; defend anyway so a
            // hand-edited config fails with an actionable reason.
            throw new Exception("Channel action '" + action.getName() + "' has no target channelId configured");
        }

        String body = Json.write(payload);
        Response response = new VMRouter().routeMessageByChannelId(channelId,
                new RawMessage(body, null, sourceMap(payload)));

        if (response != null && response.getStatus() == Status.ERROR) {
            String detail = response.getStatusMessage();
            if (detail == null || detail.isBlank()) {
                detail = response.getError();
            }
            if (detail == null || detail.isBlank()) {
                detail = "no error detail returned";
            }
            throw new Exception("Target channel " + channelId + " returned ERROR: " + detail);
        }
    }

    /**
     * Builds the flat source-map view of the payload. All values are strings
     * (enum names, stringified id) so downstream JavaScript compares them
     * without type surprises; {@code null} facts are omitted entirely —
     * synthetic test payloads have no channel id, and an absent key is easier
     * for a filter to handle than a present-but-null one.
     */
    private static Map<String, Object> sourceMap(AlertPayload payload) {
        Map<String, Object> sourceMap = new HashMap<>();
        put(sourceMap, "sentinelMonitorName", payload.getMonitorName());
        put(sourceMap, "sentinelMonitorType",
                payload.getMonitorType() != null ? payload.getMonitorType().name() : null);
        put(sourceMap, "sentinelSeverity",
                payload.getSeverity() != null ? payload.getSeverity().name() : null);
        put(sourceMap, "sentinelStatus", payload.getEventType());
        put(sourceMap, "sentinelChannelId", payload.getChannelId());
        put(sourceMap, "sentinelChannelName", payload.getChannelName());
        put(sourceMap, "sentinelMessage", payload.getMessage());
        put(sourceMap, "sentinelAlertEventId", String.valueOf(payload.getAlertEventId()));
        return sourceMap;
    }

    /** Adds the entry only when the value exists (see {@link #sourceMap}). */
    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * Parses the action's config JSON, failing with an operator-readable
     * message instead of a bare Jackson parse error when the stored JSON is
     * missing or malformed.
     */
    private static JsonNode readConfig(Action action) throws Exception {
        String configJson = action.getConfigJson();
        if (configJson == null || configJson.isBlank()) {
            throw new Exception("Channel action '" + action.getName() + "' has no configuration");
        }
        try {
            return Json.mapper().readTree(configJson);
        } catch (Exception e) {
            throw new Exception("Channel action '" + action.getName() + "' has invalid config JSON: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Reads a trimmed text field from the config, normalizing
     * missing/null/blank to {@code null} so callers have one absence check
     * instead of three.
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
