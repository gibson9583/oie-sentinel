package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler;
import org.openintegrationengine.plugins.sentinel.server.service.MonitorService;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ActivityLongWindowRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"INACTIVITY", "LOW_VOLUME"})
    void completeFortnightWithOnlyFortyThousandRowsRemainsEvaluable(String type) throws Exception {
        Instant now = Instant.parse("2026-10-13T12:00:00Z");
        int duration = 14 * 86400;
        Instant start = now.minusSeconds(duration);
        String channel = "review-long-window";
        SentinelSettings settings = new SentinelSettings();
        settings.setCollectorIntervalSeconds(30);
        settings.setSampleRetentionDays(30);
        List<ActivitySample> rows = new ArrayList<>();
        for (Instant t = start.minusSeconds(600); !t.isAfter(now); t = t.plusSeconds(30)) {
            ActivitySample row = new ActivitySample(); row.setChannelId(channel); row.setSampleTime(t);
            rows.add(row);
        }
        assertTrue(rows.size() < 100_000);
        CollectorState.getInstance().markChannelStarted(channel, start.minusSeconds(600));
        Monitor monitor = new Monitor(); monitor.setId(1); monitor.setMonitorType(MonitorType.valueOf(type));
        monitor.setConfigJson("{\"noDataForSeconds\":" + duration + ",\"windowSeconds\":" + duration + ",\"minCount\":1}");
        var validateConfig = MonitorService.class.getDeclaredMethod("validateConfig", Monitor.class);
        validateConfig.setAccessible(true);
        assertDoesNotThrow(() -> validateConfig.invoke(null, monitor), "The actual save validator accepts this duration");
        try (var scheduler = mockStatic(SentinelScheduler.class); var config = mockStatic(SettingsService.class); var repo = mockStatic(ActivityRepository.class);
                var states = mockStatic(TriggerStateRepository.class)) {
            config.when(SettingsService::get).thenReturn(settings);
            SentinelScheduler runtime = mock(SentinelScheduler.class);
            scheduler.when(SentinelScheduler::getInstance).thenReturn(runtime);
            when(runtime.getCollectorIntervalSeconds(org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(settings.getCollectorIntervalSeconds());
            repo.when(() -> ActivityRepository.readActivitySnapshot(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
            repo.when(() -> ActivityRepository.sumActivitySamplesForRange(eq(channel), any(), any()))
                    .thenReturn(new ActivityAggregate());
            repo.when(() -> ActivityRepository.listActivitySampleTimes(eq(channel), any(), any(), nullable(Instant.class), anyInt()))
                    .thenAnswer(call -> {
                        Instant from = call.getArgument(1), to = call.getArgument(2), after = call.getArgument(3);
                        int limit = call.getArgument(4);
                        assertTrue(limit <= ActivityRepository.SAMPLE_TIME_PAGE_SIZE);
                        return rows.stream().map(ActivitySample::getSampleTime)
                                .filter(t -> !t.isBefore(from) && !t.isAfter(to) && (after == null || t.isAfter(after)))
                                .limit(limit).toList();
                    });
            EvaluationOutcome result = type.equals("INACTIVITY") ? InactivityEvaluator.evaluate(monitor, channel, now)
                    : LowVolumeEvaluator.evaluate(monitor, channel, now);
            assertEquals(EvaluationOutcome.Result.BREACH, result.getResult(),
                    "Complete 14-day zero-volume window at 30s cadence has " + rows.size() + " retained rows");
        } finally {
            CollectorState.getInstance().forgetChannel(channel);
        }
    }
}
