/* OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openintegrationengine.plugins.sentinel.server.alert.ActionConditionMatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.AlertPayload;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.NotificationSuppression;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads observations only: no dispatcher invocation, writes, tests, or storm counters. */
public final class DecisionInspectorService {
    private static final Logger log = LoggerFactory.getLogger(DecisionInspectorService.class);

    private DecisionInspectorService() { }

    public record ActionMatch(Integer id, String name, ActionType transport, OperationMode phase) { }

    public static Map<String, Object> inspect(long id) {
        AlertEvent event = ProblemService.get(id);
        Map<String, Object> result = new LinkedHashMap<>();
        Instant now = Instant.now();
        result.put("observedAt", now);
        result.put("event", event);
        result.put("policy", NotificationSuppression.explain(event, now));
        try {
            Monitor monitor = MonitorRepository.getMonitor(event.getMonitorId());
            result.put("currentMinConsecutiveBreaches", monitor == null ? null : monitor.getMinConsecutiveBreaches());
            result.put("currentTrigger", TriggerStateRepository.getTriggerState(
                    event.getMonitorId(), event.getChannelId(), event.getMetadataId()));
        } catch (Exception e) {
            log.warn("Inspector evaluation read failed for event {}", id, e);
            result.put("evaluationError", "Current evaluation lookup failed; refresh to retry");
        }
        try {
            Monitor monitor = MonitorRepository.getMonitor(event.getMonitorId());
            AlertPayload payload = AlertPayload.of(event, monitor, event.getStatus().name());
            List<ActionMatch> matches = new ArrayList<>();
            for (Action action : ActionRepository.listActions(Boolean.TRUE)) {
                if (ActionConditionMatcher.firesOnPhase(action.getOperationMode(), event.getStatus() == AlertStatus.RESOLVED)
                        && ActionConditionMatcher.matches(action, payload)) {
                    matches.add(new ActionMatch(action.getId(), action.getName(), action.getActionType(), action.getOperationMode()));
                }
            }
            result.put("matchingActions", matches);
        } catch (Exception e) {
            log.warn("Inspector routing read failed for event {}", id, e);
            result.put("routingError", "Current action matching lookup failed; refresh to retry");
        }
        return result;
    }
}
