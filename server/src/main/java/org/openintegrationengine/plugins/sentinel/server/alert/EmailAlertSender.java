/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import com.fasterxml.jackson.databind.JsonNode;

import com.mirth.connect.server.util.ServerSMTPConnectionFactory;

import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;

/**
 * Delivers alert notifications through the engine's configured SMTP server.
 *
 * <p>Uses {@code ServerSMTPConnectionFactory.createSMTPConnection()} rather
 * than any Sentinel-owned SMTP settings so alert mail follows the exact
 * server settings (host, auth, TLS, from-address) the operator already
 * maintains under Settings → Server — Sentinel deliberately has no second
 * place where mail configuration could drift. A fresh connection is created
 * per send because the factory re-reads the server settings each time,
 * meaning an operator's SMTP change takes effect on the very next alert
 * without a plugin restart.</p>
 *
 * <p>Action {@code configJson} shape: {@code {to, cc?, subjectTemplate?,
 * includeDetails?}}. {@code to}/{@code cc} are comma-separated address lists
 * — passed straight through, because the engine's
 * {@code ServerSMTPConnection.send} splits on commas itself. The subject
 * template supports simple {@code ${var}} substitution (monitorName,
 * channelName, severity, status, message, runbookUrl); deliberately not
 * Velocity — alert subjects need value interpolation, not scripting, and a
 * template typo must never be able to break dispatch.</p>
 */
public final class EmailAlertSender implements AlertSender {

    /**
     * Subject used when the action does not configure {@code subjectTemplate}.
     * Leads with severity so inbox rules can match on it, and carries the
     * status token so PROBLEM and RESOLVED mails for the same monitor are
     * distinguishable at a glance.
     */
    static final String DEFAULT_SUBJECT_TEMPLATE =
            "[Sentinel][${severity}] ${monitorName} — ${channelName}: ${status}";

    /**
     * Sends one plain-text notification mail.
     *
     * <p>Propagates {@code EmailException} (SMTP failure) and the engine's
     * {@code ControllerException} (server settings unreadable) untouched —
     * per the {@link AlertSender} contract the dispatcher records the
     * exception message as the dispatch failure reason.</p>
     */
    @Override
    public void send(Action action, AlertEvent event, AlertPayload payload) throws Exception {
        JsonNode config = readConfig(action);

        String to = text(config, "to");
        if (to == null) {
            // Validated at save time by ActionService, but the config may
            // predate that validation or have been hand-edited — fail the
            // dispatch with a reason the operator can act on.
            throw new Exception("Email action '" + action.getName() + "' has no 'to' recipients configured");
        }
        String cc = text(config, "cc");

        String template = text(config, "subjectTemplate");
        if (template == null) {
            template = DEFAULT_SUBJECT_TEMPLATE;
        }
        // Default true: the details block is the evaluator's measurement
        // evidence, and an operator who never chose should see why the alert
        // fired rather than just that it fired.
        boolean includeDetails = config.path("includeDetails").asBoolean(true);

        String subject = render(template, payload);
        String body = buildBody(payload, includeDetails);

        // 4-arg overload: the from-address comes from the engine's server
        // settings, keeping Sentinel out of the sender-identity business.
        ServerSMTPConnectionFactory.createSMTPConnection().send(to, cc, subject, body);
    }

