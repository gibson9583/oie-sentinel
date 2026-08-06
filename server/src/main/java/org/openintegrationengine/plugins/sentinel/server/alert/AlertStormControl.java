/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;

/**
 * Storm control for {@link ActionDispatcher}: the two decisions that turn "one
 * shared dependency just took down thirty channels" into something other than
 * thirty pages.
 *
 * <p>Dependency suppression already handles the parent/child case <em>on one
 * channel</em>, and maintenance windows handle the planned case. Neither
 * touches unplanned fan-out across channels, which is the case that actually
 * wakes people up. This class adds the two guards that do, both of them
 * derived from data already in the database rather than from bookkeeping that
 * a restart would lose:</p>
 *
 * <ol>
 *   <li><b>Per-action ceiling</b> ({@link #ceilingVerdict}). An action
 *       configured with {@code maxNotificationsPerWindow} +
 *       {@code rollupWindowSeconds} may send that many individual
 *       notifications per window; past the ceiling the individual sends are
 *       suppressed and exactly one rollup notification naming the affected
 *       channels goes out instead. Counted from
 *       {@code sentinel_action_dispatch_log}, the same table
 *       {@code ActionDispatcher.onRepeatCheck} already treats as the source of
 *       truth for repeat pacing — so the ceiling survives a server restart
 *       mid-storm, which is precisely when an in-memory counter would reset
 *       and let the storm through a second time.</li>
 *   <li><b>Flap detection</b> ({@link #flapCheck}). A trigger that keeps
 *       opening and clearing is announced once and then goes quiet until it
 *       stabilizes.</li>
 * </ol>
 *
 * <p><b>Flapping is not a trigger state.</b> {@code TriggerStatus} is a shared
 * model enum with a persisted representation in {@code sentinel_trigger_state}
 * and is deliberately not extended here: adding a value to it would change the
 * evaluator's state machine, the five mappers' round trip and the UI's status
 * rendering, all to express something no evaluator ever measures. Flapping is
 * a property of the <em>notification stream</em>, not of the current
 * measurement, so it lives here and is derived from the alert-event history
 * for the {@code (monitorId, channelId, metadataId)} identity: N completed
 * PROBLEM→OK cycles inside {@link #FLAP_WINDOW} means flapping. The trigger
 * row keeps saying PROBLEM or OK, exactly as it did before, and the Problems
 * list keeps showing every event; only the notifications are throttled.</p>
 *
 * <p><b>Never throws.</b> Every entry point fails <em>open</em> — a broken
 * storm-control query returns "send normally" rather than swallowing an alert.
 * Suppressing a real page because a bookkeeping read failed would be a far
 * worse outcome than the duplicate notification it was trying to prevent, and
 * it is the failure mode an operator would never think to look for.</p>
 *
 * <p>Package-private: this is an internal collaborator of
 * {@link ActionDispatcher} and has no meaning without it. Its only mutable
 * state is the window snapshot, whose lifecycle hangs off the dispatcher's
 * ({@link #reset()} is called from {@code shutdownDispatchExecutor}).</p>
 */
final class AlertStormControl {

    private static final Logger log = LoggerFactory.getLogger(AlertStormControl.class);

    /**
     * Marker prefix written into {@code sentinel_action_dispatch_log.error_message}
     * for a rollup notification's row, and the mechanism that keeps a rollup
     * from feeding the very ceiling that produced it.
     *
     * <p>The rollup is a real delivery through the action's real transport, so
     * it must be logged — an unlogged send is invisible to the audit trail and
     * to repeat pacing. But a rollup row counted as an ordinary notification
     * would push the window total one higher every time, so a window that
     * tripped once could never fall back under the ceiling, and every
     * subsequent window would trip on the strength of its own rollups. Marking
     * the row lets {@link #scan} exclude rollups from the count while still
     * seeing that one was already sent this window — the two facts the
     * decision needs, from one row, with no schema change.</p>
     *
     * <p>{@code error_message} is the carrier because it is the only nullable
     * free-text column on the row, it is null on every successful ordinary
     * send, and it is read back exclusively by this class. A rollup that
     * <em>fails</em> carries the marker followed by the real failure text, so
     * the diagnostic value of the column is preserved.</p>
     */
    static final String ROLLUP_MARKER = "[sentinel:rollup]";

