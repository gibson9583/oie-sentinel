/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.openintegrationengine.plugins.sentinel.server.db.LeaseFence;

class CollectorCadenceEpochTest {
    @Test
    void evaluatorBeforeCollectorCannotReusePreviousLeadershipObservations() {
        String channel = "cadence-leadership-test";
        Instant start = Instant.parse("2026-10-14T08:00:00Z");
        var state = CollectorState.getInstance();
        LeaseFence first = new LeaseFence("sentinel", "node-a", 1L);
        LeaseFence returned = new LeaseFence("sentinel", "node-a", 3L);
        try (var leadership = mockStatic(SentinelLeadership.class)) {
            leadership.when(SentinelLeadership::captureFence).thenReturn(first);
            state.prepareCounterEpoch(first);
            state.markChannelStarted(channel, start);
            assertTrue(state.isChannelStartedThroughout(channel, start));
            state.prepareCounterEpoch(first); // Lease renewal preserves the observed window.
            assertTrue(state.isChannelStartedThroughout(channel, start));
            leadership.when(SentinelLeadership::captureFence).thenReturn(null);
            assertFalse(state.isChannelStartedThroughout(channel, start));
            leadership.when(SentinelLeadership::captureFence).thenReturn(returned);
            assertFalse(state.isChannelStartedThroughout(channel, start), "New leader's evaluator may run first");
            state.prepareCounterEpoch(returned);
            assertFalse(state.isChannelStartedThroughout(channel, start));
            Instant newStart = start.plusSeconds(7200);
            state.markChannelStarted(channel, newStart);
            assertFalse(state.isChannelStartedThroughout(channel, start));
            assertTrue(state.isChannelStartedThroughout(channel, newStart));
            assertTrue(state.isChannelStartedThroughout(channel, newStart.plusSeconds(3600)));
        } finally {
            state.forgetChannel(channel);
            state.prepareCounterEpoch(LeaseFence.unmanaged());
        }
    }
}
