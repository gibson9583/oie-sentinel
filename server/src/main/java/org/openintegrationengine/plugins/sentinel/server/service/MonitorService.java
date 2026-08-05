/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;

import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.evaluate.AnomalyEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.ConnectionStatusEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.EvaluationOutcome;
import org.openintegrationengine.plugins.sentinel.server.evaluate.InactivityEvaluator;
import org.openintegrationengine.plugins.sentinel.server.evaluate.LowVolumeEvaluator;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelTestOutcome;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorTestResult;
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
        List<ScopeResolver.ChannelTarget> targets = ScopeResolver.resolveStartedChannels(monitor);
        List<ChannelTestOutcome> outcomes = new ArrayList<>();
        boolean anyError = false;

        for (ScopeResolver.ChannelTarget target : targets) {
            try {
                switch (monitor.getMonitorType()) {
                    case CONNECTION_STATUS:
                        for (ConnectionStatusEvaluator.ConnectorEvaluation evaluation
                                : ConnectionStatusEvaluator.evaluate(monitor, target.channelId, now)) {
                            outcomes.add(outcome(target, evaluation.outcome.getResult().name(),
                                    "[connector " + evaluation.metadataId + "] "
                                            + summarize(evaluation.outcome)));
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
                }
            } catch (Exception e) {
                anyError = true;
                outcomes.add(outcome(target, "ERROR",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }

        MonitorTestResult result = new MonitorTestResult();
        result.setOk(!anyError);
        result.setMessage(targets.isEmpty()
                ? "No started channels in scope"
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
        validateConfig(monitor);
        requireValidSuppression(monitor.getSuppressedByMonitorId(), selfId);
        requireUniqueName(monitor.getName(), selfId);
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
                break;
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