    /**
     * How many completed PROBLEM→OK cycles inside {@link #FLAP_WINDOW} make a
     * trigger identity "flapping".
     *
     * <p>A constant rather than a per-action column because schema v4 added no
     * flap-tuning fields, and because flapping is a property of the trigger,
     * not of the action that would notify about it — a per-action setting
     * would let two actions disagree about whether the same trigger is
     * flapping. Four is chosen to sit clearly above normal behavior: a trigger
     * that genuinely recovers and re-breaches four times inside half an hour
     * is not reporting a sequence of distinct incidents an operator can act
     * on, it is oscillating around its own threshold, and the fix is to widen
     * the monitor's hysteresis rather than to keep paging.</p>
     */
    private static final int FLAP_MIN_CYCLES = 4;

    /** The window {@link #FLAP_MIN_CYCLES} is counted over. */
    private static final Duration FLAP_WINDOW = Duration.ofMinutes(30);

    /**
     * How far back the flap history query reaches, as a multiple of
     * {@link #FLAP_WINDOW}. The filter bounds {@code opened_time} but the
     * cycles are counted by {@code resolved_time}, so the query has to reach
     * back far enough to catch an event that opened before the window and
     * resolved inside it. Two windows is generous for the case that matters:
     * an event that took longer than {@link #FLAP_WINDOW} to resolve is by
     * definition not part of a flap.
     */
    private static final int FLAP_LOOKBACK_WINDOWS = 2;

    /**
     * Page size for the flap history read. A trigger identity that produced
     * more than this many events inside the lookback is flapping by any
     * definition, so truncation can only under-report a verdict that has
     * already been reached.
     */
    private static final int FLAP_SCAN_PAGE_SIZE = 100;

    /**
     * How many recent alert events {@link #scan} walks looking for dispatch
     * rows inside the window.
     *
     * <p>This is the cost bound on storm control. The dispatch log is indexed
     * and queried by alert event (that is the only shape the repository
     * exposes and the only shape any of the five mappers declares), so
     * "everything this action sent in the last N seconds" is assembled from
     * the events that could plausibly have produced those sends. 200 covers a
     * whole all-channels fan-out — the case the ceiling exists for — and
     * bounds the worst case to 200 single-row-key reads per
     * {@link #SNAPSHOT_TTL_MILLIS}, on the dispatch pool, and only when some
     * enabled action actually configures a ceiling.</p>
     */
    private static final int SCAN_EVENT_LIMIT = 200;

    /**
     * How long a scan result is reused before being recomputed.
     *
     * <p>Without reuse the ceiling would be O(storm²): every one of thirty
     * concurrent dispatch tasks would run its own thirty-event scan. Two
     * seconds is short enough that the snapshot never drifts meaningfully and
     * long enough that a burst arriving inside one evaluator tick shares a
     * single scan. Sends decided against a live snapshot are reserved on it
     * (see {@link Snapshot#reserved}), so reuse does not blind the ceiling to
     * what is happening right now.</p>
     */
    private static final long SNAPSHOT_TTL_MILLIS = 2000;

    /**
     * Lower clamp on {@code rollupWindowSeconds}. Must stay comfortably above
     * {@link #SNAPSHOT_TTL_MILLIS} so that a reservation taken against the
     * current snapshot is always still inside the window it was counted for.
     */
    private static final int MIN_ROLLUP_WINDOW_SECONDS = 10;

    /** Upper clamp on {@code rollupWindowSeconds}: a day. Beyond that the ceiling is a mute button. */
    private static final int MAX_ROLLUP_WINDOW_SECONDS = 86_400;

