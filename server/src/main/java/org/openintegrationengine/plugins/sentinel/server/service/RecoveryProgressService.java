/* OIE Sentinel — MPL 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.server.evaluate.RecoveryHysteresis;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

/** Read-only latest trigger progress, filtered before emitting rows or counts. */
public final class RecoveryProgressService {
    private RecoveryProgressService() { }
    public static Map<String, Object> build(int id, Set<String> authorized) {
        var monitor = MonitorService.get(id);
        int required = RecoveryHysteresis.required(monitor);
        var rows = new ArrayList<Map<String, Object>>();
        boolean truncated = false;
        for (TriggerState state : TriggerStateRepository.listTriggerStatesByMonitor(id)) {
            if (authorized != null && !authorized.contains(state.getChannelId())) continue;
            if (rows.size() == 1000) { truncated = true; break; }
            var row = new LinkedHashMap<String, Object>();
            row.put("channelId", state.getChannelId()); row.put("metadataId", state.getMetadataId());
            row.put("state", state.getState()); row.put("openAlertEventId", state.getOpenAlertEventId());
            row.put("lastEvaluatedTime", state.getLastEvaluatedTime());
            row.put("consecutiveBreachCount", state.getConsecutiveBreachCount());
            var open = state.getOpenAlertEventId() == null ? null
                    : AlertEventRepository.getAlertEvent(state.getOpenAlertEventId());
            row.put("problemStatus", open == null ? null : open.getStatus());
            row.put("healthyCount", open != null && open.getStatus() == AlertStatus.PROBLEM
                    ? RecoveryHysteresis.currentCount(monitor, state) : 0);
            row.put("required", required); rows.add(row);
        }
        return Map.of("monitorId", id, "required", required, "rows", rows, "truncated", truncated);
    }
}
