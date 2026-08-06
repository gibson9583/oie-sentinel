/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * Tests for the action condition language — the routing decision that picks
 * which of an installation's actions actually fire for a given alert.
 *
 * <p>Two properties are worth more than the rest and get the most cases here.
 * The first is the {@code >=} severity threshold, which is implemented on
 * {@link Severity} ordinals: "notify me for HIGH and worse" is the single most
 * common condition an operator writes, and it is only correct as long as the
 * enum's declaration order stays the severity order.</p>
 *
 * <p>The second is the fail-closed error policy. An unknown field, an unknown
 * operator, unparseable JSON, a non-array root, or a scalar where an array
 * belongs must all evaluate to "no match" rather than being ignored. The
 * failure mode this prevents is the dangerous one: a condition Sentinel cannot
 * understand degrading into "always fire", which would route a DISASTER page
 * into whatever quiet mailbox the operator had scoped away from it. A skipped
 * notification plus a warning in the log is the cheaper mistake, and these
 * tests pin that choice so a future "be more forgiving about malformed
 * conditions" change has to break a test to land.</p>
 *
 * <p>These are pure unit tests: the payload's package-private constructor is
 * used directly so nothing here touches the engine, a database, or
 * {@code ControllerFactory}. CHANNEL_GROUP and CHANNEL_TAG resolve membership
 * through {@code ScopeResolver} against live engine caches; those two fields'
 * matching semantics are covered in {@code ActionConditionMatcherScopeTest},
 * which isolates the resolver rather than starting an engine. What is covered
 * here for them is the null-channel short circuit, which returns before the
 * resolver is ever consulted.</p>
 */
@DisplayName("ActionConditionMatcher.matches")
class ActionConditionMatcherTest {

    /** Channel ids are opaque engine UUIDs; a realistic one keeps the case-sensitivity cases honest. */
    private static final String CHANNEL_ID = "1c8f0a7e-5b3d-4d21-9a6f-2e7c4b91d0aa";
    private static final String OTHER_CHANNEL_ID = "9d4e6b12-0a77-4c58-8f3b-6ac1de205f47";

    /** The baseline payload every case varies from: a HIGH inactivity problem on a known channel. */
    private static final AlertPayload HIGH_INACTIVITY =
            payload(Severity.HIGH, MonitorType.INACTIVITY, 7, CHANNEL_ID, "PROBLEM");

    /**
     * Builds an action carrying the given condition. Id and name are set
     * because the matcher's warning messages interpolate them, so a matcher
     * that NPEs while logging a rejection would still fail these tests.
     */
    private static Action action(String conditionJson) {
        Action action = new Action();
        action.setId(42);
        action.setName("Page on-call");
        action.setConditionJson(conditionJson);
        return action;
    }

    /**
     * Builds a payload directly through the package-private constructor —
     * {@code AlertPayload.of} resolves the channel name from the live engine
     * cache, which is exactly the dependency these tests exist to avoid.
     */
    private static AlertPayload payload(Severity severity, MonitorType monitorType, int monitorId,
            String channelId, String eventType) {
        return new AlertPayload(1001L, monitorId, "Nightly ADT feed", monitorType, channelId,
                "ADT Inbound", null, severity, eventType, "No messages received in 30m",
                Instant.parse("2026-01-01T03:00:00Z"), null);
    }

    /**
     * Condition JSON written with single quotes for legibility inline; JSON
     * has no single-quoted string form, so the substitution is unambiguous.
     */
    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    /** Evaluates a condition against the baseline payload. */
    private static boolean matches(String conditionJson) {
        return matches(conditionJson, HIGH_INACTIVITY);
    }

    private static boolean matches(String conditionJson, AlertPayload payload) {
        return ActionConditionMatcher.matches(action(json(conditionJson)), payload);
    }

    @Nested
    @DisplayName("unconditional actions")
    class Unconditional {

