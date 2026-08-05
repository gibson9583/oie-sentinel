/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * Decides whether an action's stored condition filter matches a given alert
 * payload — the routing layer that lets one Sentinel installation send
 * DISASTER pages to on-call while WARNING mail goes to a team inbox.
 *
 * <p>The condition language is deliberately tiny: {@code conditionJson} is a
 * JSON array of {@code {field, operator, value}} rows, ANDed together; a
 * {@code null}/blank/empty condition matches everything (an unconditional
 * action is the common case and must need zero configuration). Supported
 * fields/operators:</p>
 *
 * <ul>
 *   <li>{@code SEVERITY} — {@code =}, {@code !=}, {@code >=} (by enum
 *       ordinal, whose order is load-bearing: INFORMATION &lt; WARNING &lt;
 *       AVERAGE &lt; HIGH &lt; DISASTER), {@code IN}</li>
 *   <li>{@code MONITOR_TYPE} — {@code =}, {@code !=}, {@code IN}</li>
 *   <li>{@code MONITOR} — {@code =}, {@code !=}, {@code IN} (monitor
 *       database id — "this action belongs to these monitors"; the UI's
 *       monitor condition is a multi-select that writes an {@code IN}
 *       row)</li>
 *   <li>{@code CHANNEL} — {@code =}, {@code !=}, {@code IN} (channel id,
 *       exact match)</li>
 *   <li>{@code CHANNEL_GROUP} — {@code =}, {@code !=}, {@code IN}
 *       (membership resolved live via
 *       {@link ScopeResolver#groupChannelIds(String)}, so group edits apply
 *       to the next dispatch without touching the action)</li>
 *   <li>{@code CHANNEL_TAG} — {@code =}, {@code !=}, {@code IN} (membership
 *       resolved live via {@link ScopeResolver#tagChannelIds(String)}, same
 *       live-resolution contract as CHANNEL_GROUP)</li>
 *   <li>{@code EVENT_TYPE} — {@code =} only ({@code PROBLEM} or
 *       {@code RESOLVED}; lifecycle gating normally belongs to
 *       {@code operationMode}, this exists for BOTH-mode actions that still
 *       want one condition row to differ)</li>
 * </ul>
 *
 * <p>{@code value} is a string, or an array of strings for {@code IN}.
 * Error policy is fail-closed per row: an unknown field, unknown operator,
 * unparseable value, or malformed condition JSON logs a warning and does NOT
 * match. A condition the operator wrote but Sentinel cannot understand must
 * not silently become "always fire" — mis-routing a DISASTER page to a quiet
 * mailbox is worse than a skipped notification plus a warning in the log.</p>
 *
 * <p>Stateless static utility (private constructor) per the plugin's house
 * style.</p>
 */
public final class ActionConditionMatcher {

    private static final Logger log = LoggerFactory.getLogger(ActionConditionMatcher.class);

    private ActionConditionMatcher() {
    }

    /**
     * Evaluates the action's condition rows against the payload.
     *
     * @param action  the action whose {@code conditionJson} to evaluate
     * @param payload the display-resolved alert snapshot being dispatched
     * @return {@code true} if every condition row matches (or there are no
     *         rows); {@code false} on any non-matching or non-understood row
     */
    public static boolean matches(Action action, AlertPayload payload) {
        String conditionJson = action != null ? action.getConditionJson() : null;
        if (conditionJson == null || conditionJson.isBlank()) {
            return true;
        }

        JsonNode root;
        try {
            root = Json.mapper().readTree(conditionJson);
        } catch (Exception e) {
            log.warn("Condition JSON of {} does not parse; treating as non-matching (fail closed): {}",
                    label(action), e.getMessage());
            return false;
        }
        if (root == null || root.isNull()) {
            return true;
        }
        if (!root.isArray()) {
            log.warn("Condition JSON of {} is not an array; treating as non-matching (fail closed)",
                    label(action));
            return false;
        }

        for (JsonNode row : root) {
            if (!rowMatches(row, payload, action)) {
                return false;
            }
        }
        // Zero rows (empty array) falls through to here: match-all.
        return true;
    }

    /** Evaluates one {@code {field, operator, value}} row (see class Javadoc for the policy). */
    private static boolean rowMatches(JsonNode row, AlertPayload payload, Action action) {
        if (row == null || !row.isObject()) {
            log.warn("Condition row of {} is not an object; failing the condition", label(action));
            return false;
        }
        String field = text(row.get("field"));
        String operator = text(row.get("operator"));
        JsonNode value = row.get("value");
        if (field == null || operator == null) {
            log.warn("Condition row of {} is missing field/operator; failing the condition", label(action));
            return false;
        }

        switch (field.toUpperCase(Locale.ROOT)) {
            case "SEVERITY":
                return severityMatches(operator, value, payload, action);
            case "MONITOR_TYPE":
                return nameMatches(operator, value,
                        payload.getMonitorType() != null ? payload.getMonitorType().name() : null,
                        action, "MONITOR_TYPE");
            case "MONITOR":
                // Monitor ids are integers, but the row's value arrives as
                // JSON strings/numbers — nameMatches's case-insensitive
                // string equality is exact for digit strings.
                return nameMatches(operator, value, String.valueOf(payload.getMonitorId()),
                        action, "MONITOR");
            case "CHANNEL":
                return channelMatches(operator, value, payload.getChannelId(), action);
            case "CHANNEL_GROUP":
                return groupMatches(operator, value, payload.getChannelId(), action);
            case "CHANNEL_TAG":
                return tagMatches(operator, value, payload.getChannelId(), action);
            case "EVENT_TYPE":
                if (isOp(operator, "=")) {
                    String expected = single(value, action, "EVENT_TYPE");
                    return expected != null && expected.equalsIgnoreCase(payload.getEventType());
                }
                log.warn("Condition row of {}: EVENT_TYPE only supports '='; got '{}'", label(action), operator);
                return false;
            default:
                log.warn("Condition row of {} has unknown field '{}'; failing the condition",
                        label(action), field);
                return false;
        }
    }

    /**
     * SEVERITY comparison. {@code >=} works on enum ordinals because the
     * {@link Severity} declaration order is the severity order — the usual
     * "notify me for HIGH and worse" rule in one row.
     */
    private static boolean severityMatches(String operator, JsonNode value, AlertPayload payload,
            Action action) {
        Severity actual = payload.getSeverity();
        if (actual == null) {
            // A payload without a severity cannot satisfy a severity
            // condition; fail closed rather than guess.
            return false;
        }
        if (isOp(operator, ">=")) {
            String expected = single(value, action, "SEVERITY");
            Severity threshold = parseSeverity(expected, action);
            return threshold != null && actual.ordinal() >= threshold.ordinal();
        }
        return nameMatches(operator, value, actual.name(), action, "SEVERITY");
    }

    /**
     * Generic {@code =}/{@code !=}/{@code IN} over an enum-name-like actual
     * value; comparison is case-insensitive because condition JSON is
     * hand-written and {@code "high"} obviously means {@code HIGH}.
     */
    private static boolean nameMatches(String operator, JsonNode value, String actual, Action action,
            String field) {
        if (isOp(operator, "=")) {
            String expected = single(value, action, field);
            return expected != null && expected.equalsIgnoreCase(actual);
        }
        if (isOp(operator, "!=")) {
            String expected = single(value, action, field);
            return expected != null && !expected.equalsIgnoreCase(actual);
        }
        if (isOp(operator, "IN")) {
            for (String candidate : values(value)) {
                if (candidate.equalsIgnoreCase(actual)) {
                    return true;
                }
            }
            return false;
        }
        log.warn("Condition row of {}: {} does not support operator '{}'", label(action), field, operator);
        return false;
    }

    /**
     * CHANNEL comparison — exact (case-sensitive) id equality, because
     * channel ids are opaque UUIDs, not human-typed names.
     */
    private static boolean channelMatches(String operator, JsonNode value, String channelId,
            Action action) {
        if (isOp(operator, "=")) {
            String expected = single(value, action, "CHANNEL");
            return expected != null && expected.equals(channelId);
        }
        if (isOp(operator, "!=")) {
            String expected = single(value, action, "CHANNEL");
            return expected != null && !Objects.equals(expected, channelId);
        }
        if (isOp(operator, "IN")) {
            return channelId != null && values(value).contains(channelId);
        }
        log.warn("Condition row of {}: CHANNEL does not support operator '{}'", label(action), operator);
        return false;
    }

    /**
     * CHANNEL_GROUP comparison — the value names groups, and membership of
     * the payload's channel is resolved against the live group definition on
     * every evaluation (see class Javadoc for why live resolution is a
     * feature, not a cost worth caching away at this volume).
     */
    private static boolean groupMatches(String operator, JsonNode value, String channelId,
            Action action) {
        if (channelId == null) {
            return false;
        }
        if (isOp(operator, "=")) {
            String groupId = single(value, action, "CHANNEL_GROUP");
            return groupId != null && isMember(groupId, channelId);
        }
        if (isOp(operator, "!=")) {
            String groupId = single(value, action, "CHANNEL_GROUP");
            return groupId != null && !isMember(groupId, channelId);
        }
        if (isOp(operator, "IN")) {
            for (String groupId : values(value)) {
                if (isMember(groupId, channelId)) {
                    return true;
                }
            }
            return false;
        }
        log.warn("Condition row of {}: CHANNEL_GROUP does not support operator '{}'", label(action), operator);
        return false;
    }

    /** True iff the channel is currently a member of the group (unknown group = empty set = no). */
    private static boolean isMember(String groupId, String channelId) {
        Set<String> members = ScopeResolver.groupChannelIds(groupId);
        return members.contains(channelId);
    }

    /**
     * CHANNEL_TAG comparison — the value names channel tags; membership of
     * the payload's channel is resolved against the live tag definition on
     * every evaluation, exactly like CHANNEL_GROUP.
     */
    private static boolean tagMatches(String operator, JsonNode value, String channelId,
            Action action) {
        if (channelId == null) {
            return false;
        }
        if (isOp(operator, "=")) {
            String tagId = single(value, action, "CHANNEL_TAG");
            return tagId != null && isTagged(tagId, channelId);
        }
        if (isOp(operator, "!=")) {
            String tagId = single(value, action, "CHANNEL_TAG");
            return tagId != null && !isTagged(tagId, channelId);
        }
        if (isOp(operator, "IN")) {
            for (String tagId : values(value)) {
                if (isTagged(tagId, channelId)) {
                    return true;
                }
            }
            return false;
        }
        log.warn("Condition row of {}: CHANNEL_TAG does not support operator '{}'", label(action), operator);
        return false;
    }

    /** True iff the channel currently bears the tag (unknown tag = empty set = no). */
    private static boolean isTagged(String tagId, String channelId) {
        return ScopeResolver.tagChannelIds(tagId).contains(channelId);
    }

    /** Parses a Severity name, logging + returning {@code null} (fail closed) on an unknown one. */
    private static Severity parseSeverity(String name, Action action) {
        if (name == null) {
            return null;
        }
        try {
            return Severity.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Condition row of {} has unknown severity '{}'; failing the condition",
                    label(action), name);
            return null;
        }
    }

    /**
     * Extracts the single scalar value an {@code =}/{@code !=}/{@code >=}
     * row needs; a missing value (or an array where a scalar belongs — the
     * first element is NOT silently picked) logs and returns {@code null},
     * which every caller treats as a failed row.
     */
    private static String single(JsonNode value, Action action, String field) {
        if (value == null || value.isNull() || value.isArray() || value.isObject()) {
            log.warn("Condition row of {}: {} needs a single scalar value; failing the condition",
                    label(action), field);
            return null;
        }
        return text(value);
    }

    /**
     * Extracts the value list an {@code IN} row uses: a scalar is treated as
     * a one-element list (forgiving the common hand-edit of writing
     * {@code "value": "X"} with {@code IN}), an array contributes its scalar
     * elements, anything else contributes nothing — and an empty list simply
     * never matches.
     */
    private static List<String> values(JsonNode value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isNull()) {
            return result;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                String text = text(element);
                if (text != null) {
                    result.add(text);
                }
            }
        } else {
            String text = text(value);
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    /** Case-insensitive operator comparison ("in" means "IN" in hand-written JSON). */
    private static boolean isOp(String operator, String expected) {
        return expected.equalsIgnoreCase(operator.trim());
    }

    /** Scalar node to trimmed text; {@code null} for null/container/blank nodes. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isArray() || node.isObject()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** Identifies the action in warnings so an operator can find the bad condition. */
    private static String label(Action action) {
        if (action == null) {
            return "action (null)";
        }
        return "action " + action.getId() + " ('" + action.getName() + "')";
    }
}
