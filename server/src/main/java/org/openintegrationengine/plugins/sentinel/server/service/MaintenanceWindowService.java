/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;

import org.openintegrationengine.plugins.sentinel.server.db.MaintenanceWindowRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.WindowSchedule;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowMode;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowRepeat;

/**
 * Business rules for maintenance-window CRUD plus the "activate now" shortcut.
 *
 * <p>Windows only influence <em>alert creation</em> (a SUPPRESS window
 * suppresses alerts born inside its active times; an ACTIVE alerting
 * schedule suppresses alerts born outside them), so all this service must
 * guarantee is that persisted windows are coherent: a name to show in the
 * log, a resolvable scope, and a schedule the evaluator can parse. The
 * evaluator's suppression check consumes windows exactly as stored — the
 * only derived state is the response-only {@code activeNow} stamp on
 * reads.</p>
 *
 * <p>Errors follow the plugin-wide convention: {@link IllegalArgumentException}
 * → 400, {@link NoSuchElementException} → 404 (mapped in the servlet).</p>
 */
public final class MaintenanceWindowService {

    private MaintenanceWindowService() {
    }

    /**
     * Lists every window, past and future — history is part of the value
     * (why didn't we get paged last night? there was a window). Each row is
     * stamped with the response-only {@code activeNow} flag so the UI's
     * Active column shows the server's schedule math, not a re-derivation.
     *
     * @return all windows, newest active-from first; never {@code null}
     */
    public static List<MaintenanceWindow> list() {
        List<MaintenanceWindow> windows = MaintenanceWindowRepository.listMaintenanceWindows();
        Instant now = Instant.now();
        for (MaintenanceWindow window : windows) {
            window.setActiveNow(Boolean.TRUE.equals(WindowSchedule.isActiveNow(window, now)));
        }
        return windows;
    }

    /**
     * Fetches one window, stamped with {@code activeNow} like {@link #list()}.
     *
     * @param id database id
     * @return the window
     * @throws NoSuchElementException if no window has that id
     */
    public static MaintenanceWindow get(int id) {
        MaintenanceWindow window = MaintenanceWindowRepository.getMaintenanceWindow(id);
        if (window == null) {
            throw new NoSuchElementException("No maintenance window with id " + id);
        }
        window.setActiveNow(Boolean.TRUE.equals(WindowSchedule.isActiveNow(window, Instant.now())));
        return window;
    }

    /**
     * Validates and creates a window, stamping authorship.
     *
     * @param window the window to create ({@code id} ignored)
     * @param userId the acting user, stamped and audited
     * @return the created window with its generated id
     * @throws IllegalArgumentException if validation fails
     */
    public static MaintenanceWindow create(MaintenanceWindow window, int userId) {
        validate(window);
        window.setId(null);
        window.setCreatedBy(userId);
        window.setCreatedTime(Instant.now());
        MaintenanceWindowRepository.insertMaintenanceWindow(window);
        SentinelAuditLog.windowCreated(userId, window);
        return window;
    }

    /**
     * Validates and updates a window; creation stamps are preserved from the
     * stored row (authorship is server-assigned, never client-supplied).
     *
     * @param id     database id of the window to update
     * @param window the new definition
     * @param userId the acting user, audited
     * @return the updated window
     * @throws NoSuchElementException   if no window has that id
     * @throws IllegalArgumentException if validation fails
     */
    public static MaintenanceWindow update(int id, MaintenanceWindow window, int userId) {
        MaintenanceWindow existing = get(id);
        validate(window);
        window.setId(id);
        window.setCreatedBy(existing.getCreatedBy());
        window.setCreatedTime(existing.getCreatedTime());
        MaintenanceWindowRepository.updateMaintenanceWindow(window);
        SentinelAuditLog.windowUpdated(userId, window);
        return window;
    }

    /**
     * Deletes a window. Alerts suppressed while it was active keep their
     * stored {@code suppressed} flag — suppression is decided at alert
     * creation and never revisited, so deleting a window rewrites no
     * history.
     *
     * @param id     database id of the window to delete
     * @param userId the acting user, audited
     * @throws NoSuchElementException if no window has that id
     */
    public static void delete(int id, int userId) {
        MaintenanceWindow existing = get(id);
        MaintenanceWindowRepository.deleteMaintenanceWindow(id);
        SentinelAuditLog.windowDeleted(userId, existing);
    }

