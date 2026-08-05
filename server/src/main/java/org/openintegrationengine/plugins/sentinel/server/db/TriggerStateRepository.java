/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.util.SqlConfig;

import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;

/**
 * Persistence for {@code sentinel_trigger_state} — the per-{@code
 * (monitor, channel, metadata_id)} evaluation state {@code TriggerEvaluatorJob}
 * reads and updates on every tick.
 *
 * <p>Stateless: every method opens whatever it needs from
 * {@link SqlConfig#getInstance()} directly, so unlike {@code RbacRepository}
 * this class carries no constructor-time setup and needs no singleton
 * lifecycle — plain static methods are sufficient. Every statement here is a
 * single MyBatis call, so auto-commit (the default session behavior) is fine;
 * no method needs a manual-commit {@code openSession(false)} transaction.</p>
 */
public final class TriggerStateRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(TriggerStateRepository.class);

    private TriggerStateRepository() {
    }

    /** Qualifies a mapped-statement id with the plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Fetches the trigger state for one {@code (monitor, channel, metadata_id)}
     * triple.
     *
     * @param monitorId  id of the owning {@code sentinel_monitor}
     * @param channelId  the OIE channel id (a UUID string)
     * @param metadataId the connector metadata id, or {@code null} for
     *                   monitor types that evaluate at the whole-channel
     *                   level
     * @return the matching trigger state, or {@code null} if no row exists
     *         yet for this triple
     * @throws RepositoryException on persistence failure
     */
    public static TriggerState getTriggerState(int monitorId, String channelId, Integer metadataId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("monitorId", monitorId);
            params.put("channelId", channelId);
            params.put("metadataId", metadataId);

            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getTriggerState"), params);

            if (row == null) {
                return null;
            }
            return toTriggerState(row);
        } catch (Exception e) {
            log.error("Failed to get trigger state for monitor {}, channel {}, metadata {}",
                    monitorId, channelId, metadataId, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Inserts a new trigger state row.
     *
     * @param triggerState the state to persist; its {@code id} field is
     *                     ignored (the database assigns it)
     * @return the same instance with {@code id} populated from the generated key
     * @throws RepositoryException on persistence failure, including a
     *                             unique-constraint violation on
     *                             {@code (monitor_id, channel_id, metadata_id)}
     */
    public static TriggerState insertTriggerState(TriggerState triggerState) {
        try {
            Map<String, Object> params = toParams(triggerState);

            // useGeneratedKeys writes the assigned id back into params["id"]
            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertTriggerState"), params);
            triggerState.setId(toInteger(params.get("id")));

            return triggerState;
        } catch (Exception e) {
            log.error("Failed to insert trigger state for monitor {}, channel {}, metadata {}",
                    triggerState.getMonitorId(), triggerState.getChannelId(), triggerState.getMetadataId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Updates an existing trigger state row by id.
     *
     * @param triggerState the state to persist; {@code id} must identify an
     *                     existing row
     * @throws RepositoryException on persistence failure
     */
    public static void updateTriggerState(TriggerState triggerState) {
        try {
            Map<String, Object> params = toParams(triggerState);
            SqlConfig.getInstance().getSqlSessionManager().update(stmt("updateTriggerState"), params);
        } catch (Exception e) {
            log.error("Failed to update trigger state {}", triggerState.getId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Lists every trigger state belonging to a monitor, ordered by channel id.
     * Used by the evaluator to sweep a monitor's whole scope in one query.
     *
     * @param monitorId id of the owning {@code sentinel_monitor}
     * @return the monitor's trigger states, ordered by channel id; empty if
     *         the monitor has never been evaluated
     * @throws RepositoryException on persistence failure
     */
    public static List<TriggerState> listTriggerStatesByMonitor(int monitorId) {
        try {
            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listTriggerStatesByMonitor"), monitorId);

            List<TriggerState> results = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                results.add(toTriggerState(row));
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to list trigger states for monitor {}", monitorId, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO conversion ==========

    /**
     * Builds the full insert/update parameter map for a trigger state. Shared
     * by {@link #insertTriggerState(TriggerState)} and
     * {@link #updateTriggerState(TriggerState)}: the update statement simply
     * ignores the keys it doesn't {@code <set>}.
     */
    private static Map<String, Object> toParams(TriggerState triggerState) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", triggerState.getId());
        params.put("monitor_id", triggerState.getMonitorId());
        params.put("channel_id", triggerState.getChannelId());
        params.put("metadata_id", triggerState.getMetadataId());
        params.put("state", triggerState.getState() != null ? triggerState.getState().name() : null);
        params.put("consecutive_breach_count", triggerState.getConsecutiveBreachCount());
        params.put("last_value_json", triggerState.getLastValueJson());
        params.put("open_alert_event_id", triggerState.getOpenAlertEventId());
        params.put("last_change_time", toTimestamp(triggerState.getLastChangeTime()));
        params.put("last_evaluated_time", toTimestamp(triggerState.getLastEvaluatedTime()));
        return params;
    }

    private static TriggerState toTriggerState(Map<String, Object> row) {
        TriggerState triggerState = new TriggerState();
        triggerState.setId(toInteger(row.get("id")));
        triggerState.setMonitorId(toInteger(row.get("monitor_id")));
        triggerState.setChannelId((String) row.get("channel_id"));
        triggerState.setMetadataId(toInteger(row.get("metadata_id")));

        Object state = row.get("state");
        triggerState.setState(state != null ? TriggerStatus.valueOf(state.toString()) : null);

        Integer breachCount = toInteger(row.get("consecutive_breach_count"));
        triggerState.setConsecutiveBreachCount(breachCount != null ? breachCount : 0);

        triggerState.setLastValueJson((String) row.get("last_value_json"));

        Object openAlertEventId = row.get("open_alert_event_id");
        triggerState.setOpenAlertEventId(openAlertEventId != null ? ((Number) openAlertEventId).longValue() : null);

        triggerState.setLastChangeTime(toInstant(row.get("last_change_time")));
        triggerState.setLastEvaluatedTime(toInstant(row.get("last_evaluated_time")));

        return triggerState;
    }

    /** Converts an {@link Instant} to the {@link Timestamp} MyBatis/JDBC expects as a bound parameter. */
    private static Timestamp toTimestamp(Instant value) {
        return value != null ? Timestamp.from(value) : null;
    }

    /** Converts the {@link Timestamp} JDBC hands back from a SELECT to an {@link Instant}. */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        return ((Timestamp) value).toInstant();
    }

    /**
     * Coerces a MyBatis-returned key or id to an {@link Integer}. Most drivers
     * return an {@code Integer}, but Derby hands back identity/generated keys as
     * a {@link java.math.BigDecimal}, so a direct {@code (Integer)} cast throws
     * {@link ClassCastException}. Handles any {@link Number}.
     *
     * @param value the raw value from a params map or result row
     * @return the value as an {@code Integer}, or {@code null} if it is null
     */
    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.valueOf(value.toString().trim());
    }
}
