package org.openintegrationengine.plugins.sentinel.server.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.*;
import org.openintegrationengine.plugins.sentinel.shared.model.*;
class ClusterHealthServiceTest {
    @Test void failedJobReadDoesNotHideLeadershipRetentionOrAuthorizedCounts() {
        try(var nodes=mockStatic(NodeLeaseRepository.class);var jobs=mockStatic(JobObservationRepository.class);var settings=mockStatic(SettingsService.class)) {
            nodes.when(NodeLeaseRepository::getDatabaseTime).thenReturn(Instant.parse("2026-10-06T12:00:00Z"));
            nodes.when(NodeLeaseRepository::listActiveSentinelNodeIds).thenReturn(Set.of("node-a"));
            nodes.when(NodeLeaseRepository::listActiveDeployedChannelIds).thenReturn(Set.of("visible","hidden"));
            jobs.when(JobObservationRepository::list).thenThrow(new IllegalStateException("secret exception"));
            jobs.when(()->JobObservationRepository.storage(Set.of("visible"))).thenReturn(Map.of("pending",1L));
            var configuration=new SentinelSettings();configuration.setCollectorIntervalSeconds(30);configuration.setSampleRetentionDays(2);configuration.setTrendRetentionDays(3);
            settings.when(SettingsService::get).thenReturn(configuration);
            var result=ClusterHealthService.build(Set.of("visible"));
            assertEquals("UNKNOWN",result.get("jobs").state());assertFalse(result.get("jobs").detail().contains("secret"));
            assertEquals("OBSERVED",result.get("leader").state());assertEquals("ABSENT",((Map<?,?>)result.get("leader").data()).get("state"));
            assertEquals("OBSERVED",result.get("storage").state());jobs.verify(()->JobObservationRepository.storage(Set.of("visible")));
            var capacity=(Map<?,?>)result.get("estimates").data();assertEquals(1,capacity.get("visibleDeployedChannels"));assertEquals(5760.0,capacity.get("rawSampleRows"));assertEquals(72L,capacity.get("hourlyTrendRows"));
        }
    }
    @Test void databaseClockFailureAndMissingInventoryRemainUnknownNotEmptyHealthy() {
        try(var nodes=mockStatic(NodeLeaseRepository.class);var jobs=mockStatic(JobObservationRepository.class);var settings=mockStatic(SettingsService.class)) {
            nodes.when(NodeLeaseRepository::getDatabaseTime).thenThrow(new IllegalStateException("clock"));
            nodes.when(NodeLeaseRepository::listActiveSentinelNodeIds).thenThrow(new IllegalStateException("presence"));
            nodes.when(NodeLeaseRepository::listActiveDeployedChannelIds).thenThrow(new IllegalStateException("inventory"));
            jobs.when(JobObservationRepository::list).thenReturn(List.of());jobs.when(()->JobObservationRepository.storage(Set.of())).thenReturn(Map.of("pending",0L));
            settings.when(SettingsService::get).thenReturn(new SentinelSettings());
            var result=ClusterHealthService.build(Set.of());assertEquals("UNKNOWN",result.get("leader").state());assertEquals("UNKNOWN",result.get("nodes").state());assertEquals("UNKNOWN",result.get("estimates").state());
            assertEquals("OBSERVED",result.get("jobs").state());assertEquals(List.of(),result.get("jobs").data());
        }
    }
}
