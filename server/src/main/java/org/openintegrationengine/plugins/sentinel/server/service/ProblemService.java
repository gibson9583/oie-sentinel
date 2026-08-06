/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.controllers.ChannelController;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEventFilter;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.ProblemDetail;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;

/**
 * Business rules for the Problems surface: filtered listing, single-problem
 * detail with resolved display context, and the acknowledge / resolve
 * workflow.
 *
 * <p><b>Acknowledge vs resolve semantics:</b> acknowledging records "a human
 * has eyes on this" without changing the problem's lifecycle — the evaluator
 * still owns resolution. Manual resolve is the human override for conditions
 * the evaluator cannot clear itself (misconfigured monitor, decommissioned
 * feed), and therefore must also reset the owning trigger state to OK — if
 * it didn't, a trigger still sitting in PROBLEM would treat the very next
 * breach tick as "still the same problem" and silently never open a new
 * alert, or conversely re-open one instantly without honoring the monitor's
 * hysteresis from scratch.</p>
 *
 * <p>Errors follow the plugin-wide convention: {@link IllegalArgumentException}
 * → 400, {@link NoSuchElementException} → 404 (mapped in the servlet).</p>
 */
public final class ProblemService {

    private static final Logger log = LoggerFactory.getLogger(ProblemService.class);

    /** Placeholder monitor name for events whose monitor no longer exists. */
    private static final String DELETED_MONITOR = "(deleted monitor)";

    /**
     * Hard ceiling on {@code pageSize}. The grid never asks for more than a
     * few hundred rows; anything larger is a client bug or abuse, and the cap
     * keeps a single request from materializing the whole table.
     */
    private static final int MAX_PAGE_SIZE = 1000;

    /**
     * Hard ceiling on {@code page}, chosen so {@code page * pageSize} can
     * never overflow the int offset computed in the repository — an
     * unbounded {@code ?page=} would otherwise wrap to a negative OFFSET the
     * database rejects, turning a silly query parameter into an HTTP 500
     * instead of an empty page.
     */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    /**
     * Maximum acknowledge/resolve comment length — the {@code ack_comment}
     * column is VARCHAR(1024) on every vendor, and an unvalidated oversize
     * comment would fail the whole update with an opaque 500 instead of an
     * actionable 400 (mirrors ActionDispatcher's explicit bounding of
     * {@code error_message} for the sibling column).
     */
    private static final int MAX_COMMENT_LENGTH = 1024;

    private ProblemService() {
    }

    /**
     * Lists problems for a pre-built filter — the entry point for callers
     * (like the servlet's channel-restriction handling) that need to adjust
     * the filter after parsing but before querying.
     *
     * @param filter the query; its sort fields are already allow-listed by
     *               {@link AlertEventFilter}'s own setters
     * @return one page of events plus the total match count
     */
    public static PagedResult<AlertEvent> list(AlertEventFilter filter) {
        return AlertEventRepository.listAlertEvents(filter);
    }

    /**
     * Parses raw servlet parameters and lists problems in one step — the
     * common path when no filter post-processing is needed.
     *
     * @see #buildFilter for parameter semantics
     */
    public static PagedResult<AlertEvent> list(String status, String severityCsv, String channelIdCsv,
            Integer monitorId, String monitorTypeName, Boolean acknowledged, Long fromEpochMillis,
            Long toEpochMillis, String q, String sortColumn, String sortDir, int page, int pageSize) {
        return list(buildFilter(status, severityCsv, channelIdCsv, monitorId, monitorTypeName,
                acknowledged, fromEpochMillis, toEpochMillis, q, sortColumn, sortDir, page, pageSize));
    }

