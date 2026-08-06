/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

/**
 * Renders Sentinel's whole configuration surface — monitors, alert actions,
 * maintenance windows — as one portable JSON document, and applies such a
 * document back onto a server. This is the entire body behind
 * {@code GET /export} and {@code POST /import}, so the servlet stays the thin
 * translate-and-delegate layer every other endpoint keeps it as.
 *
 * <p><b>Why this exists.</b> Every Sentinel installation is otherwise
 * hand-entered per environment. Export/import makes a monitor set promotable
 * dev → test → prod the way channels already are, and makes the document
 * itself a reviewable artifact that belongs in version control alongside the
 * infrastructure it watches.</p>
 *
 * <h2>Matching is by name, never by id</h2>
 *
 * <p>Sentinel's ids are database serials assigned per install: monitor 7 on
 * dev is an unrelated monitor 7 on prod, and a document promoted between them
 * cannot mean anything by "7". Names, by contrast, are what operators write,
 * what the UI sorts on, and — for monitors and actions — what the schema
 * enforces as UNIQUE. So the export drops every id and every audit stamp, and
 * the import matches each entry against the target's entities by name
 * (trimmed, case-insensitively — the same comparison
 * {@code MonitorService}/{@code ActionService} already use to reject duplicate
 * names, so import can never create a pair the editors would consider a
 * collision). A name that exists on the target is an update; a name that does
 * not is a create.</p>
 *
 * <p>Dropping the ids also makes the document diffable: two exports of the
 * same configuration are byte-identical, so a commit shows the configuration
 * change and nothing else. Timestamps and authorship are environment facts,
 * not configuration, and would otherwise churn every line on every export.</p>
 *
 * <p><b>Engine ids are kept as-is.</b> A monitor's {@code scopeId}, a window's
 * {@code scopeId}, a CHANNEL action's {@code channelId}, and the
 * CHANNEL/CHANNEL_GROUP/CHANNEL_TAG condition rows all hold the <i>engine's</i>
 * channel, group, and tag ids — which travel with a channel export and are
 * therefore the same on every environment a channel has been promoted to.
 * They are portable exactly because they are not Sentinel's serials.</p>
 *
 * <h2>Referential integrity</h2>
 *
 * <p>Two fields point at a Sentinel monitor by its serial id and so cannot
 * survive the trip unaltered. Both are translated to names on export and
 * resolved back to ids on import, and neither is ever silently dropped:</p>
 *
 * <ul>
 * <li>{@code Monitor.suppressedByMonitorId} → {@code suppressedByMonitorName}.
 * Monitors are imported in dependency order (repeated passes, parents before
 * children), so a document that creates both a parent and its child lands them
 * in the right order regardless of array order. A child whose parent resolves
 * to nothing — not on the target, not creatable from this document, or part of
 * a circular chain a hand-edited document introduced — is reported as skipped
 * with that reason. Importing it with the dependency quietly removed would
 * turn a suppressed monitor into a paging one at the exact moment its parent
 * fails.</li>
 * <li>{@code MONITOR} rows inside an action's {@code conditionJson}, whose
 * values are monitor ids ("this action belongs to these monitors"). An
 * unresolved name skips the whole action, for the same reason: an action whose
 * monitor condition silently stopped matching is an action that stops paging
 * without anyone noticing. Actions are imported after monitors so a monitor
 * created by the same document is available to name.</li>
 * </ul>
 *
 * <h2>Secrets</h2>
 *
 * <p>The export reads actions through {@link ActionService#list()}, the same
 * redacting path every other read uses, so every masked config slot — the SNS
 * credential fields and the credential-shaped WEBHOOK headers alike — leaves as
 * {@link ActionService#REDACTED}, and a stored credential can never reach a
 * file, a browser, or a git repository. That is the feature, not a limitation —
 * but it means the operator has work to do on the target, so the import result
 * names it per action:</p>
 *
 * <ul>
 * <li>A marker whose field <i>does</i> have a stored value behind it on this
 * server (detected through the target action's own redacted copy — the same
 * path, never around it) is passed through untouched, and
 * {@code ActionService.update} substitutes the stored value. Reported as
 * {@code secretsKept}: nothing to do.</li>
 * <li>A marker with nothing behind it on this server is stripped before the
 * service call and reported as {@code secretsRequired}. Import will not invent
 * a credential or store a placeholder that makes a broken action look
 * configured, so what happens next is whatever the service's own validation
 * says: where the field is mandatory (an SNS action under STATIC or ROLE auth)
 * {@code ActionService} rejects the incomplete definition and the entry is
 * reported as skipped carrying that message, and the operator creates the
 * action once here with its real credentials — from then on every import
 * updates the rest of its configuration and keeps the secret. Where it is
 * optional (a webhook's {@code Authorization} header) the action lands without
 * it, which is why the report says so per field and per action: it will not
 * deliver until the credential is entered here.</li>
 * </ul>
 *
 * <p>Secret detection is by value, not by field name: any config value equal to
 * the redaction marker is treated as a secret, at any depth. That matters
 * because the masked set is no longer a fixed list — a webhook's secret headers
 * are named by the operator — so matching on the marker stays correct as that
 * set grows, and needs nothing from {@code ActionService} but its public marker
 * constant. See {@link #planSecrets(String, String)}.</p>
 *
 * <h2>What import will and will not do</h2>
 *
 * <p>Every entity is written through {@link MonitorService},
 * {@link ActionService}, and {@link MaintenanceWindowService}
 * {@code create}/{@code update} — never through a repository — so an import
 * cannot introduce a monitor the editors would reject, cannot skip the audit
 * trail, and cannot bypass secret encryption. A service rejection becomes a
 * skipped entry carrying the service's own message rather than failing the
 * whole request: one bad entry in a fifty-entity document must not block the
 * other forty-nine.</p>
 *
 * <p>Import is <b>additive and idempotent</b>. It never deletes: an entity
 * present on the target and absent from the document is left alone, because
 * "not in this file" and "delete this" are different intentions and only one of
 * them is recoverable. An entity whose definition already matches the document
 * is reported as skipped/unchanged and not written at all — no audit noise, no
 * {@code updatedTime} churn — which is what makes re-running a completed import
 * report all-skipped. There is no enclosing transaction; a failure part way
 * through leaves the entries already applied in place, and re-running converges
 * because of that idempotence.</p>
 *
 * <p><b>Dry run.</b> {@code dryRun} produces the same {@link ImportResult}
 * shape by the same code path, with every service call suppressed, so the UI
 * renders one component for the preview and the outcome. It resolves names,
 * classifies secrets, and detects unchanged entities exactly as the real run
 * does. The one thing it cannot report is field-level validation: the services
 * expose no validate-only entry point, so a definition the target would reject
 * shows as CREATED/UPDATED in the preview and as skipped-with-reason on apply.
 * In practice a document produced by export already passed that validation on
 * the source server.</p>
 *
 * <p>Errors follow the plugin-wide convention: {@link IllegalArgumentException}
 * → 400, {@link NoSuchElementException} → 404 (mapped in the servlet). Only a
 * document that cannot be read at all — wrong shape, missing or unsupported
 * schema version — fails the request; everything else is reported per entry.</p>
 */
