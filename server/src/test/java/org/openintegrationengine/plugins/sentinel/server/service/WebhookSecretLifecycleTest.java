package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertLifecycleTransaction;
import java.util.function.Consumer;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;

/** Exercises real CRUD/redaction/import logic with an isolated in-memory repository boundary. */
class WebhookSecretLifecycleTest {
    private static final String URL = "https://203.0.113.10/hooks/PATH_SECRET?token=QUERY_SECRET";
    private static final String TEMPLATE = "{\"routing_key\":\"BODY_SECRET\",\"text\":\"${message}\"}";
    private final Map<Integer, Action> rows = new LinkedHashMap<>();
    private MockedStatic<AlertLifecycleTransaction> transaction;
    private MockedStatic<ActionRepository> repository;
    private MockedStatic<SettingsCrypto> crypto;
    private MockedStatic<SentinelAuditLog> audit;

    @BeforeEach
    void isolatePersistenceAndEngineEncryption() {
        transaction = mockStatic(AlertLifecycleTransaction.class);
        transaction.when(() -> AlertLifecycleTransaction.execute(any(Consumer.class))).thenAnswer(i -> {
            List<Runnable> afterCommit = new ArrayList<>();
            i.<Consumer<List<Runnable>>>getArgument(0).accept(afterCommit);
            afterCommit.forEach(Runnable::run);
            return null;
        });
        repository = mockStatic(ActionRepository.class);
        crypto = mockStatic(SettingsCrypto.class);
        audit = mockStatic(SentinelAuditLog.class);
        crypto.when(() -> SettingsCrypto.encrypt(anyString())).thenAnswer(i -> encrypted(i.getArgument(0)));
        crypto.when(() -> SettingsCrypto.decrypt(anyString())).thenAnswer(i -> decrypted(i.getArgument(0)));
        repository.when(() -> ActionRepository.getAction(anyInt())).thenAnswer(i -> rows.get(i.getArgument(0)));
        repository.when(() -> ActionRepository.listActions(null)).thenAnswer(i -> new ArrayList<>(rows.values()));
        repository.when(() -> ActionRepository.insertAction(any(Action.class))).thenAnswer(i -> {
            Action action = i.getArgument(0); action.setId(rows.size() + 1); rows.put(action.getId(), action); return action;
        });
        repository.when(() -> ActionRepository.updateAction(any(Action.class))).thenAnswer(i -> {
            Action action = i.getArgument(0); rows.put(action.getId(), action); return null;
        });
    }

    @AfterEach
    void closeMocks() {
        transaction.close(); audit.close(); crypto.close(); repository.close();
    }

    @Test
    void createGetListAndUnrelatedEditProtectAllKnownCredentialLocations() {
        Action response = ActionService.create(action(URL, TEMPLATE), 42);
        assertMasked(response);
        assertMasked(ActionService.get(1));
        assertMasked(ActionService.list().get(0));
        String stored = rows.get(1).getConfigJson();
        assertNoPlainSecrets(stored);
        JsonNode config = config(rows.get(1));
        assertTrue(WebhookSecrets.isEncrypted(config.path("url").asText()));
        assertTrue(WebhookSecrets.isEncrypted(config.path("bodyTemplate").asText()));
        assertTrue(WebhookSecrets.isEncrypted(config.path("headers").path("X-Auth").asText()));
        assertEquals("application/json", WebhookSecrets.read(config.path("headers").path("Content-Type").asText()));
        assertEquals(URL, WebhookSecrets.read(config.path("url").asText()));
        assertEquals(TEMPLATE, WebhookSecrets.read(config.path("bodyTemplate").asText()));

        response.setDescription("Unrelated edit");
        assertMasked(ActionService.update(1, response, 42));
        assertEquals(stored, rows.get(1).getConfigJson(), "kept secrets must not be encrypted twice");
    }

    @Test
    void replacementAndExplicitTemplateRemovalHaveDistinctSemantics() {
        Action response = ActionService.create(action(URL, TEMPLATE), 42);
        ObjectNode config = (ObjectNode) config(response);
        String replacement = "https://203.0.113.10/hooks/REPLACEMENT_SECRET";
        config.put("url", replacement); config.remove("bodyTemplate");
        response.setConfigJson(Json.write(config));
        ActionService.update(1, response, 42);
        assertEquals(replacement, WebhookSecrets.read(config(rows.get(1)).path("url").asText()));
        assertFalse(config(rows.get(1)).has("bodyTemplate"));
        assertNoPlainSecrets(rows.get(1).getConfigJson());
    }

