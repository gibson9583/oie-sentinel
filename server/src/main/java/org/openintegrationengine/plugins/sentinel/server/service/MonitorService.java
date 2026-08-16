/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;

import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.evaluate.AnomalyEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.ChannelStateEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.ConnectionStatusEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.ErrorRateEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.InactivityEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.LowVolumeEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.QueueDepthEvaluator;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelTestOutcome;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorTestResult;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;

/**
 * Business rules for monitor CRUD plus the "test this monitor" dry run.
 *
 * <p>All persistence-independent invariants live here rather than in the
 * servlet (which only translates HTTP) or the repository (which only moves
 * rows): the evaluator jobs assume every persisted monitor has a type, a
 * resolvable scope, a parseable config, and an acyclic suppression chain, so
 * this class is the single gate that makes those assumptions safe. Errors
 * follow the plugin-wide convention the servlet maps to HTTP:
 * {@link IllegalArgumentException} (with a human message) for a rejected
 * input → 400, {@link NoSuchElementException} for a missing entity → 404.</p>
 */
public final class MonitorService {

    /**
     * Longest runbook URL accepted, matching {@code sentinel_monitor.runbook_url}'s
     * {@code VARCHAR(1024)}. Checked here so an over-long value fails as a
     * readable 400 rather than as a vendor-specific truncation or constraint
     * violation from the driver — and truncation is the worse of those two,
     * since a silently shortened URL is a link that quietly 404s at 3 a.m.
     */
    private static final int MAX_RUNBOOK_URL_LENGTH = 1024;

    private MonitorService() {
    }

    /**
     * Lists every monitor, enabled or not — the management grid shows both.
     *
     * @return all monitors ordered by name; never {@code null}
     */
    public static List<Monitor> list() {
        return MonitorRepository.listMonitors(null, null, null, null);
    }

    /**
     * Fetches one monitor.
     *
     * @param id database id
     * @return the monitor
     * @throws NoSuchElementException if no monitor has that id
     */
    public static Monitor get(int id) {
        Monitor monitor = MonitorRepository.getMonitor(id);
        if (monitor == null) {
            throw new NoSuchElementException("No monitor with id " + id);
        }
        return monitor;
    }

    /**
     * Validates and creates a monitor, stamping authorship. Both created and
     * updated audit fields are set on create so "last touched by" is never
     * empty.
     *
     * <p>Name uniqueness is pre-checked with a case-insensitive scan for a
     * friendly message; the database UNIQUE constraint remains the racy-path
     * backstop (a constraint violation surfaces as an unnamed runtime
     * exception from the repository — acceptable for the rare lost race).</p>
     *
     * @param monitor the monitor to create ({@code id} ignored)
     * @param userId  the acting user, stamped and audited
     * @return the created monitor with its generated id
     * @throws IllegalArgumentException if validation fails
     */
    public static Monitor create(Monitor monitor, int userId) {
        validate(monitor, null);
        Instant now = Instant.now();
        monitor.setId(null);
        monitor.setCreatedBy(userId);
        monitor.setCreatedTime(now);
        monitor.setUpdatedBy(userId);
        monitor.setUpdatedTime(now);
        MonitorRepository.insertMonitor(monitor);
        SentinelAuditLog.monitorCreated(userId, monitor);
        return monitor;
    }

    /**
     * Validates and updates an existing monitor. Creation stamps are
     * preserved from the stored row (the client's copy of them is ignored —
     * authorship is server-assigned, never client-supplied).
     *
     * @param id      database id of the monitor to update
     * @param monitor the new definition
     * @param userId  the acting user, stamped and audited
     * @return the updated monitor
     * @throws NoSuchElementException   if no monitor has that id
     * @throws IllegalArgumentException if validation fails
     */
    public static Monitor update(int id, Monitor monitor, int userId) {
        Monitor existing = get(id);
        validate(monitor, id);
        monitor.setId(id);
        monitor.setCreatedBy(existing.getCreatedBy());
        monitor.setCreatedTime(existing.getCreatedTime());
        monitor.setUpdatedBy(userId);
        monitor.setUpdatedTime(Instant.now());
        MonitorRepository.updateMonitor(monitor);
        SentinelAuditLog.monitorUpdated(userId, monitor);
        return monitor;
    }

