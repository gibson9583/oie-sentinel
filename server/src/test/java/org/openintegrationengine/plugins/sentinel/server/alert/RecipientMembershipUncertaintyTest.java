package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelGroup;
import com.mirth.connect.model.ChannelTag;
import com.mirth.connect.server.controllers.*;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.engine.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class RecipientMembershipUncertaintyTest {
    @ParameterizedTest
    @CsvSource({"CHANNEL_GROUP,null-inventory", "CHANNEL_TAG,null-inventory",
            "CHANNEL_GROUP,throw-inventory", "CHANNEL_TAG,throw-inventory",
            "CHANNEL_GROUP,null-members", "CHANNEL_TAG,null-members",
            "CHANNEL_GROUP,broken-member", "CHANNEL_TAG,broken-member"})
    void exclusionsNeverTurnIncompleteInventoryIntoPermission(String field, String failure) {
        ChannelController channels = mock(ChannelController.class);
        ConfigurationController configuration = mock(ConfigurationController.class);
        ControllerFactory factory = mock(ControllerFactory.class);
        when(factory.createConfigurationController()).thenReturn(configuration);
        ChannelGroup group = new ChannelGroup("restricted", "restricted", "");
        ChannelTag tag = new ChannelTag("restricted", "restricted");
        when(channels.getChannelGroups(null)).thenReturn(List.of(group));
        when(configuration.getChannelTags()).thenReturn(Set.of(tag));
        if (failure.equals("null-inventory")) {
            when(channels.getChannelGroups(null)).thenReturn(null); when(configuration.getChannelTags()).thenReturn(null);
        } else if (failure.equals("throw-inventory")) {
            when(channels.getChannelGroups(null)).thenThrow(new IllegalStateException("inventory failed"));
            when(configuration.getChannelTags()).thenThrow(new IllegalStateException("inventory failed"));
        } else if (failure.equals("null-members")) {
            group.setChannels(null); tag.setChannelIds(null);
        } else {
            Channel broken = mock(Channel.class); when(broken.getId()).thenThrow(new IllegalStateException("unreadable member"));
            group.setChannels(List.of(broken)); tag.setChannelIds(new HashSet<>(Arrays.asList((String) null)));
        }
        try (var controller = mockStatic(ChannelController.class); var factories = mockStatic(ControllerFactory.class)) {
            controller.when(ChannelController::getInstance).thenReturn(channels);
            factories.when(ControllerFactory::getFactory).thenReturn(factory);
            assertThrows(RuntimeException.class, () -> ActionConditionMatcher.matches(action(field), payload()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHANNEL_GROUP", "CHANNEL_TAG"})
    void uncertaintyRetainsPendingDeliveryAndExcludesRollupCandidate(String field) {
        AlertEvent event = new AlertEvent(); event.setId(91L); event.setMonitorId(7);
        event.setChannelId("restricted-channel"); event.setStatus(AlertStatus.PROBLEM);
        event.setOpenedTime(Instant.now()); event.setProblemPending(true);
        Action action = action(field);
        Monitor monitor = new Monitor(); monitor.setId(7); monitor.setEnabled(true);
        try (var scope = mockStatic(ScopeResolver.class);
                var actions = mockStatic(ActionRepository.class);
                var alerts = mockStatic(AlertEventRepository.class);
                var logs = mockStatic(ActionDispatchLogRepository.class);
                var monitors = mockStatic(MonitorRepository.class);
                var policy = mockStatic(NotificationSuppression.class);
                var storm = mockStatic(AlertStormControl.class);
                var leadership = mockStatic(SentinelLeadership.class)) {
            scope.when(() -> ScopeResolver.channelName(anyString())).thenAnswer(c -> c.getArgument(0));
            scope.when(() -> ScopeResolver.strictGroupChannelIds("restricted")).thenThrow(new IllegalStateException("unavailable"));
            scope.when(() -> ScopeResolver.strictTagChannelIds("restricted")).thenThrow(new IllegalStateException("unavailable"));
            actions.when(() -> ActionRepository.listActions(true)).thenReturn(List.of(action));
            actions.when(() -> ActionRepository.getAction(5)).thenReturn(action);
            alerts.when(() -> AlertEventRepository.getAlertEvent(91L)).thenReturn(event);
            alerts.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt())).thenReturn(List.of(event));
            monitors.when(() -> MonitorRepository.getMonitor(7)).thenReturn(monitor);
            policy.when(() -> NotificationSuppression.decision(any(), any())).thenReturn(NotificationSuppression.Decision.ALLOW);
            leadership.when(() -> SentinelLeadership.recheckFence(any())).thenReturn(true);
            storm.when(() -> AlertStormControl.flapCheck(any(), any(), anyBoolean())).thenReturn(
                    new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.NOT_FLAPPING, 0, 0));
            ActionDispatcher.dispatchLifecycle(event, payload(), false);
            assertTrue(event.isProblemPending());
            alerts.verify(() -> AlertEventRepository.setProblemPending(91L, false), never());
            logs.verify(() -> ActionDispatchLogRepository.insertActionDispatchLog(any()), never());
            assertTrue(RollupRecipients.channels(action, Instant.now().minusSeconds(600), Instant.now()).isEmpty());
        }
    }

    private static Action action(String field) {
        Action action = new Action(); action.setId(5); action.setEnabled(true); action.setOperationMode(OperationMode.ON_PROBLEM);
        action.setConditionJson("[{\"field\":\"" + field + "\",\"operator\":\"!=\",\"value\":\"restricted\"}]");
        return action;
    }
    private static AlertPayload payload() {
        return new AlertPayload(91, 7, "monitor", MonitorType.INACTIVITY, "restricted-channel", "restricted-channel",
                null, Severity.HIGH, "PROBLEM", "problem", Instant.now(), "{}");
    }
}
