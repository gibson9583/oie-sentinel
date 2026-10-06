package org.openintegrationengine.plugins.sentinel.server.engine;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.*;
class JobObservationTest {
    @Test void standbyTicksDoNotWriteSharedFailuresOrSuccess() {
        try(var leadership=mockStatic(SentinelLeadership.class);var repository=mockStatic(JobObservationRepository.class)) {
            new ActivityCollectorJob().execute(null);new TriggerEvaluatorJob().execute(null);
            new ActivityRollupJob().execute(null);new RetentionPruneJob().execute(null);
            repository.verifyNoInteractions();
        }
    }
    @Test void partiallyCompletedPruneReportsErrorAndNextTickCanRetry() {
        var fence=new LeaseFence("sentinel-engine","node",1L);
        try(var leadership=mockStatic(SentinelLeadership.class);var repository=mockStatic(JobObservationRepository.class);
            var activity=mockStatic(ActivityRepository.class);var alerts=mockStatic(AlertEventRepository.class);
            var settings=mockStatic(org.openintegrationengine.plugins.sentinel.server.service.SettingsService.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(fence);leadership.when(()->SentinelLeadership.recheckFence(fence)).thenReturn(true);
            settings.when(org.openintegrationengine.plugins.sentinel.server.service.SettingsService::get).thenReturn(new org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings());
            activity.when(()->ActivityRepository.deleteActivityTrendOlderThan(any(),eq(fence))).thenThrow(new IllegalStateException("trend prune unavailable"));
            new RetentionPruneJob().execute(null);
            repository.verify(()->JobObservationRepository.finish(eq("prune"),anyString(),eq(fence),eq(false)));
            alerts.verifyNoInteractions();
            activity.reset();repository.reset();
            try(var connectors=mockStatic(ConnectorStatusRepository.class)) {
                new RetentionPruneJob().execute(null);
                repository.verify(()->JobObservationRepository.finish(eq("prune"),anyString(),eq(fence),eq(true)));
            }
        }
    }

    @Test void failedUnitsDoNotAdvanceSuccessAndThreadStateIsCleared() {
        var fence=new LeaseFence("sentinel-engine","node",1L);
        try(var repository=mockStatic(JobObservationRepository.class)) {
            try(var observation=JobObservation.begin("collector",fence)) { JobObservation.failedUnit(); }
            repository.verify(()->JobObservationRepository.finish(eq("collector"),anyString(),eq(fence),eq(false)));
            try(var observation=JobObservation.begin("evaluator",fence)) { }
            repository.verify(()->JobObservationRepository.finish(eq("evaluator"),anyString(),eq(fence),eq(true)));
        }
    }
    @Test void missingObservationStoreNeverPreventsWorkAndCannotClaimSuccess() {
        var fence=new LeaseFence("sentinel-engine","node",1L);
        try(var repository=mockStatic(JobObservationRepository.class)) {
            repository.when(()->JobObservationRepository.start(anyString(),anyString(),eq(fence))).thenThrow(new IllegalStateException("unavailable"));
            assertDoesNotThrow(()->{try(var observation=JobObservation.begin("collector",fence)) { JobObservation.failedUnit(); }});
            repository.verify(()->JobObservationRepository.finish(anyString(),anyString(),any(),anyBoolean()),never());
            repository.reset();try(var observation=JobObservation.begin("collector",LeaseFence.unmanaged())) { }
            repository.verifyNoInteractions();
        }
    }
}