        @Test
        @DisplayName("null condition matches everything")
        void nullConditionMatches() {
            // An unconditional action is the common case and must need zero
            // configuration — the UI stores nothing when no condition rows
            // are added.
            assertTrue(ActionConditionMatcher.matches(action(null), HIGH_INACTIVITY));
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   ", "\n\t " })
        @DisplayName("empty and blank conditions match everything")
        void blankConditionMatches(String conditionJson) {
            // Blank is treated as absent rather than as malformed: a
            // round-trip through a text column or an over-eager UI can turn
            // "no condition" into an empty string, and that must not silently
            // stop an action from ever firing.
            assertTrue(ActionConditionMatcher.matches(action(conditionJson), HIGH_INACTIVITY));
        }

        @Test
        @DisplayName("an empty row array matches everything")
        void emptyArrayMatches() {
            assertTrue(matches("[]"));
        }

        @Test
        @DisplayName("a JSON null literal matches everything")
        void jsonNullMatches() {
            assertTrue(matches("null"));
        }

        @Test
        @DisplayName("a null action matches")
        void nullActionMatches() {
            // Defensive: the dispatcher never passes null, but the matcher
            // reads the condition off the action before anything else, so a
            // null must not become an NPE inside the dispatch loop.
            assertTrue(ActionConditionMatcher.matches(null, HIGH_INACTIVITY));
        }
    }

    @Nested
    @DisplayName("SEVERITY >= threshold")
    class SeverityThreshold {

        @Test
        @DisplayName("Severity declaration order is the severity order")
        void declarationOrderIsSeverityOrder() {
            // >= compares ordinals, so this enum's declaration order is
            // load-bearing for every stored threshold condition in every
            // installation. Reordering it would silently reinterpret existing
            // action rows with no migration and no error — pinned here
            // because this is where the dependency actually lives.
            assertArrayEquals(
                    new Severity[] { Severity.INFORMATION, Severity.WARNING, Severity.AVERAGE,
                            Severity.HIGH, Severity.DISASTER },
                    Severity.values());
        }

        @ParameterizedTest
        @CsvSource({ "INFORMATION, false", "WARNING, false", "AVERAGE, true", "HIGH, true",
                "DISASTER, true" })
        @DisplayName("'>= AVERAGE' admits AVERAGE and everything above it")
        void thresholdIsInclusiveAndAscending(Severity actual, boolean expected) {
            AlertPayload p = payload(actual, MonitorType.INACTIVITY, 7, CHANNEL_ID, "PROBLEM");
            assertEquals(expected,
                    matches("[{'field':'SEVERITY','operator':'>=','value':'AVERAGE'}]", p));
        }

        @Test
        @DisplayName("'>= INFORMATION' admits every severity")
        void lowestThresholdAdmitsAll() {
            for (Severity severity : Severity.values()) {
                AlertPayload p = payload(severity, MonitorType.INACTIVITY, 7, CHANNEL_ID, "PROBLEM");
                assertTrue(matches("[{'field':'SEVERITY','operator':'>=','value':'INFORMATION'}]", p),
                        severity + " should clear the INFORMATION threshold");
            }
        }

        @Test
        @DisplayName("'>= DISASTER' admits only DISASTER")
        void highestThresholdAdmitsOnlyTheTop() {
            assertFalse(matches("[{'field':'SEVERITY','operator':'>=','value':'DISASTER'}]"));
            assertTrue(matches("[{'field':'SEVERITY','operator':'>=','value':'DISASTER'}]",
                    payload(Severity.DISASTER, MonitorType.INACTIVITY, 7, CHANNEL_ID, "PROBLEM")));
        }

        @Test
        @DisplayName("threshold names are case-insensitive")
        void thresholdIsCaseInsensitive() {
            // Condition JSON is hand-editable, and "high" unambiguously means
            // HIGH.
            assertTrue(matches("[{'field':'SEVERITY','operator':'>=','value':'high'}]"));
        }

        @Test
        @DisplayName("an unknown severity name fails the condition")
        void unknownSeverityNameFailsClosed() {
            // "CRITICAL" is Zabbix-adjacent but not one of Sentinel's five.
            // Treating an unparseable threshold as "no threshold" would turn
            // a typo into an unconditional page.
            assertFalse(matches("[{'field':'SEVERITY','operator':'>=','value':'CRITICAL'}]"));
        }

