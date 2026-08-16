/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Query parameters for {@code AlertEventRepository.listAlertEvents}/
 * {@code countAlertEvents}: the "Problems" list's filter bar plus its sort
 * and page selection.
 *
 * <p>{@link #getSortColumn()} and {@link #getSortDir()} end up substituted
 * raw (unparameterized {@code ${}}, not a bound {@code #{}} variable) into
 * the mapped statement's {@code ORDER BY} clause, because MyBatis cannot bind
 * a column name or sort direction as a regular parameter. That makes this
 * class's validation the one real security boundary in this DTO: {@link
 * #setSortColumn(String)}/{@link #setSortDir(String)} run every assigned
 * value through {@link #validateSortColumn(String)}/{@link
 * #validateSortDir(String)} so a field on this object can never hold
 * anything other than an allow-listed value, however it was constructed.</p>
 *
 * <h2>The two list setters copy, and must keep copying</h2>
 *
 * <p>{@link #setSeverityIn(List)} and {@link #setChannelIdIn(List)} store an
 * {@link ArrayList} copy rather than the caller's list. That looks like
 * pointless defensiveness and is not: it is load-bearing, and removing it
 * silently breaks features rather than failing a build.</p>
 *
 * <p>Both fields are read by the mappers through OGNL guards of the form
 * {@code <if test="channelIdIn != null and !channelIdIn.isEmpty()">}. OGNL
 * resolves {@code isEmpty()} reflectively <em>against the concrete class</em>,
 * not the {@link List} interface. {@code List.of(...)} returns
 * {@code java.util.ImmutableCollections$List12}, which lives in {@code
 * java.base} and is not exported, so under the module system the reflective
 * call fails:</p>
 *
 * <pre>
 * Method "isEmpty" failed for object [...]
 *   IllegalAccessException: OgnlRuntime cannot access a member of class
 *   java.util.ImmutableCollections$List12 (in module java.base)
 * </pre>
 *
 * <p>which MyBatis wraps and the repository turns into a failed query. It had
 * exactly that effect twice: {@code AlertStormControl} passed
 * {@code List.of(channelId)} and so <b>flap detection never ran</b> — every
 * dispatch logged "Flap check failed; notifying normally", which is the
 * behaviour flap detection exists to prevent — and {@code MetricsService}
 * passed {@code List.of(severity)}, breaking the Prometheus endpoint's
 * per-severity counts. Neither was caught by a test, because tests mock the
 * repository and never reach OGNL.</p>
 *
 * <p>Copying here fixes every caller at once, including future ones, and is the
 * right layer for it: the constraint belongs to how this DTO is consumed, not
 * to any one call site's choice of list literal.</p>
 */
public class AlertEventFilter {

    /**
     * The only column names {@link #setSortColumn(String)} accepts — every
     * other value falls back to {@code "opened_time"}. Must stay in sync
     * with the {@code sortColumn} handling documented on {@code
     * listAlertEvents}/{@code countAlertEvents} in every vendor mapper.
     */
    public static final Set<String> ALLOWED_SORT_COLUMNS = Set.of("opened_time", "severity", "channel_id");

    private AlertStatus status;
    private List<Severity> severityIn;
    private List<String> channelIdIn;
    private Integer monitorId;
    private MonitorType monitorType;
    private Boolean acknowledged;
    private Instant from;
    private Instant to;
    private String q;
    private String sortColumn = "opened_time";
    private String sortDir = "DESC";
    private int page;
    private int pageSize;

    public AlertEventFilter() {
    }

    /**
     * @return if non-null, restrict to events with this status
     */
    public AlertStatus getStatus() {
        return status;
    }

    /**
     * @param status if non-null, restrict to events with this status
     */
    public void setStatus(AlertStatus status) {
        this.status = status;
    }

    /**
     * @return if non-null and non-empty, restrict to events whose severity is
     *         one of these
     */
    public List<Severity> getSeverityIn() {
        return severityIn;
    }

    /**
     * Stores an {@link ArrayList} copy — see the class Javadoc. A
     * {@code List.of(...)} argument would otherwise reach the mapper's OGNL
     * guard as a non-exported JDK class and fail the query at runtime.
     *
     * @param severityIn if non-null and non-empty, restrict to events whose
     *                   severity is one of these
     */
    public void setSeverityIn(List<Severity> severityIn) {
        this.severityIn = severityIn == null ? null : new ArrayList<>(severityIn);
    }

    /**
     * @return if non-null and non-empty, restrict to events whose channel id
     *         is one of these
     */
    public List<String> getChannelIdIn() {
        return channelIdIn;
    }

    /**
     * Stores an {@link ArrayList} copy — see the class Javadoc. This is the
     * setter whose missing copy left flap detection silently disabled.
     *
     * @param channelIdIn if non-null and non-empty, restrict to events whose
     *                    channel id is one of these
     */
    public void setChannelIdIn(List<String> channelIdIn) {
        this.channelIdIn = channelIdIn == null ? null : new ArrayList<>(channelIdIn);
    }

    /**
     * @return if non-null, restrict to events raised by this monitor
     */
    public Integer getMonitorId() {
        return monitorId;
    }

    /**
     * @param monitorId if non-null, restrict to events raised by this
     *                  monitor
     */
    public void setMonitorId(Integer monitorId) {
        this.monitorId = monitorId;
    }

    /**
     * @return if non-null, restrict to events raised by a monitor of this
     *         type
     */
    public MonitorType getMonitorType() {
        return monitorType;
    }

    /**
     * @param monitorType if non-null, restrict to events raised by a monitor
     *                    of this type
     */
    public void setMonitorType(MonitorType monitorType) {
        this.monitorType = monitorType;
    }

    /**
     * @return if non-null, restrict by acknowledgment state: {@code true}
     *         means {@code acknowledged_by IS NOT NULL}, {@code false} means
     *         {@code acknowledged_by IS NULL}
     */
    public Boolean getAcknowledged() {
        return acknowledged;
    }

    /**
     * @param acknowledged if non-null, restrict by acknowledgment state
     */
    public void setAcknowledged(Boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    /**
     * @return if non-null, inclusive lower bound on {@code opened_time}
     */
    public Instant getFrom() {
        return from;
    }

    /**
     * @param from if non-null, inclusive lower bound on {@code opened_time}
     */
    public void setFrom(Instant from) {
        this.from = from;
    }

    /**
     * @return if non-null, inclusive upper bound on {@code opened_time}
     */
    public Instant getTo() {
        return to;
    }

    /**
     * @param to if non-null, inclusive upper bound on {@code opened_time}
     */
    public void setTo(Instant to) {
        this.to = to;
    }

    /**
     * @return if non-null, restrict to events whose message contains this
     *         text (matched case-insensitively)
     */
    public String getQ() {
        return q;
    }

    /**
     * @param q if non-null, restrict to events whose message contains this
     *          text (matched case-insensitively)
     */
    public void setQ(String q) {
        this.q = q;
    }

    /**
     * @return the column to sort by; always one of {@link
     *         #ALLOWED_SORT_COLUMNS}, defaulting to {@code "opened_time"}
     */
    public String getSortColumn() {
        return sortColumn;
    }

    /**
     * @param sortColumn the column to sort by; run through {@link
     *                    #validateSortColumn(String)} before being stored, so
     *                    an unrecognized value silently falls back to
     *                    {@code "opened_time"} rather than being rejected
     */
    public void setSortColumn(String sortColumn) {
        this.sortColumn = validateSortColumn(sortColumn);
    }

    /**
     * @return the sort direction; always exactly {@code "ASC"} or
     *         {@code "DESC"}, defaulting to {@code "DESC"}
     */
    public String getSortDir() {
        return sortDir;
    }

    /**
     * @param sortDir the sort direction; run through {@link
     *                #validateSortDir(String)} before being stored, so an
     *                unrecognized value silently falls back to
     *                {@code "DESC"} rather than being rejected
     */
    public void setSortDir(String sortDir) {
        this.sortDir = validateSortDir(sortDir);
    }

    /**
     * @return the zero-based page number to return
     */
    public int getPage() {
        return page;
    }

    /**
     * @param page the zero-based page number to return
     */
    public void setPage(int page) {
        this.page = page;
    }

    /**
     * @return the number of rows per page
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * @param pageSize the number of rows per page
     */
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * Allow-lists a candidate {@code ORDER BY} column. This is the guard the
     * mapped statements' raw {@code ${sortColumn}} substitution relies on —
     * every caller that ends up feeding a value into that substitution
     * (directly or via {@link #setSortColumn(String)}) must go through this
     * method first.
     *
     * @param sortColumn the candidate column name, e.g. from a REST query
     *                    parameter; may be {@code null} or arbitrary
     *                    untrusted input
     * @return {@code sortColumn} unchanged if it is one of {@link
     *         #ALLOWED_SORT_COLUMNS}, otherwise {@code "opened_time"}
     */
    public static String validateSortColumn(String sortColumn) {
        return sortColumn != null && ALLOWED_SORT_COLUMNS.contains(sortColumn) ? sortColumn : "opened_time";
    }

    /**
     * Allow-lists a candidate {@code ORDER BY} direction. Same role as {@link
     * #validateSortColumn(String)} for the {@code ${sortDir}} substitution.
     *
     * @param sortDir the candidate direction, e.g. from a REST query
     *                parameter; may be {@code null} or arbitrary untrusted
     *                input
     * @return {@code "ASC"} if {@code sortDir} is exactly {@code "ASC"},
     *         otherwise {@code "DESC"}
     */
    public static String validateSortDir(String sortDir) {
        return "ASC".equals(sortDir) ? "ASC" : "DESC";
    }
}
