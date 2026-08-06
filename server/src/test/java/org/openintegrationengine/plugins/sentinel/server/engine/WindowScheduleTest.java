/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowRepeat;

/**
 * Schedule math for maintenance windows and alerting schedules — the decision
 * that silences (or unsilences) every alert a window covers.
 *
 * <p>Three properties carry most of the weight here.</p>
 *
 * <p><b>The window's own timezone wins over the server's.</b> Schema v3 moved
 * recurring windows off {@code ZoneId.systemDefault()} and onto a per-window
 * IANA zone, so a 22:00–06:00 overnight window stays 22:00–06:00 <em>on the
 * on-call rotation's clock</em> across both DST transitions instead of
 * drifting an hour twice a year because the server sits in UTC. Every case in
 * this class runs with the JVM default zone pinned to UTC, which is both the
 * usual container default and — crucially — a zone with no transitions at
 * all: if the schedule math ever leaked back onto the server zone, the DST
 * cases below could not observe a transition and would fail.</p>
 *
 * <p><b>Fallback, not failure, for a bad zone.</b> A null or blank timezone
 * means "the server's zone", because that is exactly what every row written
 * before v3 carries and those rows must keep behaving identically. An
 * <em>unparseable</em> zone falls back the same way rather than declaring the
 * window malformed — see {@link Malformed} for why a {@code null} verdict is
 * the more expensive mistake.</p>
 *
 * <p><b>Malformed means {@code null}, not {@code false}.</b> The three-state
 * return exists because the right reaction to a broken row depends on the
 * window's mode, and only the caller knows the mode:
 * {@code TriggerEvaluatorJob.suppressedByWindows} treats {@code null} as "does
 * not suppress" for a SUPPRESS window and as "inside the schedule" for an
 * ACTIVE one — both directions letting alerts through. Collapsing {@code null}
 * into {@code false} inside this class would take that choice away and mute
 * every channel a broken ACTIVE schedule covers.</p>
 *
 * <p>Pure unit tests: {@link WindowSchedule} is a stateless static utility
 * over a DTO, so nothing here touches the engine, a database, or a clock other
 * than the instants each case names explicitly.</p>
 */
@DisplayName("WindowSchedule.isActiveNow")
class WindowScheduleTest {

    /** The zone the DST cases exercise; -05:00 in winter, -04:00 in summer. */
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /**
     * 2026's US transitions. Spring forward: 02:00 EST on this date does not
     * exist (the clock jumps to 03:00 EDT).
     */
    private static final LocalDate SPRING_FORWARD = LocalDate.of(2026, 3, 8);

    /** Fall back: 01:00–02:00 local occurs twice on this date (EDT, then EST). */
    private static final LocalDate FALL_BACK = LocalDate.of(2026, 11, 1);

    private static final String ALL_DAYS =
            "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY";

    private static TimeZone originalDefault;

    /**
     * Pins the "server zone" for the whole class. Two of the properties under
     * test — pre-v3 fallback, and the window zone overriding the server zone —
     * are only expressible against a known default, and a developer laptop in
     * America/Chicago would otherwise make the DST cases pass for the wrong
     * reason. Restored in {@link #restoreServerZone()} because the default is
     * JVM-wide and surefire reuses one JVM across test classes.
     */
    @BeforeAll
    static void pinServerZoneToUtc() {
        originalDefault = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterAll
    static void restoreServerZone() {
        TimeZone.setDefault(originalDefault);
    }

    // ---------------------------------------------------------------- builders

    private static MaintenanceWindow weekly(String daysOfWeek, String start, String end, String timezone) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(1);
        window.setName("Nightly maintenance");
        window.setEnabled(true);
        window.setRepeatType(WindowRepeat.WEEKLY);
        window.setDaysOfWeek(daysOfWeek);
        window.setStartTime(start);
        window.setEndTime(end);
        window.setTimezone(timezone);
        return window;
    }