        @Test
        @DisplayName("an array value where a scalar belongs fails the condition")
        void arrayValueForThresholdFailsClosed() {
            // The first element is deliberately NOT picked: guessing which of
            // several thresholds the operator meant is worse than refusing.
            assertFalse(
                    matches("[{'field':'SEVERITY','operator':'>=','value':['AVERAGE','HIGH']}]"));
        }

        @Test
        @DisplayName("a payload with no severity fails every severity row")
        void nullSeverityFailsClosed() {
            // Unlike MONITOR_TYPE below, SEVERITY short-circuits on a null
            // actual for all operators including '!=' — a severity condition
            // is a statement about a known severity, and there is no
            // defensible answer when there isn't one.
            AlertPayload noSeverity = payload(null, MonitorType.INACTIVITY, 7, CHANNEL_ID, "PROBLEM");
            assertFalse(matches("[{'field':'SEVERITY','operator':'>=','value':'WARNING'}]", noSeverity));
            assertFalse(matches("[{'field':'SEVERITY','operator':'=','value':'HIGH'}]", noSeverity));
            assertFalse(matches("[{'field':'SEVERITY','operator':'!=','value':'HIGH'}]", noSeverity));
        }
    }

    @Nested
    @DisplayName("SEVERITY =, != and IN")
    class SeverityEquality {

        @Test
        @DisplayName("'=' matches the exact severity only")
        void equalsMatchesExactly() {
            assertTrue(matches("[{'field':'SEVERITY','operator':'=','value':'HIGH'}]"));
            assertFalse(matches("[{'field':'SEVERITY','operator':'=','value':'WARNING'}]"));
            // '=' is emphatically not the threshold operator: DISASTER does
            // not satisfy '= HIGH', which is the mistake '>=' exists to fix.
            assertFalse(matches("[{'field':'SEVERITY','operator':'=','value':'HIGH'}]",
                    payload(Severity.DISASTER, MonitorType.INACTIVITY, 7, CHANNEL_ID, "PROBLEM")));
        }

        @Test
        @DisplayName("'!=' excludes the named severity")
        void notEqualsExcludes() {
            assertTrue(matches("[{'field':'SEVERITY','operator':'!=','value':'WARNING'}]"));
            assertFalse(matches("[{'field':'SEVERITY','operator':'!=','value':'HIGH'}]"));
        }

        @Test
        @DisplayName("'IN' matches any listed severity")
        void inMatchesAnyMember() {
            assertTrue(matches("[{'field':'SEVERITY','operator':'IN','value':['HIGH','DISASTER']}]"));
            assertFalse(
                    matches("[{'field':'SEVERITY','operator':'IN','value':['INFORMATION','WARNING']}]"));
        }

        @Test
        @DisplayName("'IN' accepts a bare scalar as a one-element list")
        void inToleratesScalarValue() {
            // Forgiving the common hand-edit of writing a single value with
            // IN. This is tolerance of a well-understood shape, not of an
            // unparseable one — the fail-closed rule still governs everything
            // the matcher cannot interpret.
            assertTrue(matches("[{'field':'SEVERITY','operator':'IN','value':'HIGH'}]"));
            assertFalse(matches("[{'field':'SEVERITY','operator':'IN','value':'WARNING'}]"));
        }

        @Test
        @DisplayName("an empty 'IN' list matches nothing")
        void emptyInListMatchesNothing() {
            // An empty multi-select selects nothing, so it should fire for
            // nothing — not for everything, which is what a "no constraints"
            // reading would produce.
            assertFalse(matches("[{'field':'SEVERITY','operator':'IN','value':[]}]"));
        }

        @ParameterizedTest
        @ValueSource(strings = { ">", "<", "<=", "==", "LIKE", "NOT IN", "" })
        @DisplayName("an unsupported severity operator fails the condition")
        void unknownOperatorFailsClosed(String operator) {
            assertFalse(matches(
                    "[{'field':'SEVERITY','operator':'" + operator + "','value':'HIGH'}]"));
        }

