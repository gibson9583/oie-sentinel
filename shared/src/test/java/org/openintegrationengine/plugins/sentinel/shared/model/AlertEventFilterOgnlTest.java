/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

// MyBatis 3.1.1 — the version the engine ships at server-lib/mybatis-3.1.1.jar,
// and what pom.xml deliberately pins to. Note the package: `<if test>` is
// evaluated by org.apache.ibatis.builder.xml.dynamic.ExpressionEvaluator on
// this line; only from 3.2 does it move to scripting.xmltags. Importing the
// modern package here would not compile, which is a useful canary if the
// engine's MyBatis is ever bumped.
import org.apache.ibatis.builder.xml.dynamic.ExpressionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for a bug that disabled two shipped features without ever
 * failing a build: <b>flap detection never ran</b>, and the Prometheus
 * endpoint's per-severity counts always failed.
 *
 * <h2>What happened</h2>
 *
 * <p>Both mappers guard their {@code IN} clauses with OGNL of the form
 * {@code <if test="channelIdIn != null and !channelIdIn.isEmpty()">}. OGNL
 * resolves {@code isEmpty()} reflectively against the <em>concrete</em> class
 * rather than the {@link List} interface, and {@code List.of(...)} returns
 * {@code java.util.ImmutableCollections$List12} — a class inside {@code
 * java.base} that the module system does not export. The reflective call
 * therefore throws {@code IllegalAccessException}, MyBatis wraps it as a
 * {@code PersistenceException}, and the repository reports a failed query.</p>
 *
 * <p>{@code AlertStormControl} passed {@code List.of(channelId)} and {@code
 * MetricsService} passed {@code List.of(severity)}. Neither showed up in the
 * suite because every existing test mocks the repository, so nothing before
 * this class evaluated a real OGNL expression at all — the bug lived
 * exclusively in the gap between the DTO and MyBatis.</p>
 *
 * <h2>Why these tests use MyBatis rather than assert on the field</h2>
 *
 * <p>Asserting that the setter stored an {@link ArrayList} would pass without
 * proving anything about the failure: the defect was never "the wrong list
 * type" in the abstract, it was "OGNL cannot reflect on this list type". So
 * these drive {@link ExpressionEvaluator} — the very class MyBatis's
 * {@code <if test="...">} uses — against the real guard expressions copied from
 * the mappers. Revert the copies in the setters and these fail with the
 * original {@code IllegalAccessException}.</p>
 */
@DisplayName("AlertEventFilter ↔ MyBatis OGNL")
class AlertEventFilterOgnlTest {

    /** The guard expressions verbatim from the vendor mappers. */
    private static final String CHANNEL_GUARD = "channelIdIn != null and !channelIdIn.isEmpty()";
    private static final String SEVERITY_GUARD = "severityIn != null and !severityIn.isEmpty()";

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    /** Evaluates a mapper guard exactly as {@code <if test="...">} does. */
    private boolean guard(String expression, Object parameterObject) {
        return evaluator.evaluateBoolean(expression, parameterObject);
    }

    @Nested
    @DisplayName("channelIdIn")
    class ChannelIdIn {

        @Test
        @DisplayName("a List.of() argument survives the mapper guard — the flap-detection regression")
        void listOfIsUsable() {
            AlertEventFilter filter = new AlertEventFilter();
            // Exactly what AlertStormControl.identityHistory passes. One
            // element, so List.of returns ImmutableCollections$List12.
            filter.setChannelIdIn(List.of("a4563a16-bb13-4325-8fdf-d5a8686e292c"));

            assertDoesNotThrow(() -> guard(CHANNEL_GUARD, filter),
                    "the mapper guard must not throw for a List.of() argument");
            assertTrue(guard(CHANNEL_GUARD, filter), "a one-element filter must be applied, not skipped");
        }

        @Test
        @DisplayName("the two-element List.of shape is covered too")
        void listOfTwoIsUsable() {
            AlertEventFilter filter = new AlertEventFilter();
            // List.of(a) and List.of(a, b) are BOTH List12; List.of(a, b, c)
            // switches to ListN. Different class, same inaccessibility.
            filter.setChannelIdIn(List.of("one", "two"));
            assertTrue(guard(CHANNEL_GUARD, filter));
        }

