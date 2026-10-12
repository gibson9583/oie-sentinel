package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import com.mirth.connect.model.Channel;
import com.mirth.connect.server.controllers.ChannelController;
import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.LeaseFence;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityTrend;

class ActivityRollupRecoveryTest {
    private final LeaseFence fence = LeaseFence.unmanaged();
    private static Channel channel(String id) { Channel c = new Channel(); c.setId(id); return c; }
    private static ActivitySample sample() {
        ActivitySample s = new ActivitySample(); s.setReceivedDelta(7); s.setQueuedSnapshot(4); return s;
    }

    @Test void failedFirstHourIsRetriedBeforeAnyLaterHourCanAdvanceCheckpoint() {
        Instant first = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(3, ChronoUnit.HOURS);
        try (var repository = mockStatic(ActivityRepository.class);
             var leadership = mockStatic(SentinelLeadership.class);
             var controller = mockStatic(ChannelController.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(fence);
            leadership.when(() -> SentinelLeadership.recheckFence(fence)).thenReturn(true);
            ChannelController channels = mock(ChannelController.class);
            controller.when(ChannelController::getInstance).thenReturn(channels);
            when(channels.getChannels(null)).thenReturn(List.of(channel("a"), channel("b")));
            repository.when(ActivityRepository::getEarliestActivitySampleTime).thenReturn(first.plusSeconds(30));
            AtomicBoolean fail = new AtomicBoolean(true);
            repository.when(() -> ActivityRepository.listActivitySamples(anyString(), any(), any()))
                    .thenAnswer(call -> {
                        if (call.getArgument(0).equals("b") && fail.get()) throw new RuntimeException("read failed");
                        return List.of(sample());
                    });
            List<List<ActivityTrend>> commits = new ArrayList<>();
            repository.when(() -> ActivityRepository.replaceActivityTrendsForHour(anyList(), eq(fence)))
                    .thenAnswer(call -> { commits.add(new ArrayList<>(call.getArgument(0))); return null; });
            new ActivityRollupJob().execute(null);
            assertTrue(commits.isEmpty(), "one computed channel must not commit before another fails");
            fail.set(false);
            new ActivityRollupJob().execute(null);
            assertEquals(3, commits.size());
            assertEquals(first, commits.get(0).get(0).getHourBucket());
            for (var hour : commits) {
                assertEquals(List.of("a", "b"), hour.stream().map(ActivityTrend::getChannelId).toList());
                assertEquals(7, hour.get(0).getReceivedSum());
                assertEquals(hour.get(0).getHourBucket(), hour.get(1).getHourBucket());
            }
        }
    }

    @Test void writeFailureStopsLaterHoursAndRestartReplaysLatestHour() {
        Instant latest = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(3, ChronoUnit.HOURS);
        try (var repository = mockStatic(ActivityRepository.class);
             var leadership = mockStatic(SentinelLeadership.class);
             var controller = mockStatic(ChannelController.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(fence);
            leadership.when(() -> SentinelLeadership.recheckFence(fence)).thenReturn(true);
            ChannelController channels = mock(ChannelController.class);
            controller.when(ChannelController::getInstance).thenReturn(channels);
            when(channels.getChannels(null)).thenReturn(List.of(channel("a")));
            repository.when(ActivityRepository::getLatestTrendHourBucket).thenReturn(latest);
            repository.when(() -> ActivityRepository.listActivitySamples(anyString(), any(), any())).thenReturn(List.of(sample()));
            repository.when(() -> ActivityRepository.replaceActivityTrendsForHour(anyList(), eq(fence)))
                    .thenThrow(new RuntimeException("commit failed"));
            new ActivityRollupJob().execute(null);
            repository.verify(() -> ActivityRepository.replaceActivityTrendsForHour(anyList(), eq(fence)), times(1));
            repository.verify(() -> ActivityRepository.listActivitySamples("a", latest, latest.plus(1, ChronoUnit.HOURS).minusMillis(1)));
        }
    }

    @Test void leadershipLossBetweenChannelsLeavesNoPartialHour() {
        try (var repository = mockStatic(ActivityRepository.class);
             var leadership = mockStatic(SentinelLeadership.class);
             var controller = mockStatic(ChannelController.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(fence);
            leadership.when(() -> SentinelLeadership.recheckFence(fence)).thenReturn(true, false);
            ChannelController channels = mock(ChannelController.class);
            controller.when(ChannelController::getInstance).thenReturn(channels);
            when(channels.getChannels(null)).thenReturn(List.of(channel("a"), channel("b")));
            repository.when(() -> ActivityRepository.listActivitySamples(anyString(), any(), any())).thenReturn(List.of(sample()));
            new ActivityRollupJob().execute(null);
            repository.verify(() -> ActivityRepository.replaceActivityTrendsForHour(anyList(), any()), never());
        }
    }
}