    /**
     * How many channel names a rollup notification lists before summarizing
     * the remainder as a count. A rollup that pastes four hundred channel ids
     * into an SMS gateway has recreated the problem it was written to solve.
     */
    private static final int ROLLUP_MAX_NAMED_CHANNELS = 25;

    /** Guards {@link #snapshot} refresh so a burst produces one scan, not one per thread. */
    private static final Object SNAPSHOT_LOCK = new Object();

    /**
     * The most recent window scan, or {@code null} before the first ceiling is
     * evaluated and after {@link #reset()}. Volatile because dispatch threads
     * read it without the lock on the common (still fresh) path.
     */
    private static volatile Snapshot snapshot;

    private AlertStormControl() {
    }

    /**
     * Drops the cached window scan. Called from
     * {@code ActionDispatcher.shutdownDispatchExecutor()} so a plugin restart
     * inside one JVM cannot inherit a snapshot describing a window that has
     * long since passed, and with it a set of reservations that would count
     * against the first post-restart window.
     */
    static void reset() {
        snapshot = null;
    }

    /** What the ceiling decided for one candidate send. */
    enum CeilingOutcome {
        /** Under the ceiling (or no ceiling configured): deliver normally. */
        SEND,
        /** Over the ceiling, and no rollup has gone out this window yet: send the rollup. */
        ROLLUP,
        /** Over the ceiling and the rollup for this window has already been sent: stay quiet. */
        SILENT
    }

    /**
     * A ceiling decision. {@code sent} and {@code windowSeconds} describe the
     * window that tripped, and {@code channels} names the channels a
     * {@link CeilingOutcome#ROLLUP} should report; both are empty/zero for
     * {@link CeilingOutcome#SEND}.
     */
    record CeilingVerdict(CeilingOutcome outcome, int sent, int windowSeconds, List<String> channels) {

        private static final CeilingVerdict SEND =
                new CeilingVerdict(CeilingOutcome.SEND, 0, 0, List.of());

        private static CeilingVerdict silent(int sent, int windowSeconds) {
            return new CeilingVerdict(CeilingOutcome.SILENT, sent, windowSeconds, List.of());
        }
    }