        @Test
        @DisplayName("the many-element List.of shape (ListN) is covered too")
        void listOfManyIsUsable() {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setChannelIdIn(List.of("one", "two", "three", "four"));
            assertTrue(guard(CHANNEL_GUARD, filter));
        }

        @Test
        @DisplayName("other unexported JDK list types are covered by the same copy")
        void otherImmutableShapesAreUsable() {
            // Arrays.asList -> Arrays$ArrayList, singletonList ->
            // Collections$SingletonList, unmodifiableList -> Collections$
            // UnmodifiableRandomAccessList. All package-private in java.base,
            // all previously fatal here.
            for (List<String> source : List.of(
                    Arrays.asList("x", "y"),
                    Collections.singletonList("x"),
                    Collections.unmodifiableList(new ArrayList<>(List.of("x"))))) {
                AlertEventFilter filter = new AlertEventFilter();
                filter.setChannelIdIn(source);
                assertTrue(guard(CHANNEL_GUARD, filter),
                        "guard failed for " + source.getClass().getName());
            }
        }

        @Test
        @DisplayName("an empty list still skips the clause")
        void emptySkipsTheClause() {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setChannelIdIn(List.of());
            // The copy must not turn "no ids" into an IN () that matches
            // nothing — the guard has to keep answering false.
            assertFalse(guard(CHANNEL_GUARD, filter));
        }

        @Test
        @DisplayName("null stays null and skips the clause")
        void nullSkipsTheClause() {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setChannelIdIn(null);
            assertFalse(guard(CHANNEL_GUARD, filter));
        }
    }

    @Nested
    @DisplayName("severityIn")
    class SeverityIn {

        @Test
        @DisplayName("a List.of() argument survives the mapper guard — the /metrics regression")
        void listOfIsUsable() {
            AlertEventFilter filter = new AlertEventFilter();
            // Exactly what MetricsService passes, once per severity.
            filter.setSeverityIn(List.of(Severity.DISASTER));

            assertDoesNotThrow(() -> guard(SEVERITY_GUARD, filter));
            assertTrue(guard(SEVERITY_GUARD, filter));
        }

        @Test
        @DisplayName("an empty list still skips the clause")
        void emptySkipsTheClause() {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setSeverityIn(List.of());
            assertFalse(guard(SEVERITY_GUARD, filter));
        }
    }

    @Nested
    @DisplayName("the copy itself")
    class DefensiveCopy {

        @Test
        @DisplayName("both setters store a plain ArrayList, whatever they were given")
        void storesArrayList() {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setChannelIdIn(List.of("a"));
            filter.setSeverityIn(List.of(Severity.HIGH));
            // Not a style assertion: ArrayList is exported from java.base, and
            // being exported is the entire property the mappers depend on.
            assertInstanceOf(ArrayList.class, filter.getChannelIdIn());
            assertInstanceOf(ArrayList.class, filter.getSeverityIn());
        }

        @Test
        @DisplayName("contents and order are preserved")
        void preservesContents() {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setChannelIdIn(List.of("first", "second", "third"));
            assertTrue(filter.getChannelIdIn().equals(List.of("first", "second", "third")));
        }

        @Test
        @DisplayName("a later mutation of the caller's list does not reach the filter")
        void isolatesFromTheCaller() {
            List<String> source = new ArrayList<>(List.of("kept"));
            AlertEventFilter filter = new AlertEventFilter();
            filter.setChannelIdIn(source);
            source.add("added afterwards");
            assertTrue(filter.getChannelIdIn().size() == 1,
                    "the filter must not see mutations made after the setter ran");
        }

        @Test
        @DisplayName("a Set-backed collection is accepted through the List contract")
        void acceptsOrderedSetContents() {
            // ProblemService builds its channel filter from a LinkedHashSet;
            // this is the shape that already worked, pinned so the copy cannot
            // regress it.
            AlertEventFilter filter = new AlertEventFilter();
            filter.setChannelIdIn(new ArrayList<>(new LinkedHashSet<>(List.of("b", "a"))));
            assertTrue(guard(CHANNEL_GUARD, filter));
        }
    }
}
