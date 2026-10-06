package org.openintegrationengine.plugins.sentinel.server.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import com.mirth.connect.client.core.api.MirthApiException;
import org.openintegrationengine.plugins.sentinel.server.service.DecisionInspectorService;
import org.openintegrationengine.plugins.sentinel.server.service.ProblemService;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;

class DecisionInspectorAuthorizationTest {
    // A Mockito-created instance avoids host initialization; inherited access
    // checks are explicitly overridden without invoking engine controllers.
    abstract static class RestrictedServlet extends SentinelServlet {
        RestrictedServlet() { super(null, null); }
        @Override protected boolean doesUserHaveChannelRestrictions() { return true; }
        @Override protected boolean isChannelRedacted(String id) { return !"visible".equals(id); }
    }
    @Test
    void forbiddenChannelNeverReachesPolicyOrRoutingReads() {
        RestrictedServlet servlet = mock(RestrictedServlet.class, CALLS_REAL_METHODS);
        try (var problems = mockStatic(ProblemService.class);
             var inspector = mockStatic(DecisionInspectorService.class);
             var responses = mockStatic(Response.class)) {
            // The unit classpath provides the JAX-RS API, not Jersey's runtime.
            Response response = mock(Response.class); when(response.getStatus()).thenReturn(404);
            when(response.getStatusInfo()).thenReturn(Response.Status.NOT_FOUND);
            Response.ResponseBuilder builder = mock(Response.ResponseBuilder.class, RETURNS_SELF);
            when(builder.build()).thenReturn(response);
            responses.when(() -> Response.status(Response.Status.NOT_FOUND)).thenReturn(builder);
            AlertEvent event = new AlertEvent(); event.setChannelId("hidden");
            problems.when(() -> ProblemService.get(7)).thenReturn(event);
            MirthApiException error = assertThrows(MirthApiException.class, () -> servlet.inspectDecision(7));
            assertEquals(404, error.getResponse().getStatus());
            inspector.verifyNoInteractions();
            event.setChannelId("visible");
            inspector.when(() -> DecisionInspectorService.inspect(7)).thenReturn(Map.of("ok", true));
            assertEquals("{\"ok\":true}", servlet.inspectDecision(7));
            inspector.verify(() -> DecisionInspectorService.inspect(7));
        }
    }
}