    /**
     * Translates the raw {@code GET /problems} query parameters into an
     * {@link AlertEventFilter}: CSV values are split, enum names parsed
     * strictly (an unknown severity is a client error worth a 400, not a
     * silently empty filter), epoch millis become {@link Instant}s, and the
     * sort fields pass through the filter's own allow-list setters.
     *
     * @param status         AlertStatus name or null
     * @param severityCsv    CSV of Severity names or null
     * @param channelIdCsv   CSV of channel ids or null
     * @param monitorId      raising monitor id or null
     * @param monitorTypeName MonitorType name or null
     * @param acknowledged   acknowledged-state filter or null
     * @param fromEpochMillis inclusive lower bound on openedTime, epoch ms, or null
     * @param toEpochMillis  inclusive upper bound on openedTime, epoch ms, or null
     * @param q              free-text message search or null
     * @param sortColumn     requested sort column (allow-listed downstream)
     * @param sortDir        requested sort direction (allow-listed downstream)
     * @param page           zero-based page (clamped into [0, {@link #MAX_PAGE}])
     * @param pageSize       rows per page (clamped to [1, {@value #MAX_PAGE_SIZE}],
     *                       defaulting to 25 when non-positive)
     * @return the populated filter
     * @throws IllegalArgumentException on an unrecognized enum name
     */
    public static AlertEventFilter buildFilter(String status, String severityCsv, String channelIdCsv,
            Integer monitorId, String monitorTypeName, Boolean acknowledged, Long fromEpochMillis,
            Long toEpochMillis, String q, String sortColumn, String sortDir, int page, int pageSize) {
        AlertEventFilter filter = new AlertEventFilter();

        if (!isBlank(status)) {
            filter.setStatus(parseEnum(AlertStatus.class, status, "status"));
        }
        List<String> severityNames = splitCsv(severityCsv);
        if (severityNames != null) {
            List<Severity> severities = new ArrayList<>();
            for (String name : severityNames) {
                severities.add(parseEnum(Severity.class, name, "severity"));
            }
            filter.setSeverityIn(severities);
        }
        filter.setChannelIdIn(splitCsv(channelIdCsv));
        filter.setMonitorId(monitorId);
        if (!isBlank(monitorTypeName)) {
            filter.setMonitorType(parseEnum(MonitorType.class, monitorTypeName, "monitorType"));
        }
        filter.setAcknowledged(acknowledged);
        if (fromEpochMillis != null) {
            filter.setFrom(Instant.ofEpochMilli(fromEpochMillis));
        }
        if (toEpochMillis != null) {
            filter.setTo(Instant.ofEpochMilli(toEpochMillis));
        }
        filter.setQ(isBlank(q) ? null : q.trim());
        filter.setSortColumn(sortColumn); // setter allow-lists; bad input falls back
        filter.setSortDir(sortDir != null ? sortDir.toUpperCase(Locale.ROOT) : null);
        filter.setPage(Math.max(0, Math.min(page, MAX_PAGE))); // clamp both ends — see MAX_PAGE
        filter.setPageSize(pageSize <= 0 ? 25 : Math.min(pageSize, MAX_PAGE_SIZE));
        return filter;
    }

    /**
     * Fetches one problem with its display context resolved server-side —
     * monitor name/type, channel name, connector name, and dispatch history
     * — so the detail drawer renders from a single round trip instead of
     * fanning out four requests.
     *
     * @param id database id of the alert event
     * @return the assembled detail
     * @throws NoSuchElementException if no event has that id
     */
    public static ProblemDetail getDetail(long id) {
        AlertEvent event = requireEvent(id);

        ProblemDetail detail = new ProblemDetail();
        detail.setEvent(event);

        // The monitor FK cascades on delete (deleting a monitor deletes its
        // events), so a missing monitor is rare — but a row read mid-delete
        // must degrade to a placeholder, not a 500.
        Monitor monitor = MonitorRepository.getMonitor(event.getMonitorId());
        detail.setMonitorName(monitor != null ? monitor.getName() : DELETED_MONITOR);
        detail.setMonitorType(monitor != null ? monitor.getMonitorType() : null);

        detail.setChannelName(ScopeResolver.channelName(event.getChannelId()));
        detail.setConnectorName(resolveConnectorName(event));
        detail.setDispatches(ActionDispatchLogRepository.listActionDispatchLogsForEvent(id));
        return detail;
    }

