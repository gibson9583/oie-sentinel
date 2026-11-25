/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.util.List;

import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertLifecycleTransaction;
import org.openintegrationengine.plugins.sentinel.server.db.LeaseFence;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.LogEventsEvaluator;
import org.openintegrationengine.plugins.sentinel.server.log.LogEventsConfig;
import org.openintegrationengine.plugins.sentinel.server.log.LogNotifications;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorKind;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;

/**
 * The leader's half of a Log monitor: its condition, its outcome and, for
 * problems, its clear mode. Each occurrence as a notification is not here;
 * the node that matched it writes it at flush time.
 *
 * <p>Problems use {@link TriggerEvaluatorJob}'s state machine, which asks
 * {@link #keepsOpen} and {@link #timedOut} before clearing and calls
 * {@link #addTo} while the condition holds. TIMEOUT counts from the last
 * breach, the last evaluation at which the condition was true; that time is
 * kept as {@code lastBreach} in the open problem's details.</p>
 *
 * <p>A notification outcome fires once each time the condition becomes true
 * and re-arms when it reads false. Its trigger state is PROBLEM while fired,
 * with no alert held; a data gap neither re-arms nor fires it.</p>
 *
 * <p>A problem still open when the outcome changes to notifications is no
 * longer evaluated, so the next evaluation resolves it with
 * {@link #OUTCOME_CHANGED_MESSAGE} and the trigger starts fresh.</p>
 */
final class LogMonitorEvaluation {

    static final String OUTCOME_CHANGED_MESSAGE =
            "Monitor outcome changed to notifications; this problem is no longer evaluated";

    private LogMonitorEvaluation() {
    }

    static void evaluate(Monitor monitor, Instant now, LeaseFence fence, SentinelSettings settings) {
        LogEventsConfig config = LogEventsConfig.lenient(monitor.getId(), monitor.getConfigJson());
        if (config != null && config.notifiesEachOccurrence()) {
            resolveLeftoverProblem(monitor, now, fence);
            return;
        }
        EvaluationOutcome outcome = LogEventsEvaluator.evaluate(monitor, now, settings);
        if (config != null && config.outcome() == LogEventsConfig.Outcome.NOTIFICATION) {
            AlertLifecycleTransaction.execute(fence, afterCommit -> {
                AlertEvent fired = applyNotification(monitor, outcome, now, afterCommit);
                if (fired != null) {
                    afterCommit.add(() -> ActionDispatcher.onAlertOpened(fired,
                            AlertPayload.of(fired, monitor, LogNotifications.EVENT_TYPE)));
                }
            });
        } else {
            TriggerEvaluatorJob.applyOutcome(monitor, ProblemSubject.server(), null, outcome, now, fence);
        }
    }

    /** @return the notification written by this evaluation, or {@code null} */
    private static AlertEvent applyNotification(Monitor monitor, EvaluationOutcome outcome, Instant now,
            List<Runnable> afterCommit) {
        TriggerState state = ProblemSubject.server().loadTriggerState(monitor.getId(), null);
        boolean isNew = state == null;
        if (isNew) {
            state = new TriggerState();
            state.setMonitorId(monitor.getId());
            state.setSubjectType(ProblemSubject.server().type());
            state.setState(TriggerStatus.INSUFFICIENT_DATA);
            state.setLastChangeTime(now);
        } else {
            resolveForOutcomeChange(monitor, state, now, afterCommit);
        }
        AlertEvent fired = null;
        TriggerStatus next = state.getState();
        int breaches = 0;
        switch (outcome.getResult()) {
            case BREACH:
                if (state.getState() == TriggerStatus.PROBLEM) {
                    break;
                }
                breaches = state.getConsecutiveBreachCount() + 1;
                if (breaches >= Math.max(1, monitor.getMinConsecutiveBreaches())) {
                    fired = LogNotifications.notification(monitor, outcome.getMessage(), outcome.getValueJson(), now);
                    fired.setSuppressed(NotificationSuppression.isSuppressed(monitor, ProblemSubject.server(), now));
                    AlertEventRepository.insertAlertEvent(fired);
                    next = TriggerStatus.PROBLEM;
                    breaches = 0;
                }
                break;
            case OK:
                next = TriggerStatus.OK;
                break;
            case INSUFFICIENT_DATA:
                if (state.getState() != TriggerStatus.PROBLEM) {
                    next = TriggerStatus.INSUFFICIENT_DATA;
                }
                break;
        }
        if (next != state.getState()) {
            state.setLastChangeTime(now);
        }
        state.setState(next);
        state.setConsecutiveBreachCount(breaches);
        state.setLastEvaluatedTime(now);
        state.setLastValueJson(outcome.getValueJson());
        if (isNew) {
            TriggerStateRepository.insertTriggerState(state);
        } else {
            TriggerStateRepository.updateTriggerState(state);
        }
        return fired;
    }