    @Test
    void legacyPlaintextIsMaskedImmediatelyAndUpgradedOnSave() {
        Action legacy = action(URL, TEMPLATE); legacy.setId(1);
        ObjectNode legacyConfig = (ObjectNode) config(legacy);
        ((ObjectNode) legacyConfig.path("headers")).put("Authorization", encrypted("HEADER_SECRET"));
        legacy.setConfigJson(Json.write(legacyConfig)); rows.put(1, legacy);
        Action response = ActionService.get(1);
        assertMasked(response);
        assertEquals(URL, WebhookSecrets.read(config(legacy).path("url").asText()));
        ActionService.update(1, response, 42);
        assertNoPlainSecrets(rows.get(1).getConfigJson());
        assertEquals(URL, WebhookSecrets.read(config(rows.get(1)).path("url").asText()));
        assertEquals(TEMPLATE, WebhookSecrets.read(config(rows.get(1)).path("bodyTemplate").asText()));
    }

    @Test
    void exportRetryKeepsSecretsAndFreshImportCannotSilentlyDropThem() {
        ActionService.create(action(URL, TEMPLATE), 42);
        try (MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
                MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class)) {
            JsonNode document = ExportImportService.export();
            assertNoPlainSecrets(document.toString());
            String saved = rows.get(1).getConfigJson();
            ExportImportService.ImportResult sameServer = ExportImportService.importDocument(document, 42);
            assertEquals(1, sameServer.getSkipped());
            assertEquals(0, sameServer.getUpdated());
            assertEquals(saved, rows.get(1).getConfigJson());

            rows.clear();
            ExportImportService.ImportResult freshServer = ExportImportService.importDocument(document, 42);
            assertEquals(0, freshServer.getCreated());
            assertEquals(1, freshServer.getSkipped());
            assertTrue(rows.isEmpty());
            assertTrue(freshServer.getEntries().get(0).getSecretsRequired().containsAll(
                    List.of("url", "bodyTemplate", "headers.Authorization")));
            assertNoPlainSecrets(Json.write(freshServer));
        }
    }

    @Test
    void missingTemplateSecretDoesNotBroadenAnImportedPayload() {
        Action existing = ActionService.create(action(URL, null), 42);
        try (MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
                MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class)) {
            ObjectNode document = ExportImportService.export();
            ObjectNode imported = (ObjectNode) document.path("actions").get(0);
            ObjectNode conf = (ObjectNode) config(existing);
            conf.put("bodyTemplate", ActionService.REDACTED);
            imported.put("configJson", Json.write(conf));
            imported.put("description", "Must not partially apply");
            assertEquals(1, ExportImportService.importDocument(document, 42).getSkipped());
            assertNull(rows.get(1).getDescription());
            repository.verify(() -> ActionRepository.updateAction(any()), never());
        }
    }

    @Test
    void suppliedSecretsCreateAnImportedActionAndMarkersCannotInventSecrets() {
        try (MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class)) {
            ObjectNode document = Json.mapper().createObjectNode(); document.put("schemaVersion", 1);
            document.putArray("actions").add(Json.mapper().valueToTree(action(URL, TEMPLATE)));
            assertEquals(1, ExportImportService.importDocument(document, 42).getCreated());
            assertNoPlainSecrets(rows.get(1).getConfigJson());
        }
        Action marker = action(ActionService.REDACTED, null); marker.setName("new marker");
        assertThrows(IllegalArgumentException.class, () -> ActionService.create(marker, 42));
        assertEquals(1, rows.size());
    }

    @Test
    void encryptionAndCorruptCiphertextFailuresDoNotPersistOrExposeInputs() {
        crypto.when(() -> SettingsCrypto.encrypt(URL)).thenThrow(new IllegalStateException("encrypt PATH_SECRET failed"));
        IllegalStateException encryptionFailure = assertThrows(IllegalStateException.class,
                () -> ActionService.create(action(URL, TEMPLATE), 42));
        assertNoPlainSecrets(encryptionFailure.getMessage());
        assertNull(encryptionFailure.getCause());
        assertTrue(rows.isEmpty());
        crypto.when(() -> SettingsCrypto.decrypt("corrupt")).thenThrow(new IllegalStateException("ciphertext SECRET"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> WebhookSecrets.read("sentinel-secret:v1:corrupt"));
        assertFalse(failure.getMessage().contains("SECRET"));
        assertNull(failure.getCause());
    }

    @Test
    void malformedLegacyConfigAndNonTextSecretValuesAreRedactedFailClosed() {
        for (String bad : List.of("{\"url\":\"PATH_SECRET", "[\"PATH_SECRET\"]", "null",
                "{\"headers\":\"HEADER_SECRET\"}", "{\"headers\":[\"HEADER_SECRET\"]}")) {
            Action action = action(URL, null); action.setConfigJson(bad);
            assertEquals("{}", ActionService.redact(action).getConfigJson());
        }
        Action action = action(URL, null);
        action.setConfigJson("{\"url\":{\"secret\":\"PATH_SECRET\"},\"bodyTemplate\":[\"BODY_SECRET\"]}");
        assertNoPlainSecrets(ActionService.redact(action).getConfigJson());
    }

    @Test
    void invalidConfigSaveDoesNotExposeParserInputOrPersist() {
        Action action = action(URL, null);
        action.setConfigJson("{\"url\":\"PATH_SECRET\",\"bodyTemplate\": BODY_SECRET");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ActionService.create(action, 42));
        assertNoPlainSecrets(failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(rows.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "bodyTemplate", "Authorization", "X-Auth"})
    void nonTextSecretsAreRejectedBeforeEncryptionInsteadOfCoerced(String field) {
        Action action = action(URL, TEMPLATE);
        ObjectNode config = (ObjectNode) config(action);
        ObjectNode container = field.equals("Authorization") || field.equals("X-Auth")
                ? (ObjectNode) config.path("headers") : config;
        container.put(field, 123456);
        action.setConfigJson(Json.write(config));
        assertThrows(IllegalArgumentException.class, () -> ActionService.create(action, 42));
        assertTrue(rows.isEmpty());
        crypto.verify(() -> SettingsCrypto.encrypt("123456"), never());
    }

    @Test
    void markerCannotMoveAStoredHeaderSecretToADifferentName() {
        Action response = ActionService.create(action(URL, TEMPLATE), 42);
        ObjectNode conf = (ObjectNode) config(response);
        ObjectNode headers = (ObjectNode) conf.path("headers");
        headers.set("X-Different", headers.remove("X-Auth"));
        response.setConfigJson(Json.write(conf));
        assertThrows(IllegalArgumentException.class, () -> ActionService.update(1, response, 42));
        repository.verify(() -> ActionRepository.updateAction(any()), never());
    }

    private static Action action(String url, String body) {
        Action action = new Action(); action.setName("Webhook"); action.setActionType(ActionType.WEBHOOK);
        action.setEnabled(true); action.setOperationMode(OperationMode.ON_PROBLEM); action.setConditionJson("[]");
        ObjectNode config = Json.mapper().createObjectNode(); config.put("url", url);
        if (body != null) config.put("bodyTemplate", body);
        config.putObject("headers").put("Authorization", "HEADER_SECRET").put("X-Auth", "CUSTOM_SECRET")
                .put("Content-Type", "application/json");
        action.setConfigJson(Json.write(config)); return action;
    }

    private static JsonNode config(Action action) { return Json.read(action.getConfigJson(), JsonNode.class); }
    private static void assertMasked(Action action) {
        JsonNode config = config(action);
        assertEquals(ActionService.REDACTED, config.path("url").asText());
        assertEquals(ActionService.REDACTED, config.path("bodyTemplate").asText());
        assertEquals(ActionService.REDACTED, config.path("headers").path("Authorization").asText());
        assertEquals(ActionService.REDACTED, config.path("headers").path("X-Auth").asText());
        assertEquals(ActionService.REDACTED, config.path("headers").path("Content-Type").asText());
        assertNoPlainSecrets(action.getConfigJson());
    }
    private static void assertNoPlainSecrets(String text) {
        for (String secret : List.of("PATH_SECRET", "QUERY_SECRET", "BODY_SECRET", "HEADER_SECRET", "CUSTOM_SECRET", "REPLACEMENT_SECRET"))
            assertFalse(text.contains(secret), "Leaked " + secret);
    }
    private static String encrypted(String value) {
        return "cipher:" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String decrypted(String value) {
        if (!value.startsWith("cipher:")) throw new IllegalStateException("Invalid synthetic ciphertext");
        return new String(Base64.getDecoder().decode(value.substring(7)), StandardCharsets.UTF_8);
    }
}
