package org.openintegrationengine.plugins.sentinel.server.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.client.core.ExtensionOperation;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.client.core.api.servlets.UserServletInterface;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.User;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.UserController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelGroupInfo;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelInfo;
import org.openintegrationengine.plugins.sentinel.shared.model.TagInfo;

class CoreLookupAuthorizationTest {
    private static final String ALLOWED = "allowed";
    private static final String RESTRICTED = "restricted";

    @Test
    void monitoringPermissionDoesNotEnumerateTheUserDirectory() {
        PermissionServlet servlet = servlet();
        Operation original = servlet.currentOperation();
        try (MockedStatic<ControllerFactory> host = mockStatic(ControllerFactory.class)) {
            assertStatus(403, servlet::getCoreUsers);
            assertEquals(List.of("getAllUsers"), servlet.checked);
            assertSame(original, servlet.currentOperation());
            host.verifyNoInteractions();
        }
    }

    @Test
    void manageMonitoringIsNotASubstituteForCoreUserPermission() {
        PermissionServlet servlet = servlet();
        servlet.allowedOperations = Set.of("OIE Sentinel#sentinelGetCoreUsers",
                "OIE Sentinel#sentinelCreateMonitor", "OIE Sentinel#sentinelExportConfiguration");
        try (MockedStatic<ControllerFactory> host = mockStatic(ControllerFactory.class)) {
            assertStatus(403, servlet::getCoreUsers);
            assertEquals(List.of("getAllUsers"), servlet.checked);
            host.verifyNoInteractions();
        }
    }

    @Test
    void coreUserPermissionAloneDoesNotPassTheOuterMonitoringOperation() {
        PermissionServlet servlet = servlet();
        servlet.allowedOperations = Set.of("getAllUsers");
        try (MockedStatic<ControllerFactory> host = mockStatic(ControllerFactory.class)) {
            // Model the engine invocation order: outer annotated permission first.
            assertStatus(403, () -> { servlet.checkUserAuthorized(); servlet.getCoreUsers(); });
            assertEquals(List.of("OIE Sentinel#sentinelGetCoreUsers"), servlet.checked);
            host.verifyNoInteractions();
        }
    }

    @Test
    void authorizedUserLookupChecksTheExactCoreOperationAndReturnsOnlyDisplayFields() throws Exception {
        try (UserFixture f = new UserFixture()) {
            f.servlet.allowedOperations = Set.of("getAllUsers");
            Operation original = f.servlet.currentOperation();
            JsonNode result = tree(f.servlet.getCoreUsers());
            assertEquals(2, result.size());
            assertEquals(99, result.get(1).path("userId").asInt());
            assertEquals("synthetic-admin", result.get(1).path("username").asText());
            assertEquals(2, result.get(1).size());
            assertFalse(result.toString().contains("synthetic-secret"));
            assertEquals(List.of("getAllUsers"), f.servlet.checked);
            assertSame(original, f.servlet.currentOperation());
            assertEquals(OperationUtil.getOperation(UserServletInterface.class, "getAllUsers").getName(),
                    f.servlet.checked.get(0));
            verify(f.users).getAllUsers();
        }
    }

    @Test
    void userPermissionIsCheckedAgainAfterRevocationAndOnRetries() throws Exception {
        try (UserFixture f = new UserFixture()) {
            f.servlet.allowedOperations = Set.of("getAllUsers");
            assertEquals(2, tree(f.servlet.getCoreUsers()).size());
            Operation original = f.servlet.currentOperation();
            f.servlet.allowedOperations = Set.of();
            assertStatus(403, f.servlet::getCoreUsers);
            assertStatus(403, f.servlet::getCoreUsers);
            assertEquals(List.of("getAllUsers", "getAllUsers", "getAllUsers"), f.servlet.checked);
            assertSame(original, f.servlet.currentOperation());
            verify(f.users, times(1)).getAllUsers();
        }
    }

    @Test
    void authorizationBackendFailureRestoresOperationAndDoesNotReachUserController() {
        PermissionServlet servlet = servlet();
        Operation original = servlet.currentOperation();
        doThrow(new IllegalStateException("synthetic-secret authorization failure"))
                .when(servlet).checkUserAuthorized();
        try (MockedStatic<ControllerFactory> host = mockStatic(ControllerFactory.class)) {
            MirthApiException failure = assertThrows(MirthApiException.class, servlet::getCoreUsers);
            assertEquals(500, failure.getResponse().getStatus());
            assertFalse(String.valueOf(failure.getResponse().getEntity()).contains("synthetic-secret"));
            assertSame(original, servlet.currentOperation());
            host.verifyNoInteractions();
        }
    }