        @Test
        @DisplayName("operators and field names are case- and whitespace-tolerant")
        void operatorAndFieldParsingIsTolerant() {
            // Hand-written condition JSON is a supported authoring path, so
            // "severity"/"in" are read the same as "SEVERITY"/"IN".
            assertTrue(matches("[{'field':'severity','operator':' in ','value':['HIGH']}]"));
        }
    }

    @Nested
    @DisplayName("MONITOR_TYPE")
    class MonitorTypeField {

        @Test
        @DisplayName("'=' matches the payload's monitor type")
        void equalsMatchesType() {
            assertTrue(matches("[{'field':'MONITOR_TYPE','operator':'=','value':'INACTIVITY'}]"));
            assertFalse(matches("[{'field':'MONITOR_TYPE','operator':'=','value':'LOW_VOLUME'}]"));
        }

        @Test
        @DisplayName("'!=' excludes the named monitor type")
        void notEqualsExcludesType() {
            assertTrue(matches("[{'field':'MONITOR_TYPE','operator':'!=','value':'ANOMALY'}]"));
            assertFalse(matches("[{'field':'MONITOR_TYPE','operator':'!=','value':'INACTIVITY'}]"));
        }

        @Test
        @DisplayName("'IN' matches any listed monitor type")
        void inMatchesAnyType() {
            assertTrue(matches(
                    "[{'field':'MONITOR_TYPE','operator':'IN','value':['INACTIVITY','ANOMALY']}]"));
            assertFalse(matches(
                    "[{'field':'MONITOR_TYPE','operator':'IN','value':['LOW_VOLUME','CONNECTION_STATUS']}]"));
        }

        @Test
        @DisplayName("a deleted monitor's null type cannot satisfy '=' but does satisfy '!='")
        void nullMonitorTypeAsymmetry() {
            // A payload built for a monitor deleted between the alert opening
            // and the RESOLVED dispatch carries a null type. '=' fails, which
            // is the fail-closed reading. '!=' succeeds, which is deliberate
            // and different from SEVERITY's blanket short-circuit: the
            // operator's intent in "not CONNECTION_STATUS" is an exclusion,
            // and an unknown type is not the excluded one, so the resolve
            // notification still goes out.
            AlertPayload deletedMonitor = payload(Severity.HIGH, null, 7, CHANNEL_ID, "RESOLVED");
            assertFalse(matches("[{'field':'MONITOR_TYPE','operator':'=','value':'INACTIVITY'}]",
                    deletedMonitor));
            assertTrue(matches("[{'field':'MONITOR_TYPE','operator':'!=','value':'INACTIVITY'}]",
                    deletedMonitor));
            assertFalse(matches("[{'field':'MONITOR_TYPE','operator':'IN','value':['INACTIVITY']}]",
                    deletedMonitor));
        }
    }

    @Nested
    @DisplayName("MONITOR (multi-select of monitor ids)")
    class MonitorField {

        @Test
        @DisplayName("'IN' with JSON numbers matches the payload's monitor id")
        void inWithNumericIds() {
            // The UI's monitor condition is a multi-select, and its ids
            // arrive as JSON numbers. The matcher compares them as text, so
            // this case is what proves numbers are not silently dropped.
            assertTrue(matches("[{'field':'MONITOR','operator':'IN','value':[7,9]}]"));
            assertFalse(matches("[{'field':'MONITOR','operator':'IN','value':[1,2]}]"));
        }

        @Test
        @DisplayName("'IN' with JSON strings matches the same ids")
        void inWithStringIds() {
            // Hand-edited conditions and older UI builds write the ids
            // quoted; both spellings have to mean the same monitor.
            assertTrue(matches("[{'field':'MONITOR','operator':'IN','value':['7','9']}]"));
            assertFalse(matches("[{'field':'MONITOR','operator':'IN','value':['1','2']}]"));
        }

