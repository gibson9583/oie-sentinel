/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionTestResult;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * Fans an alert-event lifecycle edge out to every enabled, matching action —
 * the single place where "an alert opened/resolved/is still open" becomes
 * actual notifications.
 *
 * <p>The overriding contract, honored by every public method here: <b>never
 * throw</b>. These methods run inside the evaluator tick (and, for
 * {@link #sendTest}, inside a REST request); a broken SMTP server or an
 * unreachable SNS endpoint must cost one failed dispatch-log row, never the
 * evaluation of the remaining monitors. Every layer — the whole dispatch,
 * each action, each log insert — has its own catch, so one bad action cannot
 * starve the others either.</p>
 *
 * <p>Every real delivery attempt (success or failure, initial or repeat) is
 * recorded in {@code sentinel_action_dispatch_log}. That log is not just an
 * audit trail: {@link #onRepeatCheck} reads it back as the source of truth
 * for repeat pacing, which makes repeat state survive a server restart
 * without any in-memory bookkeeping. Test sends are deliberately NOT logged
 * — the log tracks real alert traffic only, and a test row would advance the
 * repeat pacing of a real open alert.</p>
 *
 * <p>Suppression is enforced by the evaluator before calling the lifecycle
 * hooks, but each hook re-checks {@code event.isSuppressed()} as defense in
 * depth: a suppressed alert that produced notifications anyway would defeat
 * the entire point of maintenance windows.</p>
 *
 * <p>Stateless static utility (private constructor) per the plugin's house
 * style; the shared sender instances are themselves stateless.</p>
 */
public final class ActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatcher.class);

    /**
     * The {@code sentinel_action_dispatch_log.error_message} column is
     * {@code VARCHAR(1024)} on every vendor, and the repositories pass
     * values through untruncated — an oversize message would fail the very
     * insert that records the failure, and a missing row makes
     * {@link #onRepeatCheck} treat the action as never-attempted (re-send
     * every tick). 1000 leaves headroom for vendors that count bytes rather
     * than characters (Oracle {@code VARCHAR2}) when the message contains
     * the odd non-ASCII character.
     */
    private static final int MAX_ERROR_LENGTH = 1000;

    /**
     * Senders are stateless (per {@link AlertSender}) so one shared instance
     * each avoids per-dispatch allocation without any lifecycle to manage.
     */
    private static final AlertSender EMAIL_SENDER = new EmailAlertSender();
    private static final AlertSender CHANNEL_SENDER = new ChannelAlertSender();
    private static final AlertSender SNS_SENDER = new SnsAlertSender();

    private ActionDispatcher() {
    }

    /**
     * Dispatches the "alert opened" edge: every enabled action whose
     * operation mode is ON_PROBLEM or BOTH and whose condition matches gets
     * one delivery attempt, each attempt logged. Never throws.
     *
     * @param event   the freshly opened alert event (already persisted, id set)
     * @param payload the display-resolved snapshot built by the evaluator
     */
    public static void onAlertOpened(AlertEvent event, AlertPayload payload) {
        dispatchLifecycle(event, payload, false);
    }

    /**
     * Dispatches the "alert resolved" edge: every enabled action whose
     * operation mode is ON_RESOLVE or BOTH and whose condition matches gets
     * one delivery attempt, each attempt logged. Never throws.
     *
     * @param event   the just-resolved alert event
     * @param payload the display-resolved snapshot (eventType "RESOLVED")
     */
    public static void onAlertResolved(AlertEvent event, AlertPayload payload) {
        dispatchLifecycle(event, payload, true);
    }

    /**
     * Re-notification pass, called by the evaluator on every tick an alert
     * stays open. Acknowledged alerts do not repeat at all — an ack means
     * "someone is on it", silencing the pager without resolving the problem.
     * Otherwise, only actions that opted in via
     * {@code repeatIntervalSeconds} participate; for each, the dispatch log
     * decides what happens:
     *
     * <ul>
     *   <li>No rows for this (event, action) yet — send now. This covers an
     *       action created (or re-enabled) after the alert opened, which
     *       would otherwise stay silent for the alert's whole lifetime.</li>
     *   <li>Rows exist — re-send only when the attempt count is still under
     *       {@code 1 + maxRepeats} (a {@code null} maxRepeats means
     *       unlimited) AND the newest attempt is at least
     *       {@code repeatIntervalSeconds} old.</li>
     * </ul>
     *
     * <p>ATTEMPTS are counted, not successes: a permanently failing
     * transport must exhaust its repeat budget and go quiet, not retry every
     * tick forever filling the log. Never throws.</p>
     *
     * @param event   the still-open alert event
     * @param payload the display-resolved snapshot (eventType "PROBLEM")
     */
    public static void onRepeatCheck(AlertEvent event, AlertPayload payload) {
        try {
            if (event == null || event.getId() == null) {
                log.warn("Repeat check invoked without a persisted alert event; ignoring");
                return;
            }
            if (event.isSuppressed()) {
                // See class Javadoc: a suppressed alert must produce no
                // notifications — including a "first" repeat send for an
                // action with no log rows yet.
                return;
            }
            if (event.getAcknowledgedBy() != null) {
                // An acknowledged problem is being worked: stop re-notifying.
                // The alert stays open (ack never resolves), so the trigger
                // cannot re-fire a duplicate while the condition persists,
                // and the resolve notification still goes out when it clears.
                return;
            }

            List<Action> actions = ActionRepository.listActions(Boolean.TRUE);
            if (actions == null || actions.isEmpty()) {
                return;
            }
            // One log read shared by all actions for this event; the per-tick
            // volume is one query instead of one per action.
            List<ActionDispatchLog> allRows =
                    ActionDispatchLogRepository.listActionDispatchLogsForEvent(event.getId());
            Instant now = Instant.now();

            for (Action action : actions) {
                try {
                    if (action.getRepeatIntervalSeconds() == null) {
                        continue;
                    }
                    if (!firesOnPhase(action.getOperationMode(), false)) {
                        continue;
                    }
                    if (!ActionConditionMatcher.matches(action, payload)) {
                        continue;
                    }

                    List<ActionDispatchLog> rows = rowsForAction(allRows, action);
                    if (rows.isEmpty()) {
                        attempt(action, event, payload);
                        continue;
                    }

                    Integer maxRepeats = action.getMaxRepeats();
                    if (maxRepeats != null && rows.size() >= 1 + maxRepeats) {
                        continue;
                    }
                    Instant newest = newestDispatchTime(rows);
                    if (!newest.isAfter(now.minusSeconds(action.getRepeatIntervalSeconds()))) {
                        attempt(action, event, payload);
                    }
                } catch (Throwable t) {
                    log.error("Repeat check failed for action {} ('{}') on alert event {}",
                            action.getId(), action.getName(), event.getId(), t);
                }
            }
        } catch (Throwable t) {
            log.error("Repeat check failed for alert event {}",
                    event != null ? event.getId() : null, t);
        }
    }

    /**
     * Sends a real test notification through the action's transport with a
     * fully synthetic payload — so an operator verifies SMTP/SNS/channel
     * wiring at configuration time instead of discovering it broken during
     * an incident. The delivery genuinely happens (this is not a dry run),
     * but the condition filter and operation mode are deliberately bypassed
     * (the operator is testing the transport, not the routing) and no
     * dispatch-log row is written (see class Javadoc).
     *
     * <p>Never throws: any failure — including a repository or config
     * problem — comes back as an unsuccessful {@link ActionTestResult} with
     * the reason, which is exactly what the "Test" button should display.</p>
     *
     * @param action the action to test, with its config UNredacted (the
     *               caller must pass the stored config, not the
     *               REST-redacted copy, or STATIC SNS auth would try to use
     *               bullet characters as a secret)
     * @return the outcome; never {@code null}
     */
    public static ActionTestResult sendTest(Action action) {
        ActionTestResult result = new ActionTestResult();
        try {
            if (action == null) {
                result.setSuccess(false);
                result.setMessage("No action provided");
                return result;
            }
            AlertSender sender = senderFor(action.getActionType());
            if (sender == null) {
                result.setSuccess(false);
                result.setMessage("Unknown action type: " + action.getActionType());
                return result;
            }

            Instant now = Instant.now();
            // eventType "TEST" (not "PROBLEM") so no recipient can mistake a
            // wiring check for a live alert; alertEventId/monitorId 0 marks
            // it as belonging to no real row.
            AlertPayload payload = new AlertPayload(0L, 0, "Sentinel test", null,
                    null, "Sentinel test", null, Severity.INFORMATION, "TEST",
                    "This is a test notification sent from OIE Sentinel to verify action delivery.",
                    now, "{\"test\":true}");
            sender.send(action, syntheticEvent(now), payload);

            result.setSuccess(true);
            result.setMessage("Test notification sent via " + action.getActionType());
        } catch (Throwable t) {
            log.warn("Test send failed for action {} ('{}')",
                    action != null ? action.getId() : null,
                    action != null ? action.getName() : null, t);
            result.setSuccess(false);
            result.setMessage(exceptionMessage(t));
        }
        return result;
    }

    /**
     * Shared open/resolve dispatch: filter enabled actions by phase and
     * condition, attempt each, log each (see {@link #attempt}).
     *
     * @param resolvedPhase {@code false} for the opened edge (ON_PROBLEM /
     *                      BOTH fire), {@code true} for the resolved edge
     *                      (ON_RESOLVE / BOTH fire)
     */
    private static void dispatchLifecycle(AlertEvent event, AlertPayload payload, boolean resolvedPhase) {
        try {
            if (event == null || event.getId() == null) {
                log.warn("Dispatch invoked without a persisted alert event; ignoring");
                return;
            }
            if (event.isSuppressed()) {
                // Defense in depth — the evaluator already skips suppressed
                // events, but notifications leaking through a maintenance
                // window would be a policy violation, not just a bug.
                return;
            }

            List<Action> actions = ActionRepository.listActions(Boolean.TRUE);
            if (actions == null || actions.isEmpty()) {
                return;
            }
            for (Action action : actions) {
                try {
                    if (!firesOnPhase(action.getOperationMode(), resolvedPhase)) {
                        continue;
                    }
                    if (!ActionConditionMatcher.matches(action, payload)) {
                        continue;
                    }
                    attempt(action, event, payload);
                } catch (Throwable t) {
                    log.error("Dispatch failed for action {} ('{}') on alert event {}",
                            action.getId(), action.getName(), event.getId(), t);
                }
            }
        } catch (Throwable t) {
            log.error("Alert action dispatch failed for event {}",
                    event != null ? event.getId() : null, t);
        }
    }

    /**
     * One delivery attempt plus its log row. The row is written whether the
     * send succeeded or failed — the log's dual role (audit trail AND repeat
     * pacing state, see class Javadoc) requires recording attempts, not
     * outcomes. A failed log insert is itself only logged: losing one
     * bookkeeping row must not cascade into losing the remaining actions'
     * deliveries.
     */
    private static void attempt(Action action, AlertEvent event, AlertPayload payload) {
        boolean success;
        String errorMessage = null;
        try {
            AlertSender sender = senderFor(action.getActionType());
            if (sender == null) {
                throw new Exception("Unknown action type: " + action.getActionType());
            }
            sender.send(action, event, payload);
            success = true;
        } catch (Throwable t) {
            success = false;
            errorMessage = truncate(exceptionMessage(t));
            log.warn("Action {} ('{}') failed for alert event {}: {}",
                    action.getId(), action.getName(), event.getId(), errorMessage);
        }

        try {
            ActionDispatchLog row = new ActionDispatchLog();
            row.setAlertEventId(event.getId());
            row.setActionId(action.getId());
            row.setDispatchTime(Instant.now());
            row.setSuccess(success);
            row.setErrorMessage(errorMessage);
            ActionDispatchLogRepository.insertActionDispatchLog(row);
        } catch (Throwable t) {
            log.error("Failed to record dispatch log for action {} on alert event {}",
                    action.getId(), event.getId(), t);
        }
    }

    /**
     * Phase filter: ON_PROBLEM and ON_RESOLVE are one-edge subscriptions,
     * BOTH fires on either edge; a {@code null} mode (malformed row) fires
     * on neither.
     */
    private static boolean firesOnPhase(OperationMode mode, boolean resolvedPhase) {
        if (mode == OperationMode.BOTH) {
            return true;
        }
        return resolvedPhase ? mode == OperationMode.ON_RESOLVE : mode == OperationMode.ON_PROBLEM;
    }

    /**
     * Filters the event's dispatch rows down to one action's attempts.
     * Rows whose {@code actionId} is {@code null} (their action was deleted;
     * the FK is ON DELETE SET NULL) belong to no live action and are
     * excluded from everyone's pacing.
     */
    private static List<ActionDispatchLog> rowsForAction(List<ActionDispatchLog> allRows, Action action) {
        List<ActionDispatchLog> rows = new ArrayList<>();
        if (allRows == null) {
            return rows;
        }
        for (ActionDispatchLog row : allRows) {
            if (row.getActionId() != null && row.getActionId().equals(action.getId())) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Newest attempt time among the rows; a defensively-null dispatch time
     * counts as the epoch so a corrupt row can only make a repeat happen
     * sooner, never suppress it forever.
     */
    private static Instant newestDispatchTime(List<ActionDispatchLog> rows) {
        Instant newest = Instant.EPOCH;
        for (ActionDispatchLog row : rows) {
            Instant time = row.getDispatchTime();
            if (time != null && time.isAfter(newest)) {
                newest = time;
            }
        }
        return newest;
    }

    /** Maps the action type to its shared sender; {@code null} for unknown/null types. */
    private static AlertSender senderFor(ActionType actionType) {
        if (actionType == null) {
            return null;
        }
        switch (actionType) {
            case EMAIL:
                return EMAIL_SENDER;
            case CHANNEL:
                return CHANNEL_SENDER;
            case SNS:
                return SNS_SENDER;
            default:
                return null;
        }
    }

    /**
     * Minimal but complete synthetic event backing a test send, so a sender
     * that reads the event (none of the built-ins do — they use the payload)
     * still sees consistent data instead of nulls.
     */
    private static AlertEvent syntheticEvent(Instant now) {
        AlertEvent event = new AlertEvent();
        event.setId(0L);
        event.setMonitorId(0);
        event.setSeverity(Severity.INFORMATION);
        event.setStatus(AlertStatus.PROBLEM);
        event.setMessage("Sentinel test notification");
        event.setOpenedTime(now);
        return event;
    }

    /**
     * Extracts a human-usable failure reason: the exception message when
     * there is one, else the exception class name (an NPE's {@code null}
     * message must still produce a diagnosable log row).
     */
    private static String exceptionMessage(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getName();
        }
        return message;
    }

    /** Applies the {@link #MAX_ERROR_LENGTH} budget to stored failure reasons. */
    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }
}
