/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.alert.WebhookAlertSender;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionTestResult;

/**
 * Business rules for notification-action CRUD, including the secret
 * round-trip that keeps credential-bearing config out of every read path.
 *
 * <p><b>The secret lifecycle:</b> the {@linkplain #secretSlots(JsonNode)
 * sensitive config values} arrive exactly once — in the create/update body —
 * and are persisted, with the true credentials encrypted first using the
 * engine's encryptor ({@link SettingsCrypto}). Every read ({@link #list()},
 * {@link #get(int)}, and the entity echoed back by create/update) returns a
 * {@linkplain #redact(Action) redacted copy} in which each of those values is
 * replaced by the {@link #REDACTED} marker. On update, a client that never
 * saw the real values simply sends the marker back, which means "keep the
 * stored value" — so nothing sensitive round-trips through the browser, and
 * the generic REST audit (which excludes bodies anyway) can never capture
 * it.</p>
 *
 * <p><b>What counts as sensitive.</b> Reads are gated at View Monitoring, the
 * plugin's most widely granted permission, so the read path is the boundary
 * that matters. Two families of value qualify, and both flow through the same
 * {@linkplain SecretSlot slot} machinery rather than through parallel
 * code paths — a second mechanism is a second place to forget a field:</p>
 * <ul>
 *   <li><b>The three SNS fields</b> ({@link #SNS_SECRET_FIELDS}). Only
 *       {@code secretAccessKey} is a credential in the cryptographic sense
 *       and only it is encrypted at rest, but {@code accessKeyId} names a
 *       specific IAM principal and {@code assumeRoleArn} embeds the AWS
 *       account number — infrastructure detail a read-only monitoring user
 *       has no reason to see. {@code region} and {@code topicArn} are
 *       deliberately <em>not</em> masked: they are not sensitive and the
 *       editor renders them directly.</li>
 *   <li><b>Credential-shaped WEBHOOK headers</b> — every entry of the config's
 *       {@code headers} object whose <em>name</em> matches
 *       {@link org.openintegrationengine.plugins.sentinel.server.alert.WebhookAlertSender#isSecretHeaderName(String)}
 *       ({@code Authorization} and friends). Unlike the SNS fields these are
 *       not a fixed key list — the operator names them — so the slot for a
 *       header is discovered from the document being processed. All of them
 *       are encrypted at rest: a webhook bearer token has no non-credential
 *       component to preserve, so there is no reason to store one in
 *       plaintext.</li>
 * </ul>
 *
 * <p>Errors follow the plugin-wide convention: {@link IllegalArgumentException}
 * → 400, {@link NoSuchElementException} → 404 (mapped in the servlet).</p>
 */
public final class ActionService {

    /**
     * The wire marker standing in for a stored secret: eight bullet
     * characters, chosen to render like a masked password field. Receiving
     * this exact value on update means "keep the existing stored value".
     */
    public static final String REDACTED = "••••••••";

    /**
     * Config key holding the SNS secret access key inside {@code configJson}
     * — the only one of the {@link #SNS_SECRET_FIELDS} encrypted at rest, and
     * the only one the SNS sender has to decrypt on the way out.
     */
    private static final String SECRET_ACCESS_KEY = "secretAccessKey";

    /**
     * Top-level config keys masked on every read and kept-on-marker on write.
     * All three are SNS-only, so non-SNS configs simply never contain them
     * and pass through both paths untouched — which is why the slot builder
     * does not need to branch on action type for them.
     */
    private static final List<String> SNS_SECRET_FIELDS =
            List.of("accessKeyId", SECRET_ACCESS_KEY, "assumeRoleArn");

    /** Config key holding the WEBHOOK request headers object. */
    private static final String HEADERS = "headers";

    private ActionService() {
    }

    /**
     * Lists every action as redacted copies — the grid never needs the
     * credential fields, so it never receives them.
     *
     * @return redacted copies of all actions; never {@code null}
     */
    public static List<Action> list() {
        List<Action> redacted = new ArrayList<>();
        for (Action action : ActionRepository.listActions(null)) {
            redacted.add(redact(action));
        }
        return redacted;
    }

    /**
     * Fetches one action as a redacted copy.
     *
     * @param id database id
     * @return a redacted copy of the action
     * @throws NoSuchElementException if no action has that id
     */
    public static Action get(int id) {
        return redact(getUnredacted(id));
    }

