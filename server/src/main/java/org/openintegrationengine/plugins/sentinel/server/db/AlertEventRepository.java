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

import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorHistoryPoint;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * Persistence for {@code sentinel_alert_event} — the "Problems" list backing
 * the raised/resolved/acknowledged lifecycle of every monitor breach.
 *
 * <p>Stateless: every method reaches {@link SqlConfig#getInstance()} directly,
 * so unlike {@code RbacRepository} this class carries no constructor-time
 * setup and needs no singleton lifecycle — plain static methods are
 * sufficient. Every statement here is a single MyBatis call (the paginated
 * {@link #listAlertEvents(AlertEventFilter)} runs two independent read-only
 * selects, not a write sequence that needs a shared transaction), so
 * auto-commit is fine throughout; no method opens a manual-commit {@code
 * openSession(false)} session.</p>
 */
public final class AlertEventRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(AlertEventRepository.class);

    /**
     * The character the free-text search uses to escape LIKE wildcards.
     *
     * <p>Must stay in lock-step with the literal {@code ESCAPE '!'} clause in
     * all five {@code *-sqlmap.xml} files' {@code listAlertEvents} and {@code
     * countAlertEvents} statements — the Java side escapes and the SQL side
     * declares, and they only work as a pair.</p>
     *
     * <p>{@code !} rather than the more conventional backslash on purpose.
     * Backslash is already LIKE's implicit escape character on PostgreSQL and
     * MySQL and is additionally a string-literal escape in MySQL, so writing
     * {@code ESCAPE '\'} means something subtly different on each of the five
     * and has to be doubled in some of them. {@code !} has no special meaning
     * to any of them, in either role.</p>
     */
    private static final char LIKE_ESCAPE_CHAR = '!';

    private AlertEventRepository() {
    }

    /** Qualifies a mapped-statement id with the plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Inserts a new alert event.
     *
     * @param event the event to create; its {@code id} field is ignored (the
     *              database assigns it)
     * @return the same {@code event} instance passed in, with {@code id}
     *         populated from the generated key
     * @throws RepositoryException on persistence failure
     */
    public static AlertEvent insertAlertEvent(AlertEvent event) {
        try {
            Map<String, Object> params = toColumnMap(event);
            // useGeneratedKeys writes the assigned id back into params["id"]
            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertAlertEvent"), params);
            event.setId(toLong(params.get("id")));
            return event;
        } catch (Exception e) {
            log.error("Failed to insert alert event for monitor {}, channel {}",
                    event.getMonitorId(), event.getChannelId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Updates an existing alert event's mutable lifecycle fields by id:
     * {@code status}, {@code resolvedTime}, {@code acknowledgedBy}, {@code
     * acknowledgedTime}, {@code ackComment}, and {@code detailsJson}.
     * {@code status} is always applied; the rest are pass-through — the
     * mapped statement's {@code <if>} blocks leave a column untouched when
     * the corresponding field on {@code event} is {@code null}, so this
     * method never needs to know which fields the caller actually intends to
     * change.
     *
     * @param event the new state for the event; {@code id} identifies the
     *              row to update
     * @throws RepositoryException on persistence failure
     */
    public static void updateAlertEvent(AlertEvent event) {
        try {
            Map<String, Object> params = toUpdateParams(event);
            SqlConfig.getInstance().getSqlSessionManager().update(stmt("updateAlertEvent"), params);
        } catch (Exception e) {
            log.error("Failed to update alert event {}", event.getId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Fetches one alert event by its database id.
     *
     * @param id database id to look up
     * @return the event, or {@code null} if no row exists
     * @throws RepositoryException on persistence failure
     */
    public static AlertEvent getAlertEvent(long id) {
        try {
            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getAlertEvent"), id);
            return row != null ? buildAlertEvent(row) : null;
        } catch (Exception e) {
            log.error("Failed to get alert event {}", id, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Lists alert events matching {@code filter}, sorted and paginated, plus
     * the total row count across every page. Runs the mapped {@code
     * listAlertEvents} and {@code countAlertEvents} statements as two
     * independent reads against the same filter — the count is not
     * transactionally consistent with the list under concurrent writes, but
     * a page-control total does not need to be.
     *
     * <p>{@code filter.getSortColumn()}/{@code filter.getSortDir()} are
     * re-validated here (in addition to the allow-list already enforced by
     * {@link AlertEventFilter#setSortColumn(String)}/{@link
     * AlertEventFilter#setSortDir(String)}) immediately before they enter the
     * parameter map that feeds the mapped statement's raw {@code
     * ${sortColumn} ${sortDir}} substitution — belt-and-suspenders at the one
     * point in this class where an unvalidated string would become a SQL
     * injection hole.</p>
     *
     * @param filter the filter, sort, and page selection to apply
     * @return the requested page of events plus the total matching row count
     * @throws RepositoryException on persistence failure
     */
    public static PagedResult<AlertEvent> listAlertEvents(AlertEventFilter filter) {
        try {
            int offset = filter.getPage() * filter.getPageSize();

            Map<String, Object> listParams = toFilterParams(filter);
            listParams.put("sortColumn", AlertEventFilter.validateSortColumn(filter.getSortColumn()));
            listParams.put("sortDir", AlertEventFilter.validateSortDir(filter.getSortDir()));
            listParams.put("offset", offset);
            listParams.put("limit", filter.getPageSize());

            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listAlertEvents"), listParams);

            List<AlertEvent> items = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                items.add(buildAlertEvent(row));
            }

            Map<String, Object> countParams = toFilterParams(filter);
            Integer total = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("countAlertEvents"), countParams);

            return new PagedResult<>(items, total != null ? total : 0L, filter.getPage(), filter.getPageSize());
        } catch (Exception e) {
            log.error("Failed to list alert events (page={}, pageSize={})", filter.getPage(), filter.getPageSize(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Lists the most recently opened alert events, most recent first. Used
     * by dashboard/summary views that want a fixed-size recent feed without
     * the full filter/pagination machinery of {@link
     * #listAlertEvents(AlertEventFilter)}.
     *
     * @param limit maximum number of events to return
     * @return up to {@code limit} events ordered by {@code opened_time}
     *         descending; never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<AlertEvent> listRecentAlertEvents(int limit) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("limit", limit);

            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listRecentAlertEvents"), params);

            List<AlertEvent> events = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                events.add(buildAlertEvent(row));
            }
            return events;
        } catch (Exception e) {
            log.error("Failed to list recent alert events (limit={})", limit, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Aggregates one monitor's alert history into per-day points: how many
     * alerts it opened each day, and their mean time to resolve.
     *
     * <p>The aggregation happens in SQL, not in Java. A monitor that has been
     * running for a while accumulates far more events than a chart has pixels —
     * the resolved-alert retention default alone keeps 180 days — and pulling
     * them all back to count them in a loop would make the cost of the chart
     * scale with history rather than with the range asked for.</p>
     *
     * <p>The day bucketing is the one part of this that is genuinely different
     * on every vendor, which is why it lives in the five mapper files rather
     * than here: PostgreSQL has {@code date_trunc('day', ...)}, Oracle
     * {@code TRUNC(...)}, SQL Server {@code CONVERT(date, ...)}, and MySQL and
     * Derby {@code DATE(...)}. The elapsed-seconds arithmetic diverges the same
     * way — {@code EXTRACT(EPOCH FROM ...)}, a {@code DATE} subtraction scaled
     * by 86400, {@code DATEDIFF}, {@code TIMESTAMPDIFF}, and the JDBC escape
     * {@code TIMESTAMPDIFF} respectively — and the integer-division trap in
     * {@code AVG} over an integer expression has to be defused per vendor too.
     * All five agree only on the three column labels this method reads back.</p>
     *
     * <p>Days with no alerts produce no row; see {@link MonitorHistoryPoint}
     * for why, and for what a caller wanting a continuous series must do about
     * it.</p>
     *
     * @param monitorId the monitor whose history to summarize
     * @param from      inclusive lower bound on {@code opened_time}
     * @param to        inclusive upper bound on {@code opened_time}
     * @return one point per day that had at least one alert, oldest first;
     *         never {@code null}
     * @throws RepositoryException on persistence failure
     */
    public static List<MonitorHistoryPoint> listMonitorHistory(int monitorId, Instant from, Instant to) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("monitorId", monitorId);
            params.put("from", toTimestamp(from));
            params.put("to", toTimestamp(to));

            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listMonitorHistory"), params);

            List<MonitorHistoryPoint> points = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                points.add(buildMonitorHistoryPoint(row));
            }
            return points;
        } catch (Exception e) {
            log.error("Failed to list monitor history (monitorId={}, from={}, to={})", monitorId, from, to, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Deletes resolved alert events older than a cutoff. Used by the
     * retention job to keep the event table bounded; open ({@code PROBLEM})
     * events are never touched regardless of age.
     *
     * @param cutoff resolved events with {@code resolved_time} before this
     *               instant are removed
     * @return the number of rows deleted
     * @throws RepositoryException on persistence failure
     */
    public static int deleteResolvedAlertEventsOlderThan(Instant cutoff) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("cutoff", toTimestamp(cutoff));
            return SqlConfig.getInstance().getSqlSessionManager()
                    .delete(stmt("deleteResolvedAlertEventsOlderThan"), params);
        } catch (Exception e) {
            log.error("Failed to delete resolved alert events older than {}", cutoff, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO Conversion ==========

    /**
     * Builds the column map for {@code insertAlertEvent} (every {@code
     * sentinel_alert_event} column except {@code id}), keyed by column name
     * to match the mapped statement's {@code #{column_name}} bind variables.
     */
    private static Map<String, Object> toColumnMap(AlertEvent event) {
        Map<String, Object> params = new HashMap<>();
        params.put("monitor_id", event.getMonitorId());
        params.put("channel_id", event.getChannelId());
        params.put("metadata_id", event.getMetadataId());
        params.put("severity", event.getSeverity() != null ? event.getSeverity().name() : null);
        params.put("status", event.getStatus() != null ? event.getStatus().name() : null);
        params.put("message", event.getMessage());
        params.put("opened_time", toTimestamp(event.getOpenedTime()));
        params.put("resolved_time", toTimestamp(event.getResolvedTime()));
        params.put("acknowledged_by", event.getAcknowledgedBy());
        params.put("acknowledged_time", toTimestamp(event.getAcknowledgedTime()));
        params.put("ack_comment", event.getAckComment());
        params.put("details_json", event.getDetailsJson());
        params.put("suppressed", event.isSuppressed());
        return params;
    }

    /**
     * Builds the parameter map for {@code updateAlertEvent}: {@code id} plus
     * the handful of columns that statement is allowed to touch. Fields left
     * {@code null} on {@code event} still enter the map (with a null value)
     * so the mapped statement's {@code <if test="... != null">} guards can
     * decide, per column, whether to leave the existing value in place.
     */
    private static Map<String, Object> toUpdateParams(AlertEvent event) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", event.getId());
        params.put("status", event.getStatus() != null ? event.getStatus().name() : null);
        params.put("resolved_time", toTimestamp(event.getResolvedTime()));
        params.put("acknowledged_by", event.getAcknowledgedBy());
        params.put("acknowledged_time", toTimestamp(event.getAcknowledgedTime()));
        params.put("ack_comment", event.getAckComment());
        params.put("details_json", event.getDetailsJson());
        return params;
    }

    /**
     * Builds the shared {@code WHERE}-clause parameter map used by both
     * {@code listAlertEvents} and {@code countAlertEvents} — the two mapped
     * statements duplicate the same filter block, so both need an identical
     * parameter map (minus the list-only sort/page keys {@link
     * #listAlertEvents(AlertEventFilter)} adds on top of this).
     */
    private static Map<String, Object> toFilterParams(AlertEventFilter filter) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", filter.getStatus() != null ? filter.getStatus().name() : null);

        List<Severity> severityIn = filter.getSeverityIn();
        if (severityIn != null) {
            List<String> severityNames = new ArrayList<>();
            for (Severity severity : severityIn) {
                severityNames.add(severity.name());
            }
            params.put("severityIn", severityNames);
        } else {
            params.put("severityIn", null);
        }

        params.put("channelIdIn", filter.getChannelIdIn());
        params.put("monitorId", filter.getMonitorId());
        params.put("monitorType", filter.getMonitorType() != null ? filter.getMonitorType().name() : null);
        params.put("acknowledged", filter.getAcknowledged());
        params.put("from", toTimestamp(filter.getFrom()));
        params.put("to", toTimestamp(filter.getTo()));
        params.put("q", escapeLikeWildcards(filter.getQ()));
        return params;
    }

    /**
     * Neutralizes the LIKE metacharacters in a free-text search term so it
     * matches literally.
     *
     * <p>{@code q} is bound as a parameter and was never a SQL injection risk,
     * but binding is not the same as escaping: the value still lands inside a
     * LIKE pattern, where {@code %} and {@code _} keep their wildcard meaning.
     * Searching for {@code 50%} therefore matched every message containing
     * {@code 50}, and a search for {@code a_b} matched {@code axb} — quietly
     * wrong answers rather than errors, which is why it went unnoticed. Each
     * metacharacter is prefixed with {@link #LIKE_ESCAPE_CHAR}, as is the
     * escape character itself so that a literal {@code !} in the search term
     * survives.</p>
     *
     * <p>Escaping is done here rather than in {@link AlertEventFilter} so that
     * the filter keeps holding exactly what the user typed: it is echoed back
     * to the UI and reused to build subsequent requests, and a stored value
     * carrying escape characters would accumulate them on every round trip.
     * This is the last point before the value reaches SQL, and both statements
     * that consume it are fed from this one map.</p>
     *
     * <p>One known gap, deliberately left: SQL Server also treats {@code [} as
     * a wildcard, opening a character class. It is not escaped because Derby
     * and Oracle reject an escape character followed by anything other than
     * {@code %}, {@code _}, or itself, so escaping it would turn a
     * wrong-results bug on one vendor into a hard error on two. A search term
     * containing {@code [} may over-match on SQL Server.</p>
     *
     * @param q the raw search term, or {@code null}
     * @return the term with LIKE metacharacters escaped, or {@code null} if
     *         {@code q} was null (which the mapped statements read as "no
     *         free-text filter")
     */
    private static String escapeLikeWildcards(String q) {
        if (q == null || q.isEmpty()) {
            return q;
        }
        StringBuilder escaped = new StringBuilder(q.length() + 8);
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == LIKE_ESCAPE_CHAR || c == '%' || c == '_') {
                escaped.append(LIKE_ESCAPE_CHAR);
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static AlertEvent buildAlertEvent(Map<String, Object> row) {
        AlertEvent event = new AlertEvent();
        event.setId(toLong(row.get("id")));

        Integer monitorId = toInteger(row.get("monitor_id"));
        event.setMonitorId(monitorId != null ? monitorId : 0);

        event.setChannelId((String) row.get("channel_id"));
        event.setMetadataId(toInteger(row.get("metadata_id")));

        Object severity = row.get("severity");
        event.setSeverity(severity != null ? Severity.valueOf(severity.toString()) : null);

        Object status = row.get("status");
        event.setStatus(status != null ? AlertStatus.valueOf(status.toString()) : null);

        event.setMessage((String) row.get("message"));
        event.setOpenedTime(toInstant(row.get("opened_time")));
        event.setResolvedTime(toInstant(row.get("resolved_time")));
        event.setAcknowledgedBy(toInteger(row.get("acknowledged_by")));
        event.setAcknowledgedTime(toInstant(row.get("acknowledged_time")));
        event.setAckComment((String) row.get("ack_comment"));
        event.setDetailsJson((String) row.get("details_json"));
        event.setSuppressed(toBoolean(row.get("suppressed")));

        return event;
    }

    private static MonitorHistoryPoint buildMonitorHistoryPoint(Map<String, Object> row) {
        MonitorHistoryPoint point = new MonitorHistoryPoint();
        point.setBucket(toBucketInstant(row.get("bucket")));

        Long alertCount = toLong(row.get("alert_count"));
        point.setAlertCount(alertCount != null ? alertCount : 0L);

        point.setAvgResolveSeconds(toDouble(row.get("avg_resolve_seconds")));
        return point;
    }

    /**
     * Converts the day bucket a vendor's date-truncation function produced to
     * the {@link Instant} at the start of that day.
     *
     * <p>The five vendors hand this column back as three different Java types.
     * The ones whose truncation yields a true {@code DATE} (Derby, MySQL, SQL
     * Server) give a {@link java.sql.Date}; PostgreSQL's {@code date_trunc}
     * yields a timestamp and Oracle's {@code TRUNC} an Oracle {@code DATE},
     * both of which arrive as {@link Timestamp}. {@code java.time} values are
     * accepted too, since a driver upgrade can start returning them. Anything
     * else is a mapper/driver mismatch worth failing loudly on rather than
     * silently charting as {@code null}.</p>
     *
     * @param value the raw bucket value from a result row
     * @return the instant starting that day in the JVM's default zone, or
     *         {@code null} if the value is null
     */
    private static Instant toBucketInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof java.time.LocalDate localDate) {
            return localDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        throw new IllegalStateException("Unexpected history bucket type " + value.getClass().getName()
                + "; the listMonitorHistory mapper for this database returned a column shape this class cannot read");
    }

    /**
     * Coerces an aggregate average to a {@link Double}. {@code AVG} comes back
     * as a {@code Double} from Derby/PostgreSQL/SQL Server but as a {@link
     * java.math.BigDecimal} from MySQL and Oracle, so a direct {@code (Double)}
     * cast throws {@link ClassCastException} on two of the five.
     *
     * @param value the raw value from a result row
     * @return the value as a {@code Double}, or {@code null} if it is null —
     *         which for an average over an all-null input is the normal result,
     *         not an error
     */
    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.valueOf(value.toString().trim());
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
     * Coerces a MyBatis-returned key or id to a {@link Long}. Most drivers
     * return a {@code Long} for a {@code BIGINT}/{@code BIGSERIAL} column,
     * but Derby hands back identity/generated keys as a {@link
     * java.math.BigDecimal}, so a direct {@code (Long)} cast throws {@link
     * ClassCastException}. Handles any {@link Number}.
     *
     * @param value the raw value from a params map or result row
     * @return the value as a {@code Long}, or {@code null} if it is null
     */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.valueOf(value.toString().trim());
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
