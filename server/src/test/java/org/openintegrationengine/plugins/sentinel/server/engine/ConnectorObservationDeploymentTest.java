/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import org.openintegrationengine.plugins.sentinel.server.db.ConnectorStatusRepository;

class ConnectorObservationDeploymentTest {
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000071";
    private final Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
    private final List<org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent> recorded = new ArrayList<>();
    private MockedStatic<ControllerFactory> controllers;
    private MockedStatic<ConnectorStatusRepository> repository;
    private Channel channel;
    private SentinelConnectorStatusListener listener;
    private boolean failWrite;

    @BeforeEach
    void setup() {
        // Invoke the real event handler without starting the engine base
        // class's consumer thread; its queue/lifecycle is not involved here.
        listener = mock(SentinelConnectorStatusListener.class, CALLS_REAL_METHODS);
        channel = mock(Channel.class);
        deployAt(now.minusSeconds(60));
        EngineController engine = mock(EngineController.class);
        when(engine.getDeployedChannel(CHANNEL)).thenReturn(channel);
        ControllerFactory factory = mock(ControllerFactory.class);
        when(factory.createEngineController()).thenReturn(engine);
        controllers = mockStatic(ControllerFactory.class);
        controllers.when(ControllerFactory::getFactory).thenReturn(factory);
        repository = mockStatic(ConnectorStatusRepository.class);
        repository.when(() -> ConnectorStatusRepository.insertConnectorStatusEvent(any())).thenAnswer(call -> {
            if (failWrite) throw new IllegalStateException("database unavailable");
            recorded.add(call.getArgument(0));
            return null;
        });
        CollectorState.getInstance().forgetChannel(CHANNEL);
    }

    @AfterEach
    void cleanup() {
        repository.close();
        controllers.close();
        CollectorState.getInstance().forgetChannel(CHANNEL);
    }

    @Test
    void preservesEventOriginAndCurrentDeploymentInsteadOfRelabelingWithProcessingTime() {
        Instant occurred = now.minusSeconds(30);
        listener.processEvent(event(occurred));
        assertEquals(1, recorded.size());
        assertEquals(occurred, recorded.get(0).getChangedTime());
        assertEquals(now.minusSeconds(60), recorded.get(0).getDeploymentTime());
    }

    @Test
    void queuedEventFromBeforeRedeployCannotBecomeAFreshObservation() {
        deployAt(now.minusSeconds(10));
        listener.processEvent(event(now.minusSeconds(30)));
        assertTrue(recorded.isEmpty());
        assertNull(CollectorState.getInstance().getConnectorState(CHANNEL, 0));
    }

    @Test
    void sameStateAcrossRedeployWritesANewObservation() {
        CollectorState.getInstance().putConnectorState(CHANNEL, 0, ConnectionStatusEventType.CONNECTED,
                now.minusSeconds(30), now.minusSeconds(60));
        deployAt(now.minusSeconds(10));
        listener.processEvent(event(now.minusSeconds(5)));
        assertEquals(1, recorded.size());
        assertEquals(now.minusSeconds(10), recorded.get(0).getDeploymentTime());
        assertNull(recorded.get(0).getPreviousState());
    }

    @Test
    void backwardDeploymentClockAlsoInvalidatesSameStateDeduplication() {
        CollectorState.getInstance().putConnectorState(CHANNEL, 0, ConnectionStatusEventType.CONNECTED,
                now.minusSeconds(10), now.minusSeconds(20));
        deployAt(now.minusSeconds(40));
        listener.processEvent(event(now.minusSeconds(30)));
        assertEquals(1, recorded.size());
        assertEquals(now.minusSeconds(40), recorded.get(0).getDeploymentTime());
    }

    @Test
    void futureQueuedEventAfterClockRollbackIsNotPublishedAsCurrent() {
        listener.processEvent(event(now.plusSeconds(3600)));
        assertTrue(recorded.isEmpty());
        assertNull(CollectorState.getInstance().getConnectorState(CHANNEL, 0));
    }

    @Test
    void failedWriteDoesNotAdvanceDeduplicationAndSuppressTheRetry() {
        ConnectionStatusEvent event = event(now.minusSeconds(5));
        failWrite = true;
        listener.processEvent(event);
        assertNull(CollectorState.getInstance().getConnectorState(CHANNEL, 0));
        failWrite = false;
        listener.processEvent(event);
        assertEquals(1, recorded.size());
        assertNotNull(CollectorState.getInstance().getConnectorState(CHANNEL, 0));
    }

    private void deployAt(Instant at) {
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(at.toEpochMilli());
        when(channel.getDeployDate()).thenReturn(date);
    }

    private ConnectionStatusEvent event(Instant occurred) {
        ConnectionStatusEvent event = mock(ConnectionStatusEvent.class);
        when(event.getChannelId()).thenReturn(CHANNEL);
        when(event.getMetaDataId()).thenReturn(0);
        when(event.getState()).thenReturn(ConnectionStatusEventType.CONNECTED);
        when(event.getDateTime()).thenReturn(occurred.toEpochMilli());
        return event;
    }
}