        @Test
        @DisplayName("'IN' mixing numbers and strings matches either spelling")
        void inWithMixedIdSpellings() {
            assertTrue(matches("[{'field':'MONITOR','operator':'IN','value':['4',7]}]"));
        }

        @Test
        @DisplayName("id comparison is whole-value, not a prefix")
        void idComparisonIsExact() {
            // Text comparison of digit strings is exact, but only as long as
            // nothing introduces a startsWith/contains shortcut: monitor 7
            // must not match a condition selecting monitor 70.
            assertFalse(matches("[{'field':'MONITOR','operator':'IN','value':[70,77]}]"));
            assertFalse(matches("[{'field':'MONITOR','operator':'=','value':'77'}]"));
        }

        @Test
        @DisplayName("'=' and '!=' work on a single monitor id")
        void equalityOnSingleId() {
            assertTrue(matches("[{'field':'MONITOR','operator':'=','value':7}]"));
            assertTrue(matches("[{'field':'MONITOR','operator':'=','value':'7'}]"));
            assertFalse(matches("[{'field':'MONITOR','operator':'=','value':8}]"));
            assertTrue(matches("[{'field':'MONITOR','operator':'!=','value':8}]"));
            assertFalse(matches("[{'field':'MONITOR','operator':'!=','value':7}]"));
        }

        @Test
        @DisplayName("an empty monitor multi-select matches nothing")
        void emptySelectionMatchesNothing() {
            assertFalse(matches("[{'field':'MONITOR','operator':'IN','value':[]}]"));
        }
    }

    @Nested
    @DisplayName("CHANNEL")
    class ChannelField {

        @Test
        @DisplayName("'=' matches the exact channel id")
        void equalsMatchesId() {
            assertTrue(matches("[{'field':'CHANNEL','operator':'=','value':'" + CHANNEL_ID + "'}]"));
            assertFalse(
                    matches("[{'field':'CHANNEL','operator':'=','value':'" + OTHER_CHANNEL_ID + "'}]"));
        }

        @Test
        @DisplayName("channel id matching is case-sensitive")
        void idMatchingIsCaseSensitive() {
            // Unlike the enum-name fields, channel ids are opaque UUIDs
            // rather than anything a human types, so case folding here would
            // only widen matching for no benefit.
            assertFalse(matches("[{'field':'CHANNEL','operator':'=','value':'"
                    + CHANNEL_ID.toUpperCase(Locale.ROOT) + "'}]"));
        }

        @Test
        @DisplayName("'IN' matches any listed channel id")
        void inMatchesAnyListedId() {
            assertTrue(matches("[{'field':'CHANNEL','operator':'IN','value':['" + OTHER_CHANNEL_ID
                    + "','" + CHANNEL_ID + "']}]"));
            assertFalse(matches(
                    "[{'field':'CHANNEL','operator':'IN','value':['" + OTHER_CHANNEL_ID + "']}]"));
        }

        @Test
        @DisplayName("'!=' excludes the named channel")
        void notEqualsExcludesChannel() {
            assertTrue(matches(
                    "[{'field':'CHANNEL','operator':'!=','value':'" + OTHER_CHANNEL_ID + "'}]"));
            assertFalse(matches("[{'field':'CHANNEL','operator':'!=','value':'" + CHANNEL_ID + "'}]"));
        }

        @Test
        @DisplayName("a payload with no channel cannot satisfy '=' or 'IN'")
        void nullChannelFailsClosed() {
            // Synthetic test-send payloads carry no channel. A channel
            // condition on one must not match, or "Send test" would report a
            // delivery an actual alert would never make.
            AlertPayload noChannel = payload(Severity.HIGH, MonitorType.INACTIVITY, 7, null, "TEST");
            assertFalse(
                    matches("[{'field':'CHANNEL','operator':'=','value':'" + CHANNEL_ID + "'}]",
                            noChannel));
            assertFalse(
                    matches("[{'field':'CHANNEL','operator':'IN','value':['" + CHANNEL_ID + "']}]",
                            noChannel));
        }

