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

import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowMode;
import org.openintegrationengine.plugins.sentinel.shared.model.WindowRepeat;

/**
 * Persistence for {@code sentinel_maintenance_window}.
 *
 * <p>Every statement here is a single round trip, so each method calls
 * {@code SqlConfig.getInstance().getSqlSessionManager()} directly rather than
 * opening a manual-commit session — there is no multi-statement sequence that
 * needs a shared transaction. This class holds no state of its own, so like
 * {@code MonitorRepository} and {@code ActionRepository} it is a plain
 * collection of static methods rather than an init()/getInstance()/close()
 * singleton (see {@code PermissionUtil} in the role-based-access-control
 * plugin for the same style of stateless helper).</p>
 *
 * <p>{@link #listEnabledMaintenanceWindows()} only narrows by
 * {@code enabled} — recurring schedules cannot be evaluated in portable SQL,
 * so the "active right now" decision (see {@code WindowSchedule}) and
 * matching a returned window's scope against a specific channel or monitor
 * are the caller's job, not this repository's.</p>
 */
public final class MaintenanceWindowRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowRepository.class);

    private MaintenanceWindowRepository() {
    }

    /** Qualifies a mapped-statement id with this plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Fetches one maintenance window by its database id.
     *
     * @param id database id to look up
     * @return the maintenance window, or {@code null} if no row exists
     * @throws RepositoryException on persistence failure
     */
    public static MaintenanceWindow getMaintenanceWindow(int id) {
        try {
            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getMaintenanceWindow"), id);
            return row != null ? buildMaintenanceWindow(row) : null;
        } catch (Exception e) {
            log.error("Failed to get maintenance window {}", id, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * @return every maintenance window, ordered by {@code active_from}
     *         descending; never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<MaintenanceWindow> listMaintenanceWindows() {
        try {
            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listMaintenanceWindows"));

            List<MaintenanceWindow> windows = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                windows.add(buildMaintenanceWindow(row));
            }
            return windows;
        } catch (Exception e) {
            log.error("Failed to list maintenance windows", e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Lists enabled maintenance windows. The caller is responsible for the
     * schedule math ({@code WindowSchedule.isActiveNow}) and for filtering
     * down to windows whose scope matches the channel/monitor it actually
     * cares about — recurring schedules cannot be evaluated in portable SQL.
     *
     * @return enabled maintenance windows in no particular order; never
     *         {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<MaintenanceWindow> listEnabledMaintenanceWindows() {
        try {
            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listEnabledMaintenanceWindows"));

            List<MaintenanceWindow> windows = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                windows.add(buildMaintenanceWindow(row));
            }
            return windows;
        } catch (Exception e) {
            log.error("Failed to list enabled maintenance windows", e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Inserts a new maintenance window.
     *
     * @param window the maintenance window to create; its {@code id} field is
     *               ignored (the database assigns it)
     * @return the same {@code window} instance passed in, with {@code id}
     *         populated from the generated key
     * @throws RepositoryException on persistence failure
     */
    public static MaintenanceWindow insertMaintenanceWindow(MaintenanceWindow window) {
        try {
            Map<String, Object> params = toColumnMap(window);
            // useGeneratedKeys writes the assigned id back into params["id"]
            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertMaintenanceWindow"), params);
            window.setId(toInteger(params.get("id")));
            return window;
        } catch (Exception e) {
            log.error("Failed to insert maintenance window '{}'", window.getName(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Updates every column of an existing maintenance window by id.
     *
     * @param window the new state for the maintenance window; {@code id}
     *               identifies the row to update
     * @throws RepositoryException on persistence failure
     */
    public static void updateMaintenanceWindow(MaintenanceWindow window) {
        try {
            Map<String, Object> params = toColumnMap(window);
            params.put("id", window.getId());
            SqlConfig.getInstance().getSqlSessionManager().update(stmt("updateMaintenanceWindow"), params);
        } catch (Exception e) {
            log.error("Failed to update maintenance window {}", window.getId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Deletes a maintenance window by id.
     *
     * @param id database id of the maintenance window to delete
     * @throws RepositoryException on persistence failure
     */
    public static void deleteMaintenanceWindow(int id) {
        try {
            SqlConfig.getInstance().getSqlSessionManager().delete(stmt("deleteMaintenanceWindow"), id);
        } catch (Exception e) {
            log.error("Failed to delete maintenance window {}", id, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO Conversion ==========

    /**
     * Builds the column map for {@code insertMaintenanceWindow}/
     * {@code updateMaintenanceWindow} (every {@code sentinel_maintenance_window}
     * column except {@code id}), keyed by column name to match the mapped
     * statements' {@code #{column_name}} bind variables.
     */
    private static Map<String, Object> toColumnMap(MaintenanceWindow window) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", window.getName());
        params.put("scope_type", window.getScopeType() != null ? window.getScopeType().name() : null);
        params.put("scope_id", window.getScopeId());
        params.put("window_mode", window.getMode() != null ? window.getMode().name() : WindowMode.SUPPRESS.name());
        params.put("repeat_type", window.getRepeatType() != null ? window.getRepeatType().name() : WindowRepeat.NONE.name());
        params.put("days_of_week", window.getDaysOfWeek());
        params.put("days_of_month", window.getDaysOfMonth());
        params.put("start_time", window.getStartTime());
        params.put("end_time", window.getEndTime());
        // Null is meaningful here (= evaluate on the server's zone) and is what
        // every pre-v3 row carries; never substitute a concrete zone id.
        params.put("timezone", window.getTimezone());
        params.put("active_from", toTimestamp(window.getActiveFrom()));
        params.put("active_until", toTimestamp(window.getActiveUntil()));
        params.put("enabled", window.isEnabled());
        params.put("created_by", window.getCreatedBy());
        params.put("created_time", toTimestamp(window.getCreatedTime()));
        return params;
    }

    private static MaintenanceWindow buildMaintenanceWindow(Map<String, Object> row) {
        MaintenanceWindow window = new MaintenanceWindow();
        window.setId(toInteger(row.get("id")));
        window.setName((String) row.get("name"));

        Object scopeType = row.get("scope_type");
        window.setScopeType(scopeType != null ? ScopeType.valueOf((String) scopeType) : null);

        window.setScopeId((String) row.get("scope_id"));

        Object mode = row.get("window_mode");
        window.setMode(mode != null ? WindowMode.valueOf((String) mode) : WindowMode.SUPPRESS);
        Object repeatType = row.get("repeat_type");
        window.setRepeatType(repeatType != null ? WindowRepeat.valueOf((String) repeatType) : WindowRepeat.NONE);
        window.setDaysOfWeek((String) row.get("days_of_week"));
        window.setDaysOfMonth((String) row.get("days_of_month"));
        window.setStartTime((String) row.get("start_time"));
        window.setEndTime((String) row.get("end_time"));
        window.setTimezone((String) row.get("timezone"));

        window.setActiveFrom(toInstant(row.get("active_from")));
        window.setActiveUntil(toInstant(row.get("active_until")));
        window.setEnabled(toBoolean(row.get("enabled")));
        window.setCreatedBy(toInteger(row.get("created_by")));
        window.setCreatedTime(toInstant(row.get("created_time")));
        return window;
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
