/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;

import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MaintenanceWindowRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.evaluate.AnomalyEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.ConnectionStatusEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.ErrorRateEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.InactivityEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.LowVolumeEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.QueueDepthEvaluator;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowMode;

/**
 * The evaluation tick: runs every enabled monitor against its resolved
 * channel scope, drives the per-trigger hysteresis state machine, opens and
 * resolves {@code sentinel_alert_event} rows, and hands notification-worthy
 * transitions to {@link ActionDispatcher}.
 *
 * <p>Orchestration per tick:</p>
 * <ol>
 *   <li>Load enabled monitors and order them so suppression dependencies
 *       ({@code suppressedByMonitorId}) are evaluated before their
 *       dependents — a dependent's at-creation suppression check reads the
 *       parent's trigger state, which must reflect <em>this</em> tick, not
 *       the previous one. Cycles (possible via direct DB edits even though
 *       MonitorService rejects them) degrade to natural order with a
 *       warning instead of dropping monitors.</li>
 *   <li>Resolve each monitor's scope to STARTED channels only — a paused
 *       channel's source is not polling, so evaluating INACTIVITY against it
 *       is a guaranteed false positive.</li>
 *   <li>Run the type-specific evaluator: one outcome per channel, except
 *       CONNECTION_STATUS which yields one per connector
 *       ({@code metadataId}) unless the monitor's {@code rollup} config
 *       collapses the channel to a single {@code null}-metadata outcome.</li>
 *   <li>Apply each outcome to its trigger state (see
 *       {@link #applyOutcome}).</li>
 *   <li>Auto-resolve open problems whose subject left the monitored set —
 *       whether that subject is a whole channel or one connector within it
 *       (see {@link #autoResolveDepartedTriggers}) — so neither an operator
 *       stopping a channel nor a deleted destination strands a stale PROBLEM
 *       row that no future tick would ever close.</li>
 * </ol>
 *
 * <p>Each monitor is wrapped in its own try/catch so one broken monitor (bad
 * config, DB hiccup on one query) cannot starve the rest of the tick; the
 * whole tick is additionally wrapped so nothing ever propagates into Quartz,
 * which would unschedule nothing but does log scary errors and skip
 * bookkeeping. Instantiated by Quartz per execution — the class is stateless
 * (all working state lives in the database and {@link CollectorState}), so
 * per-execution instantiation is free.</p>
 *
 * <p>{@code @DisallowConcurrentExecution} is load-bearing: a tick that
 * outlasts the evaluator interval (slow DB, synchronous SMTP/SNS sends
 * inside {@link ActionDispatcher}) would otherwise overlap the next tick on
 * one of the scheduler's spare threads — two concurrent read-modify-write
 * cycles over the same trigger rows can both cross the hysteresis threshold
 * and open duplicate alert events, one of which leaks open forever. The
 * misfire policy only covers the no-free-thread case, not overlap; this
 * annotation serializes executions per job while leaving the schedule
 * untouched.</p>
 */
