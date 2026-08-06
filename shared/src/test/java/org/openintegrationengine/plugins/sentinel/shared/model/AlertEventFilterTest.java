/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for the one place Sentinel interpolates untrusted-shaped
 * text into SQL.
 *
 * <p>{@code sortColumn}/{@code sortDir} reach the mapped statements as raw
 * {@code ${}} substitutions because MyBatis cannot bind a column name or a
 * sort direction as a {@code #{}} parameter. Everything that keeps that from
 * being an injection point lives in this class's two static validators and in
 * the setters that route through them — there is no second line of defence
 * downstream, so these tests assert the boundary directly rather than through
 * the repository.</p>
 *
 * <p>The important property is not "valid input is accepted" but "invalid
 * input is <em>replaced</em>, never stored". A validator that rejected by
 * throwing would still be safe; one that stored the bad value and let a caller
 * read it back would not, which is why every negative case asserts on the
 * value the getter subsequently returns.</p>
 */
@DisplayName("AlertEventFilter sort validation")
class AlertEventFilterTest {

    /**
     * Everything an attacker-controlled {@code sort} query parameter could
     * plausibly carry: statement chaining, comment truncation, tautologies,
     * ORDER BY clause smuggling, and the near-misses (case variants, trailing
     * whitespace, an allowed column with a direction glued on) that a
     * sloppier "contains"/"startsWith" check would let through.
     */
    private static final String[] HOSTILE_SORT_INPUTS = {
            "opened_time; DROP TABLE sentinel_alert_event",
            "opened_time--",
            "1=1",
            "opened_time, (SELECT password FROM person)",
            "opened_time/*",
            "opened_time' OR '1'='1",
            "opened_time)",
            "severity; DELETE FROM sentinel_alert_event",
            "OPENED_TIME",
            "Opened_Time",
            "SEVERITY",
            " opened_time",
            "opened_time ",
            "opened_time ASC",
            "opened_time DESC",
            "channel_id;--",
            "id",
            "sentinel_alert_event.opened_time",
            "openedtime"
    };

    @Nested
    @DisplayName("sortColumn")
    class SortColumn {

        @Test
        @DisplayName("defaults to opened_time on a fresh filter")
        void defaultsToOpenedTime() {
            // The default matters because the servlet layer constructs a
            // filter and only conditionally calls the setter; an unset field
            // still gets interpolated.
            assertEquals("opened_time", new AlertEventFilter().getSortColumn());
        }

        @ParameterizedTest
        @ValueSource(strings = { "opened_time", "severity", "channel_id" })
        @DisplayName("every allow-listed column round-trips unchanged")
        void allowedColumnsRoundTrip(String column) {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setSortColumn(column);
            assertEquals(column, filter.getSortColumn());
            assertEquals(column, AlertEventFilter.validateSortColumn(column));
        }

        @Test
        @DisplayName("allow-list is exactly the three mapper-supported columns")
        void allowListContentsAreFixed() {
            // The mapper XML for all five vendors hand-writes ORDER BY
            // handling for these three columns and no others. A column added
            // to the allow-list without the matching mapper change produces a
            // runtime SQL error on one vendor only, so the set is pinned here
            // to make that pairing explicit.
            assertEquals(Set.of("opened_time", "severity", "channel_id"),
                    AlertEventFilter.ALLOWED_SORT_COLUMNS);
        }

        @Test
        @DisplayName("allow-list cannot be widened at runtime")
        void allowListIsImmutable() {
            // ALLOWED_SORT_COLUMNS is public. Were it a mutable set, any code
            // on the classpath could add an entry and silently reopen the
            // injection point without touching this file or the validator.
            assertThrows(UnsupportedOperationException.class,
                    () -> AlertEventFilter.ALLOWED_SORT_COLUMNS.add("1=1"));
        }

        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = { " ", "\t", "opened_time; DROP TABLE sentinel_alert_event", "1=1",
                "opened_time--", "OPENED_TIME", "opened_time ASC", "unknown_column" })
        @DisplayName("unknown and injection-shaped input falls back to opened_time")
        void hostileInputFallsBack(String candidate) {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setSortColumn(candidate);
            assertEquals("opened_time", filter.getSortColumn());
            assertEquals("opened_time", AlertEventFilter.validateSortColumn(candidate));
        }

        @Test
        @DisplayName("no hostile input is ever stored or returned verbatim")
        void noHostileInputSurvives() {
            // Sweeping the whole payload list in one case keeps that list a
            // single place to extend when a new attack shape turns up, and
            // asserts the invariant that actually protects the ${}
            // substitution: whatever comes back is a member of the allow-list,
            // no matter what went in.
            for (String candidate : HOSTILE_SORT_INPUTS) {
                AlertEventFilter filter = new AlertEventFilter();
                filter.setSortColumn(candidate);
                assertEquals("opened_time", filter.getSortColumn(),
                        "hostile sortColumn leaked through the setter: " + candidate);
                assertTrue(AlertEventFilter.ALLOWED_SORT_COLUMNS.contains(filter.getSortColumn()),
                        "sortColumn escaped the allow-list for input: " + candidate);
            }
        }

        @Test
        @DisplayName("a rejected value resets to the default rather than keeping the prior valid one")
        void rejectionResetsToDefaultNotPreviousValue() {
            // The setter is an unconditional assignment of the validated
            // result. If it ever became "keep the old value on invalid
            // input", a caller that sets a legitimate column and then a bad
            // one would silently sort by the stale column — and any test that
            // only ever sets a single value would still pass.
            AlertEventFilter filter = new AlertEventFilter();
            filter.setSortColumn("severity");
            assertEquals("severity", filter.getSortColumn());
            filter.setSortColumn("severity; DROP TABLE sentinel_alert_event");
            assertEquals("opened_time", filter.getSortColumn());
        }
    }

    @Nested
    @DisplayName("sortDir")
    class SortDir {

        @Test
        @DisplayName("defaults to DESC on a fresh filter")
        void defaultsToDesc() {
            // Newest-first is the Problems list's useful default; an unset
            // field is interpolated raw just like a set one, so it has to
            // already be a legal keyword.
            assertEquals("DESC", new AlertEventFilter().getSortDir());
        }

        @ParameterizedTest
        @ValueSource(strings = { "ASC", "DESC" })
        @DisplayName("both legal directions round-trip")
        void legalDirectionsRoundTrip(String dir) {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setSortDir(dir);
            assertEquals(dir, filter.getSortDir());
        }

        @ParameterizedTest
        @NullSource
        @EmptySource
        @ValueSource(strings = { "asc", "Asc", " ASC", "ASC ", "ASC--",
                "ASC; DROP TABLE sentinel_alert_event", "ASC, (SELECT 1)", "DESCENDING", "1=1", "ASC/*" })
        @DisplayName("anything but an exact ASC becomes DESC")
        void everythingElseBecomesDesc(String candidate) {
            // validateSortDir is deliberately absolute — exact equality with
            // "ASC", everything else DESC — so there is no trimming and no
            // case folding, and therefore no normalization step that could
            // ever disagree with the comparison that follows it. The
            // lowercase "asc" case documents that consequence: it is safe,
            // not accepted.
            AlertEventFilter filter = new AlertEventFilter();
            filter.setSortDir(candidate);
            assertEquals("DESC", filter.getSortDir());
            assertEquals("DESC", AlertEventFilter.validateSortDir(candidate));
        }

        @Test
        @DisplayName("output is always one of exactly two SQL keywords")
        void outputIsAlwaysOneOfTwoLiterals() {
            for (String candidate : HOSTILE_SORT_INPUTS) {
                AlertEventFilter filter = new AlertEventFilter();
                filter.setSortDir(candidate);
                assertTrue("ASC".equals(filter.getSortDir()) || "DESC".equals(filter.getSortDir()),
                        "sortDir escaped the two legal keywords for input: " + candidate);
            }
        }

        @Test
        @DisplayName("a rejected value resets to DESC rather than keeping ASC")
        void rejectionResetsToDesc() {
            AlertEventFilter filter = new AlertEventFilter();
            filter.setSortDir("ASC");
            assertEquals("ASC", filter.getSortDir());
            filter.setSortDir("ASC; DROP TABLE sentinel_alert_event");
            assertEquals("DESC", filter.getSortDir());
        }
    }
}