    @Test
    void directoryBackendFailureRestoresOperationAndExposesNoPartialUserList() throws Exception {
        try (UserFixture f = new UserFixture()) {
            f.servlet.allowedOperations = Set.of("getAllUsers");
            Operation original = f.servlet.currentOperation();
            when(f.users.getAllUsers()).thenThrow(new IllegalStateException("synthetic-secret directory failure"));
            MirthApiException failure = assertThrows(MirthApiException.class, f.servlet::getCoreUsers);
            assertEquals(500, failure.getResponse().getStatus());
            assertFalse(String.valueOf(failure.getResponse().getEntity()).contains("synthetic-secret"));
            assertSame(original, f.servlet.currentOperation());
            assertEquals(List.of("getAllUsers"), f.servlet.checked);
        }
    }

    @Test
    void interleavedRequestPermissionsAndOperationsStayIndependent() throws Exception {
        try (UserFixture f = new UserFixture()) {
            PermissionServlet denied = servlet();
            Operation deniedOriginal = denied.currentOperation();
            Operation allowedOriginal = f.servlet.currentOperation();
            f.servlet.allowedOperations = Set.of("getAllUsers");
            assertStatus(403, denied::getCoreUsers);
            assertEquals(2, tree(f.servlet.getCoreUsers()).size());
            assertStatus(403, denied::getCoreUsers);
            assertSame(deniedOriginal, denied.currentOperation());
            assertSame(allowedOriginal, f.servlet.currentOperation());
            verify(f.users, times(1)).getAllUsers();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"groups", "tags"})
    void scopePickersForwardAuthorizedChannelsToScopeResolver(String endpoint) throws Exception {
        try (ScopeFixture f = new ScopeFixture()) {
            JsonNode result = tree(f.read(endpoint));
            assertEquals(1, result.size());
            assertEquals("visible", result.get(0).path("id").asText());
            assertEquals(ALLOWED, result.get(0).path("channelIds").get(0).asText());
            assertFalse(result.toString().contains(RESTRICTED));
            if (endpoint.equals("groups")) f.scopes.verify(() -> ScopeResolver.listGroups(Set.of(ALLOWED)));
            else f.scopes.verify(() -> ScopeResolver.listTags(Set.of(ALLOWED)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"groups", "tags"})
    void emptyAndRevokedChannelSetsCannotEnumerateScopePickers(String endpoint) throws Exception {
        try (ScopeFixture f = new ScopeFixture()) {
            assertEquals(1, tree(f.read(endpoint)).size());
            f.servlet.allowedChannels = Set.of();
            assertTrue(tree(f.read(endpoint)).isEmpty());
            assertTrue(tree(f.read(endpoint)).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"groups", "tags"})
    void unrestrictedCallerKeepsFullScopePickerInventory(String endpoint) throws Exception {
        try (ScopeFixture f = new ScopeFixture()) {
            f.servlet.restricted = false;
            assertEquals(5, tree(f.read(endpoint)).size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"groups", "tags"})
    void scopeLookupFailureReturnsOpaqueErrorInsteadOfPartialInventory(String endpoint) {
        try (ScopeFixture f = new ScopeFixture()) {
            if (endpoint.equals("groups")) {
                f.scopes.when(() -> ScopeResolver.listGroups(Set.of(ALLOWED))).thenThrow(new IllegalStateException("synthetic-secret groups"));
            } else {
                f.scopes.when(() -> ScopeResolver.listTags(Set.of(ALLOWED))).thenThrow(new IllegalStateException("synthetic-secret tags"));
            }
            MirthApiException failure = assertThrows(MirthApiException.class, () -> f.read(endpoint));
            assertEquals(500, failure.getResponse().getStatus());
            assertFalse(String.valueOf(failure.getResponse().getEntity()).contains("synthetic-secret"));
        }
    }

    private static PermissionServlet servlet() {
        PermissionServlet servlet = mock(PermissionServlet.class, CALLS_REAL_METHODS);
        servlet.allowedOperations = Set.of("OIE Sentinel#sentinelGetCoreUsers");
        servlet.allowedChannels = Set.of(ALLOWED);
        servlet.checked = new ArrayList<>();
        servlet.restricted = true;
        servlet.setOperation(new ExtensionOperation(SentinelServletInterface.PLUGIN_POINT,
                OperationUtil.getOperation(SentinelServletInterface.class, "getCoreUsers")));
        return servlet;
    }

    public static class PermissionServlet extends SentinelServlet {
        Set<String> allowedOperations;
        Set<String> allowedChannels;
        List<String> checked;
        boolean restricted;
        public PermissionServlet() { super(null, null); }
        @Override protected int getCurrentUserId() { return 42; }
        @Override protected boolean isUserAuthorized() {
            checked.add(operation.getName());
            return allowedOperations.contains(operation.getName());
        }
        @Override protected boolean doesUserHaveChannelRestrictions() { return restricted; }
        @Override protected boolean isChannelRedacted(String id) { return restricted && !allowedChannels.contains(id); }
        @Override protected Set<String> redactChannelIds(Set<String> ids) {
            Set<String> result = new HashSet<>(ids); result.retainAll(allowedChannels); return result;
        }
        Operation currentOperation() { return operation; }
    }

    private static class UserFixture implements AutoCloseable {
        final PermissionServlet servlet = servlet();
        final MockedStatic<ControllerFactory> host = mockStatic(ControllerFactory.class);
        final UserController users = mock(UserController.class);
        UserFixture() throws Exception {
            ControllerFactory factory = mock(ControllerFactory.class);
            host.when(ControllerFactory::getFactory).thenReturn(factory);
            when(factory.createUserController()).thenReturn(users);
            User reader = new User(); reader.setId(42); reader.setUsername("synthetic-reader");
            User admin = new User(); admin.setId(99); admin.setUsername("synthetic-admin");
            admin.setEmail("synthetic-secret@example.invalid");
            when(users.getAllUsers()).thenReturn(List.of(reader, admin));
        }
        @Override public void close() { host.close(); }
    }

    private static class ScopeFixture implements AutoCloseable {
        final PermissionServlet servlet = servlet();
        final MockedStatic<ScopeResolver> scopes = mockStatic(ScopeResolver.class);
        ScopeFixture() {
            ChannelInfo allowed = new ChannelInfo(); allowed.setChannelId(ALLOWED);
            ChannelInfo restricted = new ChannelInfo(); restricted.setChannelId(RESTRICTED);
            scopes.when(ScopeResolver::listChannels).thenReturn(List.of(allowed, restricted));
            scopes.when(() -> ScopeResolver.listGroups(Set.of(ALLOWED))).thenReturn(List.of(group("visible", List.of(ALLOWED))));
            scopes.when(() -> ScopeResolver.listTags(Set.of(ALLOWED))).thenReturn(List.of(tag("visible", List.of(ALLOWED))));
            scopes.when(() -> ScopeResolver.listGroups(Set.of())).thenReturn(List.of());
            scopes.when(() -> ScopeResolver.listTags(Set.of())).thenReturn(List.of());
            scopes.when(() -> ScopeResolver.listGroups(null)).thenReturn(List.of(
                    group("visible", List.of(ALLOWED)), group("restricted", List.of(RESTRICTED)),
                    group("mixed", List.of(ALLOWED, RESTRICTED)), group("empty", List.of()), group("unknown", null)));
            scopes.when(() -> ScopeResolver.listTags(null)).thenReturn(List.of(
                    tag("visible", List.of(ALLOWED)), tag("restricted", List.of(RESTRICTED)),
                    tag("mixed", List.of(ALLOWED, RESTRICTED)), tag("empty", List.of()), tag("unknown", null)));
        }
        String read(String endpoint) { return endpoint.equals("groups") ? servlet.getCoreChannelGroups() : servlet.getCoreTags(); }
        @Override public void close() { scopes.close(); }
        static ChannelGroupInfo group(String id, List<String> ids) {
            ChannelGroupInfo value = new ChannelGroupInfo(); value.setId(id); value.setName(id); value.setChannelIds(ids); return value;
        }
        static TagInfo tag(String id, List<String> ids) {
            TagInfo value = new TagInfo(); value.setId(id); value.setName(id); value.setChannelIds(ids); return value;
        }
    }

    private static JsonNode tree(String value) throws Exception { return Json.mapper().readTree(value); }
    private static void assertStatus(int status, org.junit.jupiter.api.function.Executable request) {
        assertEquals(status, assertThrows(MirthApiException.class, request).getResponse().getStatus());
    }
}
