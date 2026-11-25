/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.logging.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.util.Json;

/**
 * A Log monitor's {@code config_json}, parsed and checked. Every key is
 * optional; numbers may be numeric strings, as form values arrive. See
 * {@link #parse(JsonNode)} for the keys and defaults.
 *
 * <p>{@link #parse} throws one message per bad value, at save time.
 * {@link #lenient} never throws, so a config broken by a direct database edit
 * skips one monitor instead of stopping the rest.</p>
 */
public record LogEventsConfig(Level minLevel, String loggerPrefix, Pattern pattern, Condition condition,
        int threshold, int windowSeconds, Outcome outcome, ClearMode clearMode, int clearAfterSeconds,
        boolean includeMatches) {

    /** Each occurrence, at least {@code threshold} in the window, or at most {@code threshold} in it. */
    public enum Condition { EACH, AT_LEAST, AT_MOST }

    /** A one-off notification, or a problem that stays open until it clears. */
    public enum Outcome { NOTIFICATION, PROBLEM }

    /** When a problem clears: the condition goes away, a person resolves it, or time passes. */
    public enum ClearMode { CONDITION, MANUAL, TIMEOUT }

    public static final int DEFAULT_WINDOW_SECONDS = 300;

    private static final Logger log = LoggerFactory.getLogger(LogEventsConfig.class);

    /** The broken config each monitor was last warned about, so a re-read every tick warns once. */
    private static final Map<Integer, String> WARNED = new ConcurrentHashMap<>();

    /** @return whether each occurrence is sent on its own, by the node that matched it */
    public boolean notifiesEachOccurrence() {
        return condition == Condition.EACH && outcome == Outcome.NOTIFICATION;
    }

    /** @return whether the evaluator reads the window: every combination but each occurrence as a notification */
    public boolean usesWindow() {
        return !notifiesEachOccurrence();
    }

    /** @throws IllegalArgumentException naming the first invalid value */
    public static LogEventsConfig parse(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return parse(Json.mapper().createObjectNode());
        }
        try {
            return parse(Json.mapper().readTree(configJson));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Monitor config is not valid JSON", e);
        }
    }

    /**
     * Keys: {@code minLevel} (TRACE..FATAL, default ERROR), {@code loggerPrefix},
     * {@code pattern} (Java regex, {@code find()}), {@code condition} (default
     * AT_LEAST), {@code threshold} (default 1; at least 1 for AT_LEAST, 0 for
     * AT_MOST), {@code windowSeconds} (default 300), {@code outcome} (default
     * PROBLEM), {@code clearMode} (default CONDITION), {@code clearAfterSeconds}
     * (required for TIMEOUT) and {@code includeMatches} (default false).
     *
     * @throws IllegalArgumentException naming the first invalid value
     */
    public static LogEventsConfig parse(JsonNode config) {
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("Monitor config must be a JSON object");
        }
        Condition condition = oneOf(config, "condition", Condition.class, Condition.AT_LEAST);
        int minThreshold = condition == Condition.AT_MOST ? 0 : 1;
        ClearMode clearMode = oneOf(config, "clearMode", ClearMode.class, ClearMode.CONDITION);
        Integer clearAfter = atLeast(config, "clearAfterSeconds", 1);
        if (clearMode == ClearMode.TIMEOUT && clearAfter == null) {
            throw new IllegalArgumentException("clearAfterSeconds is required when clearMode is TIMEOUT");
        }
        Integer threshold = atLeast(config, "threshold", minThreshold);
        Integer window = atLeast(config, "windowSeconds", 1);
        return new LogEventsConfig(
                oneOf(config, "minLevel", Levels.class, Levels.ERROR).level,
                text(config, "loggerPrefix", true),
                pattern(config),
                condition,
                threshold != null ? threshold : 1,
                window != null ? window : DEFAULT_WINDOW_SECONDS,
                oneOf(config, "outcome", Outcome.class, Outcome.PROBLEM),
                clearMode,
                clearAfter != null ? clearAfter : 0,
                bool(config, "includeMatches"));
    }

    /** @return the parsed config, or {@code null} when it is invalid, with one warning per broken config */
    public static LogEventsConfig lenient(int monitorId, String configJson) {
        try {
            LogEventsConfig config = parse(configJson);
            WARNED.remove(monitorId);
            return config;
        } catch (IllegalArgumentException e) {
            String key = String.valueOf(configJson);
            if (!key.equals(WARNED.put(monitorId, key))) {
                log.warn("Log monitor {} has an invalid config and is skipped until it is fixed: {}",
                        monitorId, e.getMessage());
            }
            return null;
        }
    }

    /**
     * The save-time window bounds: shorter than the evaluator interval loses
     * lines between evaluations, longer than the sample retention can never be
     * covered. Checked against the settings of the moment; the evaluator
     * clamps the same way if they change later.
     *
     * @throws IllegalArgumentException when the window is used and outside the bounds
     */
    public void requireWindowWithin(int evaluatorIntervalSeconds, int sampleRetentionDays) {
        if (!usesWindow()) {
            return;
        }
        if (windowSeconds < evaluatorIntervalSeconds) {
            throw new IllegalArgumentException("windowSeconds must be at least the evaluator interval ("
                    + evaluatorIntervalSeconds + " seconds)");
        }
        long retentionSeconds = sampleRetentionDays * 86_400L;
        if (windowSeconds > retentionSeconds) {
            throw new IllegalArgumentException("windowSeconds must be at most the sample retention ("
                    + retentionSeconds + " seconds, " + sampleRetentionDays + " days)");
        }
    }

    /**
     * @return whether a stored config asks for matches, read as validation
     *         reads it but without compiling the pattern; false when the
     *         config or the value is unreadable
     */
    public static boolean includesMatches(String configJson) {
        try {
            return configJson != null && bool(Json.mapper().readTree(configJson), "includeMatches");
        } catch (Exception e) {
            return false;
        }
    }

    /** OFF and ALL are thresholds, not levels a line is logged at, so they are not offered. */
    private enum Levels {
        TRACE(Level.TRACE), DEBUG(Level.DEBUG), INFO(Level.INFO), WARN(Level.WARN), ERROR(Level.ERROR),
        FATAL(Level.FATAL);

        private final Level level;

        Levels(Level level) {
            this.level = level;
        }
    }

    // ========== Field readers ==========

    private static JsonNode present(JsonNode config, String field) {
        JsonNode node = config.get(field);
        return node == null || node.isNull() ? null : node;
    }

    private static <E extends Enum<E>> E oneOf(JsonNode config, String field, Class<E> type, E defaultValue) {
        JsonNode node = present(config, field);
        if (node == null) {
            return defaultValue;
        }
        String name = node.isTextual() ? node.asText().trim().toUpperCase(Locale.ROOT) : "";
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        StringBuilder names = new StringBuilder();
        for (E value : type.getEnumConstants()) {
            names.append(names.length() > 0 ? ", " : "").append(value.name());
        }
        throw new IllegalArgumentException(field + " must be one of " + names);
    }

    /** Blank means none. A logger name is trimmed, since it carries no spaces; a pattern is not. */
    private static String text(JsonNode config, String field, boolean trim) {
        JsonNode node = present(config, field);
        if (node == null) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String value = trim ? node.asText().trim() : node.asText();
        return value.isEmpty() ? null : value;
    }

    private static Pattern pattern(JsonNode config) {
        String regex = text(config, "pattern", false);
        try {
            return regex == null ? null : Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("pattern is not a valid regular expression: " + e.getDescription());
        }
    }

    /** Exact decimals, so "1.5", "1e400" and 2147483648 are refused rather than rounded or wrapped. */
    private static Integer atLeast(JsonNode config, String field, int min) {
        JsonNode node = present(config, field);
        if (node == null) {
            return null;
        }
        int value;
        try {
            value = (node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText().trim())).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a whole number");
        }
        if (value < min) {
            throw new IllegalArgumentException(field + " must be at least " + min);
        }
        return value;
    }

    private static boolean bool(JsonNode config, String field) {
        JsonNode node = present(config, field);
        if (node == null) {
            return false;
        }
        String value = node.isBoolean() ? node.asText() : node.isTextual() ? node.asText().trim() : "";
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value.toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException(field + " must be true or false");
    }
}