    /**
     * Deletes a monitor, first rejecting the delete when another monitor
     * depends on it for suppression. The schema would allow it (the FK is
     * {@code ON DELETE SET NULL}), but silently un-suppressing a dependent
     * monitor is a behavior change the operator did not ask for — naming the
     * dependent lets them decide.
     *
     * @param id     database id of the monitor to delete
     * @param userId the acting user, audited
     * @throws NoSuchElementException   if no monitor has that id
     * @throws IllegalArgumentException if another monitor references this
     *                                  one via {@code suppressedByMonitorId}
     */
    public static void delete(int id, int userId) {
        Monitor existing = get(id);
        for (Monitor other : MonitorRepository.listMonitors(null, null, null, null)) {
            if (other.getSuppressedByMonitorId() != null && other.getSuppressedByMonitorId() == id) {
                throw new IllegalArgumentException("Monitor '" + other.getName()
                        + "' is suppressed by this monitor; remove that dependency first");
            }
        }
        MonitorRepository.deleteMonitor(id);
        SentinelAuditLog.monitorDeleted(userId, existing);
    }

    /**
     * Flips a monitor's enabled flag without rewriting its definition — the
     * one mutation a NOC operator performs mid-incident, kept surgical so it
     * cannot clobber concurrent config edits.
     *
     * @param id      database id
     * @param enabled the new enabled state
     * @param userId  the acting user, audited
     * @return the monitor reflecting the new state
     * @throws NoSuchElementException if no monitor has that id
     */
    public static Monitor setEnabled(int id, boolean enabled, int userId) {
        Monitor existing = get(id);
        MonitorRepository.setMonitorEnabled(id, enabled);
        existing.setEnabled(enabled);
        SentinelAuditLog.monitorEnabledChanged(userId, existing);
        return existing;
    }