    /**
     * Each occurrence as a notification has no leader evaluation, so the
     * leader only resolves a problem left from the PROBLEM outcome. The state
     * is read outside a transaction first, so a monitor without one opens none.
     */
    private static void resolveLeftoverProblem(Monitor monitor, Instant now, LeaseFence fence) {
        TriggerState seen = ProblemSubject.server().loadTriggerState(monitor.getId(), null);
        if (seen == null || seen.getOpenAlertEventId() == null) {
            return;
        }
        AlertLifecycleTransaction.execute(fence, afterCommit -> {
            TriggerState state = ProblemSubject.server().loadTriggerState(monitor.getId(), null);
            if (state != null && resolveForOutcomeChange(monitor, state, now, afterCommit)) {
                state.setLastEvaluatedTime(now);
                TriggerStateRepository.updateTriggerState(state);
            }
        });
    }

    /**
     * Resolves a problem opened under the PROBLEM outcome the way the kind-change
     * sweep in {@link TriggerEvaluatorJob} does, with the normal resolution edge,
     * and resets the trigger to INSUFFICIENT_DATA with no breaches counted.
     *
     * @return whether the state held a problem; the caller saves the state
     */
    private static boolean resolveForOutcomeChange(Monitor monitor, TriggerState state, Instant now,
            List<Runnable> afterCommit) {
        if (state.getOpenAlertEventId() == null) {
            return false;
        }
        TriggerEvaluatorJob.resolveOpenAlert(monitor, state, OUTCOME_CHANGED_MESSAGE, now, afterCommit);
        state.setState(TriggerStatus.INSUFFICIENT_DATA);
        state.setConsecutiveBreachCount(0);
        state.setLastChangeTime(now);
        return true;
    }

    /** For an OK verdict: whether a Log problem stays open, because a person or the timeout clears it. */
    static boolean keepsOpen(Monitor monitor, TriggerState state, Instant now) {
        LogEventsConfig config = openLogProblem(monitor, state);
        if (config == null) {
            return false;
        }
        return config.clearMode() == LogEventsConfig.ClearMode.MANUAL
                || (config.clearMode() == LogEventsConfig.ClearMode.TIMEOUT && !elapsed(config, state, now));
    }

    /** For an INSUFFICIENT_DATA verdict: whether a TIMEOUT problem's time is up, data or not. */
    static boolean timedOut(Monitor monitor, TriggerState state, Instant now) {
        LogEventsConfig config = openLogProblem(monitor, state);
        return config != null && config.clearMode() == LogEventsConfig.ClearMode.TIMEOUT
                && elapsed(config, state, now);
    }

    /** While the condition holds, the open Log problem takes the latest evaluation. */
    static void addTo(Monitor monitor, TriggerState state, EvaluationOutcome outcome) {
        if (openLogProblem(monitor, state) != null && outcome.getValueJson() != null) {
            AlertEventRepository.updateAlertEventDetails(state.getOpenAlertEventId(), outcome.getValueJson());
        }
    }

    private static LogEventsConfig openLogProblem(Monitor monitor, TriggerState state) {
        if (state.getOpenAlertEventId() == null || monitor.getMonitorType() == null
                || monitor.getMonitorType().kind() != MonitorKind.LOG) {
            return null;
        }
        return LogEventsConfig.lenient(monitor.getId(), monitor.getConfigJson());
    }

    private static boolean elapsed(LogEventsConfig config, TriggerState state, Instant now) {
        AlertEvent open = AlertEventRepository.getAlertEvent(state.getOpenAlertEventId());
        if (open == null || open.getStatus() != AlertStatus.PROBLEM) {
            return true;
        }
        Instant lastBreach = open.getOpenedTime();
        try {
            lastBreach = Instant.parse(Json.mapper().readTree(open.getDetailsJson()).path("lastBreach").asText());
        } catch (Exception e) {
            // Details from before lastBreach existed, or damaged: count from the opening.
        }
        return lastBreach == null || !now.isBefore(lastBreach.plusSeconds(config.clearAfterSeconds()));
    }
}