@DisallowConcurrentExecution
public class TriggerEvaluatorJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(TriggerEvaluatorJob.class);

    /**
     * Message used when a problem is auto-resolved because its channel left
     * the monitored set — surfaced in the resolution notification payload so
     * the operator knows the "resolution" is administrative, not a recovery.
     */
    private static final String CHANNEL_LEFT_MESSAGE = "Channel is no longer started";

    /**
     * Message used when a problem is auto-resolved because its <em>trigger
     * identity</em> left the monitored set while its channel stayed put — the
     * connector was deleted from the channel and redeployed, or the monitor's
     * CONNECTION_STATUS rollup was switched between per-connector and
     * per-channel. Distinct from {@link #CHANNEL_LEFT_MESSAGE} because the
     * operator response differs: a departed channel is usually intentional
     * and temporary, a departed connector means this problem's subject no
     * longer exists and will not come back on its own.
     */
    private static final String TRIGGER_LEFT_MESSAGE =
            "Connector no longer evaluated (removed, or monitor rollup changed)";

    /**
     * Quartz entry point. Catches {@link Throwable} deliberately: an
     * evaluator tick must never propagate anything into the scheduler
     * thread, and the next tick gets a fresh chance anyway.
     */
    @Override
    public void execute(JobExecutionContext context) {
        // Every node schedules this job; only the lease holder runs it. Two
        // evaluators against one database would race read-modify-write on the
        // same trigger rows and can both open an alert for the same breach.
        // See SentinelLeadership — a JVM whose heartbeat has never started
        // (single node, tests) reports true, so this is inert unless a lease
        // is actually in play.
        if (!SentinelLeadership.isLeader()) {
            return;
        }
        try {
            runTick(Instant.now());
        } catch (Throwable t) {
            log.error("Sentinel trigger evaluator tick failed", t);
        }
    }

    /**
     * One full evaluation pass at a single instant. {@code now} is fixed
     * once so every trigger in the tick judges the same windows and every
     * timestamp written this tick agrees.
     */
    private static void runTick(Instant now) {
        List<Monitor> monitors = MonitorRepository.listMonitors(null, null, Boolean.TRUE, null);

        for (Monitor monitor : orderByDependency(monitors)) {
            try {
                evaluateMonitor(monitor, now);
            } catch (Exception e) {
                log.error("Evaluation failed for monitor {} ({})", monitor.getId(), monitor.getName(), e);
            }
        }

        CollectorState.getInstance().recordEvaluatorRun(now);
    }

    /**
     * Orders monitors so every monitor appears after the monitor it is
     * suppressed by (when that parent is itself in the enabled set — a
     * disabled or deleted parent is treated as no dependency, which also
     * matches how the suppression check fails open). Kahn-style repeated
     * sweeps rather than DFS: the list is small, and the sweep degrades
     * gracefully — if a cycle prevents progress, the acyclic prefix keeps
     * its correct order and only the cycle members are appended in natural
     * order with a warning.
     */
    private static List<Monitor> orderByDependency(List<Monitor> monitors) {
        Map<Integer, Monitor> byId = new HashMap<>();
        for (Monitor monitor : monitors) {
            byId.put(monitor.getId(), monitor);
        }

        List<Monitor> ordered = new ArrayList<>(monitors.size());
        Set<Integer> placed = new HashSet<>();
        List<Monitor> remaining = new ArrayList<>(monitors);

        boolean progressed = true;
        while (!remaining.isEmpty() && progressed) {
            progressed = false;
            Iterator<Monitor> it = remaining.iterator();
            while (it.hasNext()) {
                Monitor monitor = it.next();
                Integer parentId = monitor.getSuppressedByMonitorId();
                if (parentId == null || !byId.containsKey(parentId) || placed.contains(parentId)) {
                    ordered.add(monitor);
                    placed.add(monitor.getId());
                    it.remove();
                    progressed = true;
                }
            }
        }

        if (!remaining.isEmpty()) {
            List<Integer> cycleIds = new ArrayList<>();
            for (Monitor monitor : remaining) {
                cycleIds.add(monitor.getId());
            }
            log.warn("Suppression dependency cycle among monitors {}; evaluating them in natural order",
                    cycleIds);
            ordered.addAll(remaining);
        }
        return ordered;
    }

    /**
     * Evaluates one monitor: runs its evaluator over every started channel in
     * scope, then sweeps its existing trigger states for subjects that left
     * the monitored set.
     *
     * <p>Along the way it records which {@code (channelId, metadataId)}
     * identities this pass actually reached a <em>conclusion</em> about, and
     * hands that to {@link #autoResolveDepartedTriggers}. Trigger state is
     * keyed on that pair, so nothing but the pass itself knows which rows are
     * still backed by something real: the evaluator simply stops returning a
     * metadata id when its connector is gone, and without this record no
     * later tick would ever revisit the row.</p>
     *
     * <p>Only conclusive outcomes (OK/BREACH) are recorded. An
     * INSUFFICIENT_DATA outcome means the evaluator could not judge the
     * channel at all — the CONNECTION_STATUS evaluator returns exactly that
     * when {@link CollectorState} holds no observations, which is the normal
     * state for a while after a plugin restart. Treating "no observations
     * yet" as "these connectors no longer exist" would mass-resolve every
     * open connector problem on restart, so an inconclusive channel grants
     * the sweep no authority over its rows, exactly as INSUFFICIENT_DATA
     * grants {@link #applyOutcome} no authority to resolve an open alert.</p>
     */
    private static void evaluateMonitor(Monitor monitor, Instant now) {
        List<ScopeResolver.ChannelTarget> targets = ScopeResolver.resolveStartedChannels(monitor);

        Set<String> targetChannelIds = new HashSet<>();
        for (ScopeResolver.ChannelTarget target : targets) {
            targetChannelIds.add(target.channelId);
        }

        // channelId -> the metadata ids conclusively evaluated for it this
        // tick (a single null entry for a channel-level trigger). A channel
        // absent from this map was not judged at all — see the Javadoc.
        Map<String, Set<Integer>> evaluatedTriggers = new HashMap<>();

        if (monitor.getMonitorType() == MonitorType.CONNECTION_STATUS) {
            for (ScopeResolver.ChannelTarget target : targets) {
                for (ConnectionStatusEvaluator.ConnectorEvaluation evaluation
                        : ConnectionStatusEvaluator.evaluate(monitor, target.channelId, now)) {
                    applyOutcome(monitor, target.channelId, evaluation.metadataId, evaluation.outcome, now);
                    recordEvaluated(evaluatedTriggers, target.channelId, evaluation.metadataId,
                            evaluation.outcome);
                }
            }
        } else {
            for (ScopeResolver.ChannelTarget target : targets) {
                EvaluationOutcome outcome;
                switch (monitor.getMonitorType()) {
                    case INACTIVITY:
                        outcome = InactivityEvaluator.evaluate(monitor, target.channelId, now);
                        break;
                    case LOW_VOLUME:
                        outcome = LowVolumeEvaluator.evaluate(monitor, target.channelId, now);
                        break;
                    case ANOMALY:
                        outcome = AnomalyEvaluator.evaluate(monitor, target.channelId, now);
                        break;
                    case ERROR_RATE:
                        outcome = ErrorRateEvaluator.evaluate(monitor, target.channelId, now);
                        break;
                    case QUEUE_DEPTH:
                        outcome = QueueDepthEvaluator.evaluate(monitor, target.channelId, now);
                        break;
                    default:
                        // Recording nothing here is load-bearing: a type this
                        // build does not understand (a newer monitor type on
                        // an older jar) leaves the channel inconclusive, so
                        // the sweep leaves its problems alone rather than
                        // administratively closing real alerts over a gap in
                        // this switch.
                        log.warn("Monitor {} has unhandled type {}; skipping",
                                monitor.getId(), monitor.getMonitorType());
                        continue;
                }
                applyOutcome(monitor, target.channelId, null, outcome, now);
                recordEvaluated(evaluatedTriggers, target.channelId, null, outcome);
            }
        }

        autoResolveDepartedTriggers(monitor, targetChannelIds, evaluatedTriggers, now);
    }

    /**
     * Records one conclusively evaluated {@code (channelId, metadataId)} pair
     * for the departure sweep; inconclusive outcomes are deliberately dropped
     * (see {@link #evaluateMonitor}).
     */
    private static void recordEvaluated(Map<String, Set<Integer>> evaluatedTriggers, String channelId,
            Integer metadataId, EvaluationOutcome outcome) {
        if (outcome.getResult() == EvaluationOutcome.Result.INSUFFICIENT_DATA) {
            return;
        }
        // HashSet, not Set.of: a channel-level trigger's metadata id is null,
        // which the immutable factories reject on both add and contains.
        evaluatedTriggers.computeIfAbsent(channelId, id -> new HashSet<>()).add(metadataId);
    }

    /**
     * The per-trigger state machine. First sight of a {@code (monitor,
     * channel, metadataId)} triple creates its row starting from
     * INSUFFICIENT_DATA, then this tick's outcome is applied to it, so a
     * first-ever BREACH with {@code minConsecutiveBreaches = 1} opens an
     * alert immediately — no warm-up tick is lost.
     *
     * <ul>
     *   <li><b>BREACH</b> — increments the consecutive-breach counter. On
     *       reaching the hysteresis threshold from a non-PROBLEM state, opens
     *       an alert event (suppression decided once, at creation) and, if
     *       unsuppressed, dispatches. While already PROBLEM, runs the
     *       still-open pass instead (see
     *       {@link #stillOpenPass(Monitor, TriggerState)}).</li>
     *   <li><b>OK</b> — resolves any open alert (also covering the
     *       PROBLEM → INSUFFICIENT_DATA → OK path, where the state is no
     *       longer PROBLEM but an alert is still open) and settles the state
     *       to OK.</li>
     *   <li><b>INSUFFICIENT_DATA</b> — parks the state, resetting the breach
     *       counter, but deliberately leaves any open alert untouched: a
     *       transient data gap (collector restart, baseline aging out) is
     *       not evidence the underlying problem recovered, and
     *       resolve-then-reopen flapping would double-notify. The alert
     *       resolves on the next confirmed OK, and until then it still gets
     *       the still-open pass — see {@link #onInsufficientData}.</li>
     * </ul>
     */
    private static void applyOutcome(Monitor monitor, String channelId, Integer metadataId,
            EvaluationOutcome outcome, Instant now) {
        TriggerState state = TriggerStateRepository.getTriggerState(monitor.getId(), channelId, metadataId);
        boolean isNew = state == null;
        if (isNew) {
            state = new TriggerState();
            state.setMonitorId(monitor.getId());
            state.setChannelId(channelId);
            state.setMetadataId(metadataId);
            state.setState(TriggerStatus.INSUFFICIENT_DATA);
            state.setConsecutiveBreachCount(0);
            state.setLastChangeTime(now);
        }

        switch (outcome.getResult()) {
            case BREACH:
                onBreach(monitor, state, channelId, metadataId, outcome, now);
                break;
            case OK:
                onOk(monitor, state, now);
                break;
            case INSUFFICIENT_DATA:
                onInsufficientData(monitor, state, now);
                break;
        }

        state.setLastEvaluatedTime(now);
        state.setLastValueJson(outcome.getValueJson());
        if (isNew) {
            TriggerStateRepository.insertTriggerState(state);
        } else {
            TriggerStateRepository.updateTriggerState(state);
        }
    }

    /** BREACH branch of {@link #applyOutcome} — see its Javadoc. */
    private static void onBreach(Monitor monitor, TriggerState state, String channelId,
            Integer metadataId, EvaluationOutcome outcome, Instant now) {
        int required = Math.max(1, monitor.getMinConsecutiveBreaches());
        state.setConsecutiveBreachCount(state.getConsecutiveBreachCount() + 1);

        if (state.getState() == TriggerStatus.PROBLEM) {
            // Still breaching: run the still-open pass (repeat + escalation).
            stillOpenPass(monitor, state);
            return;
        }

        if (state.getConsecutiveBreachCount() < required) {
            return; // hysteresis still counting; no transition yet
        }

        // Re-breach after a PROBLEM -> INSUFFICIENT_DATA gap: onInsufficientData
        // deliberately retains the open alert (a data gap is not a recovery).
        // If that alert is still open, this breach is the SAME problem — adopt
        // it instead of inserting a second event. Opening a new one here would
        // orphan the retained row forever (every resolve path follows
        // openAlertEventId, which is about to be overwritten) and double-notify
        // the operator. A retained id pointing at a resolved event (manual
        // resolve leaves the id stale by design) falls through to a fresh open.
        if (state.getOpenAlertEventId() != null) {
            AlertEvent retained = AlertEventRepository.getAlertEvent(state.getOpenAlertEventId());
            if (retained != null && retained.getStatus() == AlertStatus.PROBLEM) {
                state.setState(TriggerStatus.PROBLEM);
                state.setLastChangeTime(now);
                stillOpenPass(monitor, state);
                return;
            }
        }

        // Transition to PROBLEM: open the alert. Suppression is decided once,
        // here at creation, and stored on the row — evaluation continues
        // underneath a maintenance window, but this alert will never notify.
        boolean suppressed = isSuppressedAtCreation(monitor, channelId, now);

        AlertEvent event = new AlertEvent();
        event.setMonitorId(monitor.getId());
        event.setChannelId(channelId);
        event.setMetadataId(metadataId);
        event.setSeverity(monitor.getSeverity());
        event.setStatus(AlertStatus.PROBLEM);
        event.setMessage(outcome.getMessage());
        event.setOpenedTime(now);
        event.setDetailsJson(outcome.getValueJson());
        event.setSuppressed(suppressed);
        AlertEventRepository.insertAlertEvent(event);

        state.setOpenAlertEventId(event.getId());
        state.setState(TriggerStatus.PROBLEM);
        state.setLastChangeTime(now);

        if (!suppressed) {
            ActionDispatcher.onAlertOpened(event, AlertPayload.of(event, monitor, "PROBLEM"));
        }
    }

    /** OK branch of {@link #applyOutcome} — see its Javadoc. */
    private static void onOk(Monitor monitor, TriggerState state, Instant now) {
        if (state.getOpenAlertEventId() != null) {
            resolveOpenAlert(monitor, state, null, now);
        }
        if (state.getState() != TriggerStatus.OK) {
            state.setLastChangeTime(now);
        }
        state.setState(TriggerStatus.OK);
        state.setConsecutiveBreachCount(0);
    }

    /**
     * INSUFFICIENT_DATA branch of {@link #applyOutcome} — see its Javadoc.
     *
     * <p>The still-open pass runs here too, and that is load-bearing rather
     * than incidental. A retained alert is an alert that is still open, and
     * the passage of time is the only input its repeat interval and its
     * escalation threshold have: a problem that stops being measurable — the
     * collector restarted, the baseline aged out, the channel went quiet in a
     * way the evaluator cannot judge — must not also stop being re-notified
     * and must not become un-escalatable. Before this, escalation was reachable
     * only on ticks that produced a fresh BREACH, so exactly the outages that
     * blind the evaluator were the ones that could never escalate.</p>
     *
     * <p>This cannot over-notify: the pass is paced entirely by the dispatch
     * log, so a data gap contributes ticks, not notifications.</p>
     */
    private static void onInsufficientData(Monitor monitor, TriggerState state, Instant now) {
        if (state.getState() != TriggerStatus.INSUFFICIENT_DATA) {
            state.setLastChangeTime(now);
        }
        state.setState(TriggerStatus.INSUFFICIENT_DATA);
        state.setConsecutiveBreachCount(0);
        // openAlertEventId intentionally retained — see applyOutcome Javadoc.
        stillOpenPass(monitor, state);
    }

    /**
     * The still-open pass for a trigger holding an open alert: hands the event
     * to {@link ActionDispatcher#onRepeatCheck}, which drives both
     * re-notification of the same action ({@code repeatIntervalSeconds}) and
     * escalation to a different one ({@code escalateAfterSeconds} /
     * {@code escalateToActionId}).
     *
     * <p>Suppressed alerts are skipped here as well as inside the dispatcher:
     * an alert born under a maintenance window never notifies, and that
     * includes never repeating and never escalating. A state whose retained
     * {@code openAlertEventId} points at an already-resolved event (a manual
     * resolve leaves the id stale by design) contributes nothing.</p>
     *
     * <p>Cheap by construction: one event read, then an enqueue. Every
     * decision about whether to actually notify — the log read, the ceiling,
     * the chain walk — happens on the dispatch pool, never on this thread.</p>
     */
    private static void stillOpenPass(Monitor monitor, TriggerState state) {
        if (state.getOpenAlertEventId() == null) {
            return;
        }
        AlertEvent open = AlertEventRepository.getAlertEvent(state.getOpenAlertEventId());
        if (open != null && open.getStatus() == AlertStatus.PROBLEM && !open.isSuppressed()) {
            ActionDispatcher.onRepeatCheck(open, AlertPayload.of(open, monitor, "PROBLEM"));
        }
    }

    /**
     * Resolves the trigger's open alert event and dispatches the resolution
     * (unless the alert was suppressed at creation — a never-announced
     * problem must not announce its recovery). {@code overrideMessage}, when
     * set, replaces the message in the outgoing notification payload only:
     * the stored row keeps its original opening message (the update
     * statement does not touch the message column), which is what the
     * problem history should show.
     */
    private static void resolveOpenAlert(Monitor monitor, TriggerState state,
            String overrideMessage, Instant now) {
        AlertEvent event = AlertEventRepository.getAlertEvent(state.getOpenAlertEventId());
        if (event != null && event.getStatus() == AlertStatus.PROBLEM) {
            event.setStatus(AlertStatus.RESOLVED);
            event.setResolvedTime(now);
            AlertEventRepository.updateAlertEvent(event);

            if (!event.isSuppressed()) {
                if (overrideMessage != null) {
                    event.setMessage(overrideMessage);
                }
                ActionDispatcher.onAlertResolved(event, AlertPayload.of(event, monitor, "RESOLVED"));
            }
        }
        state.setOpenAlertEventId(null);
    }

    /**
     * Auto-resolves open problems whose subject left the monitored set.
     * Trigger state is keyed on {@code (monitorId, channelId, metadataId)},
     * and a subject can depart at either level, so this sweep asks two
     * questions in order.
     *
     * <p><b>Did the channel depart?</b> For activity-based monitors
     * (INACTIVITY/LOW_VOLUME/ANOMALY/ERROR_RATE/QUEUE_DEPTH) "departed" means
     * no longer in this tick's STARTED targets — a stopped or paused channel
     * cannot meaningfully be inactive, low-volume or erroring, and its queue
     * is nobody's to drain, so its problem is administratively closed. For CONNECTION_STATUS "departed" means
     * undeployed: a merely paused channel's connectors still hold real
     * connection state worth keeping open, so only undeployment (which
     * destroys the connectors) closes those problems. That distinction is
     * deliberate and is preserved verbatim here.</p>
     *
     * <p><b>Did this trigger identity depart from a channel that stayed?</b>
     * If the channel is still present <em>and</em> was conclusively evaluated
     * this tick, but this row's metadata id was not among what the evaluator
     * returned, the row's subject is gone. Two ways that happens:</p>
     *
     * <ul>
     *   <li>A destination connector was deleted from the channel and the
     *       channel redeployed. {@code CollectorState.retainConnectorStates}
     *       prunes it from memory and the evaluator stops returning that
     *       metadata id — so before this sweep existed, its open problem
     *       stayed open forever, clearable only by manual resolve. That bug
     *       predates rollup entirely.</li>
     *   <li>A CONNECTION_STATUS monitor's {@code rollup} was switched. The
     *       old shape's rows (per-connector ids, or the channel-level null)
     *       stop being evaluated the moment the new shape takes over. Closing
     *       them here makes the toggle self-healing on the next tick, which
     *       is why {@code MonitorService.update} needs no migration logic for
     *       the rollup key.</li>
     * </ul>
     *
     * <p>The "conclusively evaluated" qualifier is what keeps this safe: a
     * channel absent from {@code evaluatedTriggers} was never judged, so none
     * of its rows are pruned — see {@link #evaluateMonitor}.</p>
     *
     * @param evaluatedTriggers channel id to the metadata ids conclusively
     *                          evaluated for it this tick
     */
    private static void autoResolveDepartedTriggers(Monitor monitor, Set<String> targetChannelIds,
            Map<String, Set<Integer>> evaluatedTriggers, Instant now) {
        List<TriggerState> states = TriggerStateRepository.listTriggerStatesByMonitor(monitor.getId());
        if (states.isEmpty()) {
            return;
        }

        EngineController engineController = null;
        if (monitor.getMonitorType() == MonitorType.CONNECTION_STATUS) {
            engineController = ControllerFactory.getFactory().createEngineController();
        }

        for (TriggerState state : states) {
            // Cheap gate first: only an open problem can be auto-resolved, and
            // skipping early avoids an isDeployed() call per healthy trigger.
            if (state.getState() != TriggerStatus.PROBLEM || state.getOpenAlertEventId() == null) {
                continue;
            }

            boolean channelDeparted;
            if (monitor.getMonitorType() == MonitorType.CONNECTION_STATUS) {
                channelDeparted = !engineController.isDeployed(state.getChannelId());
            } else {
                channelDeparted = !targetChannelIds.contains(state.getChannelId());
            }

            String message;
            if (channelDeparted) {
                message = CHANNEL_LEFT_MESSAGE;
            } else {
                Set<Integer> evaluated = evaluatedTriggers.get(state.getChannelId());
                if (evaluated == null || evaluated.contains(state.getMetadataId())) {
                    continue; // channel not judged this tick, or this row still is
                }
                message = TRIGGER_LEFT_MESSAGE;
            }

            resolveOpenAlert(monitor, state, message, now);
            // INSUFFICIENT_DATA, not OK: nothing was measured — the subject
            // simply stopped being measurable. If it comes back, evaluation
            // begins fresh rather than trusting a synthetic OK.
            state.setState(TriggerStatus.INSUFFICIENT_DATA);
            state.setConsecutiveBreachCount(0);
            state.setLastChangeTime(now);
            state.setLastEvaluatedTime(now);
            TriggerStateRepository.updateTriggerState(state);
        }
    }

    /**
     * The at-creation suppression check: an alert is born suppressed when
     * the mode-aware window check ({@link #suppressedByWindows}) says so, or
     * when the monitor's suppression parent already has an open,
     * unsuppressed problem on the <em>same channel</em> (dependency ordering
     * in {@link #runTick} guarantees the parent's state is current). A
     * parent that does not cover this channel at all contributes nothing —
     * the check fails open, because silently swallowing a real alert is
     * worse than a redundant one.
     */
    private static boolean isSuppressedAtCreation(Monitor monitor, String channelId, Instant now) {
        return suppressedByWindows(channelId, now) || dependencyOpen(monitor, channelId);
    }

    /**
     * The mode-aware window check. An alert on this channel is suppressed
     * when a covering SUPPRESS window is active right now, or when at least
     * one covering ACTIVE alerting schedule exists and <em>none</em> of them
     * is active right now (outside every alerting schedule = quiet hours).
     *
     * <p>Malformed schedules ({@code isActiveNow == null}) fail open in
     * whichever direction lets alerts through: a broken SUPPRESS window does
     * not suppress, a broken ACTIVE window counts as in-schedule — see
     * {@link WindowSchedule}'s class Javadoc. Rows written before modes
     * existed have a null mode and behave as SUPPRESS.</p>
     */
    private static boolean suppressedByWindows(String channelId, Instant now) {
        boolean hasAlertingSchedule = false;
        boolean insideAlertingSchedule = false;
        for (MaintenanceWindow window : MaintenanceWindowRepository.listEnabledMaintenanceWindows()) {
            if (!window.isEnabled() || !windowCoversChannel(window, channelId)) {
                continue; // enabled check is defensive; the query already filters
            }
            Boolean activeNow = WindowSchedule.isActiveNow(window, now);
            if (window.getMode() == WindowMode.ACTIVE) {
                hasAlertingSchedule = true;
                if (activeNow == null || activeNow) {
                    insideAlertingSchedule = true;
                }
            } else if (Boolean.TRUE.equals(activeNow)) {
                return true;
            }
        }
        return hasAlertingSchedule && !insideAlertingSchedule;
    }

    /**
     * Whether the window's scope (ALL / CHANNEL / GROUP / TAG membership)
     * covers the channel. Group and tag membership resolve live via
     * {@link ScopeResolver}, so edits apply to the next evaluation.
     */
    private static boolean windowCoversChannel(MaintenanceWindow window, String channelId) {
        ScopeType scope = window.getScopeType();
        if (scope == ScopeType.ALL) {
            return true;
        }
        if (scope == ScopeType.CHANNEL && channelId.equals(window.getScopeId())) {
            return true;
        }
        if (scope == ScopeType.GROUP
                && ScopeResolver.groupChannelIds(window.getScopeId()).contains(channelId)) {
            return true;
        }
        return scope == ScopeType.TAG
                && ScopeResolver.tagChannelIds(window.getScopeId()).contains(channelId);
    }

    /**
     * Whether the monitor's suppression parent has an open, unsuppressed
     * problem on the same channel. Any failure while consulting the parent
     * fails open (returns {@code false}) — a broken dependency lookup must
     * never suppress a real alert.
     */
    private static boolean dependencyOpen(Monitor monitor, String channelId) {
        Integer parentId = monitor.getSuppressedByMonitorId();
        if (parentId == null) {
            return false;
        }
        try {
            for (TriggerState parentState : TriggerStateRepository.listTriggerStatesByMonitor(parentId)) {
                if (!channelId.equals(parentState.getChannelId())
                        || parentState.getState() != TriggerStatus.PROBLEM
                        || parentState.getOpenAlertEventId() == null) {
                    continue;
                }
                AlertEvent open = AlertEventRepository.getAlertEvent(parentState.getOpenAlertEventId());
                if (open != null && open.getStatus() == AlertStatus.PROBLEM && !open.isSuppressed()) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Dependency suppression check failed for monitor {} (parent {}); failing open",
                    monitor.getId(), parentId, e);
        }
        return false;
    }
}
