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
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Recipient-specific disclosure policy; a global storm snapshot is never authority to name an incident. */
final class RollupRecipients {
    private static final Logger log = LoggerFactory.getLogger(RollupRecipients.class);
    private static final int CANDIDATE_LIMIT = 1000;

    private RollupRecipients() { }

    static List<String> channels(Action action, Instant from, Instant now) {
        Map<String, String> names = new TreeMap<>();
        for (AlertEvent candidate : AlertEventRepository.listRecentAlertEvents(CANDIDATE_LIMIT)) {
            try {
                if (candidate.getId() == null || candidate.getOpenedTime() == null
                        || candidate.getOpenedTime().isBefore(from) || candidate.getOpenedTime().isAfter(now)) {
                    continue;
                }
                AlertEvent current = AlertEventRepository.getAlertEvent(candidate.getId());
                if (current == null || current.getChannelId() == null || current.getStatus() == null
                        || NotificationSuppression.decision(current, now) != NotificationSuppression.Decision.ALLOW) {
                    continue;
                }
                OperationMode mode = action.getOperationMode();
                boolean resolved = current.getStatus() == AlertStatus.RESOLVED;
                if (mode != OperationMode.BOTH && mode != (resolved
                        ? OperationMode.ON_RESOLVE : OperationMode.ON_PROBLEM)) {
                    continue;
                }
                Monitor monitor = MonitorRepository.getMonitor(current.getMonitorId());
                if (monitor == null || !monitor.isEnabled()) {
                    continue;
                }
                AlertPayload payload = AlertPayload.of(current, monitor, resolved ? "RESOLVED" : "PROBLEM");
                if (ActionConditionMatcher.matches(action, payload)) {
                    names.put(current.getChannelId(), payload.getChannelName());
                }
            } catch (Exception e) {
                log.warn("Cannot safely include alert {} in rollup for action {}",
                        candidate.getId(), action.getId(), e);
            }
        }
        return List.copyOf(names.values());
    }
}
