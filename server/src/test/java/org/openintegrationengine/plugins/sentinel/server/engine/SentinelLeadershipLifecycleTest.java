/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.Map;
import com.mirth.connect.donkey.server.channel.Channel;
import java.util.function.BooleanSupplier;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import org.openintegrationengine.plugins.sentinel.server.db.NodeLeaseRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.NodeLease;

class SentinelLeadershipLifecycleTest {

    private MockedStatic<NodeLeaseRepository> leases;
    private MockedStatic<ControllerFactory> controllers;
    private EngineController engine;
    private boolean originalStandingDown;
    private boolean originalEngaged;
    private long originalGeneration;
    private long originalDeadline;
    private Long originalEpoch;

    @BeforeEach
    void setUp() throws Exception {
        originalStandingDown = getBoolean("standingDown");
        originalEngaged = getBoolean("leadershipEngaged");
        originalGeneration = getLong("lifecycleGeneration");
        originalDeadline = getLong("leadershipValidUntilNanos");
        originalEpoch = (Long) field("leadershipEpoch").get(null);
        leases = Mockito.mockStatic(NodeLeaseRepository.class);
        engine = Mockito.mock(EngineController.class);
        Mockito.when(engine.getDeployedIds()).thenReturn(Set.of("deployed-channel"));
        Channel deployedChannel = Mockito.mock(Channel.class);
        Mockito.when(deployedChannel.getMetaDataIds()).thenReturn(List.of(0, 1));
        java.util.Calendar deployedAt = java.util.Calendar.getInstance();
        deployedAt.setTimeInMillis(0L);
        Mockito.when(deployedChannel.getDeployDate()).thenReturn(deployedAt);
        Mockito.when(engine.getDeployedChannel("deployed-channel")).thenReturn(deployedChannel);
        ControllerFactory factory = Mockito.mock(ControllerFactory.class);
        Mockito.when(factory.createEngineController()).thenReturn(engine);
        controllers = Mockito.mockStatic(ControllerFactory.class);
        controllers.when(ControllerFactory::getFactory).thenReturn(factory);
        leases.when(() -> NodeLeaseRepository.refreshNodePresence(anyString(), anyString(), eq(90),
                any(), any(BooleanSupplier.class))).thenReturn(true);

        NodeLease presence = new NodeLease();
        presence.setNodeId(SentinelLeadership.nodeId());
        presence.setLeaseEpoch(31L);
        presence.setExpiresTime(Instant.EPOCH.plusSeconds(90));
        leases.when(() -> NodeLeaseRepository.getNodeLease(argThat(
                name -> name != null && name.startsWith("sentinel-presence-"))))
                .thenReturn(presence);
        leases.when(() -> NodeLeaseRepository.renewNodeLease(
                argThat(name -> name != null && name.startsWith("sentinel-presence-")),
                eq(SentinelLeadership.nodeId()), eq(31L), eq(90)))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        leases.close();
        controllers.close();
        set("standingDown", originalStandingDown);
        set("leadershipEngaged", originalEngaged);
        set("lifecycleGeneration", originalGeneration);
        set("leadershipValidUntilNanos", originalDeadline);
        set("leadershipEpoch", originalEpoch);
    }

    @Test
    void staleRenewalFailureCannotClearRestartedLifecycleClaim() throws Exception {
        setClaim(100L, 7L, Long.MAX_VALUE);
        leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(Instant.EPOCH);
        leases.when(() -> NodeLeaseRepository.renewNodeLease(anyString(), anyString(), eq(7L), eq(90)))
                .thenAnswer(invocation -> {
                    set("lifecycleGeneration", 101L);
                    set("leadershipEpoch", 8L);
                    set("leadershipValidUntilNanos", Long.MAX_VALUE - 1L);
                    return false;
                });

        SentinelLeadership.heartbeat(100L);

        assertEquals(8L, field("leadershipEpoch").get(null));
        assertEquals(Long.MAX_VALUE - 1L, getLong("leadershipValidUntilNanos"));
    }

    @Test
    void staleRenewalSuccessCannotOverwriteRestartedLifecycleClaim() throws Exception {
        setClaim(150L, 7L, Long.MAX_VALUE);
        leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(Instant.EPOCH);
        leases.when(() -> NodeLeaseRepository.renewNodeLease(anyString(), anyString(), eq(7L), eq(90)))
                .thenAnswer(invocation -> {
                    set("lifecycleGeneration", 151L);
                    set("leadershipEpoch", 9L);
                    set("leadershipValidUntilNanos", Long.MAX_VALUE - 2L);
                    return true;
                });

        SentinelLeadership.heartbeat(150L);

        assertEquals(9L, field("leadershipEpoch").get(null));
        assertEquals(Long.MAX_VALUE - 2L, getLong("leadershipValidUntilNanos"));
    }

    @Test
    void staleAcquisitionIsReleasedWithoutOverwritingRestartedClaim() throws Exception {
        setClaim(200L, null, 0L);
        leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(Instant.EPOCH);
        leases.when(() -> NodeLeaseRepository.getNodeLease(eq("sentinel-engine"))).thenReturn(null);
        leases.when(() -> NodeLeaseRepository.insertNodeLease(any(NodeLease.class), eq(90)))
                .thenAnswer(invocation -> {
                    set("lifecycleGeneration", 201L);
                    set("leadershipEpoch", 12L);
                    set("leadershipValidUntilNanos", Long.MAX_VALUE);
                    return true;
                });
        leases.when(() -> NodeLeaseRepository.releaseNodeLease(anyString(), anyString(), eq(1L)))
                .thenReturn(true);

        SentinelLeadership.heartbeat(200L);

        assertEquals(12L, field("leadershipEpoch").get(null));
        assertEquals(Long.MAX_VALUE, getLong("leadershipValidUntilNanos"));
        leases.verify(() -> NodeLeaseRepository.releaseNodeLease(anyString(), anyString(), eq(1L)));
    }