    /**
     * Dry-runs a monitor definition against the live engine: resolves its
     * started-channel scope and executes the real evaluator per channel,
     * without touching trigger state, alerts, or actions. This answers "what
     * would this monitor say right now?" before the operator saves it —
     * including for definitions that were never persisted.
     *
     * <p>An unsaved monitor gets a synthetic id of {@code -1} before
     * evaluation: the inactivity evaluator peeks at the trigger-state table
     * keyed by monitor id (to keep breaching on an established PROBLEM), and
     * a {@code null} id would NPE on unboxing while {@code -1} simply finds
     * no row — exactly the "no prior state" a dry run should see.</p>
     *
     * @param monitor the definition to test; only type/scope must be valid
     *                enough to resolve channels — evaluators default any
     *                missing config fields themselves
     * @return per-channel outcomes; {@code ok} is true when no evaluator
     *         threw (a BREACH result is a successful test, not a failure)
     * @throws IllegalArgumentException if the type or scope is too
     *                                  incomplete to evaluate
     */
    public static MonitorTestResult test(Monitor monitor) {
        if (monitor == null) {
            throw new IllegalArgumentException("Monitor body is required");
        }
        if (monitor.getMonitorType() == null) {
            throw new IllegalArgumentException("Monitor type is required");
        }
        if (monitor.getScopeType() == null) {
            throw new IllegalArgumentException("Scope type is required");
        }
        if (monitor.getScopeType() != ScopeType.ALL && isBlank(monitor.getScopeId())) {
            throw new IllegalArgumentException("A scope id (channel or group) is required for "
                    + monitor.getScopeType().name() + " scope");
        }
        if (monitor.getId() == null) {
            monitor.setId(-1); // synthetic id for unsaved definitions — see Javadoc
        }

        Instant now = Instant.now();
        // Mirror the evaluator's own scope choice exactly — a CHANNEL_STATE
        // dry run filtered to started channels would report "no channels in
        // scope" for the stopped channel the operator is writing the monitor
        // to catch, which is the most misleading answer a test could give.
        boolean watchesState = monitor.getMonitorType() == MonitorType.CHANNEL_STATE;
        List<ScopeResolver.ChannelTarget> targets = watchesState
                ? ScopeResolver.resolveScopedChannels(monitor)
                : ScopeResolver.resolveStartedChannels(monitor);
        List<ChannelTestOutcome> outcomes = new ArrayList<>();
        boolean anyError = false;

        for (ScopeResolver.ChannelTarget target : targets) {
            try {
                switch (monitor.getMonitorType()) {
                    case CONNECTION_STATUS:
                        for (ConnectionStatusEvaluator.ConnectorEvaluation evaluation
                                : ConnectionStatusEvaluator.evaluate(monitor, target.channelId, now)) {
                            // A null metadata id is the channel-level rollup
                            // outcome. Prefixing it would both read as
                            // "connector null" and imply a per-connector
                            // fan-out this monitor will never produce — the
                            // dry run has to show the same one-problem-per-
                            // channel shape production will.
                            outcomes.add(outcome(target, evaluation.outcome.getResult().name(),
                                    evaluation.metadataId != null
                                            ? "[connector " + evaluation.metadataId + "] "
                                                    + summarize(evaluation.outcome)
                                            : summarize(evaluation.outcome)));
                        }
                        break;
                    case INACTIVITY:
                        addOutcome(outcomes, target, InactivityEvaluator.evaluate(monitor, target.channelId, now));
                        break;
                    case LOW_VOLUME:
                        addOutcome(outcomes, target, LowVolumeEvaluator.evaluate(monitor, target.channelId, now));
                        break;
                    case ANOMALY:
                        addOutcome(outcomes, target, AnomalyEvaluator.evaluate(monitor, target.channelId, now));
                        break;
                    case ERROR_RATE:
                        addOutcome(outcomes, target, ErrorRateEvaluator.evaluate(monitor, target.channelId, now));
                        break;
                    case QUEUE_DEPTH:
                        addOutcome(outcomes, target, QueueDepthEvaluator.evaluate(monitor, target.channelId, now));
                        break;
                    case CHANNEL_STATE:
                        addOutcome(outcomes, target, ChannelStateEvaluator.evaluate(monitor, target.channelId,
                                ScopeResolver.channelState(target.channelId), now));
                        break;
                }
            } catch (Exception e) {
                anyError = true;
                outcomes.add(outcome(target, "ERROR",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }

        MonitorTestResult result = new MonitorTestResult();
        result.setOk(!anyError);
        // The empty-scope wording has to match which resolver ran, or a
        // CHANNEL_STATE test on a genuinely empty group reads as though the
        // started filter hid something from it.
        result.setMessage(targets.isEmpty()
                ? (watchesState ? "No channels in scope" : "No started channels in scope")
                : targets.size() + " channel(s) evaluated");
        result.setOutcomes(outcomes);
        return result;
    }

    // ========== Validation ==========

    /**
     * Full save-time validation. The evaluator ticks deliberately tolerate
     * broken configs (defaulting fields rather than aborting), so this method
     * is where bad input actually gets rejected — at save time, with a
     * message the operator can act on, instead of at 3 a.m. as a silently
     * defaulted threshold.
     *
     * @param monitor the candidate definition
     * @param selfId  the monitor's own id on update, {@code null} on create
     *                (used to exempt itself from the uniqueness and cycle
     *                checks)
     */
    private static void validate(Monitor monitor, Integer selfId) {
        if (monitor == null) {
            throw new IllegalArgumentException("Monitor body is required");
        }
        if (isBlank(monitor.getName())) {
            throw new IllegalArgumentException("Monitor name is required");
        }
        if (monitor.getMonitorType() == null) {
            throw new IllegalArgumentException("Monitor type is required");
        }
        if (monitor.getScopeType() == null) {
            throw new IllegalArgumentException("Scope type is required");
        }
        if (monitor.getSeverity() == null) {
            throw new IllegalArgumentException("Severity is required");
        }
        if (monitor.getScopeType() != ScopeType.ALL && isBlank(monitor.getScopeId())) {
            throw new IllegalArgumentException("A scope id (channel or group) is required for "
                    + monitor.getScopeType().name() + " scope");
        }
        // Normalize rather than reject: 0 is what an omitted JSON int
        // deserializes to, so treating it as "no hysteresis" (1) matches the
        // author's evident intent.
        if (monitor.getMinConsecutiveBreaches() < 1) {
            monitor.setMinConsecutiveBreaches(1);
        }
        monitor.setRunbookUrl(validateRunbookUrl(monitor.getRunbookUrl()));
        validateConfig(monitor);
        requireValidSuppression(monitor.getSuppressedByMonitorId(), selfId);
        requireUniqueName(monitor.getName(), selfId);
    }

    /**
     * Normalizes and validates the optional runbook URL, returning the value to
     * store: {@code null} for absent (missing, empty, or whitespace), otherwise
     * the trimmed original.
     *
     * <p><b>This is a security control, not a typo check.</b> The stored value is
     * rendered as an {@code <a href>} in the problem detail pane and travels
     * verbatim into email bodies, SNS messages, and webhook templates. An
     * unvalidated scheme therefore makes this column a stored-XSS vector: a
     * monitor saved with {@code javascript:fetch('…'+document.cookie)} would
     * become a one-click script execution in the browser of every operator who
     * opened a problem it raised, under the permission of whoever clicked rather
     * than whoever authored the monitor. {@code data:} URLs are the same attack
     * with a different prefix. Rejecting at save time is the right layer: it is
     * the one place with an operator to show the message to, and it means every
     * consumer downstream can treat the value as a real http(s) URL without each
     * re-deriving this rule (and eventually disagreeing about it).</p>
     *
     * <p>The rule is deliberately narrow — an absolute {@code http} or
     * {@code https} URI with a host. "Absolute" excludes {@code //host/path} and
     * {@code /wiki/runbook}, which a browser would resolve against the admin
     * origin rather than against the operator's intent. The host requirement
     * rejects the syntactically legal but useless {@code http:runbooks}, which
     * parses as an opaque URI and would render as a dead link. Everything past
     * the authority — path, query, fragment — is left exactly as typed: a
     * runbook link commonly carries a deep-link anchor or a ticket query
     * parameter, and normalizing those is not this method's business.</p>
     *
     * <p>Not checked, on purpose: whether the URL resolves, or what is behind it.
     * The plugin never fetches this value (see {@code Monitor#getRunbookUrl}) —
     * a save-time HTTP request would hold an admin API thread on a slow wiki and
     * would turn the monitor editor into an SSRF primitive, which is exactly the
     * hazard {@code WebhookTargetGuard} exists to contain for the one transport
     * that genuinely needs to dial out.</p>
     *
     * @param rawUrl the operator-supplied value; may be {@code null} or blank
     * @return the trimmed URL, or {@code null} when none was supplied
     * @throws IllegalArgumentException if a value is present but is not an
     *                                  absolute http/https URL with a host, or
     *                                  is longer than the storage column
     */
    private static String validateRunbookUrl(String rawUrl) {
        if (isBlank(rawUrl)) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.length() > MAX_RUNBOOK_URL_LENGTH) {
            throw new IllegalArgumentException("Runbook URL must be at most "
                    + MAX_RUNBOOK_URL_LENGTH + " characters (got " + url.length() + ")");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            // getReason() is the parser's own short diagnosis ("Illegal character
            // in path"); the full message would repeat the whole URL back, which
            // the operator is already looking at in the field.
            throw new IllegalArgumentException("Runbook URL is not a valid URL"
                    + (e.getReason() != null ? ": " + e.getReason() : ""), e);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Runbook URL must be absolute, starting with http:// or https://");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Runbook URL must use the http or https scheme; '"
                    + scheme + ":' is not allowed because this link is opened in an operator's browser");
        }
        if (isBlank(uri.getHost())) {
            throw new IllegalArgumentException("Runbook URL must include a host, "
                    + "e.g. https://wiki.example.org/runbooks/adt-inactivity");
        }
        return url;
    }

    /**
     * Parses and sanity-checks the type-specific config JSON. Unknown fields
     * are tolerated (forward compatibility with newer clients); known fields,
     * when present, must be well-typed and in range. A missing config is
     * normalized to {@code "{}"} — every evaluator has defaults for every
     * field, and the column is NOT NULL.
     */
    private static void validateConfig(Monitor monitor) {
        if (isBlank(monitor.getConfigJson())) {
            monitor.setConfigJson("{}");
        }
        JsonNode config;
        try {
            config = Json.mapper().readTree(monitor.getConfigJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("Monitor config is not valid JSON", e);
        }
        if (!config.isObject()) {
            throw new IllegalArgumentException("Monitor config must be a JSON object");
        }

        switch (monitor.getMonitorType()) {
            case INACTIVITY:
                requirePositiveIfPresent(config, "noDataForSeconds");
                break;
            case LOW_VOLUME:
                requirePositiveIfPresent(config, "windowSeconds");
                requireOneOfIfPresent(config, "compareTo", "FIXED", "BASELINE_RELATIVE");
                requireNonNegativeIfPresent(config, "minCount");
                requirePositiveIfPresent(config, "baselinePercent");
                requirePositiveIfPresent(config, "baselineLookbackDays");
                break;
            case ANOMALY:
                requireOneOfIfPresent(config, "metric", "RECEIVED", "SENT", "ERROR");
                requirePositiveIfPresent(config, "zScoreThreshold");
                requireOneOfIfPresent(config, "direction", "LOW_ONLY", "HIGH_ONLY", "BOTH");
                requirePositiveIfPresent(config, "baselineWindowDays");
                break;
            case CONNECTION_STATUS:
                requireNonNegativeIfPresent(config, "minDurationSeconds");
                validateAlertOnStates(config);
                validateRollup(config);
                break;
            case ERROR_RATE:
                requirePositiveIfPresent(config, "windowSeconds");
                requirePercentIfPresent(config, "thresholdPercent");
                requireNonNegativeIfPresent(config, "minMessages");
                break;
            case QUEUE_DEPTH:
                // Non-negative, not positive: a threshold of 0 is degenerate
                // (queue depth is never negative, so every sample breaches)
                // but it is a coherent instruction, and rejecting it would
                // also reject the honest "alert on anything queued at all"
                // reading of it. Negative is the incoherent case.
                requireNonNegativeIfPresent(config, "threshold");
                requireNonNegativeIfPresent(config, "minDurationSeconds");
                break;
            case CHANNEL_STATE:
                requireNonNegativeIfPresent(config, "minDurationSeconds");
                validateDeployedStates(config);
                break;
        }
    }

    /**
     * Validates CHANNEL_STATE's {@code alertOnStates} against the real
     * {@link DeployedState} enum, for the same reason
     * {@link #validateAlertOnStates(JsonNode)} does it for connector states:
     * the evaluator matches names textually, so a typo produces a monitor
     * that watches for a state no channel will ever report and therefore
     * never fires — a monitor the operator believes is protecting them.
     *
     * <p>An empty array is rejected rather than accepted. The evaluator falls
     * back to its resting-state default for an empty list, which is a
     * reasonable runtime degradation but a poor save-time outcome: an
     * operator who cleared every checkbox meant "watch nothing" or is
     * mid-edit, and silently storing something that behaves like the default
     * set would page them for states they explicitly deselected.</p>
     */
    private static void validateDeployedStates(JsonNode config) {
        JsonNode states = config.get("alertOnStates");
        if (states == null || states.isNull()) {
            return; // absent → evaluator defaults to the resting states
        }
        if (!states.isArray()) {
            throw new IllegalArgumentException("alertOnStates must be a JSON array of channel state names");
        }
        if (states.isEmpty()) {
            throw new IllegalArgumentException(
                    "alertOnStates must name at least one channel state to alert on");
        }
        for (JsonNode entry : states) {
            String name = entry.asText("");
            try {
                DeployedState.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown channel state '" + name + "' in alertOnStates");
            }
        }
    }

    /**
     * Requires a present field to be a percentage in {@code [0, 100]}.
     *
     * <p>Rejects rather than clamps, in both directions. Above 100 the monitor
     * would be unreachable for any channel whose errors cannot exceed its
     * receives — a threshold that can never fire is a monitor an operator
     * believes is protecting them. At exactly 0 it fires on every measurable
     * window, error or not, which is the opposite failure and just as
     * misleading, but it is a coherent instruction ("tell me about any error
     * at all") so it stays legal; a negative percentage is not, and is the
     * only low-end value refused here.</p>
     */
    private static void requirePercentIfPresent(JsonNode config, String field) {
        Double value = numericValue(config, field);
        if (value != null && (value < 0 || value > 100)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }

    /**
     * Validates {@code alertOnStates} entries against the real donkey enum.
     * The evaluator matches names textually and would silently never fire on
     * a typo like "DISCONECTED" — exactly the class of mistake save-time
     * validation exists to catch.
     */
    private static void validateAlertOnStates(JsonNode config) {
        JsonNode states = config.get("alertOnStates");
        if (states == null || states.isNull()) {
            return; // absent → evaluator defaults to DISCONNECTED
        }
        if (!states.isArray()) {
            throw new IllegalArgumentException("alertOnStates must be a JSON array of connection state names");
        }
        for (JsonNode entry : states) {
            String name = entry.asText("");
            try {
                ConnectionStatusEventType.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown connection state '" + name
                        + "' in alertOnStates");
            }
        }
    }

    /**
     * Validates {@code rollup} against the CONNECTION_STATUS rollup enum.
     *
     * <p>This one rejects rather than normalizes, which is a deliberate
     * departure from how the evaluator treats the same key. The evaluator
     * must not abort a tick, so it degrades anything that is not
     * {@code CHANNEL} to per-connector evaluation. That makes a typo
     * ({@code "channell"} from a hand-edited config) silently revert a
     * monitor to paging once per connector — the precise noise problem the
     * operator selected {@code CHANNEL} to fix, failing in the direction
     * where nothing looks broken until the next incident. Save time is the
     * only place that mistake can be caught while the operator is still
     * looking at it.</p>
     *
     * <p>The accepted spelling is trimmed and case-insensitive, matching the
     * evaluator's read exactly. Accepting a form the evaluator would not
     * honour would recreate the same silent revert one layer down.</p>
     *
     * <p>{@code SCOPE} is a reserved value with no implementation yet, so it
     * gets its own message: "not a valid value" would be misleading for a
     * name that is in the documented enum and will start working later.</p>
     */
    private static void validateRollup(JsonNode config) {
        JsonNode node = config.get("rollup");
        if (node == null || node.isNull()) {
            return; // absent → evaluator defaults to CONNECTOR
        }
        String value = node.asText("").trim().toUpperCase(Locale.ROOT);
        if (ConnectionStatusEvaluator.ROLLUP_CONNECTOR.equals(value)
                || ConnectionStatusEvaluator.ROLLUP_CHANNEL.equals(value)) {
            return;
        }
        if (ConnectionStatusEvaluator.ROLLUP_SCOPE.equals(value)) {
            throw new IllegalArgumentException("rollup '" + ConnectionStatusEvaluator.ROLLUP_SCOPE
                    + "' is reserved for a future release; use "
                    + ConnectionStatusEvaluator.ROLLUP_CONNECTOR + " or "
                    + ConnectionStatusEvaluator.ROLLUP_CHANNEL);
        }
        throw new IllegalArgumentException("rollup must be one of "
                + ConnectionStatusEvaluator.ROLLUP_CONNECTOR + ", "
                + ConnectionStatusEvaluator.ROLLUP_CHANNEL);
    }

    /**
     * Rejects a suppression reference that is missing or would create a
     * cycle. The cycle check walks the parent chain (not a full graph DFS —
     * each monitor has at most one parent, so the "graph" is a functional
     * chain) with a visited set so a pre-existing cycle introduced by direct
     * DB edits cannot loop this validation forever.
     */
    private static void requireValidSuppression(Integer suppressedByMonitorId, Integer selfId) {
        if (suppressedByMonitorId == null) {
            return;
        }
        if (suppressedByMonitorId.equals(selfId)) {
            throw new IllegalArgumentException("A monitor cannot be suppressed by itself");
        }
        if (MonitorRepository.getMonitor(suppressedByMonitorId) == null) {
            throw new IllegalArgumentException("Suppressing monitor " + suppressedByMonitorId
                    + " does not exist");
        }
        Set<Integer> visited = new HashSet<>();
        Integer cursor = suppressedByMonitorId;
        while (cursor != null && visited.add(cursor)) {
            if (cursor.equals(selfId)) {
                throw new IllegalArgumentException(
                        "Suppression by monitor " + suppressedByMonitorId + " would create a cycle");
            }
            Monitor ancestor = MonitorRepository.getMonitor(cursor);
            cursor = ancestor != null ? ancestor.getSuppressedByMonitorId() : null;
        }
    }

    /**
     * Case-insensitive uniqueness pre-check so a duplicate name yields a
     * clear 400 instead of a raw constraint violation. Case-insensitive
     * because two monitors differing only in case are indistinguishable in
     * every list the UI shows.
     */
    private static void requireUniqueName(String name, Integer selfId) {
        for (Monitor other : MonitorRepository.listMonitors(null, null, null, null)) {
            if (other.getName() != null && other.getName().equalsIgnoreCase(name.trim())
                    && !other.getId().equals(selfId)) {
                throw new IllegalArgumentException("A monitor named '" + other.getName() + "' already exists");
            }
        }
    }

    // ========== Field helpers ==========

    /** Requires a present field to be a number strictly greater than zero. */
    private static void requirePositiveIfPresent(JsonNode config, String field) {
        Double value = numericValue(config, field);
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be greater than 0");
        }
    }

    /** Requires a present field to be a number of at least zero. */
    private static void requireNonNegativeIfPresent(JsonNode config, String field) {
        Double value = numericValue(config, field);
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    /**
     * Extracts a numeric field, accepting real JSON numbers and numeric
     * strings (HTML form values arrive as strings); returns {@code null}
     * when absent. A present-but-non-numeric value is rejected here rather
     * than left for the evaluator to silently default.
     */
    private static Double numericValue(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException e) {
                // fall through to the rejection below
            }
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    /** Requires a present textual field to be one of the allowed values (case-insensitive). */
    private static void requireOneOfIfPresent(JsonNode config, String field, String... allowed) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return;
        }
        String value = node.asText("").trim().toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException(field + " must be one of " + String.join(", ", allowed));
    }

    // ========== Test helpers ==========

    /** Adds a single-outcome evaluation (all types except CONNECTION_STATUS). */
    private static void addOutcome(List<ChannelTestOutcome> outcomes, ScopeResolver.ChannelTarget target,
            EvaluationOutcome evaluation) {
        outcomes.add(outcome(target, evaluation.getResult().name(), summarize(evaluation)));
    }

    /** Builds one test-outcome row. */
    private static ChannelTestOutcome outcome(ScopeResolver.ChannelTarget target, String status,
            String valueSummary) {
        ChannelTestOutcome outcome = new ChannelTestOutcome();
        outcome.setChannelId(target.channelId);
        outcome.setChannelName(target.channelName);
        outcome.setStatus(status);
        outcome.setValueSummary(valueSummary);
        return outcome;
    }

    /** Prefers the human message (breaches have one); falls back to the value JSON. */
    private static String summarize(EvaluationOutcome evaluation) {
        return evaluation.getMessage() != null ? evaluation.getMessage() : evaluation.getValueJson();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