    /**
     * Decides — and, for {@link CeilingOutcome#SEND}, <em>reserves</em> — one
     * notification against this action's per-window ceiling.
     *
     * <p><b>How the ceiling is computed.</b> The window is
     * {@code [now - rollupWindowSeconds, now]}. Its contents come from
     * {@link #scan}: the most recent {@link #SCAN_EVENT_LIMIT} alert events,
     * each one's dispatch log read back, every row for this action whose
     * {@code dispatch_time} falls inside the window counted, and every row
     * carrying {@link #ROLLUP_MARKER} counted separately as "a rollup already
     * went out". That is the same read {@code ActionDispatcher.onRepeatCheck}
     * performs, widened from one event to the recent set, and it means the
     * count is reconstructed from durable rows rather than remembered — a
     * server restarted in the middle of a storm resumes with the ceiling
     * already most of the way consumed, instead of granting a fresh budget at
     * the worst possible moment.</p>
     *
     * <p><b>Why the verdict is a reservation.</b> Dispatch runs on a pool, so
     * several tasks can reach this method between one scan and the row inserts
     * that scan would have seen — and a plain read-then-decide would let all
     * four of them spend the last slot. Deciding and claiming are therefore a
     * single atomic step on the snapshot ({@link Snapshot#reserveSendIfUnder},
     * {@link Snapshot#reserveRollupIfNone}), which is what makes "at most N per
     * window" and "exactly one rollup per window" true rather than approximate.
     * Reservations are attempts, not successes, matching the rule the repeat
     * budget already follows: a transport that fails every time must still
     * exhaust its budget rather than retry forever. They live only until the
     * next scan replaces the snapshot, at which point the durable rows take
     * over. The one seam is a send whose row has not yet been committed when
     * the next scan runs — it is counted by neither for up to
     * {@link #SNAPSHOT_TTL_MILLIS}, so the ceiling can undercount by at most
     * the number of sends in flight during a single refresh.</p>
     *
     * <p>An action with only one half of the pair configured has no ceiling:
     * a maximum with no window has nothing to count against, and a window with
     * no maximum never trips. Both halves are separate nullable columns and
     * neither implies a default for the other.</p>
     *
     * @param action the action about to notify
     * @param now    the decision instant
     * @return the verdict; never {@code null}, and {@link CeilingOutcome#SEND}
     *         whenever the ceiling is unconfigured or could not be evaluated
     */
    static CeilingVerdict ceilingVerdict(Action action, Instant now) {
        try {
            Integer max = action.getMaxNotificationsPerWindow();
            Integer window = action.getRollupWindowSeconds();
            Integer actionId = action.getId();
            if (max == null || window == null || actionId == null || max < 0) {
                return CeilingVerdict.SEND;
            }

            int windowSeconds = clampWindow(window);
            Instant from = now.minusSeconds(windowSeconds);
            Snapshot snap = snapshotFor(now, windowSeconds);

            if (snap.reserveSendIfUnder(actionId, from, max)) {
                return CeilingVerdict.SEND;
            }
            int sent = snap.sentInWindow(actionId, from);
            if (!snap.reserveRollupIfNone(actionId, from, now)) {
                return CeilingVerdict.silent(sent, windowSeconds);
            }
            return new CeilingVerdict(CeilingOutcome.ROLLUP, sent, windowSeconds, snap.channels());
        } catch (Throwable t) {
            // Fail open: a ceiling that cannot be computed must not silence
            // the action it was meant to pace.
            log.warn("Storm-control ceiling check failed for action {} ('{}'); sending normally",
                    action != null ? action.getId() : null,
                    action != null ? action.getName() : null, t);
            return CeilingVerdict.SEND;
        }
    }

    /**
     * The operator-facing body of a rollup notification: what tripped, over
     * what window, and which channels are affected.
     *
     * @param verdict a {@link CeilingOutcome#ROLLUP} verdict
     * @return a single-paragraph message; never {@code null}
     */
    static String rollupMessage(CeilingVerdict verdict) {
        List<String> channels = verdict.channels();
        StringBuilder message = new StringBuilder()
                .append("Alert storm: this action reached its ceiling of ")
                .append(verdict.sent())
                .append(" notification(s) in ")
                .append(verdict.windowSeconds())
                .append(" seconds. Individual notifications are suppressed until the window clears; ")
                .append("this is the only rollup for this window. ");

        if (channels.isEmpty()) {
            message.append("No affected channel could be resolved.");
            return message.toString();
        }

        message.append(channels.size()).append(" channel(s) affected: ");
        int named = Math.min(channels.size(), ROLLUP_MAX_NAMED_CHANNELS);
        for (int i = 0; i < named; i++) {
            if (i > 0) {
                message.append(", ");
            }
            message.append(channels.get(i));
        }
        if (channels.size() > named) {
            message.append(" and ").append(channels.size() - named).append(" more");
        }
        message.append('.');
        return message.toString();
    }

    /** What the flap check decided for one lifecycle edge. */
    enum FlapOutcome {
        /** Not flapping (or undecidable): notify normally. */
        NOT_FLAPPING,
        /** This open edge is the one that crossed the threshold: notify once, saying so. */
        ONSET,
        /** Already announced as flapping and not yet stable: stay quiet. */
        SUPPRESSED
    }

    /** A flap verdict plus the cycle count and window it was reached on. */
    record FlapCheck(FlapOutcome outcome, int cycles, int windowMinutes) {

        private static final FlapCheck NOT_FLAPPING =
                new FlapCheck(FlapOutcome.NOT_FLAPPING, 0, 0);
    }

