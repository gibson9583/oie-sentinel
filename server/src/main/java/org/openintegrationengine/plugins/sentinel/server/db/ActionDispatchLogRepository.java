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

import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;

/**
 * Persistence for {@code sentinel_action_dispatch_log} — the write-once audit
 * trail of every attempt the dispatch job makes to fire a {@code
 * sentinel_action} against an alert event, successful or not.
 *
 * <p>Stateless: every method reaches {@link SqlConfig#getInstance()} directly,
 * so unlike {@code RbacRepository} this class carries no constructor-time
 * setup and needs no singleton lifecycle — plain static methods are
 * sufficient. Both statements here are a single round trip and use
 * auto-commit; the insert is a plain insert with no generated-key retrieval
 * (nothing here ever needs the id of a freshly-written row back), so this
 * repository still needs {@link #toBoolean(Object)} to read the {@code
 * success} column back (Boolean for Postgres/Derby/MySQL, Number for
 * Oracle/SQL Server) but has no use for a {@code toInteger}/generated-key
 * coercion helper.</p>
 */
public final class ActionDispatchLogRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(ActionDispatchLogRepository.class);

    private ActionDispatchLogRepository() {
    }

    /** Qualifies a mapped-statement id with this plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Records one dispatch attempt. Plain insert — {@code
     * sentinel_action_dispatch_log} rows are never updated after creation, so
     * no generated-key retrieval is needed.
     *
     * @param dispatchLog the attempt to persist; its {@code id} field is
     *                    ignored
     * @throws RepositoryException on persistence failure
     */
    public static void insertActionDispatchLog(ActionDispatchLog dispatchLog) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("alert_event_id", dispatchLog.getAlertEventId());
            params.put("action_id", dispatchLog.getActionId());
            params.put("dispatch_time", toTimestamp(dispatchLog.getDispatchTime()));
            params.put("success", dispatchLog.isSuccess());
            params.put("error_message", dispatchLog.getErrorMessage());

            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertActionDispatchLog"), params);
        } catch (Exception e) {
            log.error("Failed to insert action dispatch log for alert event {}, action {}",
                    dispatchLog.getAlertEventId(), dispatchLog.getActionId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Lists every dispatch attempt recorded for one alert event, ordered by
     * dispatch time. Used to render an alert event's delivery history.
     *
     * @param alertEventId id of the {@code sentinel_alert_event} to look up
     * @return matching dispatch log entries ordered by {@code dispatch_time}
     *         ascending; empty if no action has ever been dispatched for this
     *         event
     * @throws RepositoryException on persistence failure
     */
    public static List<ActionDispatchLog> listActionDispatchLogsForEvent(long alertEventId) {
        try {
            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listActionDispatchLogsForEvent"), alertEventId);

            List<ActionDispatchLog> results = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                results.add(toActionDispatchLog(row));
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to list action dispatch logs for alert event {}", alertEventId, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO conversion ==========

    private static ActionDispatchLog toActionDispatchLog(Map<String, Object> row) {
        ActionDispatchLog dispatchLog = new ActionDispatchLog();

        Object id = row.get("id");
        dispatchLog.setId(id != null ? ((Number) id).longValue() : null);

        dispatchLog.setAlertEventId(((Number) row.get("alert_event_id")).longValue());

        Object actionId = row.get("action_id");
        dispatchLog.setActionId(actionId != null ? ((Number) actionId).intValue() : null);

        dispatchLog.setDispatchTime(toInstant(row.get("dispatch_time")));
        dispatchLog.setSuccess(toBoolean(row.get("success")));
        dispatchLog.setErrorMessage((String) row.get("error_message"));

        return dispatchLog;
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
     * Coerce a boolean-ish DB value to a Java boolean. Different JDBC drivers
     * return different types for boolean columns (Boolean for Postgres/Derby/MySQL,
     * Number for Oracle/SQL Server).
     */
    private static boolean toBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return false;
    }
}