public final class ExportImportService {

    /**
     * Format version of the export document, stamped on export and required
     * on import.
     *
     * <p>Version 1 is defined by this class: ids and audit stamps removed,
     * monitor suppression carried as {@code suppressedByMonitorName}, action
     * {@code MONITOR} condition values carried as monitor names, secrets
     * carried as the redaction marker. Import accepts anything at or below
     * this number and refuses anything above it — a document from a newer
     * Sentinel may encode a reference this server does not know how to
     * resolve, and applying it half-understood is worse than refusing it.</p>
     */
    public static final int SCHEMA_VERSION = 1;

    /** Entity type of an {@link ImportEntry} sourced from {@code monitors}. */
    public static final String TYPE_MONITOR = "MONITOR";

    /** Entity type of an {@link ImportEntry} sourced from {@code actions}. */
    public static final String TYPE_ACTION = "ACTION";

    /** Entity type of an {@link ImportEntry} sourced from {@code maintenanceWindows}. */
    public static final String TYPE_MAINTENANCE_WINDOW = "MAINTENANCE_WINDOW";

    /** Outcome: no entity of that name existed here, so one was (or would be) created. */
    public static final String OUTCOME_CREATED = "CREATED";

    /** Outcome: an entity of that name existed here and was (or would be) rewritten. */
    public static final String OUTCOME_UPDATED = "UPDATED";

    /**
     * Outcome: nothing was written. Always paired with a reason — either
     * "already matches this server" or the validation/resolution failure that
     * stopped it.
     */
    public static final String OUTCOME_SKIPPED = "SKIPPED";

    /** Document key for the monitor array. */
    private static final String KEY_MONITORS = "monitors";

    /** Document key for the action array. */
    private static final String KEY_ACTIONS = "actions";

    /** Document key for the maintenance-window array. */
    private static final String KEY_WINDOWS = "maintenanceWindows";

    /** Document key carrying a monitor's suppression parent by name. */
    private static final String KEY_SUPPRESSED_BY_NAME = "suppressedByMonitorName";

    /** The {@code Monitor} column {@link #KEY_SUPPRESSED_BY_NAME} replaces. */
    private static final String FIELD_SUPPRESSED_BY_ID = "suppressedByMonitorId";

    /** The one {@code ActionConditionMatcher} field whose values are Sentinel ids. */
    private static final String CONDITION_FIELD_MONITOR = "MONITOR";

    /**
     * Columns stripped from every exported entity: database serials, audit
     * stamps, and the response-only {@code activeNow} flag
     * {@code MaintenanceWindowService} derives on read. Everything <i>not</i>
     * listed here is carried through, so a definition field added to a model
     * later travels without touching this class — while the fields that must
     * never travel are named explicitly, because dropping one is the failure
     * mode worth a compile-time-visible list.
     */
    private static final List<String> NON_PORTABLE_FIELDS =
            List.of("id", "createdBy", "createdTime", "updatedBy", "updatedTime", "activeNow");

    /**
     * Fields that hold a JSON document inside a JSON string. They are compared
     * as parsed trees rather than as text, so re-indentation or key reordering
     * by a hand-edited document does not read as a configuration change and
     * fill the diff with phantom updates.
     */
    private static final List<String> EMBEDDED_JSON_FIELDS = List.of("configJson", "conditionJson");

    private ExportImportService() {
    }

    // ========== Export ==========

    /**
     * Builds the export document: one JSON object carrying every monitor,
     * action, and maintenance window in portable form.
     *
     * <p>Shape:</p>
     * <pre>
     * {
     *   "schemaVersion": 1,
     *   "exportedAt": "2026-08-06T09:15:22.481Z",
     *   "monitors":           [ { ...definition, "suppressedByMonitorName": "Upstream down"|null } ],
     *   "actions":            [ { ...definition, secrets as the redaction marker } ],
     *   "maintenanceWindows": [ { ...definition } ]
     * }
     * </pre>
     *
     * <p>{@code exportedAt} is the server's clock at export, ISO-8601 UTC like
     * every other instant on this wire. It is not used to make decisions on
     * import — it exists so a document found in a repository a year from now
     * can be dated, and so a future format migration has an anchor beyond the
     * schema version.</p>
     *
     * <p>Actions come from {@link ActionService#list()}, so their secrets are
     * already the redaction marker before this method sees them. Nothing here
     * reads the action repository directly.</p>
     *
     * @return the export document, ready for {@code Json.write}
     */
    public static ObjectNode export() {
        MonitorIndex monitors = MonitorIndex.of(MonitorService.list());

        ObjectNode root = Json.mapper().createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("exportedAt", Instant.now().toString());

        ArrayNode monitorNodes = root.putArray(KEY_MONITORS);
        for (Monitor monitor : monitors.all()) {
            monitorNodes.add(monitorNode(monitor, monitors.nameOf(monitor.getSuppressedByMonitorId())));
        }

        ArrayNode actionNodes = root.putArray(KEY_ACTIONS);
        for (Action action : ActionService.list()) {
            actionNodes.add(actionNode(action, monitors));
        }

        ArrayNode windowNodes = root.putArray(KEY_WINDOWS);
        for (MaintenanceWindow window : MaintenanceWindowService.list()) {
            windowNodes.add(portable(window));
        }
        return root;
    }