    /**
     * Parses the action's config JSON, failing with an operator-readable
     * message instead of a bare Jackson parse error when the stored JSON is
     * missing or malformed.
     */
    private static JsonNode readConfig(Action action) throws Exception {
        String configJson = action.getConfigJson();
        if (configJson == null || configJson.isBlank()) {
            throw new Exception("Email action '" + action.getName() + "' has no configuration");
        }
        try {
            return Json.mapper().readTree(configJson);
        } catch (Exception e) {
            throw new Exception("Email action '" + action.getName() + "' has invalid config JSON: "
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

    /**
     * Substitutes the supported {@code ${var}} tokens. Plain
     * {@code String.replace} per token: unknown tokens are left verbatim
     * (visible in the subject, so the operator notices the typo) rather than
     * throwing, because a bad template must never cost the notification.
     *
     * <p>Every substituted value is flattened to a single line
     * ({@link AlertSender#singleLine}) because this renders the {@code Subject}
     * header. JavaMail happens to encode that header, so a CRLF in a monitor
     * name is not exploitable today — but the asymmetry with
     * {@code SnsAlertSender}, which has always sanitized, is the kind that
     * becomes a real defect the moment this method is reused for a header
     * nothing encodes.</p>
     *
     * <p>{@code ${runbookUrl}} is flattened like every other value even though
     * {@code MonitorService} has already rejected anything that is not a
     * syntactically valid absolute http/https URL (and a valid URI cannot
     * contain a control character). The exemption would be sound today and
     * would silently stop being sound the first time a payload gained a runbook
     * URL from somewhere other than a validated monitor — an import path, a
     * hand-edited row — so the token carries no special case. An absent runbook
     * substitutes to the empty string, matching every other absent value.</p>
     */
    private static String render(String template, AlertPayload payload) {
        return template
                .replace("${monitorName}", AlertSender.singleLine(payload.getMonitorName()))
                .replace("${channelName}", AlertSender.singleLine(payload.getChannelName()))
                .replace("${severity}", payload.getSeverity() != null ? payload.getSeverity().name() : "")
                .replace("${status}", AlertSender.singleLine(payload.getEventType()))
                .replace("${message}", AlertSender.singleLine(payload.getMessage()))
                .replace("${runbookUrl}", AlertSender.singleLine(payload.getRunbookUrl()));
    }

    /**
     * Builds the plain-text body: fixed label/value lines first (so every
     * Sentinel mail scans the same way regardless of monitor type), the
     * evaluator's message as the narrative, and optionally the raw value JSON
     * as the machine-readable evidence block.
     *
     * <p>The runbook line sits with the label block rather than at the foot,
     * and is emitted only when the monitor has one — an empty "Runbook:" line
     * on every alert would train the reader to stop looking at that spot,
     * which costs exactly the monitors that bothered to set it. It is
     * deliberately not flattened by {@link AlertSender#singleLine}: this is
     * body text, not a header, where a newline is a line break rather than a
     * structural delimiter, and mail clients linkify a bare URL on its own
     * line.</p>
     */
    private static String buildBody(AlertPayload payload, boolean includeDetails) {
        StringBuilder body = new StringBuilder(256);
        body.append("OIE Sentinel notification\n\n");
        body.append("Monitor:  ").append(safe(payload.getMonitorName()));
        if (payload.getMonitorType() != null) {
            body.append(" (").append(payload.getMonitorType().name()).append(')');
        }
        body.append('\n');
        body.append("Channel:  ").append(safe(payload.getChannelName()));
        if (payload.getChannelId() != null) {
            body.append(" [").append(payload.getChannelId()).append(']');
        }
        body.append('\n');
        if (payload.getMetadataId() != null) {
            body.append("Connector metadata id: ").append(payload.getMetadataId()).append('\n');
        }
        body.append("Severity: ")
                .append(payload.getSeverity() != null ? payload.getSeverity().name() : "(unknown)")
                .append('\n');
        body.append("Status:   ").append(safe(payload.getEventType())).append('\n');
        if (payload.getOpenedTime() != null) {
            body.append("Opened:   ").append(payload.getOpenedTime()).append('\n');
        }
        if (payload.getRunbookUrl() != null && !payload.getRunbookUrl().isBlank()) {
            body.append("Runbook:  ").append(payload.getRunbookUrl()).append('\n');
        }
        if (payload.getMessage() != null) {
            body.append('\n').append(payload.getMessage()).append('\n');
        }
        if (includeDetails && payload.getValueJson() != null && !payload.getValueJson().isBlank()) {
            body.append("\nDetails:\n").append(payload.getValueJson()).append('\n');
        }
        return body.toString();
    }

    /** Null-to-empty guard so template rendering and body building never print "null". */
    private static String safe(String value) {
        return value != null ? value : "";
    }
}
