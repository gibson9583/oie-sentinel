package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.server.controllers.*;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.server.evaluate.*;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

class ConnectorObservationRetryTest {
    private static final String ID = "retry-regression";
    private final Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
    private final Instant deployed = now.minusSeconds(120);
    private final List<org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent> saved = new ArrayList<>();
    private MockedStatic<ControllerFactory> controllers;
    private MockedStatic<ConnectorStatusRepository> repository;
    private EngineController engine;
    private ControllerFactory factory;
    private Channel channel;
    private SentinelConnectorStatusListener listener;
    private boolean fail;

    @BeforeEach void setup() {
        channel = mock(Channel.class); when(channel.getMetaDataIds()).thenReturn(List.of(0)); deploy(deployed);
        engine = mock(EngineController.class); when(engine.getDeployedChannel(ID)).thenReturn(channel);
        when(engine.getDeployedIds()).thenReturn(Set.of(ID));
        factory = mock(ControllerFactory.class); when(factory.createEngineController()).thenReturn(engine);
        controllers = mockStatic(ControllerFactory.class); controllers.when(ControllerFactory::getFactory).thenReturn(factory);
        repository = mockStatic(ConnectorStatusRepository.class);
        repository.when(() -> ConnectorStatusRepository.insertConnectorStatusEvent(any())).thenAnswer(c -> {
            if (fail) throw new IllegalStateException("selective status write failure");
            saved.add(c.getArgument(0)); return null;
        });
        listener = mock(SentinelConnectorStatusListener.class, CALLS_REAL_METHODS);
        CollectorState.getInstance().forgetChannel(ID);
    }
    @AfterEach void cleanup() {
        when(engine.getDeployedChannel(ID)).thenReturn(null); PendingConnectorObservations.flush();
        assertFalse(PendingConnectorObservations.isPending(ID, 0));
        CollectorState.getInstance().forgetChannel(ID); repository.close(); controllers.close();
    }
    @Test void singleLostTransitionIsRetriedWithoutEngineRedeliveryAndDirtyStateIsUnknown() {
        emit(ConnectionStatusEventType.CONNECTED, 90); fail = true; emit(ConnectionStatusEventType.DISCONNECTED, 60);
        assertEquals(1, saved.size()); assertTrue(PendingConnectorObservations.isPending(ID, 0));
        repository.when(() -> ConnectorStatusRepository.listLatestConnectorStatusEvents(ID)).thenReturn(List.copyOf(saved));
        try (var leases = mockStatic(NodeLeaseRepository.class)) {
            leases.when(NodeLeaseRepository::listHealthyObservationNodeIds).thenReturn(Set.of(SentinelLeadership.nodeId()));
            leases.when(() -> NodeLeaseRepository.listActiveConnectorDeployments(ID))
                    .thenReturn(Map.of(0, Map.of(SentinelLeadership.nodeId(), deployed)));
            Monitor monitor = new Monitor(); monitor.setConfigJson("{\"alertOnStates\":[\"DISCONNECTED\"]}");
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA,
                    ConnectionStatusEvaluator.evaluate(monitor, ID, now).get(0).outcome.getResult());
        }
        PendingConnectorObservations.flush(); assertEquals(1, saved.size());
        fail = false; PendingConnectorObservations.flush();
        assertEquals(2, saved.size()); assertFalse(PendingConnectorObservations.isPending(ID, 0));
        assertEquals("DISCONNECTED", saved.get(1).getNewState());
        assertEquals(now.minusSeconds(60), saved.get(1).getChangedTime());
        PendingConnectorObservations.flush(); assertEquals(2, saved.size());
    }
    @Test void laterRecoveryReplacesPendingDisconnectEvenWhenItMatchesOldDurableState() {
        emit(ConnectionStatusEventType.CONNECTED, 90); fail = true;
        emit(ConnectionStatusEventType.DISCONNECTED, 60); emit(ConnectionStatusEventType.CONNECTED, 30);
        fail = false; PendingConnectorObservations.flush();
        assertEquals(2, saved.size()); assertEquals("CONNECTED", saved.get(1).getNewState());
        assertEquals(now.minusSeconds(30), saved.get(1).getChangedTime());
    }
    @Test void redeployDropsPendingOldIncarnation() {
        fail = true; emit(ConnectionStatusEventType.DISCONNECTED, 60);
        deploy(now.minusSeconds(10)); fail = false; PendingConnectorObservations.flush();
        assertTrue(saved.isEmpty()); assertFalse(PendingConnectorObservations.isPending(ID, 0));
        emit(ConnectionStatusEventType.CONNECTED, 5); assertEquals(1, saved.size());
    }
    @Test void olderQueuedEventCannotReplaceNewerPendingOrCommittedState() {
        fail = true; emit(ConnectionStatusEventType.DISCONNECTED, 30); emit(ConnectionStatusEventType.CONNECTED, 60);
        fail = false; PendingConnectorObservations.flush();
        assertEquals("DISCONNECTED", saved.get(0).getNewState());
        emit(ConnectionStatusEventType.CONNECTED, 60); assertEquals(1, saved.size());
    }
    @Test void unrecognizedMetadataCannotAccumulatePendingEntries() {
        fail = true;
        for (int id = 1; id <= 1000; id++) {
            ConnectionStatusEvent event = event(ConnectionStatusEventType.CONNECTED, 30);
            when(event.getMetaDataId()).thenReturn(id); listener.processEvent(event);
            assertFalse(PendingConnectorObservations.isPending(ID, id));
        }
    }
    @Test void newerObservationArrivingDuringWriteSurvivesCompareAndRemove() throws Exception {
        repository.reset();
        repository.when(() -> ConnectorStatusRepository.insertConnectorStatusEvent(any())).thenAnswer(c -> {
            org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent row = c.getArgument(0);
            saved.add(row);
            if (row.getNewState().equals("DISCONNECTED")) {
                var newer = new org.openintegrationengine.plugins.sentinel.shared.model.ConnectorStatusEvent();
                newer.setChannelId(ID); newer.setMetadataId(0); newer.setNodeId(SentinelLeadership.nodeId());
                newer.setChangedTime(now.minusSeconds(10)); newer.setDeploymentTime(deployed); newer.setNewState("CONNECTED");
                Thread arrival = new Thread(() -> PendingConnectorObservations.observe(newer), "sentinel-observation-race-test");
                arrival.start(); arrival.join(2000); assertFalse(arrival.isAlive());
            }
            return null;
        });
        emit(ConnectionStatusEventType.DISCONNECTED, 30);
        assertTrue(PendingConnectorObservations.isPending(ID, 0));
        PendingConnectorObservations.flush();
        assertFalse(PendingConnectorObservations.isPending(ID, 0));
        assertEquals(List.of("DISCONNECTED", "CONNECTED"), saved.stream().map(r -> r.getNewState()).toList());
        assertEquals(ConnectionStatusEventType.CONNECTED, CollectorState.getInstance().getConnectorState(ID, 0).state);
    }

    @Test void heartbeatKeepsDeploymentPresenceWhilePendingAndPublishesHealthAfterRetry() throws Exception {
        fail = true; emit(ConnectionStatusEventType.DISCONNECTED, 30);
        when(engine.getDeployedIds()).thenReturn(Set.of(ID));
        Map<String, Object> original = new LinkedHashMap<>();
        for (String name : List.of("standingDown", "lifecycleGeneration", "leadershipEpoch")) {
            var field = SentinelLeadership.class.getDeclaredField(name); field.setAccessible(true);
            original.put(name, field.get(null));
            field.set(null, name.equals("standingDown") ? false : name.equals("lifecycleGeneration") ? 999L : null);
        }
        try (var leases = mockStatic(NodeLeaseRepository.class)) {
            leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(now);
            var leader = new org.openintegrationengine.plugins.sentinel.shared.model.NodeLease();
            leader.setNodeId("another-node"); leader.setLeaseEpoch(5L); leader.setExpiresTime(now.plusSeconds(90));
            leases.when(() -> NodeLeaseRepository.getNodeLease("sentinel-engine")).thenReturn(leader);
            SentinelLeadership.heartbeat(999L);
            leases.verify(() -> NodeLeaseRepository.refreshNodePresence(anyString(), anyString(), anyInt(),
                    any(), any(java.util.function.BooleanSupplier.class)));
            leases.verify(() -> NodeLeaseRepository.refreshObservationHealth(anyString(), anyString(), anyInt(),
                    any(java.util.function.BooleanSupplier.class)), never());
            fail = false;
            SentinelLeadership.heartbeat(998L); assertTrue(saved.isEmpty(), "stopped generation cannot retry");
            SentinelLeadership.heartbeat(999L); assertEquals(1, saved.size());
            leases.verify(() -> NodeLeaseRepository.refreshNodePresence(anyString(), anyString(), eq(90),
                    any(), any(java.util.function.BooleanSupplier.class)), times(2));
            leases.verify(() -> NodeLeaseRepository.refreshObservationHealth(anyString(), anyString(), eq(90),
                    any(java.util.function.BooleanSupplier.class)));
        } finally {
            for (var entry : original.entrySet()) {
                var field = SentinelLeadership.class.getDeclaredField(entry.getKey()); field.setAccessible(true);
                field.set(null, entry.getValue());
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void returningWriteCannotPublishAcrossActualStopRestart(boolean redeploy) throws Exception {
        Map<String, Object> original = new LinkedHashMap<>();
        for (String name : List.of("standingDown", "leadershipEngaged", "lifecycleGeneration",
                "leadershipValidUntilNanos", "leadershipEpoch")) {
            var field = SentinelLeadership.class.getDeclaredField(name); field.setAccessible(true);
            original.put(name, field.get(null));
        }
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        boolean[] restarted = {false};
        try (var leases = mockStatic(NodeLeaseRepository.class)) {
            repository.reset();
            repository.when(() -> ConnectorStatusRepository.insertConnectorStatusEvent(any())).thenAnswer(c -> {
                saved.add(c.getArgument(0));
                if (!restarted[0]) {
                    restarted[0] = true;
                    // The repository call has not returned; the real stop and
                    // restart run while its old generation still owns the writer.
                    SentinelLeadership.stopHeartbeat();
                    if (redeploy) deploy(now.minusSeconds(10));
                    Thread restart = new Thread(() -> {
                        try (var workerLeases = mockStatic(NodeLeaseRepository.class);
                                var workerControllers = mockStatic(ControllerFactory.class)) {
                            workerControllers.when(ControllerFactory::getFactory).thenReturn(factory);
                            workerLeases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(now);
                            var other = new org.openintegrationengine.plugins.sentinel.shared.model.NodeLease();
                            other.setNodeId("other-node"); other.setLeaseEpoch(5L); other.setExpiresTime(now.plusSeconds(90));
                            workerLeases.when(() -> NodeLeaseRepository.getNodeLease("sentinel-engine")).thenReturn(other);
                            SentinelLeadership.startHeartbeat();
                        } catch (Throwable thrown) { failure.set(thrown); }
                    }, "sentinel-observation-restart-test");
                    restart.start(); restart.join(2000); assertFalse(restart.isAlive()); assertNull(failure.get());
                }
                return null;
            });
            emit(ConnectionStatusEventType.DISCONNECTED, 30);
            assertNull(CollectorState.getInstance().getConnectorState(ID, 0),
                    "returning old generation must not publish into restarted cache");
            assertTrue(PendingConnectorObservations.isPending(ID, 0));
            PendingConnectorObservations.flush();
            assertFalse(PendingConnectorObservations.isPending(ID, 0));
            if (redeploy) {
                assertEquals(1, saved.size(), "old incarnation must not retry into a new deployment");
                assertNull(CollectorState.getInstance().getConnectorState(ID, 0));
                emit(ConnectionStatusEventType.CONNECTED, 5);
                assertEquals(ConnectionStatusEventType.CONNECTED, CollectorState.getInstance().getConnectorState(ID, 0).state);
            } else {
                assertEquals(2, saved.size(), "current lifecycle retries intent without stale publication");
                assertEquals(ConnectionStatusEventType.DISCONNECTED, CollectorState.getInstance().getConnectorState(ID, 0).state);
            }
        } finally {
            try (var leases = mockStatic(NodeLeaseRepository.class)) { SentinelLeadership.stopHeartbeat(); }
            for (var entry : original.entrySet()) {
                var field = SentinelLeadership.class.getDeclaredField(entry.getKey()); field.setAccessible(true);
                field.set(null, entry.getValue());
            }
        }
    }

    private void emit(ConnectionStatusEventType state, long age) { listener.processEvent(event(state, age)); }
    private ConnectionStatusEvent event(ConnectionStatusEventType state, long age) {
        ConnectionStatusEvent event = mock(ConnectionStatusEvent.class); when(event.getChannelId()).thenReturn(ID);
        when(event.getMetaDataId()).thenReturn(0); when(event.getState()).thenReturn(state);
        when(event.getDateTime()).thenReturn(now.minusSeconds(age).toEpochMilli()); return event;
    }
    private void deploy(Instant at) { Calendar date = Calendar.getInstance(); date.setTimeInMillis(at.toEpochMilli()); when(channel.getDeployDate()).thenReturn(date); }
}