    /**
     * Acknowledges an open problem: records who and when, without touching
     * the lifecycle. Strictly once — re-acknowledging is rejected rather
     * than silently overwritten, because "who took ownership first" is the
     * fact the NOC audit trail exists to preserve.
     *
     * @param id      database id of the alert event
     * @param comment optional operator note
     * @param userId  the acknowledging user
     * @return the updated event
     * @throws NoSuchElementException   if no event has that id
     * @throws IllegalArgumentException if the event is already acknowledged
     *                                  or already resolved, or the comment is
     *                                  too long
     */
    public static AlertEvent acknowledge(long id, String comment, int userId) {
        validateComment(comment);
        AlertEvent event = requireEvent(id);
        if (event.getAcknowledgedBy() != null) {
            throw new IllegalArgumentException("Problem " + id + " is already acknowledged");
        }
        if (event.getStatus() != AlertStatus.PROBLEM) {
            throw new IllegalArgumentException("Problem " + id + " is already resolved");
        }
        applyAck(event, comment, userId);
        AlertEventRepository.updateAlertEvent(event);
        SentinelAuditLog.problemAcknowledged(userId, event, comment);
        return event;
    }

    /**
     * Manually resolves a problem and resets its owning trigger state to OK
     * (see class Javadoc for why the trigger reset is mandatory). If the
     * problem was never acknowledged, the resolver's identity is recorded as
     * the acknowledgment too — a manual resolve is the strongest possible
     * form of "a human has seen this".
     *
     * <p>Note on the trigger reset: the update statement only writes
     * {@code open_alert_event_id} when non-null, so the stored id cannot be
     * NULLed here and goes stale instead. That is safe by design — every
     * consumer of the id (evaluator resolve/repeat paths) first checks the
     * referenced event's status and ignores anything not still PROBLEM, and
     * the next genuine transition overwrites it.</p>
     *
     * @param id      database id of the alert event
     * @param comment optional operator note
     * @param userId  the resolving user
     * @return the updated event
     * @throws NoSuchElementException   if no event has that id
     * @throws IllegalArgumentException if the event is already resolved or
     *                                  the comment is too long
     */
    public static AlertEvent resolve(long id, String comment, int userId) {
        validateComment(comment);
        AlertEvent event = requireEvent(id);
        if (event.getStatus() != AlertStatus.PROBLEM) {
            throw new IllegalArgumentException("Problem " + id + " is already resolved");
        }
        applyResolve(event, comment, userId);
        SentinelAuditLog.problemResolved(userId, event, comment);
        return event;
    }