    /**
     * Renders one monitor in portable form: the definition minus the
     * non-portable columns, with {@code suppressedByMonitorId} replaced by the
     * parent's name.
     *
     * <p>An explicit JSON {@code null} is written when there is no parent, so
     * the key is always present and its absence in a document is unambiguous
     * (a hand-written entry that omits it simply has no parent). An id that
     * resolves to no monitor — which the foreign key makes unreachable in
     * practice — also writes {@code null}: the source server's serial would be
     * meaningless on the target, and import reports a genuinely missing parent
     * from the name side.</p>
     */
    private static ObjectNode monitorNode(Monitor monitor, String parentName) {
        ObjectNode node = portable(monitor);
        node.remove(FIELD_SUPPRESSED_BY_ID);
        node.set(KEY_SUPPRESSED_BY_NAME, parentName == null ? NullNode.getInstance() : TextNode.valueOf(parentName));
        return node;
    }

    /**
     * Renders one already-redacted action in portable form, rewriting the
     * monitor ids inside its {@code MONITOR} condition rows to monitor names.
     * A stale id matching no monitor is left exactly as it is: rewriting it to
     * nothing would silently widen the action's condition, and leaving it
     * makes import report it by value so the operator can clean it up.
     */
    private static ObjectNode actionNode(Action action, MonitorIndex monitors) {
        ObjectNode node = portable(action);
        node.set("conditionJson", TextNode.valueOf(
                mapMonitorConditionValues(action.getConditionJson(), monitors::nameOf)));
        return node;
    }

    /**
     * Serializes an entity to a tree and strips {@link #NON_PORTABLE_FIELDS}.
     * Going through the shared mapper (rather than writing each field by hand)
     * is deliberate: the portable shape is defined by subtraction, so a new
     * definition column is exported automatically and only the environment
     * columns need maintaining here.
     */
    private static ObjectNode portable(Object entity) {
        ObjectNode node = Json.mapper().valueToTree(entity);
        for (String field : NON_PORTABLE_FIELDS) {
            node.remove(field);
        }
        return node;
    }

    // ========== Import ==========