        @ParameterizedTest
        @ValueSource(strings = { "LIKE", ">=", "CONTAINS" })
        @DisplayName("an unsupported channel operator fails the condition")
        void unknownOperatorFailsClosed(String operator) {
            assertFalse(matches("[{'field':'CHANNEL','operator':'" + operator + "','value':'"
                    + CHANNEL_ID + "'}]"));
        }
    }

    @Nested
    @DisplayName("EVENT_TYPE")
    class EventTypeField {

        @Test
        @DisplayName("'=' matches the lifecycle edge being dispatched")
        void equalsMatchesEdge() {
            assertTrue(matches("[{'field':'EVENT_TYPE','operator':'=','value':'PROBLEM'}]"));
            assertFalse(matches("[{'field':'EVENT_TYPE','operator':'=','value':'RESOLVED'}]"));
            assertTrue(matches("[{'field':'EVENT_TYPE','operator':'=','value':'RESOLVED'}]",
                    payload(Severity.HIGH, MonitorType.INACTIVITY, 7, CHANNEL_ID, "RESOLVED")));
        }

        @Test
        @DisplayName("event type comparison is case-insensitive")
        void comparisonIsCaseInsensitive() {
            assertTrue(matches("[{'field':'EVENT_TYPE','operator':'=','value':'problem'}]"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "!=", "IN", ">=" })
        @DisplayName("EVENT_TYPE supports only '=' and fails closed on anything else")
        void onlyEqualsIsSupported(String operator) {
            // Lifecycle gating belongs to the action's operationMode; this
            // field is the narrow escape hatch for BOTH-mode actions, so its
            // grammar is deliberately one operator wide. Widening it by
            // accident (a '!=' that quietly evaluated as '=') would invert an
            // operator's routing.
            assertFalse(matches(
                    "[{'field':'EVENT_TYPE','operator':'" + operator + "','value':'PROBLEM'}]"));
        }
    }

    @Nested
    @DisplayName("multiple rows are ANDed")
    class MultipleRows {

        @Test
        @DisplayName("all rows matching matches")
        void allRowsMatch() {
            assertTrue(matches("[{'field':'SEVERITY','operator':'>=','value':'AVERAGE'},"
                    + "{'field':'MONITOR_TYPE','operator':'=','value':'INACTIVITY'},"
                    + "{'field':'EVENT_TYPE','operator':'=','value':'PROBLEM'}]"));
        }

        @Test
        @DisplayName("one failing row fails the whole condition")
        void oneFailingRowFails() {
            assertFalse(matches("[{'field':'SEVERITY','operator':'>=','value':'AVERAGE'},"
                    + "{'field':'MONITOR_TYPE','operator':'=','value':'ANOMALY'}]"));
        }

        @Test
        @DisplayName("row order does not change the outcome")
        void orderIsIrrelevant() {
            assertFalse(matches("[{'field':'MONITOR_TYPE','operator':'=','value':'ANOMALY'},"
                    + "{'field':'SEVERITY','operator':'>=','value':'AVERAGE'}]"));
        }

        @Test
        @DisplayName("an unintelligible row fails the condition even when every other row matches")
        void badRowPoisonsAMatchingCondition() {
            // The sharpest form of the fail-closed rule: a condition that is
            // 90% understood is still not understood. Evaluating only the
            // rows the matcher recognizes would quietly broaden the action's
            // scope to whatever the unreadable row was meant to narrow.
            assertFalse(matches("[{'field':'SEVERITY','operator':'>=','value':'AVERAGE'},"
                    + "{'field':'CHANNEL_NAME','operator':'=','value':'ADT Inbound'}]"));
        }
    }

    @Nested
    @DisplayName("fail-closed error policy")
    class FailClosed {