    @Test
    void followerHeartbeatKeepsNodePresentInConnectorUnion() throws Exception {
        setClaim(250L, null, 0L);
        leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(Instant.EPOCH);
        NodeLease leader = new NodeLease();
        leader.setNodeId("another-node");
        leader.setLeaseEpoch(4L);
        leader.setExpiresTime(Instant.EPOCH.plusSeconds(60));
        leases.when(() -> NodeLeaseRepository.getNodeLease(eq("sentinel-engine"))).thenReturn(leader);

        SentinelLeadership.heartbeat(250L);

        leases.verify(() -> NodeLeaseRepository.refreshNodePresence(
                argThat(name -> name != null && name.startsWith("sentinel-presence-")),
                eq(SentinelLeadership.nodeId()), eq(90), eq(Map.of("deployed-channel", new NodeLeaseRepository.ChannelDeployment(Set.of(0, 1), Instant.EPOCH))), any(BooleanSupplier.class)));
        assertNull(field("leadershipEpoch").get(null));
    }

    @Test
    void unavailableLocalInventoryDoesNotPublishAnEmptySnapshotOrRenewPresence() throws Exception {
        setClaim(275L, null, 0L);
        leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(Instant.EPOCH);
        Mockito.when(engine.getDeployedIds()).thenThrow(new IllegalStateException("inventory unavailable"));

        SentinelLeadership.heartbeat(275L);

        leases.verify(() -> NodeLeaseRepository.refreshNodePresence(anyString(), anyString(), eq(90),
                any(), any(BooleanSupplier.class)), Mockito.never());
    }

    @Test
    void stalePresencePassCannotReclaimPresenceAfterShutdown() throws Exception {
        setClaim(300L, null, 0L);
        leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(Instant.EPOCH);
        leases.when(() -> NodeLeaseRepository.refreshNodePresence(anyString(), anyString(), eq(90),
                any(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    set("lifecycleGeneration", 301L);
                    return false;
                });

        SentinelLeadership.heartbeat(300L);

        leases.verify(() -> NodeLeaseRepository.getNodeLease("sentinel-engine"), Mockito.never());
    }

    @Test
    void presenceInsertedAfterShutdownIsImmediatelyReleased() throws Exception {
        setClaim(400L, null, 0L);
        leases.when(NodeLeaseRepository::getDatabaseTime).thenReturn(Instant.EPOCH);

        NodeLease inserted = new NodeLease();
        inserted.setNodeId(SentinelLeadership.nodeId());
        inserted.setLeaseEpoch(1L);
        inserted.setExpiresTime(Instant.EPOCH.plusSeconds(90));
        leases.when(() -> NodeLeaseRepository.getNodeLease(argThat(
                name -> name != null && name.startsWith("sentinel-presence-"))))
                .thenReturn(inserted);
        leases.when(() -> NodeLeaseRepository.refreshNodePresence(anyString(), anyString(), eq(90),
                any(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    set("lifecycleGeneration", 401L);
                    set("standingDown", true);
                    return true;
                });
        leases.when(() -> NodeLeaseRepository.releaseNodeLease(
                argThat(name -> name != null && name.startsWith("sentinel-presence-")),
                eq(SentinelLeadership.nodeId()), eq(1L))).thenReturn(true);

        SentinelLeadership.heartbeat(400L);

        leases.verify(() -> NodeLeaseRepository.releaseNodeLease(
                argThat(name -> name != null && name.startsWith("sentinel-presence-")),
                eq(SentinelLeadership.nodeId()), eq(1L)));
    }

    @Test
    void fallbackNodeIdentityIsStablePerInstallation() {
        String first = SentinelLeadership.fallbackNodeId("engine-host", "/srv/oie/a");
        String restart = SentinelLeadership.fallbackNodeId("engine-host", "/srv/oie/a");
        String secondInstallation = SentinelLeadership.fallbackNodeId("engine-host", "/srv/oie/b");

        assertEquals(first, restart);
        assertNotEquals(first, secondInstallation);
        assertTrue(first.startsWith("engine-host-"));
        assertTrue(first.length() <= 128);

        String longHost = "engine-host-".repeat(20);
        assertNotEquals(SentinelLeadership.fallbackNodeId(longHost, "/srv/oie/a"),
                SentinelLeadership.fallbackNodeId(longHost, "/srv/oie/b"),
                "truncating a long hostname must not discard the installation digest");
    }

    private static void setClaim(long generation, Long epoch, long deadline) throws Exception {
        set("standingDown", false);
        set("leadershipEngaged", true);
        set("lifecycleGeneration", generation);
        set("leadershipEpoch", epoch);
        set("leadershipValidUntilNanos", deadline);
    }

    private static Field field(String name) throws Exception {
        Field field = SentinelLeadership.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static boolean getBoolean(String name) throws Exception {
        return field(name).getBoolean(null);
    }

    private static long getLong(String name) throws Exception {
        return field(name).getLong(null);
    }

    private static void set(String name, Object value) throws Exception {
        field(name).set(null, value);
    }
}