    private static MaintenanceWindow monthly(String daysOfMonth, String start, String end, String timezone) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(2);
        window.setName("Month-end batch");
        window.setEnabled(true);
        window.setRepeatType(WindowRepeat.MONTHLY);
        window.setDaysOfMonth(daysOfMonth);
        window.setStartTime(start);
        window.setEndTime(end);
        window.setTimezone(timezone);
        return window;
    }

    private static MaintenanceWindow oneTime(String from, String until) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(3);
        window.setName("Firewall change");
        window.setEnabled(true);
        window.setRepeatType(WindowRepeat.NONE);
        window.setActiveFrom(from != null ? Instant.parse(from) : null);
        window.setActiveUntil(until != null ? Instant.parse(until) : null);
        return window;
    }

    /** The daily overnight window every DST case is written around. */
    private static MaintenanceWindow overnightIn(String timezone) {
        return weekly(ALL_DAYS, "22:00", "06:00", timezone);
    }

    // -------------------------------------------------------------- assertions

    private static void assertActive(MaintenanceWindow window, String iso) {
        assertEquals(Boolean.TRUE, WindowSchedule.isActiveNow(window, Instant.parse(iso)),
                "expected active at " + iso + describeLocal(window, iso));
    }

    private static void assertInactive(MaintenanceWindow window, String iso) {
        assertEquals(Boolean.FALSE, WindowSchedule.isActiveNow(window, Instant.parse(iso)),
                "expected inactive at " + iso + describeLocal(window, iso));
    }

    /** Asserts the "cannot be parsed" verdict, which is {@code null} and never {@code false}. */
    private static void assertMalformed(MaintenanceWindow window, String iso) {
        assertNull(WindowSchedule.isActiveNow(window, Instant.parse(iso)),
                "a malformed schedule must return null, not a verdict, at " + iso);
    }

    /** Renders the instant on the window's own clock, so a failure message is readable. */
    private static String describeLocal(MaintenanceWindow window, String iso) {
        String zone = window.getTimezone() != null && !window.getTimezone().isBlank()
                ? window.getTimezone().trim() : ZoneId.systemDefault().getId();
        try {
            return " (" + Instant.parse(iso).atZone(ZoneId.of(zone)) + ")";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Every minute-aligned instant in {@code [from, toExclusive)} at which the
     * window is active. Sampling the real timeline a minute at a time is the
     * only way to observe what a DST transition does to a window: the
     * interesting quantity is how many <em>actual</em> minutes a fixed
     * local-clock schedule covers, and that is 60 fewer than usual on the
     * spring-forward night and 60 more on the fall-back night.
     */
    private static List<Instant> activeMinutes(MaintenanceWindow window, String from, String toExclusive) {
        List<Instant> hits = new ArrayList<>();
        Instant end = Instant.parse(toExclusive);
        for (Instant t = Instant.parse(from); t.isBefore(end); t = t.plus(Duration.ofMinutes(1))) {
            if (Boolean.TRUE.equals(WindowSchedule.isActiveNow(window, t))) {
                hits.add(t);
            }
        }
        return hits;
    }

    /** Asserts the hits form one unbroken run — no holes punched mid-window. */
    private static void assertContiguous(List<Instant> hits) {
        assertTrue(!hits.isEmpty(), "expected at least one active minute");
        long span = Duration.between(hits.get(0), hits.get(hits.size() - 1)).toMinutes() + 1;
        assertEquals(span, hits.size(), "active minutes are not one contiguous run");
    }

    private static ZonedDateTime inNewYork(Instant instant) {
        return instant.atZone(NEW_YORK);
    }

    @Nested
    @DisplayName("enablement and the absolute bounds")
    class EnablementAndBounds {

        @Test
        @DisplayName("a null window is not active")
        void nullWindowIsInactive() {
            // Defensive: a window list that picked up a null must not NPE
            // inside the evaluator tick, which would cost every other channel
            // its evaluation.
            assertEquals(Boolean.FALSE, WindowSchedule.isActiveNow(null, Instant.parse("2026-01-15T23:00:00Z")));
        }

        @Test
        @DisplayName("a disabled window is never active")
        void disabledWindowIsNeverActive() {
            // The disabled flag is how an operator parks a window without
            // deleting it, so it has to beat a schedule that would otherwise
            // match right now.
            MaintenanceWindow recurring = overnightIn("UTC");
            recurring.setEnabled(false);
            assertInactive(recurring, "2026-01-15T23:00:00Z");

            MaintenanceWindow once = oneTime("2026-01-15T00:00:00Z", "2026-01-16T00:00:00Z");
            once.setEnabled(false);
            assertInactive(once, "2026-01-15T12:00:00Z");
        }

        @Test
        @DisplayName("a recurring window before activeFrom is inactive")
        void beforeAbsoluteStartIsInactive() {
            MaintenanceWindow window = overnightIn("UTC");
            window.setActiveFrom(Instant.parse("2026-02-01T00:00:00Z"));
            assertInactive(window, "2026-01-15T23:00:00Z");
        }

        @Test
        @DisplayName("a recurring window after activeUntil is inactive")
        void afterAbsoluteEndIsInactive() {
            MaintenanceWindow window = overnightIn("UTC");
            window.setActiveUntil(Instant.parse("2026-01-01T00:00:00Z"));
            assertInactive(window, "2026-01-15T23:00:00Z");
        }

        @Test
        @DisplayName("bounds are outer limits, not the schedule, for a recurring window")
        void boundsAreOuterLimitsForRecurringWindows() {
            // Inside the absolute range the daily times still decide: a
            // three-month change freeze does not mean "suppressed for three
            // months", it means "suppressed 22:00–06:00 for three months".
            MaintenanceWindow window = overnightIn("UTC");
            window.setActiveFrom(Instant.parse("2026-01-01T00:00:00Z"));
            window.setActiveUntil(Instant.parse("2026-04-01T00:00:00Z"));
            assertActive(window, "2026-01-15T23:00:00Z");
            assertInactive(window, "2026-01-15T12:00:00Z");
        }

        @Test
        @DisplayName("both bounds are inclusive")
        void boundsAreInclusive() {
            // isBefore/isAfter, not !isAfter/!isBefore: the instant that
            // equals a bound is inside it.
            MaintenanceWindow window = overnightIn("UTC");
            window.setActiveFrom(Instant.parse("2026-01-15T23:00:00Z"));
            window.setActiveUntil(Instant.parse("2026-01-16T00:00:00Z"));
            assertActive(window, "2026-01-15T23:00:00Z");
            assertActive(window, "2026-01-16T00:00:00Z");
            assertInactive(window, "2026-01-15T22:59:00Z");
        }

        @Test
        @DisplayName("null bounds mean unbounded in that direction")
        void nullBoundsAreUnbounded() {
            MaintenanceWindow window = overnightIn("UTC");
            assertActive(window, "1999-01-15T23:00:00Z");
            assertActive(window, "2099-01-15T23:00:00Z");
        }
    }

    @Nested
    @DisplayName("one-time windows (repeat NONE)")
    class OneTime {

        @Test
        @DisplayName("inside the absolute range is active")
        void insideRangeIsActive() {
            MaintenanceWindow window = oneTime("2026-01-15T02:00:00Z", "2026-01-15T06:00:00Z");
            assertActive(window, "2026-01-15T02:00:00Z");
            assertActive(window, "2026-01-15T04:00:00Z");
            assertActive(window, "2026-01-15T06:00:00Z");
        }

        @Test
        @DisplayName("outside the absolute range is inactive")
        void outsideRangeIsInactive() {
            MaintenanceWindow window = oneTime("2026-01-15T02:00:00Z", "2026-01-15T06:00:00Z");
            assertInactive(window, "2026-01-15T01:59:59Z");
            assertInactive(window, "2026-01-15T06:00:01Z");
        }

        @Test
        @DisplayName("a null repeat type is read as one-time")
        void nullRepeatTypeIsOneTime() {
            // Rows written before recurrence existed carry a null
            // repeat_type; they were one-time windows and must stay so.
            MaintenanceWindow window = oneTime("2026-01-15T02:00:00Z", "2026-01-15T06:00:00Z");
            window.setRepeatType(null);
            assertActive(window, "2026-01-15T04:00:00Z");
            assertInactive(window, "2026-01-15T08:00:00Z");
        }

        @Test
        @DisplayName("daily times and timezone are irrelevant to a one-time window")
        void dailyFieldsAreIgnored() {
            // One-time windows compare absolute instants, so they never need a
            // zone — the service blanks the field for them. Nothing here may
            // be parsed, which this case proves by making all of it garbage
            // and still expecting a verdict rather than a malformed null.
            MaintenanceWindow window = oneTime("2026-01-15T02:00:00Z", "2026-01-15T06:00:00Z");
            window.setStartTime("not-a-time");
            window.setEndTime("");
            window.setTimezone("Not/AZone");
            window.setDaysOfWeek("FUNDAY");
            assertActive(window, "2026-01-15T04:00:00Z");
        }

        @Test
        @DisplayName("a one-time window with no range is malformed")
        void missingRangeIsMalformed() {
            // An unbounded one-time window would be "always active" — which
            // for a SUPPRESS window means permanent silence. Validation
            // forbids it; a row that reached the database anyway is flagged
            // rather than guessed at.
            assertMalformed(oneTime(null, "2026-01-15T06:00:00Z"), "2026-01-15T04:00:00Z");
            assertMalformed(oneTime("2026-01-15T02:00:00Z", null), "2026-01-15T04:00:00Z");
            assertMalformed(oneTime(null, null), "2026-01-15T04:00:00Z");
        }
    }

    @Nested
    @DisplayName("WEEKLY recurrence")
    class Weekly {

        @Test
        @DisplayName("a matching day inside the daily times is active")
        void matchingDayInsideTimesIsActive() {
            // 2026-01-15 is a Thursday.
            MaintenanceWindow window = weekly("THURSDAY", "09:00", "17:00", "UTC");
            assertActive(window, "2026-01-15T12:00:00Z");
        }

        @Test
        @DisplayName("a matching day outside the daily times is inactive")
        void matchingDayOutsideTimesIsInactive() {
            MaintenanceWindow window = weekly("THURSDAY", "09:00", "17:00", "UTC");
            assertInactive(window, "2026-01-15T08:59:00Z");
            assertInactive(window, "2026-01-15T18:00:00Z");
        }

        @Test
        @DisplayName("a non-matching day is inactive at the same time of day")
        void nonMatchingDayIsInactive() {
            MaintenanceWindow window = weekly("THURSDAY", "09:00", "17:00", "UTC");
            assertInactive(window, "2026-01-16T12:00:00Z");
        }

        @Test
        @DisplayName("the start is inclusive and the end exclusive")
        void startInclusiveEndExclusive() {
            // Half-open so back-to-back windows (09:00–17:00, 17:00–22:00)
            // neither overlap nor leave a one-minute gap between them.
            MaintenanceWindow window = weekly("THURSDAY", "09:00", "17:00", "UTC");
            assertActive(window, "2026-01-15T09:00:00Z");
            assertActive(window, "2026-01-15T16:59:00Z");
            assertInactive(window, "2026-01-15T17:00:00Z");
        }

        @Test
        @DisplayName("every listed day matches")
        void everyListedDayMatches() {
            MaintenanceWindow window = weekly("MONDAY,THURSDAY", "09:00", "17:00", "UTC");
            assertActive(window, "2026-01-12T12:00:00Z");
            assertActive(window, "2026-01-15T12:00:00Z");
            assertInactive(window, "2026-01-13T12:00:00Z");
        }

        @Test
        @DisplayName("day names tolerate case and surrounding whitespace")
        void dayNamesAreLenientlyParsed() {
            // The column is hand-editable and the UI has changed shape more
            // than once; "monday , thursday" unambiguously means those days.
            MaintenanceWindow window = weekly("monday , thursday", "09:00", "17:00", "UTC");
            assertActive(window, "2026-01-15T12:00:00Z");
        }
    }

    @Nested
    @DisplayName("windows that wrap past midnight")
    class OvernightWrap {

        @Test
        @DisplayName("a 22:00–06:00 MONDAY window is active at 05:00 on Tuesday")
        void wrapReachesIntoTheFollowingDay() {
            // The wrap is attributed to its START day, which is what an
            // operator means by "Monday night maintenance". Getting this
            // backwards would silence Sunday night and page through Monday
            // night.
            MaintenanceWindow window = weekly("MONDAY", "22:00", "06:00", "UTC");
            assertActive(window, "2026-01-12T22:00:00Z");
            assertActive(window, "2026-01-12T23:59:00Z");
            assertActive(window, "2026-01-13T00:00:00Z");
            assertActive(window, "2026-01-13T05:00:00Z");
            assertActive(window, "2026-01-13T05:59:00Z");
        }

        @Test
        @DisplayName("a 22:00–06:00 MONDAY window is inactive at 07:00 on Tuesday")
        void wrapEndsAtTheEndTime() {
            MaintenanceWindow window = weekly("MONDAY", "22:00", "06:00", "UTC");
            assertInactive(window, "2026-01-12T21:59:00Z");
            assertInactive(window, "2026-01-13T06:00:00Z");
            assertInactive(window, "2026-01-13T07:00:00Z");
        }

        @Test
        @DisplayName("the wrap does not leak into the next night")
        void wrapDoesNotLeakIntoTheNextNight() {
            // Tuesday 22:00 belongs to Tuesday's (absent) window, not to
            // Monday's — the tail only ever extends one day.
            MaintenanceWindow window = weekly("MONDAY", "22:00", "06:00", "UTC");
            assertInactive(window, "2026-01-13T22:00:00Z");
            assertInactive(window, "2026-01-14T05:00:00Z");
        }

        @Test
        @DisplayName("an equal start and end reads as a full 24 hours")
        void equalStartAndEndIsAFullDay() {
            // "end at or before start" is the wrap test, so 22:00–22:00 is a
            // wrap of exactly one day rather than an empty window. Pinned
            // because the empty reading is the plausible alternative and it
            // would silently do nothing.
            MaintenanceWindow window = weekly("MONDAY", "22:00", "22:00", "UTC");
            assertActive(window, "2026-01-12T22:00:00Z");
            assertActive(window, "2026-01-13T10:00:00Z");
            assertActive(window, "2026-01-13T21:59:00Z");
            assertInactive(window, "2026-01-13T22:00:00Z");
            assertInactive(window, "2026-01-12T21:59:00Z");
        }

        @Test
        @DisplayName("an end time of 24:00 stops at midnight rather than wrapping a whole day")
        void endOfDayStopsAtMidnight() {
            // "22:00 to 24:00" is how an operator writes "this evening" and it
            // resolves to an end of 00:00, which is at-or-before the start and
            // therefore technically a wrap — but a wrap whose tail is
            // "everything before midnight", which is nothing. The window ends
            // exactly at midnight, which is what was asked for.
            MaintenanceWindow window = weekly("MONDAY", "22:00", "24:00", "UTC");
            assertActive(window, "2026-01-12T22:00:00Z");
            assertActive(window, "2026-01-12T23:59:00Z");
            assertInactive(window, "2026-01-13T00:00:00Z");
            assertInactive(window, "2026-01-12T21:59:00Z");
        }

        @Test
        @DisplayName("a monthly wrap crosses the month boundary")
        void monthlyWrapCrossesTheMonthBoundary() {
            // Day 31 at 22:00 runs into day 1 of the next month, and the
            // previous-day lookup has to walk the calendar rather than
            // subtract one from the day number.
            MaintenanceWindow window = monthly("31", "22:00", "06:00", "UTC");
            assertActive(window, "2026-01-31T23:00:00Z");
            assertActive(window, "2026-02-01T05:00:00Z");
            assertInactive(window, "2026-02-01T06:00:00Z");
            assertInactive(window, "2026-02-02T05:00:00Z");
        }
    }

    @Nested
    @DisplayName("MONTHLY recurrence")
    class Monthly {

        @Test
        @DisplayName("a matching day of month inside the daily times is active")
        void matchingDayIsActive() {
            MaintenanceWindow window = monthly("15", "01:00", "05:00", "UTC");
            assertActive(window, "2026-01-15T02:00:00Z");
            assertActive(window, "2026-02-15T02:00:00Z");
        }

        @Test
        @DisplayName("a non-matching day of month is inactive")
        void nonMatchingDayIsInactive() {
            MaintenanceWindow window = monthly("15", "01:00", "05:00", "UTC");
            assertInactive(window, "2026-01-16T02:00:00Z");
        }

        @Test
        @DisplayName("every listed day of month matches")
        void everyListedDayMatches() {
            MaintenanceWindow window = monthly("1, 15", "01:00", "05:00", "UTC");
            assertActive(window, "2026-01-01T02:00:00Z");
            assertActive(window, "2026-01-15T02:00:00Z");
            assertInactive(window, "2026-01-02T02:00:00Z");
        }

        @Test
        @DisplayName("a day of month that no month has simply never matches")
        void impossibleDayNeverMatches() {
            // 31 is in range and therefore well-formed, so February is not an
            // error — it is a month the window does not occur in.
            MaintenanceWindow window = monthly("31", "01:00", "05:00", "UTC");
            assertActive(window, "2026-01-31T02:00:00Z");
            assertInactive(window, "2026-02-28T02:00:00Z");
        }
    }

    @Nested
    @DisplayName("timezone resolution")
    class Timezone {

        @Test
        @DisplayName("a null timezone evaluates on the server zone")
        void nullTimezoneUsesServerZone() {
            // This is the pre-v3 contract: every row written before the column
            // existed carries null, and the upgrade must not change when a
            // single one of them suppresses. The default is pinned to UTC for
            // this class, so 23:00Z is inside a 22:00–06:00 window.
            MaintenanceWindow window = overnightIn(null);
            assertActive(window, "2026-01-15T23:00:00Z");
            assertInactive(window, "2026-01-15T12:00:00Z");
        }

        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = { " ", "\t" })
        @DisplayName("a blank timezone falls back to the server zone")
        void blankTimezoneUsesServerZone(String timezone) {
            MaintenanceWindow window = overnightIn(timezone);
            assertActive(window, "2026-01-15T23:00:00Z");
            assertInactive(window, "2026-01-15T12:00:00Z");
        }

        @ParameterizedTest
        @ValueSource(strings = { "Not/AZone", "America/Nowhere", "Eastern Standard Time", "+99:00" })
        @DisplayName("an unparseable timezone falls back to the server zone rather than failing")
        void unparseableTimezoneFallsBackRatherThanReturningNull(String timezone) {
            // This is the load-bearing fail-open decision in this class. A bad
            // zone id is a broken row, and the cheapest safe reading of a
            // broken row is the behaviour it had before the column existed:
            // the server's zone. Returning null instead would hand the
            // evaluator a malformed verdict, and for an ACTIVE alerting
            // schedule a malformed verdict counts as "inside the schedule" —
            // so one typo in one row would look fine while quietly changing
            // when every channel it covers is allowed to page.
            MaintenanceWindow window = overnightIn(timezone);
            window.setId(4242);
            assertNotNull(WindowSchedule.isActiveNow(window, Instant.parse("2026-01-15T23:00:00Z")),
                    "a bad zone must not make the whole window malformed");
            assertActive(window, "2026-01-15T23:00:00Z");
            assertInactive(window, "2026-01-15T12:00:00Z");
        }

        @Test
        @DisplayName("a timezone is trimmed before parsing")
        void timezoneIsTrimmed() {
            MaintenanceWindow padded = overnightIn("  America/New_York  ");
            // 2026-01-15T02:00Z is 21:00 on 2026-01-14 in New York: outside a
            // 22:00–06:00 window. Were the padded id rejected, this would fall
            // back to UTC and read as active.
            assertInactive(padded, "2026-01-15T02:00:00Z");
        }

        @Test
        @DisplayName("the window's zone decides the clock, not the server's")
        void windowZoneDecidesTheClock() {
            // One instant, two windows differing only in timezone, opposite
            // verdicts. 2026-01-15T02:00Z is 21:00 the previous evening in
            // New York (outside 22:00–06:00) and 02:00 in UTC (inside it).
            assertInactive(overnightIn("America/New_York"), "2026-01-15T02:00:00Z");
            assertActive(overnightIn(null), "2026-01-15T02:00:00Z");
        }

        @Test
        @DisplayName("the window's zone decides the calendar day too")
        void windowZoneDecidesTheDay() {
            // The day set is matched on the window's clock as well, not just
            // the times: 2026-01-19T02:00Z is Monday in UTC but still Sunday
            // evening in New York. A window that suppresses "Sunday nights"
            // has to mean the operator's Sunday.
            MaintenanceWindow inNewYork = weekly("SUNDAY", "20:00", "23:00", "America/New_York");
            MaintenanceWindow onServerZone = weekly("SUNDAY", "20:00", "23:00", null);
            assertActive(inNewYork, "2026-01-19T02:00:00Z");
            assertInactive(onServerZone, "2026-01-19T02:00:00Z");
        }
    }

    @Nested
    @DisplayName("DST transitions in the window's own zone")
    class DaylightSaving {

        @Test
        @DisplayName("spring forward: the overnight window still runs 22:00–06:00 local")
        void springForwardKeepsTheLocalClockWindow() {
            // The night of 2026-03-07 into 2026-03-08 loses an hour at 02:00
            // local. A 22:00–06:00 window must still start at 22:00 and end at
            // 06:00 on New York's clock — which means it covers seven real
            // hours that night, not eight. That is the correct answer: the
            // operator asked for "overnight", not "eight hours of elapsed
            // time", and the alternative (holding the elapsed length fixed)
            // would end the window at 07:00 local and page an hour early.
            List<Instant> hits = activeMinutes(overnightIn("America/New_York"),
                    "2026-03-08T00:00:00Z", "2026-03-08T14:00:00Z");
            assertContiguous(hits);
            assertEquals(7 * 60, hits.size(), "spring-forward night is one real hour shorter");

            ZonedDateTime first = inNewYork(hits.get(0));
            ZonedDateTime last = inNewYork(hits.get(hits.size() - 1));
            assertEquals(LocalTime.of(22, 0), first.toLocalTime());
            assertEquals(SPRING_FORWARD.minusDays(1), first.toLocalDate());
            assertEquals(LocalTime.of(5, 59), last.toLocalTime());
            assertEquals(SPRING_FORWARD, last.toLocalDate());

            // And the offsets prove a transition was actually crossed rather
            // than the whole night being evaluated on one fixed offset.
            assertEquals("-05:00", first.getOffset().getId());
            assertEquals("-04:00", last.getOffset().getId());
        }

        @Test
        @DisplayName("fall back: the overnight window still runs 22:00–06:00 local")
        void fallBackKeepsTheLocalClockWindow() {
            // The mirror case: the night of 2026-10-31 into 2026-11-01 gains
            // an hour, so the same 22:00–06:00 window covers nine real hours.
            // Both directions matter — a window that silently stretched or
            // shrank on the local clock would either page during a change
            // freeze or stay silent an hour after one ended.
            List<Instant> hits = activeMinutes(overnightIn("America/New_York"),
                    "2026-11-01T00:00:00Z", "2026-11-01T14:00:00Z");
            assertContiguous(hits);
            assertEquals(9 * 60, hits.size(), "fall-back night is one real hour longer");

            ZonedDateTime first = inNewYork(hits.get(0));
            ZonedDateTime last = inNewYork(hits.get(hits.size() - 1));
            assertEquals(LocalTime.of(22, 0), first.toLocalTime());
            assertEquals(FALL_BACK.minusDays(1), first.toLocalDate());
            assertEquals(LocalTime.of(5, 59), last.toLocalTime());
            assertEquals(FALL_BACK, last.toLocalDate());

            assertEquals("-04:00", first.getOffset().getId());
            assertEquals("-05:00", last.getOffset().getId());
        }

        @Test
        @DisplayName("an ordinary night is eight hours, so the transition nights are the anomaly")
        void ordinaryNightIsEightHours() {
            // The control for the two cases above: on a night with no
            // transition the same window covers exactly eight real hours, so
            // the 7/9 counts are attributable to the transition and not to an
            // off-by-something in the scan.
            List<Instant> hits = activeMinutes(overnightIn("America/New_York"),
                    "2026-01-15T00:00:00Z", "2026-01-15T14:00:00Z");
            assertContiguous(hits);
            assertEquals(8 * 60, hits.size());
        }

        @Test
        @DisplayName("a window on the server's zone drifts against the same window in New York")
        void serverZoneWindowCoversDifferentInstants() {
            // The whole reason the timezone column exists. On the same
            // spring-forward night, the pre-v3 (server-zone) row covers a flat
            // eight hours of UTC and a different eight hours at that — it is
            // active while New York is not, and idle while New York's
            // maintenance is still running. Before v3 that was every window on
            // a UTC server.
            MaintenanceWindow newYork = overnightIn("America/New_York");
            MaintenanceWindow serverZone = overnightIn(null);

            // Scanned over the server zone's own night (22:00Z to 22:00Z) so
            // the whole run is inside the sampled range.
            assertEquals(8 * 60,
                    activeMinutes(serverZone, "2026-03-07T22:00:00Z", "2026-03-08T22:00:00Z").size());

            // 09:00Z is 05:00 EDT — inside New York's window, past the end of
            // the server-zone window.
            assertActive(newYork, "2026-03-08T09:00:00Z");
            assertInactive(serverZone, "2026-03-08T09:00:00Z");

            // 23:00Z is 19:00 EDT — inside the server-zone window, hours
            // before New York's starts.
            assertInactive(newYork, "2026-03-08T23:00:00Z");
            assertActive(serverZone, "2026-03-08T23:00:00Z");
        }

        @Test
        @DisplayName("a window inside the spring-forward gap never fires that night")
        void windowInsideTheSpringForwardGapNeverFires() {
            // 02:00–03:00 local does not exist on the spring-forward date, and
            // no instant maps into it, so the window covers nothing that
            // night. Documented rather than worked around: silently shifting
            // it to 03:00 would run maintenance suppression at a time nobody
            // scheduled. The following day proves it is the gap, not the
            // window, that is at fault.
            MaintenanceWindow window = weekly(ALL_DAYS, "02:00", "03:00", "America/New_York");
            assertTrue(activeMinutes(window, "2026-03-08T00:00:00Z", "2026-03-09T00:00:00Z").isEmpty(),
                    "the 02:00 hour does not exist on the spring-forward date");
            assertEquals(60, activeMinutes(window, "2026-03-07T00:00:00Z", "2026-03-08T00:00:00Z").size(),
                    "the same window is a normal hour on the day before");
        }

        @Test
        @DisplayName("a window inside the fall-back repeated hour fires for both passes")
        void windowInsideTheFallBackRepeatedHourCoversBothPasses() {
            // 01:00–02:00 local happens twice on the fall-back date (once on
            // EDT, once on EST) and the window covers both — two real hours
            // from one hour of schedule. Correct for a suppression window:
            // whatever the operator was doing at 01:30 local, they were doing
            // it both times.
            MaintenanceWindow window = weekly(ALL_DAYS, "01:00", "02:00", "America/New_York");
            List<Instant> hits = activeMinutes(window, "2026-11-01T00:00:00Z", "2026-11-01T12:00:00Z");
            assertContiguous(hits);
            assertEquals(120, hits.size(), "the repeated hour is covered twice");
            assertEquals("-04:00", inNewYork(hits.get(0)).getOffset().getId());
            assertEquals("-05:00", inNewYork(hits.get(hits.size() - 1)).getOffset().getId());
        }

        @Test
        @DisplayName("a UTC window is unaffected by any transition")
        void utcWindowNeverTransitions() {
            // The baseline an installation gets by leaving the zone alone on a
            // UTC server: eight hours on every night of the year, including
            // both transition nights.
            MaintenanceWindow window = overnightIn("UTC");
            assertEquals(8 * 60,
                    activeMinutes(window, "2026-03-07T22:00:00Z", "2026-03-08T22:00:00Z").size());
            assertEquals(8 * 60,
                    activeMinutes(window, "2026-10-31T22:00:00Z", "2026-11-01T22:00:00Z").size());
        }
    }

    @Nested
    @DisplayName("malformed schedules return null, never a verdict")
    class Malformed {

        /**
         * Why {@code null} and not {@code false}: the caller's reaction is
         * mode-dependent and this class does not know the mode. In
         * {@code TriggerEvaluatorJob.suppressedByWindows} a {@code null}
         * SUPPRESS window does not suppress (a broken window must not swallow
         * a real alert), while a {@code null} ACTIVE window counts as
         * in-schedule (a broken alerting schedule must not mute every channel
         * it covers). Both readings let alerts through — but they are opposite
         * boolean values, so collapsing them here would silently pick the
         * wrong one for one of the two modes.
         */
        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = { " ", "9:00", "25:00", "22:60", "22:00:00", "10 PM", "22-00" })
        @DisplayName("an unparseable start or end time is malformed")
        void unparseableTimesAreMalformed(String time) {
            assertMalformed(weekly("THURSDAY", time, "17:00", "UTC"), "2026-01-15T12:00:00Z");
            assertMalformed(weekly("THURSDAY", "09:00", time, "UTC"), "2026-01-15T12:00:00Z");
        }

        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = { " ", ",", "FUNDAY", "MONDAY,FUNDAY", "MON", "1" })
        @DisplayName("an empty or unparseable day-of-week set is malformed")
        void badDaysOfWeekAreMalformed(String days) {
            // "MONDAY,FUNDAY" is the important entry: a partially valid list
            // must not silently shrink to the days that happened to parse,
            // because a suppression window that quietly lost a day is
            // indistinguishable from one that works.
            assertMalformed(weekly(days, "09:00", "17:00", "UTC"), "2026-01-15T12:00:00Z");
        }

        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = { " ", ",", "0", "32", "-1", "first", "1,32", "15.5" })
        @DisplayName("an empty or out-of-range day-of-month set is malformed")
        void badDaysOfMonthAreMalformed(String days) {
            assertMalformed(monthly(days, "09:00", "17:00", "UTC"), "2026-01-15T12:00:00Z");
        }

        @Test
        @DisplayName("a malformed schedule is still malformed outside its daily times")
        void malformedIsNotMaskedByBeingOutOfHours() {
            // The parse failure is reported whether or not the window would
            // have matched, so the log line and the null verdict appear on
            // every tick until the row is fixed rather than only during the
            // hours nobody is watching.
            assertMalformed(weekly("FUNDAY", "09:00", "17:00", "UTC"), "2026-01-15T23:00:00Z");
        }

        @Test
        @DisplayName("disabled beats malformed")
        void disabledWindowIsFalseNotMalformed() {
            // The enabled check runs before anything is parsed, so a disabled
            // broken row is unambiguously inactive — there is no verdict to
            // fail open about, and the operator does not get a warning per
            // tick for a window they already turned off.
            MaintenanceWindow window = weekly("FUNDAY", "not-a-time", "17:00", "Not/AZone");
            window.setEnabled(false);
            assertInactive(window, "2026-01-15T12:00:00Z");
        }

        @Test
        @DisplayName("out-of-bounds beats malformed")
        void outOfBoundsWindowIsFalseNotMalformed() {
            // Same reasoning for the absolute bounds: a window whose range has
            // already passed cannot be active regardless of what its recurring
            // fields say, so it is answered before they are parsed.
            MaintenanceWindow window = weekly("FUNDAY", "not-a-time", "17:00", "UTC");
            window.setActiveUntil(Instant.parse("2026-01-01T00:00:00Z"));
            assertInactive(window, "2026-01-15T12:00:00Z");
        }
    }

    @Nested
    @DisplayName("the parsers MaintenanceWindowService validates with")
    class Parsers {

        // These three are public because MaintenanceWindowService calls them
        // directly at save time; they are the reason a malformed row is
        // supposed to be impossible in the first place, so the null-return
        // contract each one has is asserted here rather than only inferred
        // from isActiveNow's behaviour.

        @Test
        @DisplayName("parseTime accepts strict HH:mm and the day's endpoints")
        void parseTimeAcceptsStrictHhMm() {
            assertEquals(LocalTime.of(0, 0), WindowSchedule.parseTime("00:00"));
            assertEquals(LocalTime.of(23, 59), WindowSchedule.parseTime("23:59"));
            assertEquals(LocalTime.of(9, 5), WindowSchedule.parseTime(" 09:05 "));
        }

        @Test
        @DisplayName("parseTime resolves 24:00 to midnight")
        void parseTimeResolvesEndOfDay() {
            // java.time's SMART resolver accepts hour 24 with a zero minute as
            // the end of the day. Pinned rather than treated as a curiosity
            // because it is a legitimate end time an operator will type — see
            // OvernightWrap#endOfDayStopsAtMidnight for what the schedule then
            // does with it.
            assertEquals(LocalTime.MIDNIGHT, WindowSchedule.parseTime("24:00"));
        }

        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = { " ", "9:00", "24:01", "25:00", "23:60", "09:05:00", "0905" })
        @DisplayName("parseTime returns null for anything else")
        void parseTimeRejectsEverythingElse(String value) {
            assertNull(WindowSchedule.parseTime(value));
        }

        @Test
        @DisplayName("parseDaysOfWeek accepts a lenient CSV of day names")
        void parseDaysOfWeekAcceptsCsv() {
            assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                    WindowSchedule.parseDaysOfWeek(" monday , FRIDAY "));
        }

        @Test
        @DisplayName("parseDaysOfWeek returns null rather than a partial set")
        void parseDaysOfWeekRejectsPartialSets() {
            assertNull(WindowSchedule.parseDaysOfWeek("MONDAY,FUNDAY"));
            assertNull(WindowSchedule.parseDaysOfWeek(null));
            assertNull(WindowSchedule.parseDaysOfWeek("  "));
        }

        @Test
        @DisplayName("parseDaysOfMonth accepts 1–31 and rejects anything outside it")
        void parseDaysOfMonthRange() {
            assertEquals(Set.of(1, 15, 31), WindowSchedule.parseDaysOfMonth("1, 15 ,31"));
            assertNull(WindowSchedule.parseDaysOfMonth("0"));
            assertNull(WindowSchedule.parseDaysOfMonth("32"));
            assertNull(WindowSchedule.parseDaysOfMonth("1,32"));
            assertNull(WindowSchedule.parseDaysOfMonth("first"));
            assertNull(WindowSchedule.parseDaysOfMonth(null));
        }
    }
}
