/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowRepeat;

/**
 * The "is this window active right now" schedule math, shared by the
 * evaluator's suppression check and the service layer's {@code activeNow}
 * stamping. Recurring windows are evaluated on the <em>server's</em> local
 * clock ({@link ZoneId#systemDefault()}) — the same clock an operator setting
 * "business hours" schedules reasons about — while one-time windows compare
 * absolute instants.
 *
 * <p>{@link #isActiveNow} returns a nullable {@link Boolean} on purpose:
 * {@code null} means the stored schedule is malformed (unparseable days or
 * times — validation prevents this, so it signals drift or a hand-edited
 * row). The right reaction depends on the window's mode, so the policy
 * belongs to the caller: for a SUPPRESS window treat {@code null} as "not
 * active" (a broken window must never silently swallow a real alert), for an
 * ACTIVE alerting schedule treat it as "active" (a broken schedule must
 * never silently mute everything it covers). Stateless static utility
 * (private constructor) per the plugin's house style.</p>
 */
public final class WindowSchedule {

    private static final Logger log = LoggerFactory.getLogger(WindowSchedule.class);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private WindowSchedule() {
    }

    /**
     * Whether the window's schedule covers {@code now}. Disabled windows are
     * never active. The absolute {@code activeFrom}/{@code activeUntil}
     * bounds apply to every repeat type (for one-time windows they ARE the
     * schedule; for recurring windows they are optional outer bounds, null =
     * unbounded). Recurring windows then match their day set and daily
     * start/end times; an end time at or before the start wraps past
     * midnight, attributed to the start day — a MONDAY 22:00–06:00 window is
     * active Tuesday 05:00.
     *
     * @param window the window to test
     * @param now    the instant to test against
     * @return {@code true}/{@code false} for a well-formed schedule,
     *         {@code null} when the stored schedule cannot be parsed (see
     *         class Javadoc for the caller's mode-dependent policy)
     */
    public static Boolean isActiveNow(MaintenanceWindow window, Instant now) {
        if (window == null || !window.isEnabled()) {
            return false;
        }
        if (window.getActiveFrom() != null && now.isBefore(window.getActiveFrom())) {
            return false;
        }
        if (window.getActiveUntil() != null && now.isAfter(window.getActiveUntil())) {
            return false;
        }

        WindowRepeat repeat = window.getRepeatType() != null ? window.getRepeatType() : WindowRepeat.NONE;
        if (repeat == WindowRepeat.NONE) {
            // Inside the absolute range is the whole schedule — but a one-time
            // window with no bounds would be "always active", which validation
            // forbids; flag it rather than guessing.
            if (window.getActiveFrom() == null || window.getActiveUntil() == null) {
                log.warn("One-time window {} has no active range; treating as malformed", window.getId());
                return null;
            }
            return true;
        }

        LocalTime start = parseTime(window.getStartTime());
        LocalTime end = parseTime(window.getEndTime());
        if (start == null || end == null) {
            log.warn("Recurring window {} has unparseable start/end time ('{}'/'{}')",
                    window.getId(), window.getStartTime(), window.getEndTime());
            return null;
        }

        ZonedDateTime local = now.atZone(ZoneId.systemDefault());
        LocalTime time = local.toLocalTime();
        boolean overnight = !end.isAfter(start);

        if (repeat == WindowRepeat.WEEKLY) {
            Set<DayOfWeek> days = parseDaysOfWeek(window.getDaysOfWeek());
            if (days == null || days.isEmpty()) {
                log.warn("Weekly window {} has unparseable days of week ('{}')",
                        window.getId(), window.getDaysOfWeek());
                return null;
            }
            if (!overnight) {
                return days.contains(local.getDayOfWeek()) && !time.isBefore(start) && time.isBefore(end);
            }
            return (days.contains(local.getDayOfWeek()) && !time.isBefore(start))
                    || (days.contains(local.minusDays(1).getDayOfWeek()) && time.isBefore(end));
        }

        // MONTHLY
        Set<Integer> days = parseDaysOfMonth(window.getDaysOfMonth());
        if (days == null || days.isEmpty()) {
            log.warn("Monthly window {} has unparseable days of month ('{}')",
                    window.getId(), window.getDaysOfMonth());
            return null;
        }
        if (!overnight) {
            return days.contains(local.getDayOfMonth()) && !time.isBefore(start) && time.isBefore(end);
        }
        return (days.contains(local.getDayOfMonth()) && !time.isBefore(start))
                || (days.contains(local.minusDays(1).getDayOfMonth()) && time.isBefore(end));
    }

    /**
     * Parses a strict {@code HH:mm} time, or {@code null} when blank or
     * malformed.
     *
     * @param value the stored time string
     */
    public static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim(), HH_MM);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses a comma-separated list of {@link DayOfWeek} names, or
     * {@code null} when blank or any entry is malformed — a partially valid
     * day set must not silently shrink the schedule.
     *
     * @param csv the stored day list (e.g. {@code "MONDAY,FRIDAY"})
     */
    public static Set<DayOfWeek> parseDaysOfWeek(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : csv.split(",")) {
            try {
                days.add(DayOfWeek.valueOf(part.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return days;
    }

    /**
     * Parses a comma-separated list of days of month (1–31), or {@code null}
     * when blank or any entry is malformed or out of range.
     *
     * @param csv the stored day list (e.g. {@code "1,15"})
     */
    public static Set<Integer> parseDaysOfMonth(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        Set<Integer> days = new HashSet<>();
        for (String part : csv.split(",")) {
            try {
                int day = Integer.parseInt(part.trim());
                if (day < 1 || day > 31) {
                    return null;
                }
                days.add(day);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return days;
    }
}
