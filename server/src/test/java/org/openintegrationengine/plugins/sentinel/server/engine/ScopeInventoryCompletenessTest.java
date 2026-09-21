/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;

class ScopeInventoryCompletenessTest {
    @Test
    void incompleteGroupCannotAuthorizeADepartureSweep() {
        ChannelController controller = mock(ChannelController.class);
        ChannelGroup group = mock(ChannelGroup.class);
        Channel healthy = mock(Channel.class);
        Channel broken = mock(Channel.class);
        when(healthy.getId()).thenReturn("healthy");
        when(broken.getId()).thenThrow(new IllegalStateException("member unavailable"));
        when(group.getId()).thenReturn("group");
        when(group.getChannels()).thenReturn(List.of(healthy, broken));
        when(controller.getChannelGroups(null)).thenReturn(List.of(group));
        Monitor monitor = monitor(ScopeType.GROUP, "group");
        try (MockedStatic<ChannelController> channels = mockStatic(ChannelController.class)) {
            channels.when(ChannelController::getInstance).thenReturn(controller);
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveStartedChannelSet(monitor));
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveScopedChannelSet(monitor));
        }
    }

    @Test
    void unavailableGroupInventoryDiffersFromADeletedGroup() {
        ChannelController controller = mock(ChannelController.class);
        Monitor monitor = monitor(ScopeType.GROUP, "group");
        try (MockedStatic<ChannelController> channels = mockStatic(ChannelController.class)) {
            channels.when(ChannelController::getInstance).thenReturn(controller);
            when(controller.getChannelGroups(null)).thenReturn(null);
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveStartedChannelSet(monitor));
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveScopedChannelSet(monitor));
            when(controller.getChannelGroups(null)).thenReturn(List.of());
            assertTrue(ScopeResolver.resolveStartedChannelSet(monitor).targets.isEmpty());
            assertTrue(ScopeResolver.resolveScopedChannelSet(monitor).targets.isEmpty());
        }
    }

    @Test
    void unavailableTagInventoryCannotAuthorizeADepartureSweep() {
        ConfigurationController configuration = mock(ConfigurationController.class);
        ControllerFactory factory = mock(ControllerFactory.class);
        when(factory.createConfigurationController()).thenReturn(configuration);
        when(configuration.getChannelTags()).thenReturn(null);
        Monitor monitor = monitor(ScopeType.TAG, "tag");
        try (MockedStatic<ControllerFactory> controllers = mockStatic(ControllerFactory.class)) {
            controllers.when(ControllerFactory::getFactory).thenReturn(factory);
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveStartedChannelSet(monitor));
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveScopedChannelSet(monitor));
        }
    }

    @Test
    void unavailableAllInventoryDiffersFromAnAuthoritativeEmptyInventory() {
        ChannelController controller = mock(ChannelController.class);
        Monitor monitor = monitor(ScopeType.ALL, null);
        try (MockedStatic<ChannelController> channels = mockStatic(ChannelController.class)) {
            channels.when(ChannelController::getInstance).thenReturn(controller);
            when(controller.getChannels(null)).thenReturn(null);
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveScopedChannelSet(monitor));
            when(controller.getChannels(null)).thenReturn(List.of());
            assertTrue(ScopeResolver.resolveScopedChannelSet(monitor).targets.isEmpty());
        }
    }

    @Test
    void unidentifiedAllMemberCannotAuthorizeDeparturesFromAPartialList() {
        ChannelController controller = mock(ChannelController.class);
        Channel healthy = mock(Channel.class);
        Channel broken = mock(Channel.class);
        when(healthy.getId()).thenReturn("healthy");
        when(broken.getId()).thenThrow(new IllegalStateException("identity unavailable"));
        when(controller.getChannels(null)).thenReturn(List.of(healthy, broken));
        Monitor monitor = monitor(ScopeType.ALL, null);
        try (MockedStatic<ChannelController> channels = mockStatic(ChannelController.class)) {
            channels.when(ChannelController::getInstance).thenReturn(controller);
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveScopedChannelSet(monitor));
            doReturn(null).when(broken).getId();
            assertThrows(IllegalStateException.class, () -> ScopeResolver.resolveScopedChannelSet(monitor));
        }
    }

    @Test
    void knownAllMemberFailurePreservesItsIdentityAndEvaluatesHealthyPeers() {
        ChannelController controller = mock(ChannelController.class);
        Channel healthy = mock(Channel.class);
        Channel broken = mock(Channel.class);
        when(healthy.getId()).thenReturn("healthy");
        when(broken.getId()).thenReturn("broken");
        when(broken.getName()).thenThrow(new IllegalStateException("name unavailable"));
        when(controller.getChannels(null)).thenReturn(List.of(healthy, broken));
        Monitor monitor = monitor(ScopeType.ALL, null);
        try (MockedStatic<ChannelController> channels = mockStatic(ChannelController.class)) {
            channels.when(ChannelController::getInstance).thenReturn(controller);
            ScopeResolver.ChannelResolution resolution = ScopeResolver.resolveScopedChannelSet(monitor);
            assertEquals(1, resolution.targets.size());
            assertEquals("healthy", resolution.targets.get(0).channelId);
            assertEquals(java.util.Set.of("broken"), resolution.unresolvedChannelIds);
        }
    }

    private static Monitor monitor(ScopeType scope, String id) {
        Monitor monitor = new Monitor();
        monitor.setScopeType(scope);
        monitor.setScopeId(id);
        return monitor;
    }
}