    /**
     * Decides whether this lifecycle edge belongs to a flapping trigger.
     *
     * <p><b>How flapping is derived without a new trigger state.</b> The
     * subject is the {@code (monitorId, channelId, metadataId)} identity, and
     * its history is already fully recorded in {@code sentinel_alert_event}:
     * one row per PROBLEM, with {@code resolved_time} set when it cleared. A
     * completed PROBLEM→OK cycle is therefore just a resolved row, and
     * "flapping" is "at least {@link #FLAP_MIN_CYCLES} rows for this identity
     * resolved inside {@link #FLAP_WINDOW}". Nothing is stored, nothing is
     * remembered between ticks, and {@code TriggerStatus} keeps exactly the
     * three values it always had.</p>
     *
     * <p><b>"Notifies once, then goes quiet" without a latch.</b> The single
     * notification is pinned to the edge that <em>crossed</em> the threshold,
     * which is recoverable from the same history: this open edge is the onset
     * if the identity is flapping as of now but was not yet flapping as of the
     * previous event's open time. Every later open edge sees the earlier event
     * already over the threshold and stays quiet. Once the old cycles age out
     * of the window the count falls back under the threshold, normal
     * notification resumes, and a fresh bout announces itself again — the
     * "until stable" half, with no state to reset.</p>
     *
     * <p>Resolve edges are never an onset and are silenced for as long as the
     * identity is flapping: a flap bout that announced one PROBLEM must not
     * then deliver a stream of RESOLVED notifications, which is the same noise
     * wearing different words. The bout's final recovery does notify, because
     * by then the cycles have aged out and the identity is no longer
     * flapping.</p>
     *
     * <p>The current event is excluded from its own history, so this is
     * insensitive to whether the caller has already written the row it is
     * dispatching for.</p>
     *
     * @param event         the event being dispatched
     * @param now           the decision instant
     * @param resolvedPhase {@code true} for the resolved edge
     * @return the verdict; never {@code null}, and
     *         {@link FlapOutcome#NOT_FLAPPING} whenever the history could not
     *         be read
     */
    static FlapCheck flapCheck(AlertEvent event, Instant now, boolean resolvedPhase) {
        try {
            if (event == null || event.getChannelId() == null) {
                return FlapCheck.NOT_FLAPPING;
            }

            List<AlertEvent> history = identityHistory(event, now);
            int windowMinutes = (int) FLAP_WINDOW.toMinutes();

            Instant at = !resolvedPhase && event.getOpenedTime() != null ? event.getOpenedTime() : now;
            int cycles = cyclesAsOf(history, at);
            if (cycles < FLAP_MIN_CYCLES) {
                return FlapCheck.NOT_FLAPPING;
            }
            if (resolvedPhase) {
                return new FlapCheck(FlapOutcome.SUPPRESSED, cycles, windowMinutes);
            }

            AlertEvent previous = previousEvent(history, at);
            if (previous != null && previous.getOpenedTime() != null
                    && cyclesAsOf(history, previous.getOpenedTime()) >= FLAP_MIN_CYCLES) {
                // An earlier open edge already crossed the threshold and
                // carried the one notification for this bout.
                return new FlapCheck(FlapOutcome.SUPPRESSED, cycles, windowMinutes);
            }
            return new FlapCheck(FlapOutcome.ONSET, cycles, windowMinutes);
        } catch (Throwable t) {
            // Fail open, as everywhere here: a flap check that cannot read its
            // history must not suppress a genuine alert.
            log.warn("Flap check failed for alert event {}; notifying normally",
                    event != null ? event.getId() : null, t);
            return FlapCheck.NOT_FLAPPING;
        }
    }

    /**
     * The flap-onset message: what is oscillating, how fast, and that this is
     * the last word until it settles.
     *
     * @param check   an {@link FlapOutcome#ONSET} verdict
     * @param message the event's own message, appended so the notification
     *                still says what actually broke
     * @return a single-paragraph message; never {@code null}
     */
    static String flappingMessage(FlapCheck check, String message) {
        StringBuilder text = new StringBuilder()
                .append("Flapping: this trigger has opened and cleared ")
                .append(check.cycles())
                .append(" time(s) in the last ")
                .append(check.windowMinutes())
                .append(" minutes. This is the only notification until it stabilizes — widen the ")
                .append("monitor's consecutive-breach threshold if this is expected. Latest: ");
        text.append(message == null || message.isBlank() ? "(no message)" : message);
        return text.toString();
    }