        @ParameterizedTest
        @ValueSource(strings = { "CHANNEL_NAME", "MONITOR_NAME", "MESSAGE", "TAG", "SEVERITY_LEVEL",
                "CHANNEL_ID", "MONITOR_ID" })
        @DisplayName("an unknown field fails the condition")
        void unknownFieldFailsClosed(String field) {
            // Covers the forward-compatibility trap directly: if a newer
            // Sentinel adds a field and an action written against it is read
            // by an older server, the older server must decline to fire, not
            // fire unconditionally.
            assertFalse(matches("[{'field':'" + field + "','operator':'=','value':'x'}]"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "[{'field':'SEVERITY','operator':'=','value':'HIGH'",
                "[{'field':'SEVERITY',}]", "not json at all", "[{'field':}]", "{{" })
        @DisplayName("malformed condition JSON fails the condition")
        void malformedJsonFailsClosed(String conditionJson) {
            assertFalse(matches(conditionJson));
        }

        @ParameterizedTest
        @ValueSource(strings = { "{'field':'SEVERITY','operator':'=','value':'HIGH'}", "'HIGH'", "5",
                "true" })
        @DisplayName("a non-array root fails the condition")
        void nonArrayRootFailsClosed(String conditionJson) {
            // A single row written without the enclosing array is the most
            // likely hand-edit mistake. Reading it as one row would be
            // convenient and would also mean the matcher's shape rules are
            // negotiable; it declines instead and logs.
            assertFalse(matches(conditionJson));
        }

        @ParameterizedTest
        @ValueSource(strings = { "['SEVERITY']", "[5]", "[null]", "[[]]", "[['SEVERITY','=','HIGH']]" })
        @DisplayName("a row that is not an object fails the condition")
        void nonObjectRowFailsClosed(String conditionJson) {
            assertFalse(matches(conditionJson));
        }

        @ParameterizedTest
        @ValueSource(strings = { "[{'operator':'=','value':'HIGH'}]",
                "[{'field':'SEVERITY','value':'HIGH'}]", "[{}]",
                "[{'field':null,'operator':'=','value':'HIGH'}]",
                "[{'field':'SEVERITY','operator':null,'value':'HIGH'}]",
                "[{'field':'  ','operator':'=','value':'HIGH'}]" })
        @DisplayName("a row missing its field or operator fails the condition")
        void incompleteRowFailsClosed(String conditionJson) {
            assertFalse(matches(conditionJson));
        }

        @ParameterizedTest
        @ValueSource(strings = { "[{'field':'SEVERITY','operator':'='}]",
                "[{'field':'SEVERITY','operator':'=','value':null}]",
                "[{'field':'SEVERITY','operator':'=','value':['HIGH']}]",
                "[{'field':'SEVERITY','operator':'=','value':{'name':'HIGH'}}]",
                "[{'field':'CHANNEL','operator':'!=','value':[]}]" })
        @DisplayName("a scalar-valued operator given a container (or nothing) fails the condition")
        void wrongValueShapeFailsClosed(String conditionJson) {
            // '=' / '!=' / '>=' take one value. An array here means the
            // operator and the value disagree about the operator's arity, and
            // picking an element to proceed with would be a guess.
            assertFalse(matches(conditionJson));
        }

        @Test
        @DisplayName("an 'IN' whose value is an object matches nothing")
        void inWithObjectValueMatchesNothing() {
            assertFalse(matches("[{'field':'SEVERITY','operator':'IN','value':{'0':'HIGH'}}]"));
        }

        @Test
        @DisplayName("a channel-scoped field cannot match a payload with no channel")
        void channelScopedFieldsShortCircuitOnNullChannel() {
            // CHANNEL_GROUP and CHANNEL_TAG normally consult ScopeResolver
            // against live engine caches, but both return before that when
            // the payload carries no channel — which is why this one case is
            // engine-free and belongs here rather than in the isolated
            // resolver test.
            AlertPayload noChannel = payload(Severity.HIGH, MonitorType.INACTIVITY, 7, null, "TEST");
            assertFalse(matches("[{'field':'CHANNEL_GROUP','operator':'=','value':'Production'}]",
                    noChannel));
            assertFalse(matches("[{'field':'CHANNEL_TAG','operator':'IN','value':['pii']}]",
                    noChannel));
        }
    }
}
