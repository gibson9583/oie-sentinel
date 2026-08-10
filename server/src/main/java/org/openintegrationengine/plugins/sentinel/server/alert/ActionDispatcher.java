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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p><b>Nothing is delivered on the caller's thread.</b>
 * {@link #onAlertOpened}, {@link #onAlertResolved} and {@link #onRepeatCheck}
 * do no work beyond handing a task to the small bounded pool this class owns.
 * The reason is a real outage mode, not tidiness: an SMTP handshake, an SNS
 * publish and a VMRouter dispatch are all synchronous network calls, and they
 * used to run on the Quartz evaluator thread inside
 * {@code @DisallowConcurrentExecution}. One upstream failure that breaches
 * 200 channels at once therefore performed 200 serial sends before the tick
 * could return — at a 30 second SMTP timeout, over an hour during which no
 * monitor of any type was evaluated. The scheduler pool is three threads and
 * its misfire policy skips missed ticks, so those evaluations were never made
 * up: monitoring stopped silently during exactly the incident it exists to
 * catch. Off-thread dispatch bounds the evaluator's cost per notification to
 * an enqueue.</p>
 *
 * <p>Saturation is resolved by <b>dropping and logging</b>, deliberately NOT
 * by {@code CallerRunsPolicy}. Caller-runs executes the rejected task on the
 * submitting thread — the evaluator thread — which would quietly restore the
 * exact stall this pool exists to prevent, and would do it at the worst
 * possible moment, since the queue can only fill during a mass breach or a
 * transport outage. A lost notification with a loud WARN is recoverable by an
 * operator reading the log; a stalled evaluator is not recoverable at all.</p>
 *
 * <p>The overriding contract, honored by every public method here: <b>never
 * throw</b>. For the three asynchronous hooks that contract lives inside the
 * submitted task (a {@link Throwable} escaping a pool worker kills its
 * thread), with the enqueue itself guarded as well; {@link #sendTest} keeps
 * it on the REST request thread, which must have the result to return. A
 * broken SMTP server or an unreachable SNS endpoint must cost one failed
 * dispatch-log row, never the evaluation of the remaining monitors. Every
 * layer — the whole dispatch, each action, each log insert — has its own
 * catch, so one bad action cannot starve the others either.</p>
 *
 * <p>Every real delivery attempt (success or failure, initial, repeat, rollup
 * or escalation) is recorded in {@code sentinel_action_dispatch_log}. That log
 * is not just an audit trail: {@link #onRepeatCheck} reads it back as the
 * source of truth for repeat pacing, {@link AlertStormControl} reads it back
 * as the source of truth for the per-action ceiling, and escalation reads it
 * back to decide whether a target has already been told. All of that state
 * therefore survives a server restart without any in-memory bookkeeping. Test
 * sends are deliberately NOT logged — the log tracks real alert traffic only,
 * and a test row would advance the repeat pacing of a real open alert.</p>
 *
 * <p>That durable pacing is also why going asynchronous costs nothing in
 * correctness, with one ordering caveat: because {@link #onRepeatCheck} reads
 * the log to decide whether to send, two ticks whose tasks overlap could both
 * read it before either writes its row and double-notify. The
 * {@link #inFlightPacedSends} set closes that window in memory; see
 * {@link #attemptPaced} for why its entries cannot leak.</p>
 *
 * <p><b>The order the gates apply.</b> Every candidate notification passes
 * through the same three stages, in this order, and an operator who has
 * configured all of them needs to know it is this order and not another:</p>
 *
 * <ol>
 *   <li><b>Suppress.</b> {@code event.isSuppressed()} (maintenance window or
 *       dependency), acknowledgment on the repeat path, and flap detection
 *       (see {@link AlertStormControl#flapCheck}). A notification stopped
 *       here does not happen at all — it is not counted, not rolled up and
 *       not escalated, because a suppressed alert must be invisible to
 *       everything downstream.</li>
 *   <li><b>Ceiling.</b> {@link AlertStormControl#ceilingVerdict} decides
 *       whether this action may still send individually inside its rollup
 *       window. Past the ceiling the individual send becomes one rollup
 *       notification naming the affected channels, and then silence for the
 *       rest of the window. This applies to opens, resolves, repeats and
 *       escalation sends alike: it is a budget on the action's total
 *       notification volume, not on any one problem.</li>
 *   <li><b>Escalate.</b> Independently of what stage 2 decided, a problem
 *       still open past {@code escalateAfterSeconds} routes to
 *       {@code escalateToActionId} (see {@link #maybeEscalate}). Escalation
 *       is deliberately not conditional on the source action having actually
 *       delivered — a source silenced by its own ceiling is, if anything, a
 *       stronger reason to widen the audience. The escalated send is itself
 *       subject to the <em>target's</em> ceiling.</li>
 * </ol>
 *
 * <p>Repeat and escalation are independent axes over the same open problem:
 * {@code repeatIntervalSeconds} re-notifies the same action, escalation hands
 * the problem to a different one, and an action may have either, both or
 * neither. An acknowledged problem does neither.</p>
 *
 * <p>Suppression is enforced by the evaluator before calling the lifecycle
 * hooks, but each hook re-checks {@code event.isSuppressed()} as defense in
 * depth: a suppressed alert that produced notifications anyway would defeat
 * the entire point of maintenance windows.</p>
 *
 * <p>Static utility (private constructor) per the plugin's house style; the
 * shared sender instances are themselves stateless. The only mutable state is
 * the dispatch pool and its in-flight set, whose lifecycle hangs off
 * {@code SentinelServicePlugin.start()} / {@code stop()} alongside the
 * scheduler's.</p>
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
    private static final AlertSender WEBHOOK_SENDER = new WebhookAlertSender();

    /**
     * Dispatch pool width. Four is enough that a few slow transports (a 30
     * second SMTP timeout, an unreachable SNS endpoint) cannot head-of-line
     * block the rest of a fan-out, and small enough that a mass breach cannot
     * turn into hundreds of concurrent SMTP connections against a mail server
     * that is very likely already unhealthy — the point is to protect the
     * evaluator, not to deliver a storm as fast as physically possible.
     */
    private static final int DISPATCH_THREADS = 4;

    /**
     * Queue depth. 500 absorbs a whole all-channels fan-out (the case that
     * motivated this pool) with headroom. A backlog past that is not a burst
     * any more, it is a delivery outage, and anything queued behind it would
     * arrive far too late to act on — so the bound is where notifications
     * start being dropped loudly instead of piling up invisibly.
     */
    private static final int DISPATCH_QUEUE_CAPACITY = 500;

    /**
     * Idle pool threads retire after this long, so a quiet engine carries no
     * parked dispatch threads at all (they are created on demand).
     */
    private static final long DISPATCH_THREAD_KEEPALIVE_SECONDS = 60;

    /**
     * How long {@link #shutdownDispatchExecutor()} waits for in-flight sends
     * to finish before giving up on them. Deliberately short: shutdown must
     * not be held hostage by a transport that is already timing out.
     */
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    /** Numbers the pool threads so thread dumps read "sentinel-dispatch-1", "-2"... */
    private static final AtomicInteger DISPATCH_THREAD_SEQUENCE = new AtomicInteger();

    /**
     * How many links of an escalation chain one repeat check will walk.
     *
     * <p>{@code escalateToActionId} carries no foreign key by design (see
     * {@code Action.getEscalateToActionId()}), so nothing at the schema level
     * prevents A → B → A, or a chain long enough to be a mistake. The visited
     * set in {@link #maybeEscalate} already makes a cycle terminate; this bound
     * is the second, independent guard, and it is the one that holds even if a
     * future edit to the walk drops the set. Four links is more escalation
     * depth than any on-call rotation has tiers, so hitting it means a
     * misconfiguration worth a warning rather than a chain worth completing.</p>
     */
    private static final int MAX_ESCALATION_HOPS = 4;

    /**
     * The dispatch pool, or {@code null} whenever the plugin is stopped.
     * Volatile because the plugin lifecycle thread writes it while evaluator
     * ticks read it.
     */
    private static volatile ThreadPoolExecutor dispatchExecutor;

    /**
     * {@code alertEventId:actionId} keys for log-paced sends (repeats and
     * escalations) that have passed their "should I send?" decision but have
     * not yet written the dispatch log row that decision reads. See
     * {@link #attemptPaced} — including why membership is added on the worker
     * thread and not at submit time.
     */
    private static final Set<String> inFlightPacedSends = ConcurrentHashMap.newKeySet();

    private ActionDispatcher() {
    }

    /**
     * Creates the dispatch pool. Called from
     * {@code SentinelServicePlugin.start()} ahead of the scheduler, so the
     * very first evaluator tick already has somewhere to hand its sends —
     * a tick that found no pool would drop its notifications (see
     * {@link #submit}).
     *
     * <p>Idempotence guard mirrors {@code SentinelScheduler.start}: a second
     * call logs and returns rather than orphaning a live pool, whose threads
     * would then have no owner left to shut them down.</p>
     *
     * <p>Threads are named and daemon on purpose. Named so a thread dump
     * during an alert storm immediately shows whether Sentinel is stuck in a
     * send; daemon so a transport wedged in a socket read can never be what
     * keeps the JVM from exiting.</p>
     */
    public static synchronized void startDispatchExecutor() {
        ThreadPoolExecutor existing = dispatchExecutor;
        if (existing != null && !existing.isShutdown()) {
            log.warn("Sentinel dispatch executor already started; ignoring duplicate start request");
            return;
        }

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                DISPATCH_THREADS, DISPATCH_THREADS,
                DISPATCH_THREAD_KEEPALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(DISPATCH_QUEUE_CAPACITY),
                ActionDispatcher::newDispatchThread,
                ActionDispatcher::dropAndLog);
        executor.allowCoreThreadTimeOut(true);
        dispatchExecutor = executor;

        log.info("Sentinel dispatch executor started ({} threads, queue capacity {}); "
                        + "alert delivery no longer runs on the evaluator thread",
                DISPATCH_THREADS, DISPATCH_QUEUE_CAPACITY);
    }

    /**
     * Stops the dispatch pool. Called from
     * {@code SentinelServicePlugin.stop()} after the scheduler has stopped,
     * so nothing is still producing work and the brief wait below is spent
     * draining rather than chasing new arrivals.
     *
     * <p>Clears the handle first: from that moment {@link #submit} drops with
     * a warning instead of racing a closing pool, and no rejection storm is
     * logged during a normal shutdown. Then a short
     * {@link #SHUTDOWN_WAIT_SECONDS} grace period for in-flight sends,
     * followed by {@code shutdownNow}, whose returned backlog is logged so an
     * operator knows notifications were lost rather than merely delayed.</p>
     *
     * <p>Never throws — {@code stop()} runs during server shutdown or
     * redeploy, where an exception could disrupt the rest of the extension
     * teardown, and daemon threads die with the JVM regardless.</p>
     */
    public static synchronized void shutdownDispatchExecutor() {
        ThreadPoolExecutor executor = dispatchExecutor;
        if (executor == null) {
            return;
        }
        dispatchExecutor = null;

        try {
            executor.shutdown();
            if (executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.info("Sentinel dispatch executor shut down");
            } else {
                List<Runnable> abandoned = executor.shutdownNow();
                log.warn("Sentinel dispatch executor did not drain within {}s; {} queued alert "
                                + "notification(s) abandoned and in-flight sends interrupted",
                        SHUTDOWN_WAIT_SECONDS, abandoned.size());
            }
        } catch (InterruptedException e) {
            List<Runnable> abandoned = executor.shutdownNow();
            log.warn("Interrupted while draining the Sentinel dispatch executor; {} queued alert "
                    + "notification(s) abandoned", abandoned.size());
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.warn("Failed to shut down the Sentinel dispatch executor cleanly", t);
        } finally {
            // Belt and braces: every task removes its own key in a finally,
            // so this should already be empty. Clearing anyway guarantees a
            // restart within the same JVM cannot inherit a stale entry, which
            // would silence that (event, action) repeat pairing permanently.
            inFlightPacedSends.clear();
            // Same reasoning for the storm-control window: a snapshot taken
            // before the stop describes a window that has passed by the time
            // the plugin starts again, and its reservations would be charged
            // against the first window after the restart.
            AlertStormControl.reset();
        }
    }

    /**
     * Queues the "alert opened" edge: every enabled action whose operation
     * mode is ON_PROBLEM or BOTH and whose condition matches gets one
     * delivery attempt, each attempt logged. Returns as soon as the task is
     * enqueued — the sends themselves happen on the dispatch pool (see class
     * Javadoc). Never throws.
     *
     * @param event   the freshly opened alert event (already persisted, id set)
     * @param payload the display-resolved snapshot built by the evaluator;
     *                immutable, so handing it to another thread is safe
     */
    public static void onAlertOpened(AlertEvent event, AlertPayload payload) {
        submit("alert-opened dispatch for alert event " + eventId(event),
                () -> dispatchLifecycle(event, payload, false));
    }

    /**
     * Queues the "alert resolved" edge: every enabled action whose operation
     * mode is ON_RESOLVE or BOTH and whose condition matches gets one
     * delivery attempt, each attempt logged. Asynchronous like
     * {@link #onAlertOpened}. Never throws.
     *
     * @param event   the just-resolved alert event
     * @param payload the display-resolved snapshot (eventType "RESOLVED")
     */
    public static void onAlertResolved(AlertEvent event, AlertPayload payload) {
        submit("alert-resolved dispatch for alert event " + eventId(event),
                () -> dispatchLifecycle(event, payload, true));
    }

    /**
     * The still-open pass, called by the evaluator on every tick an alert
     * stays open. It drives BOTH re-notification (same action, per
     * {@code repeatIntervalSeconds}) and escalation (a different action, per
     * {@code escalateAfterSeconds}); the two are independent and an action may
     * configure either, both or neither.
     *
     * <p>Acknowledged alerts do neither — an ack means "someone is on it",
     * silencing the pager without resolving the problem. That the ack check
     * covers escalation as well is deliberate and not incidental: escalating a
     * problem somebody is actively working, to a wider audience, on the theory
     * that nobody is looking at it, is the single most annoying thing this
     * class could do.</p>
     *
     * <p>For an action that opted into repeats, the dispatch log decides what
     * happens:</p>
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
     * tick forever filling the log. A rollup row counts as an attempt for this
     * purpose — the operator was notified, in aggregate, so the repeat clock
     * should not behave as though nothing was sent.</p>
     *
     * <p>Never throws. Queued, not executed, for the reason in the class
     * Javadoc — which includes the log read above: that query, not just the
     * send, used to sit on the evaluator thread once per open alert per
     * tick.</p>
     *
     * @param event   the still-open alert event
     * @param payload the display-resolved snapshot (eventType "PROBLEM")
     */
    public static void onRepeatCheck(AlertEvent event, AlertPayload payload) {
        submit("repeat check for alert event " + eventId(event),
                () -> runRepeatCheck(event, payload));
    }

    /**
     * The repeat-check body, running on a dispatch thread. Keeps
     * {@link #onRepeatCheck}'s never-throw contract here, where it now
     * matters twice over: an escaping {@link Throwable} would kill the pool
     * worker as well as losing the check.
     */
    // Package-private (not private) purely so tests can drive the check synchronously.
    static void runRepeatCheck(AlertEvent event, AlertPayload payload) {
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
            // Index built once per tick and shared by every escalation walk,
            // so resolving a chain costs no queries at all.
            Map<Integer, Action> byId = indexById(actions);
            // Action ids this pass has already decided to notify. allRows is
            // read once, up front, so it cannot see a row written later in the
            // same pass — and an escalation target that is itself a matching
            // action would otherwise be told twice in one tick, once by the
            // escalation and once by its own "no rows yet, send now" repeat
            // rule. Both consult this set on top of the log.
            Set<Integer> notifiedThisPass = new HashSet<>();
            Instant now = Instant.now();

            for (Action action : actions) {
                try {
                    if (!firesOnPhase(action.getOperationMode(), false)) {
                        continue;
                    }
                    if (!ActionConditionMatcher.matches(action, payload)) {
                        continue;
                    }
                    maybeRepeat(action, allRows, notifiedThisPass, event, payload, now);
                    maybeEscalate(action, byId, allRows, notifiedThisPass, event, payload, now);
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
     * The re-notification half of {@link #runRepeatCheck} for one matched
     * action — the rules are spelled out on {@link #onRepeatCheck}. Actions
     * that did not opt into repeats return immediately; they may still
     * escalate.
     */
    private static void maybeRepeat(Action action, List<ActionDispatchLog> allRows,
            Set<Integer> notifiedThisPass, AlertEvent event, AlertPayload payload, Instant now) {
        if (action.getRepeatIntervalSeconds() == null) {
            return;
        }
        if (notifiedThisPass.contains(action.getId())) {
            return; // an escalation earlier in this pass already routed here
        }

        List<ActionDispatchLog> rows = rowsForAction(allRows, action);
        if (rows.isEmpty()) {
            notifiedThisPass.add(action.getId());
            attemptPaced(action, event, payload);
            return;
        }

        Integer maxRepeats = action.getMaxRepeats();
        if (maxRepeats != null && rows.size() >= 1 + maxRepeats) {
            return;
        }
        Instant newest = newestDispatchTime(rows);
        if (!newest.isAfter(now.minusSeconds(action.getRepeatIntervalSeconds()))) {
            notifiedThisPass.add(action.getId());
            attemptPaced(action, event, payload);
        }
    }

    /**
     * The escalation half of {@link #runRepeatCheck} for one matched action:
     * a problem still open past {@code escalateAfterSeconds} is handed to
     * {@code escalateToActionId} — typically a wider audience — on the theory
     * that a problem nobody closed in that time is a problem the first target
     * is not acting on.
     *
     * <p><b>Pacing comes from the dispatch log, not from a timer.</b> The
     * target is notified exactly when it has no rows for this event yet;
     * afterwards its own {@code repeatIntervalSeconds} and ceiling take over,
     * because the escalated send is logged against the TARGET's action id
     * rather than the source's. That is what keeps every downstream accounting
     * coherent: the target's repeat clock starts when the target was actually
     * told, its repeat budget counts its own attempts, and its ceiling counts
     * the escalation against the target's window, not the source's.</p>
     *
     * <p><b>Chains.</b> Each link's {@code escalateAfterSeconds} is measured
     * from the problem's open time and accumulates, so A(5m) → B(10m) reaches
     * B at five minutes and C at fifteen. The walk advances past links that
     * have already been notified and delivers to at most one new target per
     * tick, which paces the chain naturally without storing where it got to.</p>
     *
     * <p><b>Defensive resolution.</b> {@code escalateToActionId} has no
     * foreign key (deliberately — see {@code Action.getEscalateToActionId()},
     * where a constraint would make an action undeletable for as long as
     * anything escalated to it). A dangling id, a disabled target, an action
     * escalating to itself and a cycle are therefore all reachable
     * configurations, and every one of them logs and ends the chain rather
     * than throwing: a broken escalation must cost the escalation, never the
     * remaining actions on this event.</p>
     */
    private static void maybeEscalate(Action source, Map<Integer, Action> byId,
            List<ActionDispatchLog> allRows, Set<Integer> notifiedThisPass,
            AlertEvent event, AlertPayload payload, Instant now) {
        if (source.getEscalateAfterSeconds() == null || source.getEscalateToActionId() == null) {
            return;
        }
        Instant opened = event.getOpenedTime();
        if (opened == null) {
            return;
        }
        long ageSeconds = Duration.between(opened, now).getSeconds();
        if (ageSeconds < 0) {
            return; // clock skew; nothing is "still open past" a future instant
        }

        Set<Integer> visited = new LinkedHashSet<>();
        visited.add(source.getId());
        Action current = source;
        long threshold = 0;

        for (int hop = 1; hop <= MAX_ESCALATION_HOPS; hop++) {
            Integer after = current.getEscalateAfterSeconds();
            Integer targetId = current.getEscalateToActionId();
            if (after == null || targetId == null) {
                return; // chain ends here
            }
            threshold += Math.max(0, after);
            if (ageSeconds < threshold) {
                return; // this link is not due yet
            }
            if (targetId.equals(current.getId())) {
                log.warn("Action {} ('{}') escalates to itself; ignoring the escalation",
                        current.getId(), current.getName());
                return;
            }
            if (!visited.add(targetId)) {
                log.warn("Escalation cycle from action {} ('{}') back to action {}; "
                                + "ignoring the rest of the chain (visited {})",
                        source.getId(), source.getName(), targetId, visited);
                return;
            }
            Action target = byId.get(targetId);
            if (target == null) {
                // Deleted or disabled: escalate_to_action_id has no FK, so a
                // dangling reference is expected rather than exceptional.
                log.warn("Action {} ('{}') escalates to action id {}, which is not an enabled action "
                                + "(deleted or disabled); ending the chain for alert event {}",
                        current.getId(), current.getName(), targetId, event.getId());
                return;
            }
            if (!hasRowsForAction(allRows, targetId) && !notifiedThisPass.contains(targetId)) {
                log.info("Escalating alert event {} to action {} ('{}') after {}s open "
                                + "(hop {} from action {} '{}')",
                        event.getId(), target.getId(), target.getName(), ageSeconds,
                        hop, source.getId(), source.getName());
                notifiedThisPass.add(targetId);
                attemptPaced(target, event, escalationPayload(payload, ageSeconds, source));
                return; // one new target per tick
            }
            current = target; // already told; see whether ITS escalation is due
        }

        log.warn("Escalation chain from action {} ('{}') exceeded {} hops on alert event {}; "
                        + "stopping. Check escalateToActionId for a loop or an over-long chain.",
                source.getId(), source.getName(), MAX_ESCALATION_HOPS, event.getId());
    }

    /**
     * Sends a real test notification through the action's transport with a
     * fully synthetic payload — so an operator verifies SMTP/SNS/channel/
     * webhook wiring at configuration time instead of discovering it broken
     * during an incident. For WEBHOOK actions this is also where the egress
     * guard's verdict on the configured host first becomes visible, since
     * that check is deliberately made at send time rather than at save time.
     * The delivery genuinely happens (this is not a dry run),
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
     * Hands one dispatch task to the pool. Never throws, and — critically —
     * never runs the task here: every fallback in this method drops the work
     * with a warning, because the one thing that must not happen is a send
     * executing on the calling (evaluator) thread.
     *
     * <p>A {@code null} pool means the plugin is stopped or stopping. That is
     * not an error worth escalating: every caller is the evaluator, and the
     * scheduler is torn down before the pool, so the only work that can still
     * arrive is the tail of a tick already in flight. Dropping those
     * notifications during shutdown is preferable to reviving a pool that no
     * lifecycle would then own.</p>
     *
     * @param description what is being delivered, in operator language; it is
     *                    the task's {@code toString()}, so the rejection
     *                    handler can name exactly what was dropped
     */
    private static void submit(String description, Runnable task) {
        ThreadPoolExecutor executor = dispatchExecutor;
        if (executor == null) {
            log.warn("Sentinel dispatch executor is not running; {} was not delivered", description);
            return;
        }
        try {
            executor.execute(new DispatchTask(description, task));
        } catch (Throwable t) {
            // The rejection handler drops rather than throwing, so this is
            // only reachable if the pool is being shut down underneath this
            // call. Same rule: log it, never run it inline.
            log.warn("Failed to queue {}; it was not delivered", description, t);
        }
    }

    /**
     * A named unit of dispatch work: carries the operator-facing description
     * (so a dropped task can be identified in the log) and is the outermost
     * {@link Throwable} backstop on the pool thread. The dispatch bodies
     * already catch everything themselves; this guarantees it even if a
     * future body forgets, because an escaping Throwable kills the worker.
     */
    private static final class DispatchTask implements Runnable {

        private final String description;
        private final Runnable work;

        private DispatchTask(String description, Runnable work) {
            this.description = description;
            this.work = work;
        }

        @Override
        public void run() {
            try {
                work.run();
            } catch (Throwable t) {
                log.error("Sentinel dispatch task failed: {}", description, t);
            }
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /** Named, daemon dispatch thread — see {@link #startDispatchExecutor()} for why both matter. */
    private static Thread newDispatchThread(Runnable task) {
        Thread thread = new Thread(task, "sentinel-dispatch-" + DISPATCH_THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    /**
     * The rejection policy: drop the notification and say so loudly. See the
     * class Javadoc for why this is not {@code CallerRunsPolicy} — running
     * the send here would put it straight back on the evaluator thread, which
     * is the failure this pool exists to prevent, and would do so precisely
     * when the system is least able to afford it.
     */
    private static void dropAndLog(Runnable task, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            log.warn("Sentinel is shutting down; {} was not delivered", task);
            return;
        }
        log.warn("Sentinel dispatch queue is full ({} queued across {} threads) — DROPPING {}. "
                        + "Notifications are being produced faster than the transports can deliver them; "
                        + "look for a hung SMTP/SNS endpoint or an alert storm. This notification is lost "
                        + "on purpose: blocking here would stall the monitor evaluator and stop all "
                        + "monitoring.",
                executor.getQueue().size(), executor.getPoolSize(), task);
    }

    /** Alert event id for log messages, tolerating the null event the dispatch bodies check for. */
    private static Long eventId(AlertEvent event) {
        return event != null ? event.getId() : null;
    }

    /**
     * Shared open/resolve dispatch: apply the flap gate to the whole edge,
     * then filter enabled actions by phase and condition and attempt each
     * through the ceiling (see {@link #gatedAttempt}).
     *
     * <p>The flap check runs once for the edge rather than per action, because
     * flapping is a property of the trigger identity, not of any action's
     * routing — two actions matching the same event must never disagree about
     * whether it is flapping. Its verdict is applied before the action loop,
     * as stage 1 of the ordering in the class Javadoc: a flap-suppressed edge
     * is not counted against any ceiling and cannot produce a rollup, because
     * the notification it would have produced never existed.</p>
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
                // Nothing to deliver to, so nothing is worth deciding about:
                // return before the flap history read rather than after it.
                return;
            }

            AlertPayload effectivePayload = payload;
            AlertStormControl.FlapCheck flap =
                    AlertStormControl.flapCheck(event, Instant.now(), resolvedPhase);
            if (flap.outcome() == AlertStormControl.FlapOutcome.SUPPRESSED) {
                log.info("Alert event {} belongs to a flapping trigger ({} cycles in {} minutes); "
                                + "notifications stay suppressed until it stabilizes",
                        event.getId(), flap.cycles(), flap.windowMinutes());
                return;
            }
            if (flap.outcome() == AlertStormControl.FlapOutcome.ONSET) {
                log.info("Alert event {} crossed the flap threshold ({} cycles in {} minutes); "
                                + "sending one flapping notification, then going quiet",
                        event.getId(), flap.cycles(), flap.windowMinutes());
                effectivePayload = withMessage(payload,
                        AlertStormControl.flappingMessage(flap, payload.getMessage()));
            }

            for (Action action : actions) {
                try {
                    if (!firesOnPhase(action.getOperationMode(), resolvedPhase)) {
                        continue;
                    }
                    if (!ActionConditionMatcher.matches(action, payload)) {
                        continue;
                    }
                    gatedAttempt(action, event, effectivePayload);
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
     * Stage 2 of the ordering in the class Javadoc: one delivery attempt,
     * subject to the action's per-window ceiling.
     *
     * <p>Under the ceiling this is exactly {@link #attempt}. Over it, the
     * individual send is dropped and — once per window — replaced by a single
     * rollup naming the affected channels, which is the whole point: thirty
     * channels breaking together should cost one notification, not thirty.
     * After that rollup the action is silent for the rest of the window.</p>
     *
     * <p>Every real send in the class routes through here, including repeats
     * and escalations, so the ceiling is a bound on the action's total
     * notification volume rather than on any one problem's.</p>
     */
    private static void gatedAttempt(Action action, AlertEvent event, AlertPayload payload) {
        AlertStormControl.CeilingVerdict verdict =
                AlertStormControl.ceilingVerdict(action, Instant.now());
        switch (verdict.outcome()) {
            case SEND:
                attempt(action, event, payload, null);
                return;
            case ROLLUP:
                attemptRollup(action, event, payload, verdict);
                return;
            case SILENT:
            default:
                log.debug("Action {} ('{}') is over its ceiling ({} in {}s) and has already sent this "
                                + "window's rollup; alert event {} produces no notification",
                        action.getId(), action.getName(), verdict.sent(), verdict.windowSeconds(),
                        event.getId());
        }
    }

    /**
     * Delivers the one rollup notification for this action's current window.
     *
     * <p><b>Why this cannot recurse.</b> Two independent reasons, either of
     * which would be sufficient. First, it calls {@link #attempt} directly and
     * never {@link #gatedAttempt}, so a rollup is not itself subject to a
     * ceiling decision and cannot produce a rollup of its own. Second, its
     * dispatch-log row is tagged with {@link AlertStormControl#ROLLUP_MARKER},
     * which {@code AlertStormControl} excludes from the window count — so even
     * a future caller that did route a rollup through the gate could not make
     * the count climb on the strength of its own rollups. The marker is
     * simultaneously how "a rollup already went out this window" is
     * recognized, which is what makes it exactly one per window rather than
     * one per suppressed send.</p>
     *
     * <p>The individual sends the ceiling suppressed write no rows at all.
     * That is deliberate: an action with {@code repeatIntervalSeconds} set
     * therefore still sees "no rows for this event" on later ticks and will
     * deliver the notification for real once the window clears — the ceiling
     * defers those alerts rather than losing them.</p>
     */
    private static void attemptRollup(Action action, AlertEvent event, AlertPayload payload,
            AlertStormControl.CeilingVerdict verdict) {
        log.warn("Action {} ('{}') reached its ceiling of {} notification(s) per {}s; sending one "
                        + "rollup for {} affected channel(s) instead of individual notifications",
                action.getId(), action.getName(), verdict.sent(), verdict.windowSeconds(),
                verdict.channels().size());
        AlertPayload rollup = new AlertPayload(
                payload.getAlertEventId(), payload.getMonitorId(), payload.getMonitorName(),
                payload.getMonitorType(), payload.getChannelId(), payload.getChannelName(),
                payload.getMetadataId(), payload.getSeverity(), "ROLLUP",
                AlertStormControl.rollupMessage(verdict), payload.getOpenedTime(),
                payload.getValueJson());
        attempt(action, event, rollup, AlertStormControl.ROLLUP_MARKER);
    }

    /**
     * A log-paced send — a repeat, or an escalation to a target that has not
     * been told yet — guarded against the one race that off-thread dispatch
     * introduces. {@link #onRepeatCheck} decides whether to send by reading
     * the dispatch log, and the row that records the send is written
     * only after the send returns — a 30 second SMTP timeout wide. Two ticks'
     * repeat tasks running concurrently on the pool can therefore both read
     * a log that says "due", and both send. The key {@code (alertEventId,
     * actionId)} is claimed in this window: whoever claims it sends, the
     * other skips and lets the next tick re-decide against a log that by then
     * has the row.
     *
     * <p>Escalation shares the guard for free, and needs it for the same
     * reason: it decides by asking whether the target has rows yet, and it
     * logs against the TARGET's id — so keying on the action actually being
     * written also interlocks an escalation with a concurrent repeat send for
     * that same target.</p>
     *
     * <p><b>Why the claim is taken here and not before submit.</b> The set is
     * in-memory, so a key that is added but never removed silences that
     * (event, action) pairing for the life of the JVM — strictly worse than
     * the duplicate notification it is preventing. Adding at submit time
     * makes that reachable: a task the pool rejects (queue full) or discards
     * at shutdown never runs, so its removal never runs either. Adding on the
     * worker thread, immediately followed by a {@code try}/{@code finally}
     * with nothing in between that can fail, means the removal is unavoidable
     * once the addition has happened — it survives a send that throws, a
     * worker interrupted by {@code shutdownNow}, and a {@link Throwable} from
     * the log insert. A dropped task simply never claims anything.</p>
     *
     * <p>Note this is deliberately only on the log-paced paths. The opened and
     * resolved edges are one-shot transitions driven by trigger state, not by
     * reading the log back, so they have no such window.</p>
     */
    private static void attemptPaced(Action action, AlertEvent event, AlertPayload payload) {
        String key = event.getId() + ":" + action.getId();
        if (!inFlightPacedSends.add(key)) {
            log.debug("Paced send for alert event {} / action {} is already in flight; "
                    + "skipping until the next tick", event.getId(), action.getId());
            return;
        }
        try {
            gatedAttempt(action, event, payload);
        } finally {
            inFlightPacedSends.remove(key);
        }
    }

    /**
     * One delivery attempt plus its log row. The row is written whether the
     * send succeeded or failed — the log's several roles (audit trail, repeat
     * pacing, ceiling accounting and escalation state, see class Javadoc)
     * require recording attempts, not outcomes. A failed log insert is itself
     * only logged: losing one bookkeeping row must not cascade into losing the
     * remaining actions' deliveries.
     *
     * <p>{@code marker}, when non-null, is prefixed to the row's
     * {@code error_message} to classify the row for later reads —
     * {@link AlertStormControl#ROLLUP_MARKER} is the only one, and it is what
     * lets a rollup be logged like any other send without being counted like
     * one. It is prefixed rather than substituted so a marked send that
     * <em>fails</em> still carries its real failure reason.</p>
     */
    private static void attempt(Action action, AlertEvent event, AlertPayload payload, String marker) {
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
            errorMessage = exceptionMessage(t);
            log.warn("Action {} ('{}') failed for alert event {}: {}",
                    action.getId(), action.getName(), event.getId(), errorMessage);
        }

        try {
            ActionDispatchLog row = new ActionDispatchLog();
            row.setAlertEventId(event.getId());
            row.setActionId(action.getId());
            row.setDispatchTime(Instant.now());
            row.setSuccess(success);
            row.setErrorMessage(truncate(mark(marker, errorMessage)));
            ActionDispatchLogRepository.insertActionDispatchLog(row);
        } catch (Throwable t) {
            log.error("Failed to record dispatch log for action {} on alert event {}",
                    action.getId(), event.getId(), t);
        }
    }

    /**
     * Combines a row classification marker with a failure reason. Either may
     * be absent: an ordinary success stores {@code null}, an unmarked failure
     * stores just the reason, and a marked success stores just the marker —
     * which is what makes the marker recognizable by prefix on every row it
     * appears on.
     */
    private static String mark(String marker, String errorMessage) {
        if (marker == null) {
            return errorMessage;
        }
        return errorMessage == null ? marker : marker + " " + errorMessage;
    }

    /**
     * Indexes enabled actions by id for escalation-target resolution. Ids are
     * assigned by the database and cannot be null on a listed row, but a
     * defensive skip costs nothing and keeps a malformed row out of the map
     * rather than into it under a null key.
     */
    private static Map<Integer, Action> indexById(List<Action> actions) {
        Map<Integer, Action> byId = new HashMap<>();
        for (Action action : actions) {
            if (action.getId() != null) {
                byId.put(action.getId(), action);
            }
        }
        return byId;
    }

    /** Whether the event's dispatch rows include any attempt against this action id. */
    private static boolean hasRowsForAction(List<ActionDispatchLog> allRows, Integer actionId) {
        if (allRows == null || actionId == null) {
            return false;
        }
        for (ActionDispatchLog row : allRows) {
            if (actionId.equals(row.getActionId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies a payload with a different message. Used where the dispatcher
     * needs to say something about the notification itself (it is a flap
     * onset, it is an escalation) while leaving every routing-relevant field —
     * severity, monitor, channel, event type — exactly as the evaluator
     * produced it, so conditions and templates behave identically.
     */
    private static AlertPayload withMessage(AlertPayload payload, String message) {
        return new AlertPayload(payload.getAlertEventId(), payload.getMonitorId(),
                payload.getMonitorName(), payload.getMonitorType(), payload.getChannelId(),
                payload.getChannelName(), payload.getMetadataId(), payload.getSeverity(),
                payload.getEventType(), message, payload.getOpenedTime(), payload.getValueJson());
    }

    /**
     * The payload an escalation target receives: the original problem, with
     * the message prefixed by how long it has been open and who handed it
     * over. The target is usually a wider audience seeing this problem for the
     * first time, and "why am I being told about this now" is the first thing
     * it needs to know.
     */
    private static AlertPayload escalationPayload(AlertPayload payload, long ageSeconds, Action source) {
        String message = "Escalated after " + ageSeconds + "s unresolved (from action '"
                + source.getName() + "'): "
                + (payload.getMessage() == null ? "" : payload.getMessage());
        return withMessage(payload, message);
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
            case WEBHOOK:
                return WEBHOOK_SENDER;
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
