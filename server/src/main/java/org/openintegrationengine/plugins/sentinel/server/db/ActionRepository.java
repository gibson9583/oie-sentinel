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

import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;

/**
 * Persistence for {@code sentinel_action}.
 *
 * <p>Every statement here is a single round trip, so each method calls
 * {@code SqlConfig.getInstance().getSqlSessionManager()} directly rather than
 * opening a manual-commit session — there is no multi-statement sequence that
 * needs a shared transaction. This class holds no state of its own, so like
 * {@code MonitorRepository} it is a plain collection of static methods rather
 * than an init()/getInstance()/close() singleton (see {@code PermissionUtil}
 * in the role-based-access-control plugin for the same style of stateless
 * helper).</p>
 */
public final class ActionRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(ActionRepository.class);

    private ActionRepository() {
    }

    /** Qualifies a mapped-statement id with this plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Fetches one action by its database id.
     *
     * @param id database id to look up
     * @return the action, or {@code null} if no row exists
     * @throws RepositoryException on persistence failure
     */
    public static Action getAction(int id) {
        try {
            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getAction"), id);
            return row != null ? buildAction(row) : null;
        } catch (Exception e) {
            log.error("Failed to get action {}", id, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Lists actions, optionally filtered by enabled state, ordered by name.
     *
     * @param enabled if non-null, restrict to actions with this enabled state
     * @return matching actions ordered by name; never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<Action> listActions(Boolean enabled) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("enabled", enabled);

            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listActions"), params);

            List<Action> actions = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                actions.add(buildAction(row));
            }
            return actions;
        } catch (Exception e) {
            log.error("Failed to list actions (enabled={})", enabled, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Inserts a new action.
     *
     * @param action the action to create; its {@code id} field is ignored
     *               (the database assigns it)
     * @return the same {@code action} instance passed in, with {@code id}
     *         populated from the generated key
     * @throws RepositoryException on persistence failure including a
     *                             duplicate-name violation
     */
    public static Action insertAction(Action action) {
        try {
            Map<String, Object> params = toColumnMap(action);
            // useGeneratedKeys writes the assigned id back into params["id"]
            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertAction"), params);
            action.setId(toInteger(params.get("id")));
            return action;
        } catch (Exception e) {
            log.error("Failed to insert action '{}'", action.getName(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Updates every column of an existing action by id.
     *
     * @param action the new state for the action; {@code id} identifies the
     *               row to update
     * @throws RepositoryException on persistence failure
     */
    public static void updateAction(Action action) {
        try {
            Map<String, Object> params = toColumnMap(action);
            params.put("id", action.getId());
            SqlConfig.getInstance().getSqlSessionManager().update(stmt("updateAction"), params);
        } catch (Exception e) {
            log.error("Failed to update action {}", action.getId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Deletes an action by id.
     *
     * @param id database id of the action to delete
     * @throws RepositoryException on persistence failure
     */
    public static void deleteAction(int id) {
        try {
            SqlConfig.getInstance().getSqlSessionManager().delete(stmt("deleteAction"), id);
        } catch (Exception e) {
            log.error("Failed to delete action {}", id, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO Conversion ==========

    /**
     * Builds the column map for {@code insertAction}/{@code updateAction}
     * (every {@code sentinel_action} column except {@code id}), keyed by
     * column name to match the mapped statements' {@code #{column_name}}
     * bind variables.
     */
    private static Map<String, Object> toColumnMap(Action action) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", action.getName());
        params.put("description", action.getDescription());
        params.put("enabled", action.isEnabled());
        params.put("action_type", action.getActionType() != null ? action.getActionType().name() : null);
        params.put("condition_json", action.getConditionJson());
        params.put("operation_mode", action.getOperationMode() != null ? action.getOperationMode().name() : null);
        params.put("repeat_interval_seconds", action.getRepeatIntervalSeconds());
        params.put("max_repeats", action.getMaxRepeats());
        params.put("config_json", action.getConfigJson());
        params.put("created_by", action.getCreatedBy());
        params.put("created_time", toTimestamp(action.getCreatedTime()));
        params.put("updated_by", action.getUpdatedBy());
        params.put("updated_time", toTimestamp(action.getUpdatedTime()));
        return params;
    }

    private static Action buildAction(Map<String, Object> row) {
        Action action = new Action();
        action.setId(toInteger(row.get("id")));
        action.setName((String) row.get("name"));
        action.setDescription((String) row.get("description"));
        action.setEnabled(toBoolean(row.get("enabled")));

        Object actionType = row.get("action_type");
        action.setActionType(actionType != null ? ActionType.valueOf((String) actionType) : null);

        action.setConditionJson((String) row.get("condition_json"));

        Object operationMode = row.get("operation_mode");
        action.setOperationMode(operationMode != null ? OperationMode.valueOf((String) operationMode) : null);

        action.setRepeatIntervalSeconds(toInteger(row.get("repeat_interval_seconds")));
        action.setMaxRepeats(toInteger(row.get("max_repeats")));
        action.setConfigJson((String) row.get("config_json"));
        action.setCreatedBy(toInteger(row.get("created_by")));
        action.setCreatedTime(toInstant(row.get("created_time")));
        action.setUpdatedBy(toInteger(row.get("updated_by")));
        action.setUpdatedTime(toInstant(row.get("updated_time")));
        return action;
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
