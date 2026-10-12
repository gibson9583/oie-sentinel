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
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.model.*;

class ActivityCoverageRegressionTest {
    private static final String CHANNEL = "coverage-regression";
    private static final Instant END = Instant.parse("2026-01-15T12:00:00Z");
    private static final Instant START = END.minusSeconds(3600);

    @ParameterizedTest
    @ValueSource(strings = {"complete", "empty", "stale", "gap", "partial", "startup", "retention", "slow-complete", "slow-gap"})
    void absenceRequiresDurableCoverageAcrossEntireWindow(String scenario) {
        int interval = scenario.startsWith("slow") ? 600 : 30;
        SentinelSettings config = new SentinelSettings(); config.setCollectorIntervalSeconds(interval);
        List<ActivitySample> samples = new ArrayList<>();
        for (Instant time = START; time.isBefore(END); time = time.plusSeconds(interval)) {
            if (scenario.equals("empty") || scenario.equals("retention")) continue;
            if (scenario.equals("stale") && time.isAfter(START.plusSeconds(1800))) continue;
            if ((scenario.equals("gap") || scenario.equals("slow-gap")) && time.equals(START.plusSeconds(1800))) continue;
            if (scenario.equals("partial") && time.equals(START)) continue;
            ActivitySample sample = new ActivitySample(); sample.setChannelId(CHANNEL); sample.setSampleTime(time);
            samples.add(sample);
        }
        CollectorState.getInstance().forgetChannel(CHANNEL);
        CollectorState.getInstance().markChannelStarted(CHANNEL,
                scenario.equals("startup") ? START.plusSeconds(60) : START.minusSeconds(60));
        try (var settings = mockStatic(SettingsService.class);
                var activity = mockStatic(ActivityRepository.class);
                var baseline = mockStatic(BaselineResolver.class)) {
            settings.when(SettingsService::get).thenReturn(config);
            activity.when(() -> ActivityRepository.listActivitySamples(eq(CHANNEL), any(), any())).thenReturn(samples);
            activity.when(() -> ActivityRepository.sumActivitySamplesForRange(eq(CHANNEL), any(), any()))
                    .thenReturn(new ActivityAggregate());
            baseline.when(() -> BaselineResolver.resolve(eq(CHANNEL), any(), anyString(), anyInt(), anyBoolean()))
                    .thenReturn(new BaselineResolver.Baseline(100, 10, 20, 1));
            EvaluationOutcome.Result expected = scenario.endsWith("complete")
                    ? EvaluationOutcome.Result.BREACH : EvaluationOutcome.Result.INSUFFICIENT_DATA;
            Monitor monitor = new Monitor(); monitor.setId(7); monitor.setConfigJson("{\"minCount\":10}");
            assertEquals(expected, InactivityEvaluator.evaluate(monitor, CHANNEL, END).getResult(), "inactivity");
            assertEquals(expected, LowVolumeEvaluator.evaluate(monitor, CHANNEL, END).getResult(), "low volume");
            assertEquals(expected, AnomalyEvaluator.evaluate(monitor, CHANNEL, END).getResult(), "anomaly");
        } finally {
            CollectorState.getInstance().forgetChannel(CHANNEL);
        }
    }
    @org.junit.jupiter.api.Test
    void slowCollectorAnchorAloneCannotProveAShortWindow() {
        SentinelSettings config = new SentinelSettings(); config.setCollectorIntervalSeconds(600);
        ActivitySample anchor = new ActivitySample(); anchor.setSampleTime(END.minusSeconds(620));
        try (var settings = mockStatic(SettingsService.class); var activity = mockStatic(ActivityRepository.class)) {
            settings.when(SettingsService::get).thenReturn(config);
            activity.when(() -> ActivityRepository.listActivitySamples(eq(CHANNEL), any(), any()))
                    .thenReturn(List.of(anchor));
            assertFalse(ActivityCoverage.covers(CHANNEL, END.minusSeconds(120), END));
            anchor.setSampleTime(END.minusSeconds(120));
            assertFalse(ActivityCoverage.covers(CHANNEL, END.minusSeconds(120), END),
                    "even an anchor on the start boundary needs later evidence");
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {999990L, 999991L, 7776000L})
    void boundsHistoricalRawRowsBeforeLoadingEvenAfterCadenceWasSlowed(long queryWidthSeconds) {
        SentinelSettings config = new SentinelSettings(); config.setCollectorIntervalSeconds(600);
        config.setSampleRetentionDays(90);
        Instant start = END.minusSeconds(queryWidthSeconds - 900);
        try (var settings = mockStatic(SettingsService.class); var activity = mockStatic(ActivityRepository.class)) {
            settings.when(SettingsService::get).thenReturn(config);
            assertFalse(ActivityCoverage.covers(CHANNEL, start, END));
            activity.verify(() -> ActivityRepository.listActivitySamples(CHANNEL, start.minusSeconds(900), END),
                    times(queryWidthSeconds <= 999990 ? 1 : 0));
            activity.verifyNoMoreInteractions();
        }
    }

}
