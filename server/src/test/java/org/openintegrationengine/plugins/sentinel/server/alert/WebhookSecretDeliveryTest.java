package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsCrypto;
import org.openintegrationengine.plugins.sentinel.server.service.WebhookSecrets;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

/** Mock HTTP client captures real requests; no sockets or external delivery. */
class WebhookSecretDeliveryTest {
    private static final String URL = "https://203.0.113.10/PATH_SECRET?token=QUERY_SECRET";
    private static final String TEMPLATE = "{\"routing_key\":\"BODY_SECRET\",\"message\":\"${message}\"}";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyAndEncryptedFieldsProduceTheSameActualRequest(boolean encrypted) throws Exception {
        HttpClient client = clientReturning(204, "");
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            Action action = action(encrypted);
            new WebhookAlertSender(client).send(action, new AlertEvent(), payload());
            ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
            verify(client).sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals(URL, request.getValue().uri().toString());
            assertEquals("Bearer HEADER_SECRET", request.getValue().headers().firstValue("Authorization").orElseThrow());
            assertEquals("application/json", request.getValue().headers().firstValue("Content-Type").orElseThrow());
            assertEquals("CUSTOM_SECRET", request.getValue().headers().firstValue("X-Auth").orElseThrow());
            assertEquals("{\"routing_key\":\"BODY_SECRET\",\"message\":\"quoted \\\"message\\\"\"}",
                    body(request.getValue()));
        }
    }

    @Test
    void responsesCannotEchoCredentialsIntoDispatchErrors() {
        HttpClient client = clientReturning(400, URL + " " + TEMPLATE + " Bearer HEADER_SECRET");
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            Exception failure = assertThrows(Exception.class,
                    () -> new WebhookAlertSender(client).send(action(true), new AlertEvent(), payload()));
            assertTrue(failure.getMessage().contains("HTTP 400"));
            assertNoSecrets(failure);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capabilityHostnamesAreNotExposedByHttpOrGuardFailures(boolean guardFails) {
        HttpClient client = clientReturning(400, "");
        try (MockedStatic<SettingsCrypto> crypto = crypto();
                MockedStatic<WebhookTargetGuard> guard = mockStatic(WebhookTargetGuard.class, CALLS_REAL_METHODS)) {
            Action action = action(true);
            ObjectNode config = Json.read(action.getConfigJson(), ObjectNode.class);
            String secretUrl = "https://host-secret.example.invalid/hook";
            config.put("url", WebhookSecrets.encrypt(secretUrl)); action.setConfigJson(Json.write(config));
            guard.when(() -> WebhookTargetGuard.assertTargetAllowed(eq("test"), any(java.net.URI.class), eq(false)))
                    .thenAnswer(i -> {
                        if (guardFails) throw new Exception("Cannot resolve host-secret.example.invalid");
                        return null;
                    });
            Exception failure = assertThrows(Exception.class,
                    () -> new WebhookAlertSender(client).send(action, new AlertEvent(), payload()));
            assertNoSecrets(failure);
            if (guardFails) verifyNoInteractions(client);
            else assertTrue(failure.getMessage().contains("HTTP 400"));
        }
    }

    @Test
    void transportExceptionDetailsAndCausesCannotExposeTheRequest() {
        HttpClient client = mock(HttpClient.class);
        doReturn(CompletableFuture.failedFuture(new IOException(URL + " BODY_SECRET HEADER_SECRET")))
                .when(client).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            Exception failure = assertThrows(Exception.class,
                    () -> new WebhookAlertSender(client).send(action(true), new AlertEvent(), payload()));
            assertTrue(failure.getMessage().contains("IOException"));
            assertNoSecrets(failure);
        }
    }

    @Test
    void synchronousClientExceptionsCannotExposeTheRequest() {
        HttpClient client = mock(HttpClient.class);
        doThrow(new IllegalArgumentException(URL + " BODY_SECRET HEADER_SECRET"))
                .when(client).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            Exception failure = assertThrows(Exception.class,
                    () -> new WebhookAlertSender(client).send(action(true), new AlertEvent(), payload()));
            assertNoSecrets(failure);
        }
    }

    @Test
    void requestBuilderRejectsIllegalDecryptedHeaderWithoutEchoingIt() {
        HttpClient client = mock(HttpClient.class);
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            Action action = action(true);
            ObjectNode config = Json.read(action.getConfigJson(), ObjectNode.class);
            ((ObjectNode) config.path("headers")).put("Authorization", "encrypted:HEADER_SECRET\u20ac");
            action.setConfigJson(Json.write(config));
            assertNoSecrets(assertThrows(Exception.class,
                    () -> new WebhookAlertSender(client).send(action, new AlertEvent(), payload())));
            verifyNoInteractions(client);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "bodyTemplate", "Authorization", "X-Auth"})
    void corruptEncryptedFieldsFailBeforeHttpWithoutLeakingCryptoErrors(String field) {
        HttpClient client = mock(HttpClient.class);
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            crypto.when(() -> SettingsCrypto.decrypt("bad"))
                    .thenThrow(new IllegalStateException("PATH_SECRET BODY_SECRET HEADER_SECRET"));
            Action action = action(true);
            ObjectNode config = Json.read(action.getConfigJson(), ObjectNode.class);
            if (field.equals("Authorization")) ((ObjectNode) config.path("headers")).put(field, "bad");
            else if (field.equals("X-Auth")) ((ObjectNode) config.path("headers")).put(field, "sentinel-secret:v1:bad");
            else config.put(field, "sentinel-secret:v1:bad");
            action.setConfigJson(Json.write(config));
            Exception failure = assertThrows(Exception.class,
                    () -> new WebhookAlertSender(client).send(action, new AlertEvent(), payload()));
            assertNoSecrets(failure);
            verifyNoInteractions(client);
        }
    }

    @Test
    void malformedStoredConfigDoesNotLeakParserInput() {
        Action action = new Action(); action.setName("corrupt");
        action.setConfigJson("{\"url\":\"PATH_SECRET\",\"bodyTemplate\": BODY_SECRET");
        Exception failure = assertThrows(Exception.class,
                () -> new WebhookAlertSender(mock(HttpClient.class)).send(action, new AlertEvent(), payload()));
        assertNoSecrets(failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", "bodyTemplate", "Authorization", "X-Auth"})
    void malformedSecretTypesNeverDropAuthenticationOrBroadenThePayload(String field) {
        HttpClient client = mock(HttpClient.class);
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            Action action = action(true);
            ObjectNode config = Json.read(action.getConfigJson(), ObjectNode.class);
            ObjectNode container = field.equals("Authorization") || field.equals("X-Auth")
                    ? (ObjectNode) config.path("headers") : config;
            container.putObject(field).put("corrupt", "BODY_SECRET HEADER_SECRET");
            action.setConfigJson(Json.write(config));
            assertNoSecrets(assertThrows(Exception.class,
                    () -> new WebhookAlertSender(client).send(action, new AlertEvent(), payload())));
            verifyNoInteractions(client);
        }
    }

    @Test
    void emptyDecryptedCredentialNeverDropsAuthentication() {
        HttpClient client = mock(HttpClient.class);
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            crypto.when(() -> SettingsCrypto.decrypt("encrypted:Bearer HEADER_SECRET")).thenReturn(null);
            assertNoSecrets(assertThrows(Exception.class,
                    () -> new WebhookAlertSender(client).send(action(true), new AlertEvent(), payload())));
            verifyNoInteractions(client);
        }
    }

    @Test
    void encryptedUrlStillPassesTheSameEgressGuard() {
        try (MockedStatic<SettingsCrypto> crypto = crypto()) {
            ObjectNode config = Json.mapper().createObjectNode();
            config.put("url", WebhookSecrets.encrypt("https://127.0.0.1/private"));
            assertThrows(IllegalArgumentException.class, () -> WebhookAlertSender.validateConfig("loopback", config));
            config.put("url", WebhookSecrets.encrypt("http://203.0.113.10/private"));
            assertThrows(IllegalArgumentException.class, () -> WebhookAlertSender.validateConfig("plaintext", config));
        }
    }

    private static MockedStatic<SettingsCrypto> crypto() {
        MockedStatic<SettingsCrypto> crypto = mockStatic(SettingsCrypto.class);
        crypto.when(() -> SettingsCrypto.encrypt(anyString())).thenAnswer(i -> "encrypted:" + i.getArgument(0));
        crypto.when(() -> SettingsCrypto.decrypt(anyString())).thenAnswer(i -> {
            String stored = i.getArgument(0);
            if (!stored.startsWith("encrypted:")) throw new IllegalStateException("Invalid ciphertext");
            return stored.substring("encrypted:".length());
        });
        return crypto;
    }

    private static Action action(boolean encrypted) {
        Action action = new Action(); action.setName("test"); action.setActionType(ActionType.WEBHOOK);
        ObjectNode config = Json.mapper().createObjectNode();
        config.put("url", encrypted ? WebhookSecrets.encrypt(URL) : URL);
        config.put("bodyTemplate", encrypted ? WebhookSecrets.encrypt(TEMPLATE) : TEMPLATE);
        config.putObject("headers").put("Authorization", "encrypted:Bearer HEADER_SECRET")
                .put("Content-Type", encrypted ? WebhookSecrets.encrypt("application/json") : "application/json")
                .put("X-Auth", encrypted ? WebhookSecrets.encrypt("CUSTOM_SECRET") : "CUSTOM_SECRET");
        action.setConfigJson(Json.write(config)); return action;
    }

    @SuppressWarnings("unchecked")
    private static HttpClient clientReturning(int status, String body) {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body);
        doReturn(CompletableFuture.completedFuture(response)).when(client)
                .sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return client;
    }

    private static AlertPayload payload() {
        return new AlertPayload(1, 1, "test", MonitorType.INACTIVITY, "channel", "channel", null,
                Severity.WARNING, "PROBLEM", "quoted \"message\"", Instant.EPOCH, "{}");
    }

    private static void assertNoSecrets(Throwable failure) {
        assertFalse(failure.getMessage().toUpperCase(java.util.Locale.ROOT).contains("SECRET"));
        assertNull(failure.getCause(), "Do not retain a secret-bearing throwable for downstream logging");
    }

    private static String body(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> complete = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { while (buffer.hasRemaining()) bytes.write(buffer.get()); }
            public void onError(Throwable error) { complete.completeExceptionally(error); }
            public void onComplete() { complete.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return complete.get(1, TimeUnit.SECONDS);
    }
}
