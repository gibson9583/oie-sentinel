/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.util.Json;

/** Parsing a Log monitor's config: defaults, every refused value with its message, and the save-time window bounds. */
class LogEventsConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "{}", "{\"unknownKey\": 5, \"loggerPrefix\": \"  \", \"pattern\": \"\"}"})
    void absentOrEmptyValuesTakeTheDefaults(String json) {
        LogEventsConfig config = LogEventsConfig.parse(json);
        assertEquals(Level.ERROR, config.minLevel());
        assertNull(config.loggerPrefix());
        assertNull(config.pattern());
        assertEquals(LogEventsConfig.Condition.AT_LEAST, config.condition());
        assertEquals(1, config.threshold());
        assertEquals(300, config.windowSeconds());
        assertEquals(LogEventsConfig.Outcome.PROBLEM, config.outcome());
        assertEquals(LogEventsConfig.ClearMode.CONDITION, config.clearMode());
        assertEquals(0, config.clearAfterSeconds());
        assertFalse(config.includeMatches());
        assertEquals(LogEventsConfig.parse("{}"), LogEventsConfig.parse((String) null));
    }

    @Test
    void everyFieldIsReadIncludingFormStyleStrings() {
        LogEventsConfig config = LogEventsConfig.parse("{\"minLevel\": \" info \", \"loggerPrefix\": \" transformer \","
                + " \"pattern\": \" timed out\", \"condition\": \"at_most\", \"threshold\": \"3\", \"windowSeconds\": 600.0,"
                + " \"outcome\": \"notification\", \"clearMode\": \"TIMEOUT\", \"clearAfterSeconds\": \"900\","
                + " \"includeMatches\": \"TRUE\"}");
        assertEquals(Level.INFO, config.minLevel());
        assertEquals("transformer", config.loggerPrefix(), "the prefix is trimmed");
        assertEquals(" timed out", config.pattern().pattern(), "the pattern is not trimmed: a space is significant");
        assertEquals(LogEventsConfig.Condition.AT_MOST, config.condition());
        assertEquals(3, config.threshold());
        assertEquals(600, config.windowSeconds());
        assertEquals(LogEventsConfig.Outcome.NOTIFICATION, config.outcome());
        assertEquals(LogEventsConfig.ClearMode.TIMEOUT, config.clearMode());
        assertEquals(900, config.clearAfterSeconds());
        assertTrue(config.includeMatches());
        assertEquals(2147483647, LogEventsConfig.parse("{\"threshold\": 2147483647}").threshold());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACE", "debug", "Info", "WARN", "error", "FATAL"})
    void everyStandardLevelIsAccepted(String level) {
        assertEquals(Level.valueOf(level.toUpperCase()), LogEventsConfig.parse("{\"minLevel\": \"" + level + "\"}").minLevel());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"minLevel\": \"OFF\"}|minLevel must be one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL",
            "{\"minLevel\": \"ALL\"}|minLevel must be one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL",
            "{\"minLevel\": \"SEVERE\"}|minLevel must be one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL",
            "{\"minLevel\": 400}|minLevel must be one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL",
            "{\"loggerPrefix\": 5}|loggerPrefix must be a string",
            "{\"loggerPrefix\": [\"a\"]}|loggerPrefix must be a string",
            "{\"pattern\": 5}|pattern must be a string",
            "{\"pattern\": \"(unclosed\"}|pattern is not a valid regular expression: Unclosed group",
            "{\"condition\": \"SOMETIMES\"}|condition must be one of EACH, AT_LEAST, AT_MOST",
            "{\"threshold\": 0}|threshold must be at least 1",
            "{\"condition\": \"AT_MOST\", \"threshold\": -1}|threshold must be at least 0",
            "{\"threshold\": 1.5}|threshold must be a whole number",
            "{\"threshold\": \"2.5\"}|threshold must be a whole number",
            "{\"threshold\": \"many\"}|threshold must be a whole number",
            "{\"threshold\": true}|threshold must be a whole number",
            "{\"threshold\": 2147483648}|threshold must be a whole number",
            "{\"threshold\": 1e400}|threshold must be a whole number",
            "{\"windowSeconds\": 0}|windowSeconds must be at least 1",
            "{\"windowSeconds\": \"\"}|windowSeconds must be a whole number",
            "{\"windowSeconds\": 1e10}|windowSeconds must be a whole number",
            "{\"outcome\": \"PAGE\"}|outcome must be one of NOTIFICATION, PROBLEM",
            "{\"clearMode\": \"LATER\"}|clearMode must be one of CONDITION, MANUAL, TIMEOUT",
            "{\"clearMode\": \"TIMEOUT\"}|clearAfterSeconds is required when clearMode is TIMEOUT",
            "{\"clearAfterSeconds\": 0}|clearAfterSeconds must be at least 1",
            "{\"includeMatches\": \"yes\"}|includeMatches must be true or false",
            "{\"includeMatches\": 1}|includeMatches must be true or false",
            "[]|Monitor config must be a JSON object",
            "{not json|Monitor config is not valid JSON"})
    void everyInvalidValueIsRefusedWithAMessageNamingIt(String json, String message) {
        assertEquals(message, assertThrows(IllegalArgumentException.class, () -> LogEventsConfig.parse(json)).getMessage());
    }

    @Test
    void onlyAtMostAllowsAThresholdOfZero() {
        assertEquals(0, LogEventsConfig.parse("{\"condition\": \"AT_MOST\", \"threshold\": 0}").threshold());
        assertThrows(IllegalArgumentException.class, () -> LogEventsConfig.parse("{\"condition\": \"AT_LEAST\", \"threshold\": 0}"));
    }

    @Test
    void theWindowMustLieBetweenTheEvaluatorIntervalAndTheSampleRetentionUnlessItIsUnused() {
        assertEquals("windowSeconds must be at least the evaluator interval (60 seconds)",
                assertThrows(IllegalArgumentException.class,
                        () -> LogEventsConfig.parse("{\"windowSeconds\": 59}").requireWindowWithin(60, 1)).getMessage());
        assertEquals("windowSeconds must be at most the sample retention (86400 seconds, 1 days)",
                assertThrows(IllegalArgumentException.class,
                        () -> LogEventsConfig.parse("{\"windowSeconds\": 86401}").requireWindowWithin(60, 1)).getMessage());
        assertDoesNotThrow(() -> LogEventsConfig.parse("{\"windowSeconds\": 60}").requireWindowWithin(60, 1));
        assertDoesNotThrow(() -> LogEventsConfig.parse("{\"windowSeconds\": 86400}").requireWindowWithin(60, 1));

        LogEventsConfig eachNotification = LogEventsConfig.parse(
                "{\"condition\": \"EACH\", \"outcome\": \"NOTIFICATION\", \"windowSeconds\": 1}");
        assertTrue(eachNotification.notifiesEachOccurrence());
        assertFalse(eachNotification.usesWindow());
        assertDoesNotThrow(() -> eachNotification.requireWindowWithin(60, 1));
        assertThrows(IllegalArgumentException.class,
                () -> LogEventsConfig.parse("{\"condition\": \"EACH\", \"windowSeconds\": 1}").requireWindowWithin(60, 1),
                "each occurrence as a problem reads the window");
    }

    @Test
    void includesMatchesReadsOnlyThatKeyAndNeverThrows() {
        assertTrue(LogEventsConfig.includesMatches("{\"includeMatches\": true, \"pattern\": \"(unclosed\"}"));
        assertFalse(LogEventsConfig.includesMatches("{\"includeMatches\": false}"));
        assertFalse(LogEventsConfig.includesMatches("{}"));
        assertFalse(LogEventsConfig.includesMatches("{not json"));
        assertFalse(LogEventsConfig.includesMatches(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"TRUE\"", "\" True \"", "false", "\"false\"", "\"yes\"", "1", "null"})
    void includesMatchesReadsTheOptionTheWayValidationAcceptsIt(String value) {
        String config = "{\"includeMatches\": " + value + "}";
        boolean accepted;
        boolean saved;
        try {
            saved = LogEventsConfig.parse(config).includeMatches();
            accepted = true;
        } catch (IllegalArgumentException rejected) {
            saved = false;
            accepted = false;
        }
        assertEquals(saved, LogEventsConfig.includesMatches(config),
                value + (accepted ? " is accepted on save" : " is refused on save, so it reads as off"));
    }

    @Test
    void theJsonNodeFormIsWhatMonitorServiceCanCall() throws Exception {
        LogEventsConfig config = LogEventsConfig.parse(Json.mapper().readTree("{\"threshold\": 4, \"pattern\": \"ORA-\\\\d{5}\"}"));
        assertEquals(4, config.threshold());
        assertTrue(config.pattern().matcher("ORA-12345").find());
        assertThrows(IllegalArgumentException.class, () -> LogEventsConfig.parse((JsonNode) null));
    }

    @Test
    void theRuntimePathSkipsABrokenMonitorInsteadOfThrowing() {
        assertNull(LogEventsConfig.lenient(9101, "{\"threshold\": 0}"));
        assertNull(LogEventsConfig.lenient(9101, "{\"threshold\": 0}"), "the same broken config again");
        assertNull(LogEventsConfig.lenient(9101, "{not json"));
        LogEventsConfig fixed = LogEventsConfig.lenient(9101, "{\"threshold\": 2}");
        assertNotNull(fixed);
        assertEquals(2, fixed.threshold());
        assertNotNull(LogEventsConfig.lenient(9102, null), "no config means defaults");
    }
}
