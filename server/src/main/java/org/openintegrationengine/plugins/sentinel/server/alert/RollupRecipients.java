package org.openintegrationengine.plugins.sentinel.server.alert;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.server.engine.ProblemSubject;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Recipient-specific disclosure policy; a global storm snapshot is never authority to name an incident. */
final class RollupRecipients {
    private static final Logger log = LoggerFactory.getLogger(RollupRecipients.class);
    private static final int CANDIDATE_LIMIT = 1000;

    private RollupRecipients() { }

    /** Reauthorizes disclosed subjects on the I/O worker without reserving another budget slot. */
    static AlertPayload refreshPayload(Action action, AlertPayload payload, Instant now) {
        if (payload == null || payload.rollupSummary() == null) return payload;
        AlertPayload.RollupSummary summary = payload.rollupSummary();
        var verdict = new AlertStormControl.CeilingVerdict(AlertStormControl.CeilingOutcome.ROLLUP,
                summary.sent(), summary.windowSeconds(),
                channels(action, now.minusSeconds(summary.windowSeconds()), now));
        return payload.copy(payload.getEventType(), AlertStormControl.rollupMessage(verdict));
    }

    /**
     * Names the subjects this recipient may be told about, in subject order:
     * channels by id, then "Log" when a Log problem or notification qualifies.
     * Notifications count, as they spend the same budget, on the opened edge.
     * Keyed on the subject, not the name, for the reason the storm scan gives.
     */
    static List<String> channels(Action action, Instant from, Instant now) {
        Map<ProblemSubject, String> names = new TreeMap<>();
        for (AlertEvent candidate : AlertEventRepository.listRecentAlertEvents(CANDIDATE_LIMIT)) {
            try {
                if (candidate.getId() == null || candidate.getOpenedTime() == null
                        || candidate.getOpenedTime().isBefore(from) || candidate.getOpenedTime().isAfter(now)) {
                    continue;
                }
                AlertEvent current = AlertEventRepository.getAlertEvent(candidate.getId());
                if (current == null || !ProblemSubject.of(current).isIdentified() || current.getStatus() == null
                        || NotificationSuppression.decision(current, now) != NotificationSuppression.Decision.ALLOW) {
                    continue;
                }
                OperationMode mode = action.getOperationMode();
                boolean resolved = current.getStatus() == AlertStatus.RESOLVED
                        && current.getKind() != AlertKind.NOTIFICATION;
                if (mode != OperationMode.BOTH && mode != (resolved
                        ? OperationMode.ON_RESOLVE : OperationMode.ON_PROBLEM)) {
                    continue;
                }
                Monitor monitor = MonitorRepository.getMonitor(current.getMonitorId());
                if (monitor == null || !monitor.isEnabled()) {
                    continue;
                }
                // Matched as the edge it is sent on; a notification as NOTIFICATION.
                AlertPayload payload = AlertPayload.of(current, monitor, "PROBLEM");
                payload = payload.copy(payload.conditionEventType(resolved), payload.getMessage());
                if (ActionConditionMatcher.matches(action, payload)) {
                    names.put(ProblemSubject.of(current), payload.getChannelName());
                }
            } catch (Exception e) {
                log.warn("Cannot safely include alert {} in rollup for action {}",
                        candidate.getId(), action.getId(), e);
            }
        }
        return List.copyOf(names.values());
    }
}
