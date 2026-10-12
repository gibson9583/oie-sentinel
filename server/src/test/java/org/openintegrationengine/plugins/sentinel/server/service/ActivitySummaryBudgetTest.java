package org.openintegrationengine.plugins.sentinel.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.MonitorRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;

class ActivitySummaryBudgetTest {
    private static List<String> channels(int count) {
        return IntStream.range(0, count).mapToObj(i -> "channel-" + i).toList();
    }

    @Test void rejectsHugeWindowBeforeResolvingDefaultChannelsOrReadingSamples() {
        try (var activity = mockStatic(ActivityRepository.class);
             var monitors = mockStatic(MonitorRepository.class)) {
            for (int window : List.of(86401, Integer.MAX_VALUE)) {
                assertThrows(IllegalArgumentException.class,
                        () -> ActivityQueryService.getActivitySummary((List<String>) null, window, 1));
            }
            activity.verifyNoInteractions();
            monitors.verifyNoInteractions();
        }
    }

    @Test void rejectsChannelAndTotalWorkBudgetsBeforeAnyActivityRead() {
        try (var activity = mockStatic(ActivityRepository.class)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ActivityQueryService.getActivitySummary(channels(251), 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> ActivityQueryService.getActivitySummary(channels(12), 86400, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> ActivityQueryService.getActivitySummary(channels(250), 4000, 1));
            activity.verifyNoInteractions();
        }
    }

    @Test void defaultWatchedScopeCannotBypassTotalBudget() {
        try (var activity = mockStatic(ActivityRepository.class);
             var monitors = mockStatic(MonitorRepository.class);
             var scopes = mockStatic(ScopeResolver.class)) {
            Monitor monitor = new Monitor();
            monitors.when(() -> MonitorRepository.listMonitors(null, null, Boolean.TRUE, null))
                    .thenReturn(List.of(monitor));
            scopes.when(() -> ScopeResolver.resolveStartedChannels(monitor))
                    .thenReturn(channels(12).stream().map(id -> new ScopeResolver.ChannelTarget(id, id)).toList());
            assertThrows(IllegalArgumentException.class,
                    () -> ActivityQueryService.getActivitySummary((List<String>) null, 86400, 1));
            activity.verifyNoInteractions();
        }
    }

    @Test void deduplicatesAndNormalizesBeforeBudgetAndQueries() {
        try (var activity = mockStatic(ActivityRepository.class);
             var scopes = mockStatic(ScopeResolver.class)) {
            var result = ActivityQueryService.getActivitySummary(" a, b ,a, , b", 3600, 1);
            assertEquals(List.of("a", "b"), result.stream().map(s -> s.getChannelId()).toList());
            for (String id : List.of("a", "b")) {
                activity.verify(() -> ActivityRepository.listActivitySamples(eq(id), any(Instant.class), any(Instant.class)), times(1));
                activity.verify(() -> ActivityRepository.sumActivitySamplesForRange(eq(id), any(Instant.class), any(Instant.class)), times(1));
            }
        }
    }

    @Test void acceptsExactSourceBudgetAndChannelBoundary() {
        try (var activity = mockStatic(ActivityRepository.class);
             var scopes = mockStatic(ScopeResolver.class)) {
            // 250 * (3990 / 10 + 1) = 100,000; the next interval is refused above.
            assertEquals(250, ActivityQueryService.getActivitySummary(channels(250), 3990, 1).size());
            assertEquals(1, ActivityQueryService.getActivitySummary(List.of("a"), 86400, 1).size());
        }
    }

    @Test void emptyAuthorizedScopeStaysEmptyAndDefaultsRemainCompatible() {
        try (var activity = mockStatic(ActivityRepository.class);
             var monitors = mockStatic(MonitorRepository.class);
             var scopes = mockStatic(ScopeResolver.class)) {
            assertTrue(ActivityQueryService.getActivitySummary(List.of(), 0, 0).isEmpty());
            activity.verifyNoInteractions();
            monitors.verifyNoInteractions();
            var summary = ActivityQueryService.getActivitySummary(Arrays.asList(null, " ", "a"), -1, 0);
            assertEquals(1, summary.size());
            assertEquals(20, summary.get(0).getSparkline().size());
        }
    }
}