    /**
     * Acknowledges a batch of problems, skipping (rather than failing on)
     * ids that are missing, already acknowledged, or already resolved — a
     * grid multi-select routinely races the evaluator and other operators,
     * and "acknowledge whatever is still open" is what the user means.
     *
     * @param ids     the alert-event ids to acknowledge
     * @param comment optional operator note applied to each
     * @param userId  the acknowledging user
     * @return how many events were newly acknowledged
     * @throws IllegalArgumentException if the comment is too long — validated
     *                                  up front so the whole batch 400s
     *                                  cleanly instead of every row being
     *                                  silently "skipped" by the per-row
     *                                  catch below
     */
    public static int bulkAcknowledge(List<Long> ids, String comment, int userId) {
        validateComment(comment);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int acknowledged = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            try {
                AlertEvent event = AlertEventRepository.getAlertEvent(id);
                if (event == null || event.getAcknowledgedBy() != null
                        || event.getStatus() != AlertStatus.PROBLEM) {
                    continue;
                }
                applyAck(event, comment, userId);
                AlertEventRepository.updateAlertEvent(event);
                acknowledged++;
            } catch (Exception e) {
                // One bad row must not abort the batch; the count tells the
                // client how many actually took.
                log.warn("Bulk acknowledge skipped problem {}", id, e);
            }
        }
        if (acknowledged > 0) {
            SentinelAuditLog.problemBulkAcknowledged(userId, acknowledged, comment);
        }
        return acknowledged;
    }

    /**
     * Resolves a batch of problems, skipping (rather than failing on) ids that
     * are missing or already resolved — the resolve-side mirror of
     * {@link #bulkAcknowledge(List, String, int)}, and skipping for the same
     * reason: after a storm an operator sweeps a multi-select selection that
     * the evaluator is concurrently clearing underneath them, and "resolve
     * whatever is still open" is what they mean.
     *
     * <p>Each resolved event takes the full single-resolve treatment
     * ({@link #applyResolve}), trigger-state reset included — a bulk resolve
     * that skipped the reset would leave every swept trigger stuck in PROBLEM,
     * silently unable to open a new alert on the next genuine breach, which is
     * precisely the failure mode a post-storm sweep must not create.</p>
     *
     * <p><b>Audited as one event with a count</b> via
     * {@code SentinelAuditLog.problemBulkResolved}, mirroring bulk
     * acknowledge — per-event entries would flood the System Events log,
     * which is exactly what that pairing exists to prevent. Recorded under
     * its own verb rather than reusing the acknowledge one because "who
     * closed these fifty problems" and "who silenced them" are different
     * questions when the log is read back.</p>
     *
     * @param ids     the alert-event ids to resolve
     * @param comment optional operator note applied to each
     * @param userId  the resolving user
     * @return how many events were newly resolved
     * @throws IllegalArgumentException if the comment is too long — validated
     *                                  up front so the whole batch 400s
     *                                  cleanly instead of every row being
     *                                  silently "skipped" by the per-row
     *                                  catch below
     */
    public static int bulkResolve(List<Long> ids, String comment, int userId) {
        validateComment(comment);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int resolved = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            try {
                AlertEvent event = AlertEventRepository.getAlertEvent(id);
                if (event == null || event.getStatus() != AlertStatus.PROBLEM) {
                    continue;
                }
                applyResolve(event, comment, userId);
                resolved++;
            } catch (Exception e) {
                // One bad row must not abort the batch; the count tells the
                // client how many actually took.
                log.warn("Bulk resolve skipped problem {}", id, e);
            }
        }
        if (resolved > 0) {
            SentinelAuditLog.problemBulkResolved(userId, resolved, comment);
        }
        return resolved;
    }

    /**
     * Fetches one alert event by id, without the display-context assembly of
     * {@link #getDetail(long)}. Exists for callers that only need the raw
     * event — notably the servlet's channel-restriction guard, which must
     * learn the event's channel id before deciding whether the caller may
     * see or mutate it, and should not pay for monitor/channel/dispatch
     * resolution just to make that decision.
     *
     * @param id database id of the alert event
     * @return the event
     * @throws NoSuchElementException if no event has that id
     */
    public static AlertEvent get(long id) {
        return requireEvent(id);
    }

    // ========== Internals ==========

    /**
     * Rejects comments the {@code ack_comment} VARCHAR(1024) column cannot
     * hold, before any row is touched — rejection (not silent truncation)
     * because an operator's incident note is exactly the text that must not
     * be quietly cut short. Length is checked on the trimmed form, which is
     * what {@link #applyAck} persists.
     */
    private static void validateComment(String comment) {
        if (comment != null && comment.trim().length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException(
                    "Comment is too long (maximum " + MAX_COMMENT_LENGTH + " characters)");
        }
    }

    /** Stamps the acknowledgment fields (shared by ack, resolve, and bulk-ack). */
    private static void applyAck(AlertEvent event, String comment, int userId) {
        event.setAcknowledgedBy(userId);
        event.setAcknowledgedTime(Instant.now());
        if (comment != null && !comment.isBlank()) {
            event.setAckComment(comment.trim());
        }
    }

    /**
     * Performs one manual resolve end to end — stamp, persist, reset the
     * owning trigger — shared by {@link #resolve(long, String, int)} and
     * {@link #bulkResolve(List, String, int)} so the two can never drift on
     * the part that matters. The callers keep only what genuinely differs
     * between them: how a non-open event is handled (throw vs. skip) and
     * whether an audit event is written.
     *
     * <p>The resolver's identity doubles as the acknowledgment when none was
     * recorded — a manual resolve is the strongest possible form of "a human
     * has seen this" — and the trigger reset is mandatory, not optional; see
     * the class Javadoc.</p>
     */
    private static void applyResolve(AlertEvent event, String comment, int userId) {
        Instant now = Instant.now();
        event.setStatus(AlertStatus.RESOLVED);
        event.setResolvedTime(now);
        if (event.getAcknowledgedBy() == null) {
            applyAck(event, comment, userId);
        }
        AlertEventRepository.updateAlertEvent(event);
        resetTriggerState(event, now);
    }

    /**
     * Resets the trigger state that owns a manually resolved event: state OK,
     * breach counter zeroed, so the evaluator must observe a full fresh run
     * of {@code minConsecutiveBreaches} before re-opening. Only the state
     * whose {@code openAlertEventId} still points at this event is touched —
     * if the evaluator already moved on, its state is the newer truth.
     * Failures are logged, not thrown: the resolve itself has already been
     * persisted and must not be reported as failed.
     */
    private static void resetTriggerState(AlertEvent event, Instant now) {
        try {
            TriggerState state = TriggerStateRepository.getTriggerState(
                    event.getMonitorId(), event.getChannelId(), event.getMetadataId());
            if (state == null || !event.getId().equals(state.getOpenAlertEventId())) {
                return;
            }
            state.setState(TriggerStatus.OK);
            state.setConsecutiveBreachCount(0);
            state.setLastChangeTime(now);
            TriggerStateRepository.updateTriggerState(state);
        } catch (Exception e) {
            log.warn("Failed to reset trigger state after manual resolve of problem {} "
                    + "(monitor {}, channel {})", event.getId(), event.getMonitorId(),
                    event.getChannelId(), e);
        }
    }

    /**
     * Resolves the connector name for per-connector events
     * (CONNECTION_STATUS alerts carry a {@code metadataId}). Null-safe end
     * to end: the channel may be deleted, the controller may return
     * {@code null}, or the metadata id may be gone after an edit — a detail
     * view must render regardless.
     */
    private static String resolveConnectorName(AlertEvent event) {
        if (event.getMetadataId() == null) {
            return null;
        }
        try {
            Map<Integer, String> names = ChannelController.getInstance()
                    .getConnectorNames(event.getChannelId());
            return names != null ? names.get(event.getMetadataId()) : null;
        } catch (Exception e) {
            log.debug("Could not resolve connector name for problem {} (channel {}, metadataId {})",
                    event.getId(), event.getChannelId(), event.getMetadataId(), e);
            return null;
        }
    }

    /** Fetches an event or throws the 404-mapped exception. */
    private static AlertEvent requireEvent(long id) {
        AlertEvent event = AlertEventRepository.getAlertEvent(id);
        if (event == null) {
            throw new NoSuchElementException("No problem with id " + id);
        }
        return event;
    }

    /** Splits a CSV parameter into trimmed, non-empty tokens; {@code null} when nothing remains. */
    private static List<String> splitCsv(String csv) {
        if (isBlank(csv)) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values.isEmpty() ? null : values;
    }

    /** Strict enum parse with a client-actionable message on failure. */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, String paramName) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + paramName + " '" + name + "'");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
