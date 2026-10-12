/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.event.EventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.SentinelServicePlugin;
import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;

/** Listener retirement must not depend on the engine successfully removing it. */
class SentinelServicePluginListenerShutdownTest {
    private enum Failure { FACTORY_LOOKUP, EVENT_CONTROLLER_LOOKUP, LISTENER_REMOVAL }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void retirementPrecedesEachFallibleEngineRemovalStep(Failure failure) throws Exception {
        SentinelServicePlugin plugin = new SentinelServicePlugin();
        SentinelConnectorStatusListener listener = listenerWithoutConsumer();
        field(SentinelServicePlugin.class, "connectorStatusListener").set(plugin, listener);
        SentinelScheduler scheduler = mock(SentinelScheduler.class);
        ControllerFactory factory = mock(ControllerFactory.class);
        EventController events = mock(EventController.class);
        try (MockedStatic<SentinelScheduler> schedulers = mockStatic(SentinelScheduler.class);
                MockedStatic<SentinelLeadership> leadership = mockStatic(SentinelLeadership.class);
                MockedStatic<ActionDispatcher> dispatcher = mockStatic(ActionDispatcher.class);
                MockedStatic<ControllerFactory> controllers = mockStatic(ControllerFactory.class)) {
            schedulers.when(SentinelScheduler::getInstance).thenReturn(scheduler);
            controllers.when(ControllerFactory::getFactory).thenAnswer(call -> {
                assertRetired(listener);
                if (failure == Failure.FACTORY_LOOKUP) throw unavailable();
                return factory;
            });
            when(factory.createEventController()).thenAnswer(call -> {
                assertRetired(listener);
                if (failure == Failure.EVENT_CONTROLLER_LOOKUP) throw unavailable();
                return events;
            });
            doAnswer(call -> {
                assertRetired(listener);
                throw unavailable();
            }).when(events).removeListener(listener);

            assertDoesNotThrow(plugin::stop);

            assertRetired(listener);
            assertNull(field(SentinelServicePlugin.class, "connectorStatusListener").get(plugin));
            verify(scheduler).shutdown();
            leadership.verify(SentinelLeadership::stopHeartbeat);
            dispatcher.verify(ActionDispatcher::shutdownDispatchExecutor);
            switch (failure) {
                case FACTORY_LOOKUP -> verifyNoInteractions(factory, events);
                case EVENT_CONTROLLER_LOOKUP -> verifyNoInteractions(events);
                case LISTENER_REMOVAL -> verify(events).removeListener(listener);
            }
        }
    }

    @Test
    void engineSecondShutdownAndRepeatedPluginStopRemainSafe() throws Exception {
        SentinelServicePlugin plugin = new SentinelServicePlugin();
        SentinelConnectorStatusListener listener = listenerWithoutConsumer();
        field(SentinelServicePlugin.class, "connectorStatusListener").set(plugin, listener);
        Thread consumer = (Thread) field(EventListener.class, "workerThread").get(listener);
        SentinelScheduler scheduler = mock(SentinelScheduler.class);
        ControllerFactory factory = mock(ControllerFactory.class);
        EventController events = mock(EventController.class);
        when(factory.createEventController()).thenReturn(events);
        // DefaultEventController.removeListener invokes shutdown after removing
        // its queue registrations, even if the plugin already retired it.
        doAnswer(call -> {
            assertRetired(listener);
            listener.shutdown();
            return null;
        }).when(events).removeListener(listener);
        try (MockedStatic<SentinelScheduler> schedulers = mockStatic(SentinelScheduler.class);
                MockedStatic<SentinelLeadership> leadership = mockStatic(SentinelLeadership.class);
                MockedStatic<ActionDispatcher> dispatcher = mockStatic(ActionDispatcher.class);
                MockedStatic<ControllerFactory> controllers = mockStatic(ControllerFactory.class)) {
            schedulers.when(SentinelScheduler::getInstance).thenReturn(scheduler);
            controllers.when(ControllerFactory::getFactory).thenReturn(factory);
            assertDoesNotThrow(plugin::stop);
            assertDoesNotThrow(plugin::stop);
            assertRetired(listener);
            verify(consumer, times(2)).interrupt();
            verify(events).removeListener(listener);
            verify(scheduler, times(2)).shutdown();
            leadership.verify(SentinelLeadership::stopHeartbeat, times(2));
            dispatcher.verify(ActionDispatcher::shutdownDispatchExecutor, times(2));
        }
    }

    private static SentinelConnectorStatusListener listenerWithoutConsumer() throws Exception {
        // Mockito bypasses EventListener's constructor. Supply only its mock
        // worker so the real public shutdown and protected onShutdown execute
        // without creating or signalling any real consumer thread.
        SentinelConnectorStatusListener listener = mock(SentinelConnectorStatusListener.class, CALLS_REAL_METHODS);
        field(EventListener.class, "workerThread").set(listener, mock(Thread.class));
        return listener;
    }

    private static void assertRetired(SentinelConnectorStatusListener listener) {
        // The observable boundary is that a callback never reads the event,
        // inventory, or pending map after shutdown, including removal failure.
        ConnectionStatusEvent late = mock(ConnectionStatusEvent.class);
        listener.processEvent(late);
        verifyNoInteractions(late);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("controlled engine removal failure");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
