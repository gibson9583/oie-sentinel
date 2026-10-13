/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mirth.connect.client.core.ControllerException;
import com.mirth.connect.client.core.ExtensionOperation;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.User;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.AuthorizationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.UserController;
import java.lang.reflect.Field;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.service.ProblemService;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;

/** Uses the real MirthServlet authorization and exception-wrapping code. */
class EngineAuthorizationFailureTest {
    @Test
    void wrappedEngineAuthorizationFailureMustNotSerializeControllerDetails() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.auth.isUserAuthorized(eq(42), any(), anyMap(), anyString(), eq(true)))
                    .thenAnswer(call -> {
                        Operation op = call.getArgument(1);
                        if (op.getName().equals("getAllUsers")) {
                            throw new ControllerException("SYNTHETIC_PRIVATE_AUTH_DATABASE_DETAIL");
                        }
                        return true;
                    });
            f.servlet.checkUserAuthorized(); // The real outer operation succeeds.
            MirthApiException failure = assertThrows(MirthApiException.class, f.servlet::getCoreUsers);
            Object entity = failure.getResponse().getEntity();
            System.out.println("ENGINE_AUTH_FAILURE status=" + failure.getResponse().getStatus()
                    + " entityClass=" + entity.getClass().getName() + " entity=" + entity);
            assertEquals(500, failure.getResponse().getStatus());
            assertSame(f.original, f.servlet.currentOperation());
            verifyNoInteractions(f.users);
            assertFalse(String.valueOf(entity).contains("SYNTHETIC_PRIVATE"),
                    "A wrapped engine ControllerException reaches the HTTP response with internal details");
            assertInstanceOf(String.class, entity);
            assertTrue(entity.toString().matches("Sentinel operation failed \\(ref: [0-9a-f-]{36}\\)"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverErrorResponsesAreSanitizedEvenWhenTheirEntityIsText(int status) throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.auth.isUserAuthorized(eq(42), any(), anyMap(), anyString(), eq(true)))
                    .thenAnswer(call -> {
                        Operation op = call.getArgument(1);
                        if (op.getName().equals("getAllUsers")) {
                            throw new MirthApiException(Response.status(status)
                                    .entity("SYNTHETIC_PRIVATE_AUTH_BACKEND_RESPONSE").build());
                        }
                        return true;
                    });
            f.servlet.checkUserAuthorized();
            MirthApiException failure = assertThrows(MirthApiException.class, f.servlet::getCoreUsers);
            assertEquals(500, failure.getResponse().getStatus());
            assertFalse(String.valueOf(failure.getResponse().getEntity()).contains("SYNTHETIC_PRIVATE"));
            assertSame(f.original, f.servlet.currentOperation());
            verifyNoInteractions(f.users);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404, 429})
    void nonServerErrorsKeepTheirStatusAndResponse(int status) throws Exception {
        try (Fixture f = new Fixture()) {
            MirthApiException expected = new MirthApiException(Response.status(status)
                    .entity("Synthetic client-facing reason").build());
            when(f.auth.isUserAuthorized(eq(42), any(), anyMap(), anyString(), eq(true)))
                    .thenAnswer(call -> {
                        Operation op = call.getArgument(1);
                        if (op.getName().equals("getAllUsers")) throw expected;
                        return true;
                    });
            f.servlet.checkUserAuthorized();
            assertSame(expected, assertThrows(MirthApiException.class, f.servlet::getCoreUsers));
            assertSame(f.original, f.servlet.currentOperation());
            verifyNoInteractions(f.users);
        }
    }

    @Test
    void retryAfterBackendFailureRechecksPermissionAndRestoresOperation() throws Exception {
        try (Fixture f = new Fixture()) {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            when(f.auth.isUserAuthorized(eq(42), any(), anyMap(), anyString(), eq(true)))
                    .thenAnswer(call -> {
                        Operation op = call.getArgument(1);
                        if (op.getName().equals("getAllUsers") && attempts.getAndIncrement() == 0) {
                            throw new ControllerException("Synthetic transient authorization failure");
                        }
                        return true;
                    });
            f.servlet.checkUserAuthorized();
            assertThrows(MirthApiException.class, f.servlet::getCoreUsers);
            assertSame(f.original, f.servlet.currentOperation());
            assertEquals(2, Json.mapper().readTree(f.servlet.getCoreUsers()).size());
            assertSame(f.original, f.servlet.currentOperation());
            assertEquals(2, attempts.get());
            verify(f.users, times(1)).getAllUsers();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realEngineFalseAndForbiddenDenialsReturnOnlyAuthorizedFallback(boolean throwsForbidden) throws Exception {
        try (Fixture f = new Fixture(); MockedStatic<ProblemService> problems = mockStatic(ProblemService.class)) {
            problems.when(() -> ProblemService.acknowledgingUserIds(null)).thenReturn(new HashSet<>());
            when(f.auth.isUserAuthorized(eq(42), any(), anyMap(), anyString(), eq(true)))
                    .thenAnswer(call -> {
                        Operation op = call.getArgument(1);
                        if (!op.getName().equals("getAllUsers")) return true;
                        if (throwsForbidden) throw new MirthApiException(Response.Status.FORBIDDEN);
                        return false;
                    });
            f.servlet.checkUserAuthorized();
            var result = Json.mapper().readTree(f.servlet.getCoreUsers());
            assertEquals(1, result.size());
            assertEquals(42, result.get(0).path("userId").asInt());
            assertSame(f.original, f.servlet.currentOperation());
        }
    }

    @Test
    void realEngineDirectoryGrantKeepsFullDirectoryAndRestoresOperation() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.auth.isUserAuthorized(eq(42), any(), anyMap(), anyString(), eq(true))).thenReturn(true);
            f.servlet.checkUserAuthorized();
            assertEquals(2, Json.mapper().readTree(f.servlet.getCoreUsers()).size());
            assertSame(f.original, f.servlet.currentOperation());
        }
    }

    public static class ActualEngineServlet extends SentinelServlet {
        public ActualEngineServlet() { super(null, null); }
        void prepare() {
            request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("127.0.0.1");
            context = new ServerEventContext(42);
            parameterMap = new HashMap<>();
            operation = new ExtensionOperation(SentinelServletInterface.PLUGIN_POINT,
                    OperationUtil.getOperation(SentinelServletInterface.class, "getCoreUsers"));
        }
        Operation currentOperation() { return operation; }
    }

    static class Fixture implements AutoCloseable {
        final ActualEngineServlet servlet = mock(ActualEngineServlet.class, CALLS_REAL_METHODS);
        final AuthorizationController auth = mock(AuthorizationController.class);
        final MockedStatic<ControllerFactory> factory = mockStatic(ControllerFactory.class);
        final UserController users = mock(UserController.class);
        final Operation original;
        Fixture() throws Exception {
            servlet.prepare();
            setEngineField("authorizationController", auth);
            setEngineField("currentUserId", 42);
            original = servlet.currentOperation();
            ControllerFactory host = mock(ControllerFactory.class);
            factory.when(ControllerFactory::getFactory).thenReturn(host);
            when(host.createUserController()).thenReturn(users);
            User self = new User(); self.setId(42); self.setUsername("test-self");
            User other = new User(); other.setId(99); other.setUsername("test-other");
            when(users.getAllUsers()).thenReturn(List.of(self, other));
        }
        void setEngineField(String name, Object value) throws Exception {
            Field field = MirthServlet.class.getDeclaredField(name);
            field.setAccessible(true); field.set(servlet, value);
        }
        @Override public void close() { factory.close(); }
    }
}