    /** Whether a dispatch row records a rollup notification rather than an ordinary one. */
    private static boolean isRollupRow(ActionDispatchLog row) {
        String error = row.getErrorMessage();
        return error != null && error.startsWith(ROLLUP_MARKER);
    }

    /** Keeps an operator-supplied rollup window inside the range this class can honor. */
    private static int clampWindow(int windowSeconds) {
        return Math.max(MIN_ROLLUP_WINDOW_SECONDS, Math.min(MAX_ROLLUP_WINDOW_SECONDS, windowSeconds));
    }

    /**
     * Returns a snapshot covering at least {@code windowSeconds} and no older
     * than {@link #SNAPSHOT_TTL_MILLIS}, scanning if necessary.
     *
     * <p>A snapshot taken for a wider window serves a narrower one — callers
     * filter by their own {@code from} — so only a request for a wider window
     * than the current snapshot covers forces a rescan.</p>
     *
     * <p>The scan happens under {@link #SNAPSHOT_LOCK}, holding the other
     * dispatch threads for its duration on purpose: they then reuse the fresh
     * result instead of each starting a redundant scan of the same rows, which
     * during a storm is the difference between one scan and one per
     * notification.</p>
     */
    private static Snapshot snapshotFor(Instant now, int windowSeconds) {
        Snapshot current = snapshot;
        if (isUsable(current, now, windowSeconds)) {
            return current;
        }
        synchronized (SNAPSHOT_LOCK) {
            current = snapshot;
            if (isUsable(current, now, windowSeconds)) {
                return current;
            }
            // Never narrow: carrying the previous width forward keeps two
            // actions with different windows from alternately forcing a
            // rescan of each other's, which would defeat the whole point of
            // caching a scan at all.
            int width = current == null ? windowSeconds : Math.max(windowSeconds, current.windowSeconds);
            Snapshot fresh = scan(now, width);
            snapshot = fresh;
            return fresh;
        }
    }

    private static boolean isUsable(Snapshot candidate, Instant now, int windowSeconds) {
        return candidate != null
                && candidate.windowSeconds >= windowSeconds
                && !candidate.scannedAt.isBefore(now.minusMillis(SNAPSHOT_TTL_MILLIS));
    }

