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
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.InactivityEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.LowVolumeEvaluator;
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
 *       ({@code metadataId}).</li>
 *   <li>Apply each outcome to its trigger state (see
 *       {@link #applyOutcome}).</li>
 *   <li>Auto-resolve open problems whose channel left the monitored set, so
 *       an operator stopping a channel does not strand a stale PROBLEM row
 *       that would mis-resolve the moment the channel restarts.</li>
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
     * Quartz entry point. Catches {@link Throwable} deliberately: an
     * evaluator tick must never propagate anything into the scheduler
     * thread, and the next tick gets a fresh chance anyway.
     */
    @Override
    public void execute(JobExecutionContext context) {
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
     * scope, then sweeps its existing trigger states for channels that left
     * the monitored set.
     */
    private static void evaluateMonitor(Monitor monitor, Instant now) {
        List<ScopeResolver.ChannelTarget> targets = ScopeResolver.resolveStartedChannels(monitor);

        Set<String> targetChannelIds = new HashSet<>();
        for (ScopeResolver.ChannelTarget target : targets) {
            targetChannelIds.add(target.channelId);
        }

        if (monitor.getMonitorType() == MonitorType.CONNECTION_STATUS) {
            for (ScopeResolver.ChannelTarget target : targets) {
                for (ConnectionStatusEvaluator.ConnectorEvaluation evaluation
                        : ConnectionStatusEvaluator.evaluate(monitor, target.channelId, now)) {
                    applyOutcome(monitor, target.channelId, evaluation.metadataId, evaluation.outcome, now);
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
                    default:
                        log.warn("Monitor {} has unhandled type {}; skipping",
                                monitor.getId(), monitor.getMonitorType());
                        continue;
                }
                applyOutcome(monitor, target.channelId, null, outcome, now);
            }
        }

        autoResolveDepartedChannels(monitor, targetChannelIds, now);
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
     *       escalation repeat check instead.</li>
     *   <li><b>OK</b> — resolves any open alert (also covering the
     *       PROBLEM → INSUFFICIENT_DATA → OK path, where the state is no
     *       longer PROBLEM but an alert is still open) and settles the state
     *       to OK.</li>
     *   <li><b>INSUFFICIENT_DATA</b> — parks the state, resetting the breach
     *       counter, but deliberately leaves any open alert untouched: a
     *       transient data gap (collector restart, baseline aging out) is
     *       not evidence the underlying problem recovered, and
     *       resolve-then-reopen flapping would double-notify. The alert
     *       resolves on the next confirmed OK.</li>
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
                onInsufficientData(state, now);
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
            // Still breaching: give repeat-interval escalation actions a
            // chance. Suppressed alerts never notify, including repeats.
            if (state.getOpenAlertEventId() != null) {
                AlertEvent open = AlertEventRepository.getAlertEvent(state.getOpenAlertEventId());
                if (open != null && open.getStatus() == AlertStatus.PROBLEM && !open.isSuppressed()) {
                    ActionDispatcher.onRepeatCheck(open, AlertPayload.of(open, monitor, "PROBLEM"));
                }
            }
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
                if (!retained.isSuppressed()) {
                    ActionDispatcher.onRepeatCheck(retained, AlertPayload.of(retained, monitor, "PROBLEM"));
                }
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

    /** INSUFFICIENT_DATA branch of {@link #applyOutcome} — see its Javadoc. */
    private static void onInsufficientData(TriggerState state, Instant now) {
        if (state.getState() != TriggerStatus.INSUFFICIENT_DATA) {
            state.setLastChangeTime(now);
        }
        state.setState(TriggerStatus.INSUFFICIENT_DATA);
        state.setConsecutiveBreachCount(0);
        // openAlertEventId intentionally retained — see applyOutcome Javadoc.
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
     * Auto-resolves open problems whose channel left the monitored set.
     * For activity-based monitors (INACTIVITY/LOW_VOLUME/ANOMALY) "left"
     * means no longer in this tick's STARTED targets — a stopped or paused
     * channel cannot meaningfully be inactive or low-volume, so its problem
     * is administratively closed. For CONNECTION_STATUS "left" means
     * undeployed: a merely paused channel's connectors still hold real
     * connection state worth keeping open, so only undeployment (which
     * destroys the connectors) closes those problems.
     */
    private static void autoResolveDepartedChannels(Monitor monitor, Set<String> targetChannelIds,
            Instant now) {
        List<TriggerState> states = TriggerStateRepository.listTriggerStatesByMonitor(monitor.getId());
        if (states.isEmpty()) {
            return;
        }

        EngineController engineController = null;
        if (monitor.getMonitorType() == MonitorType.CONNECTION_STATUS) {
            engineController = ControllerFactory.getFactory().createEngineController();
        }

        for (TriggerState state : states) {
            boolean departed;
            if (monitor.getMonitorType() == MonitorType.CONNECTION_STATUS) {
                departed = !engineController.isDeployed(state.getChannelId());
            } else {
                departed = !targetChannelIds.contains(state.getChannelId());
            }
            if (!departed || state.getState() != TriggerStatus.PROBLEM
                    || state.getOpenAlertEventId() == null) {
                continue;
            }

            resolveOpenAlert(monitor, state, CHANNEL_LEFT_MESSAGE, now);
            // INSUFFICIENT_DATA, not OK: nothing was measured — the channel
            // simply stopped being measurable. If it restarts, evaluation
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