    /**
     * Validates and creates an action, encrypting any supplied secret before
     * persist and stamping authorship.
     *
     * @param action the action to create ({@code id} ignored)
     * @param userId the acting user, stamped and audited
     * @return a redacted copy of the created action, with its generated id
     * @throws IllegalArgumentException if validation fails, including a
     *                                  {@link #REDACTED} value in any
     *                                  {@linkplain #secretSlots(JsonNode)
     *                                  sensitive slot} on create (there is no
     *                                  stored value to keep)
     */
    public static Action create(Action action, int userId) {
        prepareSecrets(action, null);
        validate(action, null);
        Instant now = Instant.now();
        action.setId(null);
        action.setCreatedBy(userId);
        action.setCreatedTime(now);
        action.setUpdatedBy(userId);
        action.setUpdatedTime(now);
        ActionRepository.insertAction(action);
        SentinelAuditLog.actionCreated(userId, action);
        return redact(action);
    }

    /**
     * Validates and updates an action. Each {@link #REDACTED} value among
     * the {@linkplain #secretSlots(JsonNode) sensitive slots} is replaced
     * with the stored value before validation, so editing an SNS action
     * without re-entering its key pair or role ARN — or a webhook without
     * re-entering its {@code Authorization} header — "just works"; any other
     * non-blank value is treated as newly supplied (and, where the slot is
     * encrypted at rest, encrypted). Creation stamps are preserved from the
     * stored row.
     *
     * @param id     database id of the action to update
     * @param action the new definition
     * @param userId the acting user, stamped and audited
     * @return a redacted copy of the updated action
     * @throws NoSuchElementException   if no action has that id
     * @throws IllegalArgumentException if validation fails
     */
    public static Action update(int id, Action action, int userId) {
        Action existing = getUnredacted(id);
        prepareSecrets(action, existing);
        validate(action, id);
        action.setId(id);
        action.setCreatedBy(existing.getCreatedBy());
        action.setCreatedTime(existing.getCreatedTime());
        action.setUpdatedBy(userId);
        action.setUpdatedTime(Instant.now());
        ActionRepository.updateAction(action);
        SentinelAuditLog.actionUpdated(userId, action);
        return redact(action);
    }

    /**
     * Deletes an action. Past dispatch-log rows survive with a nulled action
     * reference (schema {@code ON DELETE SET NULL}) — history must outlive
     * configuration.
     *
     * @param id     database id of the action to delete
     * @param userId the acting user, audited
     * @throws NoSuchElementException if no action has that id
     */
    public static void delete(int id, int userId) {
        Action existing = getUnredacted(id);
        ActionRepository.deleteAction(id);
        SentinelAuditLog.actionDeleted(userId, existing);
    }

    /**
     * Sends a real test notification through the stored action — SMTP mail,
     * channel dispatch, SNS publish or webhook request with a synthetic
     * payload. The <em>unredacted</em> stored action is handed to the
     * dispatcher: its secrets are the encrypted-at-rest form, which the SNS
     * and webhook senders decrypt just-in-time.
     *
     * @param id database id of the action to test
     * @return the delivery outcome (success flag + human message)
     * @throws NoSuchElementException if no action has that id
     */
    public static ActionTestResult test(int id) {
        return ActionDispatcher.sendTest(getUnredacted(id));
    }

    /**
     * Returns a deep-enough copy of an action with every populated
     * {@linkplain #secretSlots(JsonNode) sensitive config value} replaced by
     * {@link #REDACTED}. Always a copy — redacting the caller's instance in
     * place would corrupt the encrypted secret of an entity that is about to
     * be persisted or dispatched.
     *
     * @param action the stored action
     * @return a copy safe to serialize to any client
     */
    public static Action redact(Action action) {
        Action copy = copyOf(action);
        if (copy.getConfigJson() == null || copy.getConfigJson().isBlank()) {
            return copy;
        }
        try {
            JsonNode config = Json.mapper().readTree(copy.getConfigJson());
            if (!config.isObject()) {
                return copy;
            }
            boolean masked = false;
            for (SecretSlot slot : secretSlots(config)) {
                if (!slot.read(config).isBlank()) {
                    slot.write(config, REDACTED);
                    masked = true;
                }
            }
            if (masked) {
                copy.setConfigJson(Json.write(config));
            }
        } catch (Exception e) {
            // Unparsable stored config: nothing recognizable to leak, and
            // validation prevents persisting one — return the copy as-is.
        }
        return copy;
    }