    /**
     * Reconstructs the window from the dispatch log.
     *
     * <p>Walks the most recent {@link #SCAN_EVENT_LIMIT} alert events and
     * reads the dispatch rows of the ones that could have produced traffic
     * inside the window: those opened inside it (the fan-out case) and those
     * still open (their repeats, escalations and eventual resolution land
     * inside it however long ago they opened). Events that are both older than
     * the window and already resolved are skipped without a query.</p>
     *
     * <p>The affected-channel list is built from the same pass: the distinct,
     * unsuppressed channels that opened a problem inside the window. That is
     * the set an operator reading a rollup wants — "what is broken right
     * now" — and it deliberately does not re-run each action's condition
     * filter against every event, which would cost a condition evaluation per
     * event per action to sharpen a summary line.</p>
     */
    private static Snapshot scan(Instant now, int windowSeconds) {
        Instant from = now.minusSeconds(windowSeconds);
        Map<Integer, List<Instant>> sends = new HashMap<>();
        Map<Integer, Instant> rollups = new HashMap<>();
        // Keyed on channel id, not on the resolved name: two channels can
        // share a display name, and every deleted channel resolves to the
        // same "(unknown)" placeholder, so deduplicating on the name would
        // undercount exactly the storm the rollup is reporting.
        Map<String, String> affected = new TreeMap<>();

        List<AlertEvent> recent = AlertEventRepository.listRecentAlertEvents(SCAN_EVENT_LIMIT);
        int scanned = 0;
        for (AlertEvent event : recent) {
            if (event.getId() == null) {
                continue;
            }
            boolean openedInWindow = event.getOpenedTime() != null && !event.getOpenedTime().isBefore(from);
            boolean stillOpen = event.getStatus() == AlertStatus.PROBLEM;
            if (!openedInWindow && !stillOpen) {
                continue;
            }
            if (openedInWindow && !event.isSuppressed() && event.getChannelId() != null) {
                affected.computeIfAbsent(event.getChannelId(), ScopeResolver::channelName);
            }

            scanned++;
            for (ActionDispatchLog row : ActionDispatchLogRepository
                    .listActionDispatchLogsForEvent(event.getId())) {
                Integer actionId = row.getActionId();
                Instant at = row.getDispatchTime();
                if (actionId == null || at == null || at.isBefore(from)) {
                    continue;
                }
                if (isRollupRow(row)) {
                    Instant newest = rollups.get(actionId);
                    if (newest == null || at.isAfter(newest)) {
                        rollups.put(actionId, at);
                    }
                } else {
                    sends.computeIfAbsent(actionId, id -> new ArrayList<>()).add(at);
                }
            }
        }

        log.debug("Storm-control window scan: {}s window, {} of {} recent events read, "
                + "{} action(s) with traffic", windowSeconds, scanned, recent.size(), sends.size());
        return new Snapshot(now, windowSeconds, sends, rollups, List.copyOf(affected.values()));
    }

    /**
     * The alert-event history for one trigger identity, most recent first,
     * excluding the event being dispatched.
     *
     * <p>Filtered in SQL by monitor, channel and open time; the metadata id is
     * matched here because the filter has no column for it (the Problems list
     * never needed one) and adding one would mean touching all five mappers.
     * The channel filter already reduces the row count to a single channel's
     * events, so the in-Java pass is over a handful of rows.</p>
     */
    private static List<AlertEvent> identityHistory(AlertEvent event, Instant now) {
        AlertEventFilter filter = new AlertEventFilter();
        filter.setMonitorId(event.getMonitorId());
        filter.setChannelIdIn(List.of(event.getChannelId()));
        filter.setFrom(now.minus(FLAP_WINDOW.multipliedBy(FLAP_LOOKBACK_WINDOWS)));
        filter.setSortColumn("opened_time");
        filter.setSortDir("DESC");
        filter.setPage(0);
        filter.setPageSize(FLAP_SCAN_PAGE_SIZE);

        List<AlertEvent> history = new ArrayList<>();
        for (AlertEvent candidate : AlertEventRepository.listAlertEvents(filter).items()) {
            if (Objects.equals(candidate.getId(), event.getId())) {
                continue;
            }
            if (!Objects.equals(candidate.getMetadataId(), event.getMetadataId())) {
                continue;
            }
            history.add(candidate);
        }
        return history;
    }

    /** Completed PROBLEM→OK cycles for the identity in the window ending at {@code at}. */
    private static int cyclesAsOf(List<AlertEvent> history, Instant at) {
        Instant from = at.minus(FLAP_WINDOW);
        int cycles = 0;
        for (AlertEvent event : history) {
            Instant resolved = event.getResolvedTime();
            if (resolved != null && !resolved.isBefore(from) && !resolved.isAfter(at)) {
                cycles++;
            }
        }
        return cycles;
    }

    /** The identity's most recent event opened strictly before {@code at}, or {@code null}. */
    private static AlertEvent previousEvent(List<AlertEvent> history, Instant at) {
        AlertEvent newest = null;
        for (AlertEvent event : history) {
            Instant opened = event.getOpenedTime();
            if (opened == null || !opened.isBefore(at)) {
                continue;
            }
            if (newest == null || opened.isAfter(newest.getOpenedTime())) {
                newest = event;
            }
        }
        return newest;
    }

