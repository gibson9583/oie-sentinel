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
import java.util.NoSuchElementException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionTestResult;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;

/**
 * Business rules for notification-action CRUD, including the secret
 * round-trip that keeps the SNS {@code secretAccessKey} out of every read
 * path.
 *
 * <p><b>The secret lifecycle:</b> a plaintext secret arrives exactly once —
 * in the create/update body — and is encrypted with the engine's encryptor
 * ({@link SettingsCrypto}) before it is persisted. Every read
 * ({@link #list()}, {@link #get(int)}, and the entity echoed back by
 * create/update) returns a {@linkplain #redact(Action) redacted copy} whose
 * secret is replaced by the {@link #REDACTED} marker. On update, a client
 * that never saw the plaintext simply sends the marker back, which means
 * "keep the stored secret" — so the plaintext never round-trips through the
 * browser, and the generic REST audit (which excludes bodies anyway) can
 * never capture it.</p>
 *
 * <p>Errors follow the plugin-wide convention: {@link IllegalArgumentException}
 * → 400, {@link NoSuchElementException} → 404 (mapped in the servlet).</p>
 */
public final class ActionService {

    /**
     * The wire marker standing in for a stored secret: eight bullet
     * characters, chosen to render like a masked password field. Receiving
     * this exact value on update means "keep the existing stored secret".
     */
    public static final String REDACTED = "••••••••";

    /** Config key holding the SNS secret inside {@code configJson}. */
    private static final String SECRET_FIELD = "secretAccessKey";

    private ActionService() {
    }

    /**
     * Lists every action as redacted copies — the grid never needs the
     * secret, so it never receives it.
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
     *                                  {@link #REDACTED} secret on create
     *                                  (there is no stored secret to keep)
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
     * Validates and updates an action. A {@link #REDACTED} secret in the
     * incoming config is replaced with the stored (already encrypted) secret
     * before validation, so editing an SNS action without re-entering its
     * key "just works"; any other non-blank secret is treated as a new
     * plaintext and encrypted. Creation stamps are preserved from the stored
     * row.
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
     * channel dispatch, or SNS publish with a synthetic payload. The
     * <em>unredacted</em> stored action is handed to the dispatcher: its
     * secret is the encrypted-at-rest form, which the SNS sender decrypts
     * just-in-time.
     *
     * @param id database id of the action to test
     * @return the delivery outcome (success flag + human message)
     * @throws NoSuchElementException if no action has that id
     */
    public static ActionTestResult test(int id) {
        return ActionDispatcher.sendTest(getUnredacted(id));
    }

    /**
     * Returns a deep-enough copy of an action with any config secret
     * replaced by {@link #REDACTED}. Always a copy — redacting the caller's
     * instance in place would corrupt the encrypted secret of an entity
     * that is about to be persisted or dispatched.
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
            if (config.isObject() && config.hasNonNull(SECRET_FIELD)
                    && !config.get(SECRET_FIELD).asText("").isBlank()) {
                ((ObjectNode) config).put(SECRET_FIELD, REDACTED);
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
     * Normalizes the incoming config's secret before validation/persist:
     * substitutes the stored secret for the {@link #REDACTED} marker on
     * update, or encrypts a newly supplied plaintext. Runs before
     * {@link #validate} so the STATIC-auth "secret required" check sees the
     * post-substitution reality.
     *
     * @param action   the incoming definition (its {@code configJson} is
     *                 rewritten in place)
     * @param existing the stored action on update, {@code null} on create
     */
    private static void prepareSecrets(Action action, Action existing) {
        if (action == null || action.getActionType() != ActionType.SNS
                || action.getConfigJson() == null || action.getConfigJson().isBlank()) {
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
        String secret = config.hasNonNull(SECRET_FIELD) ? config.get(SECRET_FIELD).asText("") : "";
        if (secret.isBlank()) {
            return;
        }

        if (REDACTED.equals(secret)) {
            String stored = storedSecret(existing);
            if (stored == null) {
                throw new IllegalArgumentException(
                        "secretAccessKey is the redaction marker but there is no stored secret to keep");
            }
            ((ObjectNode) config).put(SECRET_FIELD, stored);
        } else {
            ((ObjectNode) config).put(SECRET_FIELD, SettingsCrypto.encrypt(secret));
        }
        action.setConfigJson(Json.write(config));
    }

    /** Extracts the stored (encrypted) secret from an existing action's config, or {@code null}. */
    private static String storedSecret(Action existing) {
        if (existing == null || existing.getConfigJson() == null || existing.getConfigJson().isBlank()) {
            return null;
        }
        try {
            JsonNode config = Json.mapper().readTree(existing.getConfigJson());
            if (config.isObject() && config.hasNonNull(SECRET_FIELD)) {
                String stored = config.get(SECRET_FIELD).asText("");
                return stored.isBlank() ? null : stored;
            }
        } catch (Exception e) {
            // treat unparsable stored config as "no stored secret"
        }
        return null;
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
                requireField(config, SECRET_FIELD, "SNS STATIC auth requires a 'secretAccessKey'");
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
