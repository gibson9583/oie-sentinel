/* OIE Sentinel — MPL 2.0. */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;

/** Consecutive healthy progress travels atomically with the trigger's existing
 * last_value_json CLOB. No process-local counters and no vendor-specific JSON SQL.
 * Incident identity and policy value fence progress from prior/manual closures
 * and configuration changes. Unknown data breaks the consecutive sequence. */
public final class RecoveryHysteresis {
    public static final String CONFIG_KEY = "minConsecutiveRecoveries";
    public static final String EVIDENCE_KEY = "sentinelRecovery";
    private static final ObjectMapper JSON = new ObjectMapper();
    private RecoveryHysteresis() { }

    public static void validate(JsonNode config) {
        JsonNode n = config.get(CONFIG_KEY);
        if (n == null || n.isNull()) return;
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 1 || n.intValue() > 10000) {
            throw new IllegalArgumentException(CONFIG_KEY + " must be a whole number between 1 and 10000");
        }
    }

    public static int required(Monitor monitor) {
        JsonNode config = parse(monitor.getConfigJson());
        validate(config);
        return config.path(CONFIG_KEY).asInt(1);
    }

    public record Progress(int healthyCount, int required, Long alertEventId) { }

    /** Counter belongs only to this incident and requirement; stale/manual
     * closure metadata must never be presented as current progress. */
    public static int currentCount(Monitor monitor, TriggerState state) {
        int required = required(monitor);
        if (required == 1 || state.getOpenAlertEventId() == null) return 0;
        JsonNode previous = parse(state.getLastValueJson()).path(EVIDENCE_KEY);
        Long priorIncident = previous.path("alertEventId").canConvertToLong()
                ? previous.path("alertEventId").longValue() : null;
        if (!Objects.equals(state.getOpenAlertEventId(), priorIncident)
                || previous.path("required").asInt() != required) return 0;
        return Math.max(0, Math.min(required, previous.path("healthyCount").asInt()));
    }

    public static Progress advance(Monitor monitor, TriggerState state, EvaluationOutcome outcome) {
        int required = required(monitor);
        Long incident = state.getOpenAlertEventId();
        int count = 0;
        if (required > 1 && incident != null && outcome.getResult() == EvaluationOutcome.Result.OK) {
            count = currentCount(monitor, state);
            count = Math.min(required, count + 1);
        }
        return new Progress(count, required, incident);
    }

    public static String evidence(String raw, Progress progress) {
        if (progress.required() == 1) return raw; // Preserve old JSON/default behavior exactly.
        ObjectNode value = parse(raw).deepCopy();
        ObjectNode recovery = value.putObject(EVIDENCE_KEY);
        recovery.put("healthyCount", progress.healthyCount());
        recovery.put("required", progress.required());
        if (progress.alertEventId() == null) recovery.putNull("alertEventId");
        else recovery.put("alertEventId", progress.alertEventId());
        return value.toString();
    }

    private static ObjectNode parse(String raw) {
        try {
            JsonNode n = raw == null ? null : JSON.readTree(raw);
            if (n != null && n.isObject()) return (ObjectNode) n;
        } catch (Exception ignored) { /* absent/legacy evidence starts from zero */ }
        return JSON.createObjectNode();
    }
}