    // ========== Secret handling ==========

    /**
     * One place in a config document holding a sensitive value: either a
     * top-level field ({@code owner} null) or a field of a nested object
     * ({@code owner} = {@link #HEADERS}). Two levels is all the shape needs
     * and all it should have — a general JSON-pointer walker here would be
     * more machinery than the two real cases justify.
     *
     * <p>{@code encrypted} says whether the value is stored as ciphertext.
     * It is false for the SNS identifiers (an access key id and a role ARN
     * are masked from readers but not credentials to protect at rest) and
     * true for the SNS secret access key and every webhook secret header.</p>
     */
    private static final class SecretSlot {

        private final String owner;
        private final String field;
        private final boolean encrypted;

        private SecretSlot(String owner, String field, boolean encrypted) {
            this.owner = owner;
            this.field = field;
            this.encrypted = encrypted;
        }

        /** The object actually holding the field, or {@code null} if absent from this document. */
        private ObjectNode container(JsonNode config) {
            if (owner == null) {
                return config.isObject() ? (ObjectNode) config : null;
            }
            JsonNode nested = config.get(owner);
            return nested != null && nested.isObject() ? (ObjectNode) nested : null;
        }

        /** The stored value, or {@code ""} when the slot is absent/null/blank. */
        private String read(JsonNode config) {
            ObjectNode container = container(config);
            if (container == null || !container.hasNonNull(field)) {
                return "";
            }
            return container.get(field).asText("");
        }

        /** Replaces the value in place; a no-op when the slot's container is absent. */
        private void write(JsonNode config, String value) {
            ObjectNode container = container(config);
            if (container != null) {
                container.put(field, value);
            }
        }
    }

