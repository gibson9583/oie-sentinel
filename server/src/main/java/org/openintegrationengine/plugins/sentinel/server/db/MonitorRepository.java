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

import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * Persistence for {@code sentinel_monitor}.
 *
 * <p>Every statement here is a single round trip, so each method calls
 * {@code SqlConfig.getInstance().getSqlSessionManager()} directly rather than
 * opening a manual-commit session — there is no multi-statement sequence that
 * needs a shared transaction (contrast with a repository that has to keep a
 * parent row and its child rows in lock-step). This class holds no state of
 * its own, so unlike a repository that needs shared mutable state across
 * calls, it is a plain collection of static methods rather than an
 * init()/getInstance()/close() singleton (see {@code PermissionUtil} in the
 * role-based-access-control plugin for the same style of stateless helper).</p>
 */
public final class MonitorRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(MonitorRepository.class);

    private MonitorRepository() {
    }

    /** Qualifies a mapped-statement id with this plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Fetches one monitor by its database id.
     *
     * @param id database id to look up
     * @return the monitor, or {@code null} if no row exists
     * @throws RepositoryException on persistence failure
     */
    public static Monitor getMonitor(int id) {
        try {
            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getMonitor"), id);
            return row != null ? buildMonitor(row) : null;
        } catch (Exception e) {
            log.error("Failed to get monitor {}", id, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Lists monitors, optionally filtered, ordered by name.
     *
     * @param scopeType   if non-null, restrict to monitors with this scope type
     * @param monitorType if non-null, restrict to monitors with this rule type
     * @param enabled     if non-null, restrict to monitors with this enabled state
     * @param scopeId     if non-null, restrict to monitors whose scope_id matches
     *                    exactly (a CHANNEL/GROUP scope look-up for a specific
     *                    channel or group already chosen by the caller)
     * @return matching monitors ordered by name; never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<Monitor> listMonitors(ScopeType scopeType, MonitorType monitorType, Boolean enabled, String scopeId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("scopeType", scopeType != null ? scopeType.name() : null);
            params.put("monitorType", monitorType != null ? monitorType.name() : null);
            params.put("enabled", enabled);
            params.put("scopeId", scopeId);

            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listMonitors"), params);

            List<Monitor> monitors = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                monitors.add(buildMonitor(row));
            }
            return monitors;
        } catch (Exception e) {
            log.error("Failed to list monitors (scopeType={}, monitorType={}, enabled={}, scopeId={})",
                    scopeType, monitorType, enabled, scopeId, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Inserts a new monitor.
     *
     * @param monitor the monitor to create; its {@code id} field is ignored
     *                (the database assigns it)
     * @return the same {@code monitor} instance passed in, with {@code id}
     *         populated from the generated key
     * @throws RepositoryException on persistence failure including a
     *                             duplicate-name violation
     */
    public static Monitor insertMonitor(Monitor monitor) {
        try {
            Map<String, Object> params = toColumnMap(monitor);
            // useGeneratedKeys writes the assigned id back into params["id"]
            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertMonitor"), params);
            monitor.setId(toInteger(params.get("id")));
            return monitor;
        } catch (Exception e) {
            log.error("Failed to insert monitor '{}'", monitor.getName(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Updates every column of an existing monitor by id.
     *
     * @param monitor the new state for the monitor; {@code id} identifies the
     *                row to update
     * @throws RepositoryException on persistence failure
     */
    public static void updateMonitor(Monitor monitor) {
        try {
            Map<String, Object> params = toColumnMap(monitor);
            params.put("id", monitor.getId());
            SqlConfig.getInstance().getSqlSessionManager().update(stmt("updateMonitor"), params);
        } catch (Exception e) {
            log.error("Failed to update monitor {}", monitor.getId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Deletes a monitor by id.
     *
     * @param id database id of the monitor to delete
     * @throws RepositoryException on persistence failure
     */
    public static void deleteMonitor(int id) {
        try {
            SqlConfig.getInstance().getSqlSessionManager().delete(stmt("deleteMonitor"), id);
        } catch (Exception e) {
            log.error("Failed to delete monitor {}", id, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Flips a monitor's enabled flag without touching any other column.
     *
     * @param id      database id of the monitor to update
     * @param enabled the new enabled state
     * @throws RepositoryException on persistence failure
     */
    public static void setMonitorEnabled(int id, boolean enabled) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", id);
            params.put("enabled", enabled);
            SqlConfig.getInstance().getSqlSessionManager().update(stmt("setMonitorEnabled"), params);
        } catch (Exception e) {
            log.error("Failed to set enabled={} for monitor {}", enabled, id, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO Conversion ==========

    /**
     * Builds the column map for {@code insertMonitor}/{@code updateMonitor}
     * (every {@code sentinel_monitor} column except {@code id}), keyed by
     * column name to match the mapped statements' {@code #{column_name}}
     * bind variables.
     */
    private static Map<String, Object> toColumnMap(Monitor monitor) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", monitor.getName());
        params.put("description", monitor.getDescription());
        params.put("monitor_type", monitor.getMonitorType() != null ? monitor.getMonitorType().name() : null);
        params.put("scope_type", monitor.getScopeType() != null ? monitor.getScopeType().name() : null);
        params.put("scope_id", monitor.getScopeId());
        params.put("enabled", monitor.isEnabled());
        params.put("severity", monitor.getSeverity() != null ? monitor.getSeverity().name() : null);
        params.put("config_json", monitor.getConfigJson());
        params.put("min_consecutive_breaches", monitor.getMinConsecutiveBreaches());
        params.put("suppressed_by_monitor_id", monitor.getSuppressedByMonitorId());
        params.put("runbook_url", monitor.getRunbookUrl());
        params.put("created_by", monitor.getCreatedBy());
        params.put("created_time", toTimestamp(monitor.getCreatedTime()));
        params.put("updated_by", monitor.getUpdatedBy());
        params.put("updated_time", toTimestamp(monitor.getUpdatedTime()));
        return params;
    }

    private static Monitor buildMonitor(Map<String, Object> row) {
        Monitor monitor = new Monitor();
        monitor.setId(toInteger(row.get("id")));
        monitor.setName((String) row.get("name"));
        monitor.setDescription((String) row.get("description"));

        Object monitorType = row.get("monitor_type");
        monitor.setMonitorType(monitorType != null ? MonitorType.valueOf((String) monitorType) : null);

        Object scopeType = row.get("scope_type");
        monitor.setScopeType(scopeType != null ? ScopeType.valueOf((String) scopeType) : null);

        monitor.setScopeId((String) row.get("scope_id"));
        monitor.setEnabled(toBoolean(row.get("enabled")));

        Object severity = row.get("severity");
        monitor.setSeverity(severity != null ? Severity.valueOf((String) severity) : null);

        monitor.setConfigJson((String) row.get("config_json"));

        Integer minConsecutiveBreaches = toInteger(row.get("min_consecutive_breaches"));
        monitor.setMinConsecutiveBreaches(minConsecutiveBreaches != null ? minConsecutiveBreaches : 0);

        monitor.setSuppressedByMonitorId(toInteger(row.get("suppressed_by_monitor_id")));
        monitor.setRunbookUrl((String) row.get("runbook_url"));
        monitor.setCreatedBy(toInteger(row.get("created_by")));
        monitor.setCreatedTime(toInstant(row.get("created_time")));
        monitor.setUpdatedBy(toInteger(row.get("updated_by")));
        monitor.setUpdatedTime(toInstant(row.get("updated_time")));
        return monitor;
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