    /**
     * Re-times an existing window to start right now — the mid-incident
     * "stop paging us, we're working on it" action. The window's scope is
     * kept; only its active range and enabled flag change, so an operator can
     * keep a pre-built "all channels" window around and slam it on when
     * needed.
     *
     * @param id              database id of the window to activate
     * @param durationMinutes how long the window should run from now
     * @param userId          the acting user, audited
     * @return the re-timed window
     * @throws NoSuchElementException   if no window has that id
     * @throws IllegalArgumentException if {@code durationMinutes} is not
     *                                  positive
     */
    public static MaintenanceWindow activateNow(int id, int durationMinutes, int userId) {
        if (durationMinutes < 1) {
            throw new IllegalArgumentException("durationMinutes must be at least 1");
        }
        MaintenanceWindow window = get(id);
        if (window.getRepeatType() != null && window.getRepeatType() != WindowRepeat.NONE) {
            // Re-timing a recurring window would silently overwrite its outer
            // bounds; the mid-incident shortcut is a one-time-window concept.
            throw new IllegalArgumentException(
                    "Activate now only applies to one-time windows; recurring windows follow their schedule");
        }
        Instant now = Instant.now();
        window.setActiveFrom(now);
        window.setActiveUntil(now.plus(durationMinutes, ChronoUnit.MINUTES));
        window.setEnabled(true);
        MaintenanceWindowRepository.updateMaintenanceWindow(window);
        SentinelAuditLog.windowActivated(userId, window);
        return window;
    }

    /**
     * Save-time validation — same scope rules as monitors (the suppression
     * check resolves window scopes with the same code paths), plus
     * per-repeat-type schedule coherence: one-time windows need a non-empty
     * absolute range, recurring windows need a day set and parseable
     * start/end times ({@code WindowSchedule} treats malformed stored rows
     * as drift and alarms in the log, so nothing incoherent may get in
     * here). Null mode/repeat normalize to SUPPRESS/NONE so pre-v2 clients
     * keep working unchanged.
     */
    private static void validate(MaintenanceWindow window) {
        if (window == null) {
            throw new IllegalArgumentException("Maintenance window body is required");
        }
        if (window.getName() == null || window.getName().isBlank()) {
            throw new IllegalArgumentException("Maintenance window name is required");
        }
        if (window.getScopeType() == null) {
            throw new IllegalArgumentException("Scope type is required");
        }
        if (window.getScopeType() != ScopeType.ALL
                && (window.getScopeId() == null || window.getScopeId().isBlank())) {
            throw new IllegalArgumentException("A scope id (channel, group, or tag) is required for "
                    + window.getScopeType().name() + " scope");
        }

        if (window.getMode() == null) {
            window.setMode(WindowMode.SUPPRESS);
        }
        if (window.getRepeatType() == null) {
            window.setRepeatType(WindowRepeat.NONE);
        }

        if (window.getRepeatType() == WindowRepeat.NONE) {
            if (window.getActiveFrom() == null || window.getActiveUntil() == null) {
                throw new IllegalArgumentException(
                        "Both activeFrom and activeUntil are required for a one-time window");
            }
            // A one-time window carries no recurrence fields; blank them so a
            // stored row never mixes schedule vocabularies.
            window.setDaysOfWeek(null);
            window.setDaysOfMonth(null);
            window.setStartTime(null);
            window.setEndTime(null);
        } else {
            if (WindowSchedule.parseTime(window.getStartTime()) == null
                    || WindowSchedule.parseTime(window.getEndTime()) == null) {
                throw new IllegalArgumentException(
                        "Recurring windows require startTime and endTime as HH:mm");
            }
            if (window.getRepeatType() == WindowRepeat.WEEKLY) {
                if (WindowSchedule.parseDaysOfWeek(window.getDaysOfWeek()) == null) {
                    throw new IllegalArgumentException(
                            "Weekly windows require daysOfWeek as a comma-separated list of day names"
                                    + " (e.g. MONDAY,FRIDAY)");
                }
                window.setDaysOfMonth(null);
            } else {
                if (WindowSchedule.parseDaysOfMonth(window.getDaysOfMonth()) == null) {
                    throw new IllegalArgumentException(
                            "Monthly windows require daysOfMonth as a comma-separated list of days 1-31"
                                    + " (e.g. 1,15)");
                }
                window.setDaysOfWeek(null);
            }
        }

        if (window.getActiveFrom() != null && window.getActiveUntil() != null
                && !window.getActiveFrom().isBefore(window.getActiveUntil())) {
            throw new IllegalArgumentException("activeFrom must be before activeUntil");
        }
    }
}