    /**
     * Every sensitive slot in one config document.
     *
     * <p>Derived from the document rather than from the action type on
     * purpose. The SNS keys appear in no other transport's config, so
     * scanning for them unconditionally is free and — more importantly —
     * keeps redaction working for a row whose {@code action_type} is null or
     * unrecognized, which is exactly the row a leak would hide behind. The
     * webhook header slots <em>have</em> to be discovered this way regardless:
     * their names are chosen by the operator, so there is no fixed list to
     * consult.</p>
     *
     * <p>Header secrecy is decided by
     * {@link org.openintegrationengine.plugins.sentinel.server.alert.WebhookAlertSender#isSecretHeaderName(String)}
     * rather than by a copy of the pattern here, so the sender's just-in-time
     * decrypt and this redaction can never disagree about which headers are
     * ciphertext.</p>
     *
     * @param config a config document already known to be a JSON object
     * @return the slots present in this document; never {@code null}
     */
    private static List<SecretSlot> secretSlots(JsonNode config) {
        List<SecretSlot> slots = new ArrayList<>();
        for (String field : SNS_SECRET_FIELDS) {
            slots.add(new SecretSlot(null, field, SECRET_ACCESS_KEY.equals(field)));
        }
        JsonNode headers = config.get(HEADERS);
        if (headers != null && headers.isObject()) {
            Iterator<String> names = headers.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (WebhookAlertSender.isSecretHeaderName(name)) {
                    slots.add(new SecretSlot(HEADERS, name, true));
                }
            }
        }
        return slots;
    }

    /**
     * Normalizes the incoming config's {@linkplain #secretSlots(JsonNode)
     * sensitive slots} before validation/persist: each one that still carries
     * the {@link #REDACTED} marker is swapped for the stored value (the exact
     * inverse of {@link #redact(Action)}, which is what makes a GET → PUT
     * round trip lossless), and every newly supplied value in an
     * {@linkplain SecretSlot#encrypted encrypted} slot is encrypted. Newly
     * supplied values in the other slots — the SNS access key id and role ARN
     * — are persisted verbatim.
     *
     * <p>Because slots are matched by position in the document, renaming a
     * secret header while leaving its value as the marker is correctly
     * rejected: there is no stored value at the new name to keep, and
     * silently carrying the old header's token onto a differently-named
     * header would be worse than an error message.</p>
     *
     * <p>Runs before {@link #validate} so the per-type "required field"
     * checks see the post-substitution reality.</p>
     *
     * @param action   the incoming definition (its {@code configJson} is
     *                 rewritten in place)
     * @param existing the stored action on update, {@code null} on create
     * @throws IllegalArgumentException if a slot carries the marker but has
     *                                  no stored value behind it
     */
    private static void prepareSecrets(Action action, Action existing) {
        if (action == null || action.getConfigJson() == null || action.getConfigJson().isBlank()) {
            return;
        }
        JsonNode config;
        try {
            config = Json.mapper().readTree(action.getConfigJson());
        } catch (Exception e) {
            return; // validate() rejects the malformed JSON with a clear message
        }
        if (!config.isObject()) {
            return; // likewise rejected by validate()
        }

        JsonNode storedConfig = storedConfig(existing);
        boolean rewritten = false;
        for (SecretSlot slot : secretSlots(config)) {
            String incoming = slot.read(config);
            if (incoming.isBlank()) {
                continue;
            }
            if (REDACTED.equals(incoming)) {
                String stored = storedConfig == null ? "" : slot.read(storedConfig);
                if (stored.isBlank()) {
                    throw new IllegalArgumentException(slot.field
                            + " is the redaction marker but there is no stored value to keep");
                }
                slot.write(config, stored);
                rewritten = true;
            } else if (slot.encrypted) {
                slot.write(config, SettingsCrypto.encrypt(incoming));
                rewritten = true;
            }
            // Any other newly supplied value is persisted verbatim.
        }
        if (rewritten) {
            action.setConfigJson(Json.write(config));
        }
    }

    /**
     * The existing action's stored config as a JSON object — the source of
     * the "keep the stored value" side of the marker round trip — or
     * {@code null} on create and for an unparsable stored document (treated
     * as "nothing stored to keep").
     */
    private static JsonNode storedConfig(Action existing) {
        if (existing == null || existing.getConfigJson() == null || existing.getConfigJson().isBlank()) {
            return null;
        }
        try {
            JsonNode config = Json.mapper().readTree(existing.getConfigJson());
            return config.isObject() ? config : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ========== Validation ==========

    /**
     * Save-time validation: identity fields, condition shape, and per-type
     * config requirements. Like monitors, the dispatch path is deliberately
     * tolerant at send time — so this is the gate that keeps an EMAIL action
     * without recipients or an SNS action without a topic from ever being
     * stored.
     */
    private static void validate(Action action, Integer selfId) {
        if (action == null) {
            throw new IllegalArgumentException("Action body is required");
        }
        if (action.getName() == null || action.getName().isBlank()) {
            throw new IllegalArgumentException("Action name is required");
        }
        if (action.getActionType() == null) {
            throw new IllegalArgumentException("Action type is required");
        }
        if (action.getOperationMode() == null) {
            throw new IllegalArgumentException("Operation mode is required");
        }
        if (action.getRepeatIntervalSeconds() != null && action.getRepeatIntervalSeconds() < 60) {
            throw new IllegalArgumentException("repeatIntervalSeconds must be at least 60");
        }
        if (action.getMaxRepeats() != null && action.getMaxRepeats() < 0) {
            throw new IllegalArgumentException("maxRepeats must not be negative");
        }
        validateCondition(action);
        validateConfig(action);
        requireUniqueName(action.getName(), selfId);
    }

    /**
     * Validates the condition JSON (an ANDed array of {field, operator,
     * value} rows) and normalizes an absent condition to {@code "[]"} —
     * the column is NOT NULL, and an empty array is the canonical
     * "matches everything".
     */
    private static void validateCondition(Action action) {
        if (action.getConditionJson() == null || action.getConditionJson().isBlank()) {
            action.setConditionJson("[]");
            return;
        }
        JsonNode condition;
        try {
            condition = Json.mapper().readTree(action.getConditionJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("Action condition is not valid JSON", e);
        }
        if (!condition.isArray()) {
            throw new IllegalArgumentException("Action condition must be a JSON array of condition rows");
        }
    }

    /** Parses the config and applies the per-type required-field rules. */
    private static void validateConfig(Action action) {
        if (action.getConfigJson() == null || action.getConfigJson().isBlank()) {
            throw new IllegalArgumentException("Action config is required");
        }
        JsonNode config;
        try {
            config = Json.mapper().readTree(action.getConfigJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("Action config is not valid JSON", e);
        }
        if (!config.isObject()) {
            throw new IllegalArgumentException("Action config must be a JSON object");
        }

        switch (action.getActionType()) {
            case EMAIL:
                requireField(config, "to", "EMAIL actions require a 'to' recipient list");
                break;
            case CHANNEL:
                requireField(config, "channelId", "CHANNEL actions require a target 'channelId'");
                break;
            case SNS:
                requireField(config, "region", "SNS actions require a 'region'");
                requireField(config, "topicArn", "SNS actions require a 'topicArn'");
                validateSnsAuth(config);
                break;
            case WEBHOOK:
                // Delegated rather than reimplemented here: the URL scheme
                // rules, the allowed verbs, the timeout bounds and the legal
                // header names are all properties of the transport, and a
                // second copy in this class is a second thing to forget when
                // one of them changes. It throws IllegalArgumentException, so
                // it maps to a 400 exactly like the checks above.
                WebhookAlertSender.validateConfig(action.getName(), config);
                break;
        }
    }

    /**
     * Per-auth-type SNS requirements, mirroring the credential factory's
     * needs: STATIC cannot build without a key pair, ROLE cannot assume
     * without a role ARN. DEFAULT (instance profile / env chain) needs
     * nothing. Runs after {@link #prepareSecrets}, so on update the
     * kept-stored secret already satisfies the STATIC check.
     */
    private static void validateSnsAuth(JsonNode config) {
        String authType = config.hasNonNull("authType")
                ? config.get("authType").asText("").trim().toUpperCase(Locale.ROOT)
                : "DEFAULT";
        switch (authType) {
            case "DEFAULT":
                break;
            case "STATIC":
                requireField(config, "accessKeyId", "SNS STATIC auth requires an 'accessKeyId'");
                requireField(config, SECRET_ACCESS_KEY, "SNS STATIC auth requires a 'secretAccessKey'");
                break;
            case "ROLE":
                requireField(config, "assumeRoleArn", "SNS ROLE auth requires an 'assumeRoleArn'");
                break;
            default:
                throw new IllegalArgumentException("SNS authType must be one of DEFAULT, STATIC, ROLE");
        }
    }

    /** Requires a non-blank textual config field, with a per-field message. */
    private static void requireField(JsonNode config, String field, String message) {
        if (!config.hasNonNull(field) || config.get(field).asText("").isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Case-insensitive uniqueness pre-check — same rationale as
     * {@code MonitorService}: a clear 400 beats a raw UNIQUE-constraint
     * failure; the constraint remains the racy-path backstop.
     */
    private static void requireUniqueName(String name, Integer selfId) {
        for (Action other : ActionRepository.listActions(null)) {
            if (other.getName() != null && other.getName().equalsIgnoreCase(name.trim())
                    && !other.getId().equals(selfId)) {
                throw new IllegalArgumentException("An action named '" + other.getName() + "' already exists");
            }
        }
    }

    // ========== Plumbing ==========

    /** Fetches the stored action without redaction — internal callers only. */
    private static Action getUnredacted(int id) {
        Action action = ActionRepository.getAction(id);
        if (action == null) {
            throw new NoSuchElementException("No action with id " + id);
        }
        return action;
    }

    /**
     * Field-by-field copy. Written out longhand (no reflection/serialization
     * tricks) because the DTO is a plain bean with fourteen fields and the
     * copy must stay correct if a field is added — a compile break here on a
     * getter rename is a feature.
     */
    private static Action copyOf(Action action) {
        Action copy = new Action();
        copy.setId(action.getId());
        copy.setName(action.getName());
        copy.setDescription(action.getDescription());
        copy.setEnabled(action.isEnabled());
        copy.setActionType(action.getActionType());
        copy.setConditionJson(action.getConditionJson());
        copy.setOperationMode(action.getOperationMode());
        copy.setRepeatIntervalSeconds(action.getRepeatIntervalSeconds());
        copy.setMaxRepeats(action.getMaxRepeats());
        copy.setConfigJson(action.getConfigJson());
        copy.setCreatedBy(action.getCreatedBy());
        copy.setCreatedTime(action.getCreatedTime());
        copy.setUpdatedBy(action.getUpdatedBy());
        copy.setUpdatedTime(action.getUpdatedTime());
        return copy;
    }
}
