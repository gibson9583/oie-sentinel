/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

class ActivityCoveragePagingTest {
    private static final String CHANNEL = "paged-coverage";
    private static final Instant END = Instant.parse("2026-10-13T12:00:00Z");
    private static final int PAGE_SIZE = ActivityRepository.SAMPLE_TIME_PAGE_SIZE;

    @ParameterizedTest
    @CsvSource({"14,30,30", "90,10,10", "90,30,30", "90,600,10", "90,600,600"})
    void allSupportedRetainedDurationsAndHistoricalFasterCadencesAreCovered(
            int days, int currentInterval, int historicInterval) {
        Instant start = END.minusSeconds(days * 86400L);
        SentinelSettings config = settings(currentInterval);
        AtomicInteger calls = new AtomicInteger();
        try (var settings = mockStatic(SettingsService.class); var activity = mockStatic(ActivityRepository.class)) {
            settings.when(SettingsService::get).thenReturn(config);
            activity.when(() -> ActivityRepository.listActivitySampleTimes(eq(CHANNEL), any(), eq(END),
                    nullable(Instant.class), anyInt())).thenAnswer(call -> {
                        calls.incrementAndGet();
                        Instant after = call.getArgument(3);
                        int limit = call.getArgument(4);
                        assertTrue(limit > 0 && limit <= PAGE_SIZE);
                        Instant first = after == null ? start : after.plusSeconds(historicInterval);
                        return cadence(first, END, historicInterval, limit);
                    });
            assertTrue(ActivityCoverage.covers(CHANNEL, start, END));
            long observations = days * 86400L / historicInterval + 1;
            assertEquals((observations + PAGE_SIZE - 1) / PAGE_SIZE, calls.get());
            activity.verify(() -> ActivityRepository.listActivitySamples(anyString(), any(), any()), never());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "cross-page-gap", "missing-anchor", "stale-tail", "truncated", "failure"})
    void checksAnchorCrossPageAdjacencyAndTail(String scenario) {
        Instant start = END.minusSeconds(2L * PAGE_SIZE * 30);
        AtomicInteger calls = new AtomicInteger();
        try (var settings = mockStatic(SettingsService.class); var activity = mockStatic(ActivityRepository.class)) {
            settings.when(SettingsService::get).thenReturn(settings(30));
            activity.when(() -> ActivityRepository.listActivitySampleTimes(eq(CHANNEL), any(), eq(END),
                    nullable(Instant.class), anyInt())).thenAnswer(call -> {
                        int page = calls.incrementAndGet();
                        if (page == 2 && scenario.equals("failure")) throw new IllegalStateException("page failed");
                        if (page == 2 && scenario.equals("truncated")) return List.of();
                        Instant after = call.getArgument(3);
                        Instant first = after == null ? start : after.plusSeconds(30);
                        if ((after == null && scenario.equals("missing-anchor"))
                                || (page == 2 && scenario.equals("cross-page-gap"))) first = first.plusSeconds(30);
                        Instant last = scenario.equals("stale-tail") ? END.minusSeconds(60) : END;
                        return cadence(first, last, 30, call.getArgument(4));
                    });
            if (scenario.equals("failure")) {
                assertThrows(IllegalStateException.class, () -> ActivityCoverage.covers(CHANNEL, start, END));
            } else {
                assertEquals(scenario.equals("complete"), ActivityCoverage.covers(CHANNEL, start, END));
            }
            assertTrue(calls.get() <= 3, "failed coverage must stop rather than rescan");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-page", "null-time", "duplicate", "backwards", "before-range", "after-range", "oversized", "repeated-page"})
    void malformedPagesCannotCertifyCoverageOrPreventProgress(String scenario) {
        Instant start = END.minusSeconds(PAGE_SIZE * 30L);
        List<Instant> first = cadence(start, END, 30, PAGE_SIZE);
        AtomicInteger calls = new AtomicInteger();
        try (var settings = mockStatic(SettingsService.class); var activity = mockStatic(ActivityRepository.class)) {
            settings.when(SettingsService::get).thenReturn(settings(30));
            activity.when(() -> ActivityRepository.listActivitySampleTimes(eq(CHANNEL), any(), eq(END),
                    nullable(Instant.class), anyInt())).thenAnswer(call -> {
                        calls.incrementAndGet();
                        return switch (scenario) {
                            case "null-page" -> null;
                            case "null-time" -> Arrays.asList(start, null);
                            case "duplicate" -> List.of(start, start);
                            case "backwards" -> List.of(start, start.minusSeconds(1));
                            case "before-range" -> List.of(start.minusSeconds(46));
                            case "after-range" -> List.of(END.plusSeconds(1));
                            case "oversized" -> cadence(start, END, 30, PAGE_SIZE + 1);
                            default -> first;
                        };
                    });
            assertFalse(ActivityCoverage.covers(CHANNEL, start, END));
            assertEquals(scenario.equals("repeated-page") ? 2 : 1, calls.get());
        }
    }

    @Test
    void fullPageNeedsExhaustionUnlessItReachedExactRangeEnd() {
        Instant start = END.minusSeconds(PAGE_SIZE * 30L);
        List<Instant> full = cadence(start, END.minusSeconds(30), 30, PAGE_SIZE);
        try (var settings = mockStatic(SettingsService.class); var activity = mockStatic(ActivityRepository.class)) {
            settings.when(SettingsService::get).thenReturn(settings(30));
            activity.when(() -> ActivityRepository.listActivitySampleTimes(eq(CHANNEL), any(), eq(END),
                    nullable(Instant.class), anyInt())).thenReturn(full, List.of());
            assertTrue(ActivityCoverage.covers(CHANNEL, start, END));
            activity.verify(() -> ActivityRepository.listActivitySampleTimes(CHANNEL, start.minusSeconds(45),
                    END, END.minusSeconds(30), PAGE_SIZE));
        }
    }

    @Test
    void denseMalformedHistoryHasABoundedTotalScanWithoutAnExtraQuery() {
        Instant start = END.minusSeconds(90 * 86400L);
        AtomicInteger observed = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        try (var settings = mockStatic(SettingsService.class); var activity = mockStatic(ActivityRepository.class)) {
            settings.when(SettingsService::get).thenReturn(settings(10));
            activity.when(() -> ActivityRepository.listActivitySampleTimes(eq(CHANNEL), any(), eq(END),
                    nullable(Instant.class), anyInt())).thenAnswer(call -> {
                        int limit = call.getArgument(4);
                        assertTrue(limit <= PAGE_SIZE);
                        queries.incrementAndGet();
                        assertTrue(observed.addAndGet(limit) <= 1_000_000);
                        Instant after = call.getArgument(3);
                        return cadence(after == null ? start : after.plusSeconds(1), END, 1, limit);
                    });
            assertFalse(ActivityCoverage.covers(CHANNEL, start, END));
            assertEquals(1_000_000, observed.get());
            assertEquals((1_000_000 + PAGE_SIZE - 1) / PAGE_SIZE, queries.get());
        }
    }

    @Test
    void shortenedCurrentCadenceStillRejectsHistoricalSlowGaps() {
        Instant start = END.minusSeconds(3600);
        try (var settings = mockStatic(SettingsService.class); var activity = mockStatic(ActivityRepository.class)) {
            settings.when(SettingsService::get).thenReturn(settings(10));
            activity.when(() -> ActivityRepository.listActivitySampleTimes(eq(CHANNEL), any(), eq(END),
                    nullable(Instant.class), anyInt())).thenReturn(cadence(start, END, 600, PAGE_SIZE));
            assertFalse(ActivityCoverage.covers(CHANNEL, start, END));
        }
    }

    private static SentinelSettings settings(int interval) {
        SentinelSettings config = new SentinelSettings();
        config.setCollectorIntervalSeconds(interval);
        config.setSampleRetentionDays(90);
        return config;
    }

    private static List<Instant> cadence(Instant first, Instant last, int interval, int limit) {
        List<Instant> times = new ArrayList<>(limit);
        for (Instant time = first; !time.isAfter(last) && times.size() < limit; time = time.plusSeconds(interval)) {
            times.add(time);
        }
        return times;
    }
}
