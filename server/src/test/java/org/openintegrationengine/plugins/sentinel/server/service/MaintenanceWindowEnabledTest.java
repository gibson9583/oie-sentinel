/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.MaintenanceWindowRepository;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.*;
import com.mirth.connect.client.core.api.MirthOperation;

class MaintenanceWindowEnabledTest {
    private MaintenanceWindow window() {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setId(7); w.setName("On call"); w.setMode(WindowMode.ACTIVE);
        w.setScopeType(ScopeType.CHANNEL); w.setScopeId("current-channel");
        w.setRepeatType(WindowRepeat.NONE);
        w.setActiveFrom(Instant.now().minusSeconds(60));
        w.setActiveUntil(Instant.now().plusSeconds(3600));
        return w;
    }

    @Test
    void togglesOnlyFlagAndReturnsPolicyEditedDuringTheOperation() {
        MaintenanceWindow before = window(), after = window();
        after.setName("Concurrent rename"); after.setScopeId("concurrent-channel"); after.setEnabled(true);
        try (MockedStatic<MaintenanceWindowRepository> repo = mockStatic(MaintenanceWindowRepository.class);
                MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            repo.when(() -> MaintenanceWindowRepository.getMaintenanceWindow(7)).thenReturn(before, after);
            MaintenanceWindow result = MaintenanceWindowService.setEnabled(7, true, 101);
            assertSame(after, result);
            assertEquals("concurrent-channel", result.getScopeId());
            assertEquals(WindowMode.ACTIVE, result.getMode());
            assertTrue(result.getActiveNow());
            repo.verify(() -> MaintenanceWindowRepository.setMaintenanceWindowEnabled(7, true));
            repo.verify(() -> MaintenanceWindowRepository.updateMaintenanceWindow(any()), never());
            audit.verify(() -> SentinelAuditLog.windowEnabledChanged(101, before));
        }
    }

    @Test
    void requiresExplicitStateBeforeAnyReadOrMutation() {
        try (MockedStatic<MaintenanceWindowRepository> repo = mockStatic(MaintenanceWindowRepository.class)) {
            assertThrows(IllegalArgumentException.class, () -> MaintenanceWindowService.setEnabled(7, null, 101));
            repo.verifyNoInteractions();
        }
    }

    @Test
    void validatesCurrentStoredDefinitionBeforeEnablingButAllowsDisabling() {
        MaintenanceWindow invalid = window(); invalid.setScopeId(null);
        try (MockedStatic<MaintenanceWindowRepository> repo = mockStatic(MaintenanceWindowRepository.class);
                MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            repo.when(() -> MaintenanceWindowRepository.getMaintenanceWindow(7)).thenReturn(invalid);
            assertThrows(IllegalArgumentException.class, () -> MaintenanceWindowService.setEnabled(7, true, 101));
            repo.verify(() -> MaintenanceWindowRepository.setMaintenanceWindowEnabled(anyInt(), anyBoolean()), never());
            MaintenanceWindowService.setEnabled(7, false, 101);
            repo.verify(() -> MaintenanceWindowRepository.setMaintenanceWindowEnabled(7, false));
        }
    }

    @Test
    void missingRowDoesNotWriteOrAudit() {
        try (MockedStatic<MaintenanceWindowRepository> repo = mockStatic(MaintenanceWindowRepository.class);
                MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            assertThrows(NoSuchElementException.class, () -> MaintenanceWindowService.setEnabled(7, true, 101));
            repo.verify(() -> MaintenanceWindowRepository.setMaintenanceWindowEnabled(anyInt(), anyBoolean()), never());
            audit.verifyNoInteractions();
        }
    }

    @Test
    void writeFailureDoesNotReportOrAuditSuccess() {
        try (MockedStatic<MaintenanceWindowRepository> repo = mockStatic(MaintenanceWindowRepository.class);
                MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            repo.when(() -> MaintenanceWindowRepository.getMaintenanceWindow(7)).thenReturn(window());
            repo.when(() -> MaintenanceWindowRepository.setMaintenanceWindowEnabled(7, true))
                    .thenThrow(new IllegalStateException("write failed"));
            assertThrows(IllegalStateException.class, () -> MaintenanceWindowService.setEnabled(7, true, 101));
            audit.verifyNoInteractions();
        }
    }

    @Test
    void concurrentDeletionIsNotReportedAsSuccess() {
        try (MockedStatic<MaintenanceWindowRepository> repo = mockStatic(MaintenanceWindowRepository.class);
                MockedStatic<SentinelAuditLog> audit = mockStatic(SentinelAuditLog.class)) {
            repo.when(() -> MaintenanceWindowRepository.getMaintenanceWindow(7)).thenReturn(window(), null);
            assertThrows(NoSuchElementException.class, () -> MaintenanceWindowService.setEnabled(7, true, 101));
            audit.verifyNoInteractions();
        }
    }

    @Test
    void endpointUsesSchedulePermissionAndPost() throws Exception {
        var method = SentinelServletInterface.class.getMethod("setMaintenanceWindowEnabled", int.class, Boolean.class);
        assertEquals(SentinelServletInterface.PERMISSION_MAINTENANCE, method.getAnnotation(MirthOperation.class).permission());
        assertNotNull(method.getAnnotation(javax.ws.rs.POST.class));
        assertEquals("/maintenanceWindows/{id}/_setEnabled", method.getAnnotation(javax.ws.rs.Path.class).value());
    }
}
