/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ConnectorStatusRepository;
import org.openintegrationengine.plugins.sentinel.server.db.LeaseFence;
import org.openintegrationengine.plugins.sentinel.server.db.MatchSampleRepository;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/** Log match samples are pruned with the raw-sample retention, under the leader's fence, like the rows beside them. */
class RetentionPruneLogMatchTest {

    @Test
    void logMatchSamplesOlderThanTheSampleRetentionArePrunedWithTheFence() {
        LeaseFence fence = new LeaseFence("sentinel-engine", "node-a", 7L);
        AtomicReference<Instant> cutoff = new AtomicReference<>();
        Instant before = Instant.now();
        try (MockedStatic<SentinelLeadership> leadership = mockStatic(SentinelLeadership.class);
                MockedStatic<SettingsService> settings = mockStatic(SettingsService.class);
                MockedStatic<ActivityRepository> activity = mockStatic(ActivityRepository.class);
                MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class);
                MockedStatic<ConnectorStatusRepository> status = mockStatic(ConnectorStatusRepository.class);
                MockedStatic<MatchSampleRepository> logMatches = mockStatic(MatchSampleRepository.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(fence);
            leadership.when(() -> SentinelLeadership.recheckFence(fence)).thenReturn(true);
            settings.when(SettingsService::get).thenReturn(retention(3));
            logMatches.when(() -> MatchSampleRepository.deleteSamplesOlderThan(any(Instant.class), eq(fence)))
                    .thenAnswer(call -> {
                        cutoff.set(call.getArgument(0));
                        return 4;
                    });

            new RetentionPruneJob().execute(null);

            logMatches.verify(() -> MatchSampleRepository.deleteSamplesOlderThan(any(Instant.class), eq(fence)));
        }
        Instant expectedLow = before.minus(Duration.ofDays(3));
        Instant expectedHigh = Instant.now().minus(Duration.ofDays(3));
        assertFalse(cutoff.get().isBefore(expectedLow), cutoff.get() + " is before " + expectedLow);
        assertFalse(cutoff.get().isAfter(expectedHigh), cutoff.get() + " is after " + expectedHigh);
    }

    @Test
    void aLostFenceStopsThePruneBeforeTheLogMatchSamples() {
        LeaseFence fence = new LeaseFence("sentinel-engine", "node-a", 7L);
        try (MockedStatic<SentinelLeadership> leadership = mockStatic(SentinelLeadership.class);
                MockedStatic<SettingsService> settings = mockStatic(SettingsService.class);
                MockedStatic<ActivityRepository> activity = mockStatic(ActivityRepository.class);
                MockedStatic<AlertEventRepository> alerts = mockStatic(AlertEventRepository.class);
                MockedStatic<ConnectorStatusRepository> status = mockStatic(ConnectorStatusRepository.class);
                MockedStatic<MatchSampleRepository> logMatches = mockStatic(MatchSampleRepository.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(fence);
            // Held through the first three units, lost before the fourth recheck.
            leadership.when(() -> SentinelLeadership.recheckFence(fence)).thenReturn(true, true, true, false);
            settings.when(SettingsService::get).thenReturn(retention(7));

            new RetentionPruneJob().execute(null);

            status.verify(() -> ConnectorStatusRepository.deleteConnectorStatusEventsOlderThan(any(), eq(fence)));
            logMatches.verifyNoInteractions();
        }
    }

    @Test
    void aNonLeaderPrunesNothing() {
        try (MockedStatic<SentinelLeadership> leadership = mockStatic(SentinelLeadership.class);
                MockedStatic<MatchSampleRepository> logMatches = mockStatic(MatchSampleRepository.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(null);
            new RetentionPruneJob().execute(null);
            logMatches.verifyNoInteractions();
        }
    }

    private static SentinelSettings retention(int sampleDays) {
        SentinelSettings settings = new SentinelSettings();
        settings.setSampleRetentionDays(sampleDays);
        return settings;
    }
}