    /**
     * Applies an export document to this server, or previews the application
     * when {@code dryRun} is set.
     *
     * <p>Order is monitors → actions → windows, because an action's
     * {@code MONITOR} condition rows name monitors that the same document may
     * be creating. Within the monitors, dependency order is resolved by
     * repeated passes so suppression parents land before their children.</p>
     *
     * @param document the parsed request body (the servlet owns "a body is
     *                 required" and "the body is not JSON"; this method owns
     *                 "the body is not an export document")
     * @param dryRun   {@code true} to compute the result without writing
     *                 anything
     * @param userId   the acting user, stamped and audited by the services
     *                 this delegates to
     * @return one entry per document entity plus the created/updated/skipped
     *         totals; identical in shape whether or not it was a dry run
     * @throws IllegalArgumentException if the document is not an object, or
     *                                  carries a missing/unsupported
     *                                  {@code schemaVersion}, or holds a
     *                                  non-array where an entity list belongs
     */
    public static ImportResult importDocument(JsonNode document, boolean dryRun, int userId) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("The import body must be an export document (a JSON object)");
        }
        int schemaVersion = requireSchemaVersion(document);

        List<ImportEntry> entries = new ArrayList<>();
        MonitorIndex monitors = importMonitors(entityArray(document, KEY_MONITORS), dryRun, userId, entries);
        importActions(entityArray(document, KEY_ACTIONS), monitors, dryRun, userId, entries);
        importWindows(entityArray(document, KEY_WINDOWS), dryRun, userId, entries);

        ImportResult result = new ImportResult();
        result.setDryRun(dryRun);
        result.setSchemaVersion(schemaVersion);
        result.setExportedAt(textOrNull(document, "exportedAt"));
        result.setEntries(entries);
        result.setCreated(countOf(entries, OUTCOME_CREATED));
        result.setUpdated(countOf(entries, OUTCOME_UPDATED));
        result.setSkipped(countOf(entries, OUTCOME_SKIPPED));
        result.setSecretsNotice(secretsNotice(entries));
        return result;
    }

    /**
     * Reads and range-checks {@code schemaVersion}. A document without one is
     * not an export document — most likely a bare monitor array or someone
     * else's JSON — and guessing at its shape would apply configuration the
     * operator never wrote.
     */
    private static int requireSchemaVersion(JsonNode document) {
        JsonNode node = document.get("schemaVersion");
        if (node == null || !node.canConvertToInt()) {
            throw new IllegalArgumentException(
                    "The import body is missing a numeric 'schemaVersion'; it does not look like a Sentinel export");
        }
        int version = node.asInt();
        if (version < 1) {
            throw new IllegalArgumentException("Unsupported export schemaVersion " + version);
        }
        if (version > SCHEMA_VERSION) {
            throw new IllegalArgumentException("This export was produced by a newer Sentinel (schemaVersion "
                    + version + "); this server understands up to " + SCHEMA_VERSION);
        }
        return version;
    }

    /**
     * Reads one entity list. Absent and JSON-null are both "this document
     * carries none of these" — a monitors-only document is a legitimate thing
     * to hand-write — while a present-but-not-an-array value is a malformed
     * document and fails the request rather than being silently ignored.
     */
    private static ArrayNode entityArray(JsonNode document, String key) {
        JsonNode node = document.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("'" + key + "' must be a JSON array");
        }
        return (ArrayNode) node;
    }

    // ========== Import: monitors ==========

    /**
     * Imports the monitor list in dependency order and returns the resulting
     * name index, which the action pass needs to resolve {@code MONITOR}
     * condition rows.
     *
     * <p>The ordering is a repeated-pass loop rather than a sort: each monitor
     * has at most one parent, so "can this land yet?" is a single containment
     * check against the names already available (everything on this server,
     * plus everything this run has landed), and a pass that lands nothing
     * proves the remainder is unresolvable. That also makes a circular chain —
     * only reachable through a hand-edited document, since
     * {@code MonitorService} rejects cycles on save — terminate as a reported
     * skip instead of spinning.</p>
     *
     * <p>In a dry run a would-be-created monitor is added to the index as a
     * placeholder (name, no id), so its children and any action naming it
     * resolve exactly as they would on the real run.</p>
     */
    private static MonitorIndex importMonitors(ArrayNode nodes, boolean dryRun, int userId,
            List<ImportEntry> entries) {
        MonitorIndex index = MonitorIndex.of(MonitorService.list());
        if (nodes == null) {
            return index;
        }

        Set<String> documentNames = new LinkedHashSet<>();
        List<JsonNode> pending = new ArrayList<>();
        for (JsonNode node : nodes) {
            pending.add(node);
            String name = textOrNull(node, "name");
            if (!isBlank(name)) {
                documentNames.add(nameKey(name));
            }
        }

        while (!pending.isEmpty()) {
            List<JsonNode> deferred = new ArrayList<>();
            boolean progressed = false;
            for (JsonNode node : pending) {
                String parentName = textOrNull(node, KEY_SUPPRESSED_BY_NAME);
                if (!isBlank(parentName) && !index.has(parentName)) {
                    deferred.add(node);
                    continue;
                }
                entries.add(applyMonitor(node, index, dryRun, userId));
                progressed = true;
            }
            if (!progressed) {
                for (JsonNode node : deferred) {
                    entries.add(new ImportEntry(TYPE_MONITOR, textOrNull(node, "name"))
                            .skipped(unresolvedParentReason(textOrNull(node, KEY_SUPPRESSED_BY_NAME), documentNames)));
                }
                break;
            }
            pending = deferred;
        }
        return index;
    }

    /**
     * Explains why a suppression parent could not be resolved, distinguishing
     * the two causes the operator has to fix differently: a parent this
     * document never defines (create it on the target, or export it too)
     * versus a parent the document does define but could not land (its own
     * entry was skipped, or the chain is circular).
     */
    private static String unresolvedParentReason(String parentName, Set<String> documentNames) {
        String quoted = "'" + (parentName == null ? "" : parentName) + "'";
        if (parentName != null && documentNames.contains(nameKey(parentName))) {
            return "Suppressing monitor " + quoted + " is defined in this document but could not be created"
                    + " (its own entry was skipped, or the suppression chain is circular);"
                    + " the dependency was not dropped, so this monitor was left untouched";
        }
        return "Suppressing monitor " + quoted + " does not exist on this server and is not defined in this"
                + " document; the dependency was not dropped, so this monitor was left untouched";
    }

    /**
     * Creates, updates, or skips one monitor. The unchanged comparison happens
     * in the document's own name space — both sides rendered by
     * {@link #monitorNode} — so a monitor whose only difference is its
     * environment's serial ids never reads as changed.
     */
    private static ImportEntry applyMonitor(JsonNode node, MonitorIndex index, boolean dryRun, int userId) {
        String name = textOrNull(node, "name");
        ImportEntry entry = new ImportEntry(TYPE_MONITOR, name);
        if (isBlank(name)) {
            return entry.skipped("Entry has no name; monitors are matched by name");
        }

        Monitor incoming;
        try {
            incoming = Json.mapper().treeToValue(node, Monitor.class);
        } catch (Exception e) {
            return entry.skipped("Not a valid monitor definition: " + rootMessage(e));
        }
        incoming.setId(null);

        String parentName = textOrNull(node, KEY_SUPPRESSED_BY_NAME);
        Monitor existing = index.byName(name);
        if (existing != null && sameDefinition(monitorNode(incoming, parentName),
                monitorNode(existing, index.nameOf(existing.getSuppressedByMonitorId())))) {
            return entry.skipped("Already matches this server");
        }

        Monitor parent = isBlank(parentName) ? null : index.byName(parentName);
        if (!isBlank(parentName) && parent == null) {
            // Unreachable via importMonitors, which defers until the parent is
            // in the index; kept so a future caller cannot lose the dependency.
            return entry.skipped("Suppressing monitor '" + parentName + "' could not be resolved on this server");
        }
        incoming.setSuppressedByMonitorId(parent == null ? null : parent.getId());

        if (dryRun) {
            if (existing == null) {
                index.put(placeholderMonitor(name));
                return entry.created();
            }
            return entry.updated();
        }
        try {
            if (existing == null) {
                index.put(MonitorService.create(incoming, userId));
                return entry.created();
            }
            index.put(MonitorService.update(existing.getId(), incoming, userId));
            return entry.updated();
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return entry.skipped(rootMessage(e));
        }
    }

    // ========== Import: actions ==========

    /**
     * Imports the action list. Actions have no dependencies on each other, so
     * this is a straight pass — their one cross-entity reference (the
     * {@code MONITOR} condition rows) is satisfied by the monitor index the
     * previous pass returned.
     */
    private static void importActions(ArrayNode nodes, MonitorIndex monitors, boolean dryRun, int userId,
            List<ImportEntry> entries) {
        if (nodes == null) {
            return;
        }
        Map<String, Action> byName = new LinkedHashMap<>();
        for (Action action : ActionService.list()) {
            byName.put(nameKey(action.getName()), action);
        }
        for (JsonNode node : nodes) {
            entries.add(applyAction(node, byName, monitors, dryRun, userId));
        }
    }

    /**
     * Creates, updates, or skips one action, classifying its secrets and
     * resolving its monitor condition rows on the way.
     *
     * <p>Ordering inside this method matters. The unchanged comparison runs
     * <i>before</i> either rewrite, with both sides in name space and both
     * sides redacted, so it compares like with like. One consequence is worth
     * stating plainly: an action whose only difference is a secret reads as
     * unchanged, because the export never carried the secret to compare
     * against. Secrets are re-entered on the target, never diffed from a
     * file.</p>
     */
    private static ImportEntry applyAction(JsonNode node, Map<String, Action> byName, MonitorIndex monitors,
            boolean dryRun, int userId) {
        String name = textOrNull(node, "name");
        ImportEntry entry = new ImportEntry(TYPE_ACTION, name);
        if (isBlank(name)) {
            return entry.skipped("Entry has no name; actions are matched by name");
        }

        Action incoming;
        try {
            incoming = Json.mapper().treeToValue(node, Action.class);
        } catch (Exception e) {
            return entry.skipped("Not a valid action definition: " + rootMessage(e));
        }
        incoming.setId(null);

        Action existing = byName.get(nameKey(name));
        // Computed against the UNTOUCHED incoming config, so both sides are
        // redacted the same way and compare like with like.
        boolean unchanged = existing != null
                && sameDefinition(portable(incoming), actionNode(existing, monitors));

        // Secret classification runs even for an unchanged or about-to-be-
        // skipped action: "this action is already here and its key is already
        // stored" is exactly the reassurance the operator came for.
        SecretPlan secrets = planSecrets(incoming.getConfigJson(),
                existing == null ? null : existing.getConfigJson());
        entry.setSecretsKept(secrets.kept);
        entry.setSecretsRequired(secrets.required);
        entry.setSecretsNote(secretsNote(secrets.kept, secrets.required));

        if (unchanged) {
            return entry.skipped("Already matches this server");
        }

        List<String> unresolved = new ArrayList<>();
        UnaryOperator<String> toId = value -> {
            if (!monitors.has(value)) {
                unresolved.add(value);
                return null;
            }
            Integer id = monitors.idOf(value);
            return id == null ? null : String.valueOf(id); // null only for a dry-run placeholder
        };
        String resolvedCondition = mapMonitorConditionValues(incoming.getConditionJson(), toId);
        if (!unresolved.isEmpty()) {
            return entry.skipped("MONITOR condition rows name monitor(s) that do not exist on this server: "
                    + String.join(", ", unresolved)
                    + "; the condition was not widened, so this action was left untouched");
        }
        incoming.setConditionJson(resolvedCondition);
        incoming.setConfigJson(secrets.configJson);

        if (dryRun) {
            if (existing == null) {
                byName.put(nameKey(name), incoming);
                return entry.created();
            }
            return entry.updated();
        }
        try {
            if (existing == null) {
                byName.put(nameKey(name), ActionService.create(incoming, userId));
                return entry.created();
            }
            byName.put(nameKey(name), ActionService.update(existing.getId(), incoming, userId));
            return entry.updated();
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return entry.skipped(rootMessage(e));
        }
    }

    // ========== Import: maintenance windows ==========

    /**
     * Imports the maintenance-window list.
     *
     * <p>Window names, unlike monitor and action names, carry no UNIQUE
     * constraint, so the index is name → all matches. A name matching more
     * than one existing window is ambiguous under a name-matching import and
     * is reported as skipped rather than resolved by picking one: guessing
     * which of two identically named windows the operator meant could silence
     * the wrong channels.</p>
     */
    private static void importWindows(ArrayNode nodes, boolean dryRun, int userId, List<ImportEntry> entries) {
        if (nodes == null) {
            return;
        }
        Map<String, List<MaintenanceWindow>> byName = new LinkedHashMap<>();
        for (MaintenanceWindow window : MaintenanceWindowService.list()) {
            byName.computeIfAbsent(nameKey(window.getName()), k -> new ArrayList<>()).add(window);
        }
        for (JsonNode node : nodes) {
            entries.add(applyWindow(node, byName, dryRun, userId));
        }
    }

    /** Creates, updates, or skips one maintenance window. */
    private static ImportEntry applyWindow(JsonNode node, Map<String, List<MaintenanceWindow>> byName,
            boolean dryRun, int userId) {
        String name = textOrNull(node, "name");
        ImportEntry entry = new ImportEntry(TYPE_MAINTENANCE_WINDOW, name);
        if (isBlank(name)) {
            return entry.skipped("Entry has no name; maintenance windows are matched by name");
        }

        MaintenanceWindow incoming;
        try {
            incoming = Json.mapper().treeToValue(node, MaintenanceWindow.class);
        } catch (Exception e) {
            return entry.skipped("Not a valid maintenance window definition: " + rootMessage(e));
        }
        incoming.setId(null);
        incoming.setActiveNow(null);

        List<MaintenanceWindow> matches = byName.getOrDefault(nameKey(name), List.of());
        if (matches.size() > 1) {
            return entry.skipped(matches.size() + " maintenance windows on this server are named '" + name
                    + "'; import matches on name and cannot choose between them — rename them and re-import");
        }
        MaintenanceWindow existing = matches.isEmpty() ? null : matches.get(0);
        if (existing != null && sameDefinition(portable(incoming), portable(existing))) {
            return entry.skipped("Already matches this server");
        }

        if (dryRun) {
            if (existing == null) {
                byName.put(nameKey(name), List.of(incoming));
                return entry.created();
            }
            return entry.updated();
        }
        try {
            if (existing == null) {
                byName.put(nameKey(name), List.of(MaintenanceWindowService.create(incoming, userId)));
                return entry.created();
            }
            byName.put(nameKey(name),
                    List.of(MaintenanceWindowService.update(existing.getId(), incoming, userId)));
            return entry.updated();
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return entry.skipped(rootMessage(e));
        }
    }

    // ========== Secrets ==========

    /**
     * What to do about one action's secrets: which markers the target can
     * satisfy from its own stored config, which the operator has to enter
     * here, and the config to actually send to {@link ActionService} once the
     * unsatisfiable ones are removed.
     */
    private static final class SecretPlan {

        private final List<String> kept = new ArrayList<>();
        private final List<String> required = new ArrayList<>();
        private String configJson;
    }

    /**
     * Classifies every redaction marker in an incoming action config against
     * the target action's own redacted config, and strips the markers nothing
     * on this server can satisfy.
     *
     * <p><b>Detection is by value, not by field name.</b> The marker is
     * {@link ActionService}'s public contract; its list of masked slots is
     * private and no longer even a fixed list — a WEBHOOK action's secret
     * headers are named by the operator, so they are discovered per document.
     * Matching on the marker value therefore stays correct as that set grows,
     * and needs nothing from {@code ActionService} but the constant.</p>
     *
     * <p>Both configs are walked <b>in parallel</b>, descending into nested
     * objects together, so a marker at {@code headers.Authorization} is
     * checked against {@code headers.Authorization} on the target and not
     * against some same-named key elsewhere. A marker the target also masks is
     * left in place — {@code ActionService.update} substitutes the stored
     * value — and a marker the target cannot match is removed, because a
     * marker with nothing behind it carries no information and
     * {@code ActionService} would reject it with a message about markers
     * rather than the per-type validation message that tells the operator
     * which field to go enter.</p>
     *
     * <p>Reported paths are dotted for readability ({@code headers.Authorization}).
     * They are labels for the operator, never used to navigate — removal
     * happens against the parent node the walk is already standing on, so a
     * header name containing a dot cannot mis-target.</p>
     *
     * @param incomingConfigJson the document's config for this action
     * @param existingRedactedConfigJson the target action's config as
     *        {@link ActionService#list()} redacted it, or {@code null} when no
     *        action of that name exists here yet (every marker is then
     *        unsatisfiable by definition)
     * @return the classification plus the config to send onward
     */
    private static SecretPlan planSecrets(String incomingConfigJson, String existingRedactedConfigJson) {
        SecretPlan plan = new SecretPlan();
        plan.configJson = incomingConfigJson;
        if (isBlank(incomingConfigJson)) {
            return plan;
        }
        JsonNode incoming;
        JsonNode existing = null;
        try {
            incoming = Json.mapper().readTree(incomingConfigJson);
            if (!isBlank(existingRedactedConfigJson)) {
                existing = Json.mapper().readTree(existingRedactedConfigJson);
            }
        } catch (Exception e) {
            return plan; // unparseable: nothing to classify, validation speaks next
        }
        if (!incoming.isObject()) {
            return plan;
        }
        if (planSecrets((ObjectNode) incoming, existing, "", plan)) {
            plan.configJson = Json.write(incoming);
        }
        return plan;
    }

    /**
     * One level of the parallel walk. Returns whether anything was removed, so
     * the caller only re-serializes a config it actually changed.
     */
    private static boolean planSecrets(ObjectNode incoming, JsonNode existing, String prefix, SecretPlan plan) {
        List<String> unsatisfiable = new ArrayList<>();
        boolean removed = false;
        Iterator<Map.Entry<String, JsonNode>> it = incoming.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> field = it.next();
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            JsonNode counterpart = existing != null && existing.isObject() ? existing.get(field.getKey()) : null;
            if (value.isObject()) {
                removed |= planSecrets((ObjectNode) value, counterpart, path, plan);
            } else if (value.isTextual() && ActionService.REDACTED.equals(value.asText())) {
                if (counterpart != null && counterpart.isTextual()
                        && ActionService.REDACTED.equals(counterpart.asText())) {
                    plan.kept.add(path);
                } else {
                    plan.required.add(path);
                    unsatisfiable.add(field.getKey());
                }
            }
        }
        // Removed after the walk rather than through the iterator: mutating a
        // node while iterating its own field set is the kind of subtlety that
        // survives review and fails in production.
        for (String field : unsatisfiable) {
            incoming.remove(field);
            removed = true;
        }
        return removed;
    }

    /**
     * The per-action sentence the operator reads. States what was kept and
     * what has to be entered here, in the imperative, because the whole point
     * of naming the fields is that someone has to go type them.
     */
    private static String secretsNote(List<String> kept, List<String> required) {
        if (kept.isEmpty() && required.isEmpty()) {
            return null;
        }
        StringBuilder note = new StringBuilder("Secrets are never exported. ");
        if (!kept.isEmpty()) {
            note.append(String.join(", ", kept))
                    .append(kept.size() == 1 ? " was kept" : " were kept")
                    .append(" from the action already stored on this server. ");
        }
        if (!required.isEmpty()) {
            note.append("Enter ").append(String.join(", ", required))
                    .append(" on this server; until then this action cannot deliver.");
        }
        return note.toString().trim();
    }

    /**
     * One banner-sized summary naming every action still waiting on a secret,
     * so the UI can surface the follow-up work without walking the entry list
     * itself. {@code null} when nothing needs re-entering.
     */
    private static String secretsNotice(List<ImportEntry> entries) {
        List<String> names = new ArrayList<>();
        for (ImportEntry entry : entries) {
            if (!entry.getSecretsRequired().isEmpty()) {
                names.add(entry.getName());
            }
        }
        if (names.isEmpty()) {
            return null;
        }
        return "Secrets export as the redaction marker and must be entered on this server. "
                + "Re-enter the credentials for: " + String.join(", ", names) + ".";
    }

    // ========== Condition rewriting ==========

    /**
     * Walks an action's condition array and rewrites the value of every
     * {@code MONITOR} row through {@code rewrite}, handling both the scalar
     * form ({@code =}, {@code !=}) and the array form ({@code IN}).
     *
     * <p>{@code rewrite} returns {@code null} to mean "leave this value
     * alone", which is how both directions report a value they could not
     * translate: export leaves a stale id in place, and import leaves the name
     * in place after recording it as unresolved. Nothing is ever removed —
     * dropping a condition row silently widens what the action matches.</p>
     *
     * <p>Anything that is not a condition array is returned untouched;
     * {@code ActionService.validateCondition} owns rejecting a malformed
     * one.</p>
     */
    private static String mapMonitorConditionValues(String conditionJson, UnaryOperator<String> rewrite) {
        if (isBlank(conditionJson)) {
            return conditionJson;
        }
        JsonNode condition;
        try {
            condition = Json.mapper().readTree(conditionJson);
        } catch (Exception e) {
            return conditionJson;
        }
        if (!condition.isArray()) {
            return conditionJson;
        }
        boolean rewritten = false;
        for (JsonNode row : condition) {
            JsonNode field = row.isObject() ? row.get("field") : null;
            if (field == null || !CONDITION_FIELD_MONITOR.equalsIgnoreCase(field.asText(""))) {
                continue;
            }
            JsonNode value = row.get("value");
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray()) {
                ArrayNode values = (ArrayNode) value;
                for (int i = 0; i < values.size(); i++) {
                    String next = rewrite.apply(values.get(i).asText(""));
                    if (next != null) {
                        values.set(i, TextNode.valueOf(next));
                        rewritten = true;
                    }
                }
            } else {
                String next = rewrite.apply(value.asText(""));
                if (next != null) {
                    ((ObjectNode) row).set("value", TextNode.valueOf(next));
                    rewritten = true;
                }
            }
        }
        return rewritten ? Json.write(condition) : conditionJson;
    }

    // ========== Comparison ==========

    /**
     * Whether two portable renderings describe the same definition. Both sides
     * are always produced by the same renderer, so this is a plain tree
     * comparison once the {@linkplain #EMBEDDED_JSON_FIELDS embedded JSON
     * strings} are parsed — which is what keeps a re-indented hand-edited
     * config from reading as a change and filling the diff with updates that
     * change nothing.
     */
    private static boolean sameDefinition(ObjectNode incoming, ObjectNode existing) {
        return comparable(incoming).equals(comparable(existing));
    }

    /** Copy of a portable node with its embedded JSON strings parsed into trees. */
    private static JsonNode comparable(ObjectNode node) {
        ObjectNode copy = node.deepCopy();
        for (String field : EMBEDDED_JSON_FIELDS) {
            JsonNode value = copy.get(field);
            if (value == null || !value.isTextual()) {
                continue;
            }
            try {
                copy.set(field, Json.mapper().readTree(value.asText()));
            } catch (Exception e) {
                // Not JSON after all: compare it as the raw string it is.
            }
        }
        return copy;
    }

    // ========== Plumbing ==========

    /**
     * The monitor name index shared by the monitor and action passes: name →
     * monitor for matching and id lookup, id → name for rendering the export.
     *
     * <p>Kept live through the run — every create feeds back into it — so a
     * monitor created early in a document can be a suppression parent or a
     * condition target later in the same document. On a dry run it holds
     * id-less placeholders for the monitors that would be created, which makes
     * every resolution decision identical to the real run's.</p>
     */
    private static final class MonitorIndex {

        private final Map<String, Monitor> byName = new LinkedHashMap<>();
        private final Map<Integer, String> namesById = new HashMap<>();

        static MonitorIndex of(List<Monitor> monitors) {
            MonitorIndex index = new MonitorIndex();
            for (Monitor monitor : monitors) {
                index.put(monitor);
            }
            return index;
        }

        void put(Monitor monitor) {
            if (monitor == null || isBlank(monitor.getName())) {
                return;
            }
            byName.put(nameKey(monitor.getName()), monitor);
            if (monitor.getId() != null) {
                namesById.put(monitor.getId(), monitor.getName());
            }
        }

        List<Monitor> all() {
            return new ArrayList<>(byName.values());
        }

        Monitor byName(String name) {
            return name == null ? null : byName.get(nameKey(name));
        }

        boolean has(String name) {
            return name != null && byName.containsKey(nameKey(name));
        }

        /** Target id for a name, or {@code null} for an unknown name or a dry-run placeholder. */
        Integer idOf(String name) {
            Monitor monitor = byName(name);
            return monitor == null ? null : monitor.getId();
        }

        /** Name for an id, or {@code null} for {@code null} and for ids this server does not have. */
        String nameOf(Integer id) {
            return id == null ? null : namesById.get(id);
        }

        /** Overload for the condition rewriter, which works in strings both ways. */
        String nameOf(String id) {
            try {
                return nameOf(Integer.valueOf(id.trim()));
            } catch (NumberFormatException e) {
                return null; // not an id at all — leave the value alone
            }
        }
    }

    /**
     * A name-only monitor standing in for one a dry run would create. It never
     * reaches a service or a repository; it exists so the dry run's dependency
     * resolution sees the same world the real run would.
     */
    private static Monitor placeholderMonitor(String name) {
        Monitor placeholder = new Monitor();
        placeholder.setName(name);
        return placeholder;
    }

    /** Match key: trimmed and case-folded, matching the services' duplicate-name check. */
    private static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Reads a text field, treating missing, JSON-null, and non-object parents alike as absent. */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null || !node.isObject() ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * The most useful message from a failure. Jackson's binding exceptions
     * carry a class-and-line suffix that means nothing to an operator reading
     * a skipped row, and a null message would render as "null" — so the
     * message is trimmed at its first newline and falls back to the exception
     * type.
     */
    private static String rootMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).trim();
    }

    /** Counts entries with one outcome. */
    private static int countOf(List<ImportEntry> entries, String outcome) {
        int count = 0;
        for (ImportEntry entry : entries) {
            if (outcome.equals(entry.getOutcome())) {
                count++;
            }
        }
        return count;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ========== Result DTOs ==========

    /**
     * The {@code POST /import} response, returned unchanged in shape for both
     * a dry run and a real run so the dashboard renders one component for the
     * preview and the outcome — the preview differing only in that
     * {@link #isDryRun()} is set and nothing was written.
     */
    public static final class ImportResult {

        private boolean dryRun;
        private int schemaVersion;
        private String exportedAt;
        private int created;
        private int updated;
        private int skipped;
        private List<ImportEntry> entries = new ArrayList<>();
        private String secretsNotice;

        /**
         * @return {@code true} when this was a preview and nothing was
         *         written; {@code false} when the entries were applied
         */
        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        /**
         * @return the {@code schemaVersion} the document declared, echoed so a
         *         saved result records which format it was read as
         */
        public int getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        /**
         * @return the document's {@code exportedAt} verbatim — an echo of what
         *         the file claims, kept as text rather than parsed so a
         *         malformed cosmetic field cannot fail an otherwise valid
         *         import; {@code null} if the document carried none
         */
        public String getExportedAt() {
            return exportedAt;
        }

        public void setExportedAt(String exportedAt) {
            this.exportedAt = exportedAt;
        }

        /** @return how many entities were (or would be) created */
        public int getCreated() {
            return created;
        }

        public void setCreated(int created) {
            this.created = created;
        }

        /** @return how many entities were (or would be) updated */
        public int getUpdated() {
            return updated;
        }

        public void setUpdated(int updated) {
            this.updated = updated;
        }

        /**
         * @return how many entities were left untouched — both those already
         *         matching this server and those a failure stopped; each
         *         entry's reason says which
         */
        public int getSkipped() {
            return skipped;
        }

        public void setSkipped(int skipped) {
            this.skipped = skipped;
        }

        /**
         * @return one row per document entity, in the order they were
         *         processed (monitors, then actions, then windows); never
         *         {@code null}
         */
        public List<ImportEntry> getEntries() {
            return entries;
        }

        public void setEntries(List<ImportEntry> entries) {
            this.entries = entries == null ? new ArrayList<>() : entries;
        }

        /**
         * @return a single sentence naming every action still needing a
         *         credential entered here, or {@code null} when none does
         */
        public String getSecretsNotice() {
            return secretsNotice;
        }

        public void setSecretsNotice(String secretsNotice) {
            this.secretsNotice = secretsNotice;
        }
    }

    /**
     * What happened (or would happen) to one entity in the document — one row
     * of the result table.
     */
    public static final class ImportEntry {

        private final String entityType;
        private final String name;
        private String outcome;
        private String reason;
        private List<String> secretsKept = new ArrayList<>();
        private List<String> secretsRequired = new ArrayList<>();
        private String secretsNote;

        /**
         * @param entityType one of {@link ExportImportService#TYPE_MONITOR},
         *                   {@link ExportImportService#TYPE_ACTION},
         *                   {@link ExportImportService#TYPE_MAINTENANCE_WINDOW}
         * @param name       the entity's name as the document spells it; may be
         *                   {@code null} for an entry that has none, which is
         *                   itself a skip reason
         */
        ImportEntry(String entityType, String name) {
            this.entityType = entityType;
            this.name = name;
        }

        /** Marks this entry created and returns itself, for a one-line call site. */
        ImportEntry created() {
            this.outcome = OUTCOME_CREATED;
            return this;
        }

        /** Marks this entry updated and returns itself. */
        ImportEntry updated() {
            this.outcome = OUTCOME_UPDATED;
            return this;
        }

        /** Marks this entry skipped with the reason it was skipped, and returns itself. */
        ImportEntry skipped(String reason) {
            this.outcome = OUTCOME_SKIPPED;
            this.reason = reason;
            return this;
        }

        /** @return which entity list this row came from */
        public String getEntityType() {
            return entityType;
        }

        /** @return the entity's name — the value the import matched on */
        public String getName() {
            return name;
        }

        /**
         * @return {@link ExportImportService#OUTCOME_CREATED},
         *         {@link ExportImportService#OUTCOME_UPDATED}, or
         *         {@link ExportImportService#OUTCOME_SKIPPED}; on a dry run,
         *         what would happen
         */
        public String getOutcome() {
            return outcome;
        }

        /**
         * @return why this entry was skipped — "already matches this server",
         *         an unresolved reference, or the service's own validation
         *         message; {@code null} for a create or update
         */
        public String getReason() {
            return reason;
        }

        /**
         * @return config fields that arrived as the redaction marker and were
         *         satisfied by the value already stored here — nothing to do;
         *         never {@code null}
         */
        public List<String> getSecretsKept() {
            return secretsKept;
        }

        void setSecretsKept(List<String> secretsKept) {
            this.secretsKept = secretsKept == null ? new ArrayList<>() : secretsKept;
        }

        /**
         * @return config fields that arrived as the redaction marker with no
         *         stored value behind them on this server — these must be
         *         entered here before the action can deliver; never
         *         {@code null}
         */
        public List<String> getSecretsRequired() {
            return secretsRequired;
        }

        void setSecretsRequired(List<String> secretsRequired) {
            this.secretsRequired = secretsRequired == null ? new ArrayList<>() : secretsRequired;
        }

        /**
         * @return the human sentence covering {@link #getSecretsKept()} and
         *         {@link #getSecretsRequired()}, or {@code null} when this
         *         entity carried no secrets at all
         */
        public String getSecretsNote() {
            return secretsNote;
        }

        void setSecretsNote(String secretsNote) {
            this.secretsNote = secretsNote;
        }
    }
}
