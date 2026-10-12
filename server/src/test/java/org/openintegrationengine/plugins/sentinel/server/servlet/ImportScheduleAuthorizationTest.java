package org.openintegrationengine.plugins.sentinel.server.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.MirthApiException;
import org.openintegrationengine.plugins.sentinel.server.service.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ImportScheduleAuthorizationTest {
    private static final String SCHEDULE_DOCUMENT = "{\"schemaVersion\":1,"
            + "\"monitors\":[{\"name\":\"Do not create before checking permission\"}],"
            + "\"actions\":[{\"name\":\"Do not create either\"}],"
            + "\"maintenanceWindows\":[{\"name\":\"Imported schedule\",\"scopeType\":\"ALL\"}]}";

    @Test
    void deniedSchedulesPreventEveryImportReadAndWriteAndRestoreOperation() {
        PermissionServlet servlet = servlet(false);
        Operation original = servlet.original();
        try (MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
                MockedStatic<ActionService> actions = mockStatic(ActionService.class);
                MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class)) {
            MirthApiException denied = assertThrows(MirthApiException.class,
                    () -> servlet.importConfiguration(SCHEDULE_DOCUMENT));
            assertEquals(403, denied.getResponse().getStatus());
            assertEquals(List.of("OIE Sentinel#sentinelCreateMaintenanceWindow"), servlet.checked);
            assertSame(original, servlet.original());
            monitors.verifyNoInteractions();
            actions.verifyNoInteractions();
            windows.verifyNoInteractions();
        }
    }

    @Test
    void allowedSchedulesImportAndRetryThroughTheExistingValidationPath() {
        PermissionServlet servlet = servlet(true);
        try (MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
                MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class)) {
            windows.when(() -> MaintenanceWindowService.create(any(MaintenanceWindow.class), eq(42)))
                    .thenAnswer(i -> { MaintenanceWindow w = i.getArgument(0); w.setId(7); return w; });
            String document = "{\"schemaVersion\":1,\"maintenanceWindows\":[{\"name\":\"Imported schedule\"}]}";
            assertTrue(servlet.importConfiguration(document).contains("CREATED"));
            windows.verify(() -> MaintenanceWindowService.create(any(MaintenanceWindow.class), eq(42)));
            // Re-check at action time even for an identical retry from a stale page.
            servlet.schedulesAllowed = false;
            assertEquals(403, assertThrows(MirthApiException.class,
                    () -> servlet.importConfiguration(document)).getResponse().getStatus());
            windows.verify(() -> MaintenanceWindowService.create(any(MaintenanceWindow.class), eq(42)), times(1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"maintenanceWindows\":null", ",\"maintenanceWindows\":[]"})
    void monitorOnlyImportsDoNotRequireSchedules(String schedules) {
        PermissionServlet servlet = servlet(false);
        try (MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
                MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class)) {
            String response = servlet.importConfiguration("{\"schemaVersion\":1" + schedules + "}");
            assertTrue(response.contains("\"created\":0"));
            assertTrue(servlet.checked.isEmpty());
            monitors.verify(MonitorService::list);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{\"schemaVersion\":99}",
            "{\"schemaVersion\":1,\"monitors\":[{\"name\":\"must not write\"}],\"maintenanceWindows\":{}}",
            "{\"schemaVersion\":1,\"monitors\":[],\"actions\":false}"})
    void invalidEnvelopeIsRejectedBeforeAnyWriteOrExtraPermissionCheck(String document) {
        PermissionServlet servlet = servlet(true);
        try (MockedStatic<MonitorService> monitors = mockStatic(MonitorService.class);
                MockedStatic<ActionService> actions = mockStatic(ActionService.class);
                MockedStatic<MaintenanceWindowService> windows = mockStatic(MaintenanceWindowService.class)) {
            assertEquals(400, assertThrows(MirthApiException.class,
                    () -> servlet.importConfiguration(document)).getResponse().getStatus());
            assertTrue(servlet.checked.isEmpty());
            monitors.verifyNoInteractions(); actions.verifyNoInteractions(); windows.verifyNoInteractions();
        }
    }

    @Test
    void unexpectedAuthorizationFailureAlsoRestoresTheOriginalOperation() {
        PermissionServlet servlet = servlet(true);
        Operation original = servlet.original();
        doThrow(new IllegalStateException("Authorization backend unavailable"))
                .when(servlet).checkUserAuthorized();
        assertEquals(500, assertThrows(MirthApiException.class,
                () -> servlet.importConfiguration(SCHEDULE_DOCUMENT)).getResponse().getStatus());
        assertSame(original, servlet.original());
    }

    private static PermissionServlet servlet(boolean schedulesAllowed) {
        PermissionServlet servlet = mock(PermissionServlet.class, CALLS_REAL_METHODS);
        servlet.schedulesAllowed = schedulesAllowed;
        servlet.checked = new ArrayList<>();
        servlet.setOperation(new Operation("sentinelImportConfiguration", "Import", ExecuteType.SYNC, false));
        return servlet;
    }

    public static class PermissionServlet extends SentinelServlet {
        boolean schedulesAllowed;
        List<String> checked;
        public PermissionServlet() { super(null, null); }
        @Override protected int getCurrentUserId() { return 42; }
        @Override protected boolean isUserAuthorized() {
            checked.add(operation.getName());
            return schedulesAllowed;
        }
        Operation original() { return operation; }
    }
}