    /**
     * One reconstruction of the notification window: what the dispatch log
     * said at {@link #scannedAt}, plus the sends reserved against it since.
     *
     * <p>Immutable except for the two reservation maps, which exist only for
     * the snapshot's short life and are discarded wholesale when it is
     * replaced. That is what keeps the reservations from being "state that
     * dies on restart": they are never the record of anything, only a
     * correction covering the seconds between a scan and the rows that scan
     * would have seen.</p>
     */
    private static final class Snapshot {

        private final Instant scannedAt;
        private final int windowSeconds;
        private final Map<Integer, List<Instant>> sends;
        private final Map<Integer, Instant> rollups;
        private final List<String> channels;

        /** Ordinary sends decided against this snapshot, per action id. */
        private final Map<Integer, AtomicInteger> reserved = new ConcurrentHashMap<>();

        /** Rollups decided against this snapshot, per action id. */
        private final Map<Integer, Instant> reservedRollups = new ConcurrentHashMap<>();

        private Snapshot(Instant scannedAt, int windowSeconds, Map<Integer, List<Instant>> sends,
                Map<Integer, Instant> rollups, List<String> channels) {
            this.scannedAt = scannedAt;
            this.windowSeconds = windowSeconds;
            this.sends = sends;
            this.rollups = rollups;
            this.channels = channels;
        }

        /** Logged sends in {@code [from, scannedAt]} plus everything reserved since. */
        private int sentInWindow(Integer actionId, Instant from) {
            AtomicInteger since = reserved.get(actionId);
            return loggedInWindow(actionId, from) + (since != null ? since.get() : 0);
        }

        /** Logged (non-rollup) sends for this action inside the window. */
        private int loggedInWindow(Integer actionId, Instant from) {
            List<Instant> logged = sends.get(actionId);
            if (logged == null) {
                return 0;
            }
            int count = 0;
            for (Instant at : logged) {
                if (!at.isBefore(from)) {
                    count++;
                }
            }
            return count;
        }

        /**
         * Claims one send slot if the window is still under {@code max}.
         *
         * <p>The compare-and-set loop is what keeps the ceiling exact when
         * several dispatch threads reach it at once: each caller re-reads the
         * pending count and only wins if the total it computed is still the
         * total when it commits, so two threads cannot both spend the last
         * slot.</p>
         *
         * @return {@code true} if a slot was claimed and the caller may send
         */
        private boolean reserveSendIfUnder(Integer actionId, Instant from, int max) {
            int logged = loggedInWindow(actionId, from);
            AtomicInteger since = reserved.computeIfAbsent(actionId, id -> new AtomicInteger());
            while (true) {
                int pending = since.get();
                if (logged + pending >= max) {
                    return false;
                }
                if (since.compareAndSet(pending, pending + 1)) {
                    return true;
                }
            }
        }

        /**
         * Claims the window's single rollup slot if no rollup has gone out yet
         * — neither logged nor claimed by a concurrent caller.
         *
         * <p>{@link java.util.concurrent.ConcurrentHashMap#compute} makes the
         * "is there one already / put mine" pair atomic per action, so exactly
         * one of several simultaneous over-ceiling sends sends the rollup and
         * the rest go quiet. A reservation left over from an earlier window
         * (it falls before {@code from}) is replaced rather than honored.</p>
         *
         * @return {@code true} if the caller owns this window's rollup
         */
        private boolean reserveRollupIfNone(Integer actionId, Instant from, Instant at) {
            Instant logged = rollups.get(actionId);
            if (logged != null && !logged.isBefore(from)) {
                return false;
            }
            // One-element array because the remapping function runs under the
            // map's per-key lock and cannot return two things; this is the
            // conventional way to carry "did I win?" out of it.
            boolean[] claimed = { false };
            reservedRollups.compute(actionId, (id, existing) -> {
                if (existing != null && !existing.isBefore(from)) {
                    return existing;
                }
                claimed[0] = true;
                return at;
            });
            return claimed[0];
        }

        private List<String> channels() {
            return channels;
        }
    }
}
