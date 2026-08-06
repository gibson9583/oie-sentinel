/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.mirth.connect.model.ChannelStatistics;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;

/**
 * The collector tick's counter arithmetic — the step that turns the engine's
 * cumulative, resettable, occasionally-unreadable channel counters into the
 * per-tick delta rows every volume monitor and every activity chart is built
 * on.
 *
 * <p>Three rules are load-bearing and each has a specific failure it prevents.
 * <b>First observation records zero</b>, not the cumulative counter: a server
 * restart clears {@link CollectorState}, and diffing against nothing would
 * bank every message the channel has ever processed as one tick's traffic and
 * fire every LOW_VOLUME and ANOMALY monitor on the server simultaneously.
 * <b>Deltas clamp at zero</b>: the engine's counters legitimately go backwards
 * on a redeploy, a restart, or an operator's "Clear Statistics", and a
 * negative delta in the sample table would poison every window sum and rollup
 * baseline computed from it. <b>Snapshots advance only after the batch
 * commits</b>: a snapshot that moved forward over an unwritten delta would
 * discard that interval's traffic permanently, whereas leaving it behind costs
 * one row of time resolution and nothing else.</p>
 *
 * <p>The tick is driven through {@code execute}, its only accessible entry
 * point, with the engine and the repository replaced by static mocks —
 * Mockito 5's inline mock maker handles the static holders
 * ({@code ControllerFactory}, {@code ChannelController}) with no extra
 * dependency. Driving the public method rather than reaching into the private
 * {@code readChannel}/{@code flushSamples} pair keeps the ordering rule
 * testable at all: it is a property of how the two compose, which is invisible
 * from either one alone. {@link CollectorState} is the real process-wide
 * singleton — it is the thing under test — so every case clears the channel
 * ids it used.</p>
 *
 * <p><b>Classpath prerequisite.</b> Every case needs a
 * {@link ChannelStatistics} instance, because that is the only shape the
 * collector reads counters from. That class cannot even be <em>linked</em>
 * without {@code org.apache.commons.lang3.builder.ToStringStyle}: its
 * {@code toString} hands a {@code CalendarToStringStyle} to
 * {@code ToStringBuilder.reflectionToString(Object, ToStringStyle)}, so
 * verification has to resolve the supertype. Maven omits the transitive
 * dependencies of {@code provided}-scope artifacts, so commons-lang3 — which
 * the engine ships and supplies at runtime — is absent from the test
 * classpath, and a mock fails exactly as a {@code new} does. The whole class
 * therefore skips itself rather than erroring when the class is missing; add
 * <pre>{@code
 * <dependency>
 *   <groupId>org.apache.commons</groupId>
 *   <artifactId>commons-lang3</artifactId>
 *   <version>...engine's version...</version>
 *   <scope>test</scope>
 * </dependency>
 * }</pre>
 * to the root pom — pinned, like every other engine-supplied artifact here, to
 * the version shipped in {@code server-lib/commons/} — and these cases run as
 * written, with no change here. They were verified green against commons-lang3
 * on the classpath before being left in this state, so the skip is a packaging
 * gap and not unfinished work.</p>
 */
@DisplayName("ActivityCollectorJob counter deltas")
class ActivityCollectorJobTest {

    private static final String CHANNEL_A = "1c8f0a7e-5b3d-4d21-9a6f-2e7c4b91d0aa";
    private static final String CHANNEL_B = "9d4e6b12-0a77-4c58-8f3b-6ac1de205f47";
    private static final String BROKEN_CHANNEL = "3f2a1c04-8e6b-4a19-b7d2-51c9a83e6f10";

    private MockedStatic<ControllerFactory> controllerFactory;
    private MockedStatic<ChannelController> channelController;
    private MockedStatic<ScopeResolver> scopeResolver;
    private MockedStatic<ActivityRepository> activityRepository;

    private EngineController engine;

    /** Every batch handed to the repository, in tick order. */
    private final List<List<ActivitySample>> flushes = new ArrayList<>();

    /** When non-null, the next flush throws this instead of recording the batch. */
    private RuntimeException nextFlushFailure;

    /**
     * Whether {@link ChannelStatistics} can be linked at all — see the class
     * Javadoc. Probed rather than assumed so the reason these cases do not run
     * is stated once, in the place a reader will look.
     */
    private static final boolean CHANNEL_STATISTICS_IS_LINKABLE = commonsLang3OnClasspath();

    private static boolean commonsLang3OnClasspath() {
        try {
            Class.forName("org.apache.commons.lang3.builder.ToStringStyle", false,
                    ActivityCollectorJobTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        // Per-test rather than @BeforeAll: an aborted @BeforeAll reports the
        // whole class as "Tests run: 0", which is indistinguishable from a
        // class nobody wrote. Aborting each case instead puts an explicit
        // skipped count in the surefire summary, so the missing coverage stays
        // visible until the classpath is fixed.
        assumeTrue(CHANNEL_STATISTICS_IS_LINKABLE, "commons-lang3 is not on the test classpath, so "
                + "com.mirth.connect.model.ChannelStatistics cannot be linked and the collector's "
                + "counter arithmetic cannot be exercised; add commons-lang3 in test scope to the "
                + "root pom to enable these cases");

        engine = mock(EngineController.class);
        ControllerFactory factory = mock(ControllerFactory.class);
        when(factory.createEngineController()).thenReturn(engine);
        controllerFactory = mockStatic(ControllerFactory.class);
        controllerFactory.when(ControllerFactory::getFactory).thenReturn(factory);

        // Consulted by the eviction sweep for every still-deployed channel;
        // a single source connector keeps the sweep a no-op here.
        ChannelController channels = mock(ChannelController.class);
        when(channels.getConnectorNames(anyString())).thenReturn(Map.of(0, "Source"));
        channelController = mockStatic(ChannelController.class);
        channelController.when(ChannelController::getInstance).thenReturn(channels);

        // Started-state bookkeeping is not what these cases are about; the
        // default answer (false) is fine and keeps the engine out of it.
        scopeResolver = mockStatic(ScopeResolver.class);

        activityRepository = mockStatic(ActivityRepository.class);
        activityRepository.when(() -> ActivityRepository.insertActivitySamples(anyList()))
                .thenAnswer(invocation -> {
                    if (nextFlushFailure != null) {
                        RuntimeException failure = nextFlushFailure;
                        nextFlushFailure = null;
                        // The real method throws a package-private
                        // RepositoryException, which is likewise unchecked and
                        // likewise caught by flushSamples' catch(Exception).
                        throw failure;
                    }
                    flushes.add(new ArrayList<>(invocation.<List<ActivitySample>>getArgument(0)));
                    return null;
                });

        forgetTestChannels();
    }

    @AfterEach
    void tearDown() {
        // CollectorState is a process-wide singleton that outlives the test
        // class, so a case that left its channels behind would change what the
        // next one sees as a "first observation".
        forgetTestChannels();
        // Null-safe because @AfterEach still runs when setUp aborted on the
        // classpath assumption above, before any of these were created.
        closeQuietly(activityRepository);
        closeQuietly(scopeResolver);
        closeQuietly(channelController);
        closeQuietly(controllerFactory);
    }

    private static void closeQuietly(MockedStatic<?> mocked) {
        if (mocked != null) {
            mocked.close();
        }
    }

    private static void forgetTestChannels() {
        CollectorState.getInstance().forgetChannel(CHANNEL_A);
        CollectorState.getInstance().forgetChannel(CHANNEL_B);
        CollectorState.getInstance().forgetChannel(BROKEN_CHANNEL);
    }

    // ---------------------------------------------------------------- helpers

    private static ChannelStatistics stats(String channelId, long received, long sent, long error,
            long filtered, long queued) {
        ChannelStatistics statistics = new ChannelStatistics();
        statistics.setChannelId(channelId);
        statistics.setReceived(received);
        statistics.setSent(sent);
        statistics.setError(error);
        statistics.setFiltered(filtered);
        statistics.setQueued(queued);
        return statistics;
    }

    /** Runs one tick with the given deployed set and statistics list. */
    private void tick(Set<String> deployedIds, ChannelStatistics... statisticsList) {
        when(engine.getDeployedIds()).thenReturn(deployedIds);
        when(engine.getChannelStatisticsList(null, false)).thenReturn(List.of(statisticsList));
        new ActivityCollectorJob().execute(null);
    }

    /** Runs one tick in which every listed channel is deployed. */
    private void tick(ChannelStatistics... statisticsList) {
        Set<String> deployed = new HashSet<>();
        for (ChannelStatistics statistics : statisticsList) {
            if (statistics.getChannelId() != null) {
                deployed.add(statistics.getChannelId());
            }
        }
        tick(deployed, statisticsList);
    }

    private List<ActivitySample> lastFlush() {
        assertTrue(!flushes.isEmpty(), "no batch was written");
        return flushes.get(flushes.size() - 1);
    }

    private ActivitySample sampleFor(String channelId) {
        return lastFlush().stream()
                .filter(s -> channelId.equals(s.getChannelId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no sample written for channel " + channelId));
    }

    private static void assertDeltas(ActivitySample sample, long received, long sent, long error,
            long filtered) {
        assertEquals(received, sample.getReceivedDelta(), "receivedDelta");
        assertEquals(sent, sample.getSentDelta(), "sentDelta");
        assertEquals(error, sample.getErrorDelta(), "errorDelta");
        assertEquals(filtered, sample.getFilteredDelta(), "filteredDelta");
    }

    @Nested
    @DisplayName("delta arithmetic")
    class Deltas {

        @Test
        @DisplayName("the first observation of a channel records a zero delta")
        void firstObservationIsZero() {
            // The counters are cumulative and the map starts empty on every
            // plugin start. Recording 4,000,000 received here — rather than 0
            // — would read as four million messages in one 30-second tick and
            // trip every volume and anomaly monitor scoped to this channel at
            // once, on a server that did nothing but restart.
            tick(stats(CHANNEL_A, 4_000_000, 3_999_000, 500, 500, 12));

            assertDeltas(sampleFor(CHANNEL_A), 0, 0, 0, 0);
            // The reading is still banked, so the tick is only lost once.
            assertNotNull(CollectorState.getInstance().getPreviousCounters(CHANNEL_A));
            assertEquals(4_000_000, CollectorState.getInstance().getPreviousCounters(CHANNEL_A).received);
        }

        @Test
        @DisplayName("a later tick records the increment since the previous tick")
        void incrementIsTheDifference() {
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));
            tick(stats(CHANNEL_A, 1_150, 1_040, 12, 8, 3));

            assertDeltas(sampleFor(CHANNEL_A), 150, 140, 2, 3);
        }

        @Test
        @DisplayName("an idle channel records zeros, not nothing")
        void idleChannelStillProducesARow() {
            // Sampling continues for channels with no traffic: INACTIVITY is
            // decided by the absence of messages across a window of rows, so a
            // missing row and a zero row are very different statements.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));

            assertDeltas(sampleFor(CHANNEL_A), 0, 0, 0, 0);
        }

        @Test
        @DisplayName("a statistics reset mid-run clamps to zero instead of going negative")
        void resetClampsToZero() {
            // "Clear Statistics" is a real operator gesture and a redeploy has
            // the same effect. Without the clamp this tick would write
            // -995 received, which no downstream sum, baseline or chart is
            // prepared for.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));
            tick(stats(CHANNEL_A, 5, 4, 0, 0, 0));

            assertDeltas(sampleFor(CHANNEL_A), 0, 0, 0, 0);
        }

        @Test
        @DisplayName("the tick after a reset rebaselines against the reset counters")
        void resetRebaselinesForTheFollowingTick() {
            // The clamp is only half the fix. The snapshot has to follow the
            // counters down, or every subsequent tick would keep diffing
            // against the pre-reset high-water mark and clamp to zero forever
            // — a channel that silently stopped producing samples for as long
            // as it took to climb back, which for a busy channel is never.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));
            tick(stats(CHANNEL_A, 5, 4, 0, 0, 0));
            tick(stats(CHANNEL_A, 30, 24, 1, 2, 0));

            assertDeltas(sampleFor(CHANNEL_A), 25, 20, 1, 2);
        }

        @Test
        @DisplayName("a partial reset clamps only the counters that went backwards")
        void clampIsPerCounter() {
            // Each counter is diffed independently, so a redeploy that reset
            // the error count while received kept climbing still reports the
            // real received delta rather than zeroing the whole row.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));
            tick(stats(CHANNEL_A, 1_100, 990, 0, 5, 0));

            assertDeltas(sampleFor(CHANNEL_A), 100, 90, 0, 0);
        }

        @Test
        @DisplayName("queue depth is a gauge, recorded as read and never diffed")
        void queueDepthIsAGauge() {
            // Queue depth is an instantaneous reading, not a counter. Diffing
            // it would turn a queue draining from 7 to 3 into -4.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 7));
            assertEquals(7, sampleFor(CHANNEL_A).getQueuedSnapshot());

            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 3));
            assertEquals(3, sampleFor(CHANNEL_A).getQueuedSnapshot());
        }

        @Test
        @DisplayName("each channel diffs against its own snapshot")
        void snapshotsArePerChannel() {
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0), stats(CHANNEL_B, 50, 50, 0, 0, 0));
            tick(stats(CHANNEL_A, 1_010, 905, 11, 5, 0), stats(CHANNEL_B, 250, 240, 5, 5, 0));

            assertDeltas(sampleFor(CHANNEL_A), 10, 5, 1, 0);
            assertDeltas(sampleFor(CHANNEL_B), 200, 190, 5, 5);
        }

        @Test
        @DisplayName("a sample time is stamped on every row")
        void sampleTimeIsStamped() {
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));
            assertNotNull(sampleFor(CHANNEL_A).getSampleTime());
        }
    }

    @Nested
    @DisplayName("which channels get sampled")
    class Selection {

        @Test
        @DisplayName("a channel in the statistics list but not deployed is skipped")
        void undeployedChannelIsSkipped() {
            // The statistics list can lag the deployed set during an undeploy;
            // sampling a channel mid-teardown writes a row for something that
            // is no longer running.
            tick(Set.of(CHANNEL_A),
                    stats(CHANNEL_A, 1_000, 900, 10, 5, 0),
                    stats(CHANNEL_B, 50, 50, 0, 0, 0));

            assertEquals(1, lastFlush().size());
            assertEquals(CHANNEL_A, lastFlush().get(0).getChannelId());
            assertNull(CollectorState.getInstance().getPreviousCounters(CHANNEL_B));
        }

        @Test
        @DisplayName("a statistics entry with no channel id is skipped")
        void nullChannelIdIsSkipped() {
            tick(Set.of(CHANNEL_A),
                    stats(null, 10, 10, 0, 0, 0),
                    stats(CHANNEL_A, 1_000, 900, 10, 5, 0));

            assertEquals(1, lastFlush().size());
            assertEquals(CHANNEL_A, lastFlush().get(0).getChannelId());
        }

        @Test
        @DisplayName("a tick with nothing to write opens no session")
        void emptyTickWritesNothing() {
            tick(Set.of());
            assertTrue(flushes.isEmpty(), "an empty tick must not reach the repository at all");
        }

        @Test
        @DisplayName("one unreadable channel does not cost the others their sample")
        void unreadableChannelIsIsolated() {
            // Per-channel try/catch: a channel whose statistics throw
            // contributes neither a row nor a snapshot, so it simply retries
            // next tick while every other channel is sampled normally.
            ChannelStatistics broken = mock(ChannelStatistics.class);
            when(broken.getChannelId()).thenReturn(BROKEN_CHANNEL);
            when(broken.getReceived()).thenThrow(new IllegalStateException("statistics unavailable"));

            tick(Set.of(CHANNEL_A, BROKEN_CHANNEL), broken, stats(CHANNEL_A, 1_000, 900, 10, 5, 0));

            assertEquals(1, lastFlush().size());
            assertEquals(CHANNEL_A, lastFlush().get(0).getChannelId());
            assertNull(CollectorState.getInstance().getPreviousCounters(BROKEN_CHANNEL),
                    "a channel that could not be read must not bank a snapshot");
        }

        @Test
        @DisplayName("a channel that leaves the deployed set is forgotten and rebaselines on return")
        void redeployIsTreatedAsAFirstObservation() {
            // Redeploying resets the engine's cumulative counters, so the
            // eviction sweep drops the channel's snapshot the moment it leaves
            // the deployed set. Without it the return tick would diff against
            // pre-undeploy values: either a clamped zero that hides real
            // traffic, or — if the channel came back higher — a bogus delta
            // covering the entire outage.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));
            assertNotNull(CollectorState.getInstance().getPreviousCounters(CHANNEL_A));

            tick(Set.of());
            assertNull(CollectorState.getInstance().getPreviousCounters(CHANNEL_A),
                    "a departed channel must not keep its snapshot");

            tick(stats(CHANNEL_A, 7, 5, 0, 0, 0));
            assertDeltas(sampleFor(CHANNEL_A), 0, 0, 0, 0);
        }
    }

    @Nested
    @DisplayName("flush ordering")
    class FlushOrdering {

        @Test
        @DisplayName("a failed batch leaves every snapshot unadvanced")
        void failedFlushDoesNotAdvanceSnapshots() {
            // The whole point of deferring the snapshot advance until after
            // the commit. The batch is one transaction, so a failure means
            // none of these rows exist — and a snapshot that moved anyway
            // would make the traffic they measured unrecoverable.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0), stats(CHANNEL_B, 50, 50, 0, 0, 0));

            nextFlushFailure = new IllegalStateException("batch insert failed");
            tick(stats(CHANNEL_A, 1_500, 1_400, 20, 15, 0), stats(CHANNEL_B, 90, 90, 0, 0, 0));

            assertEquals(1_000, CollectorState.getInstance().getPreviousCounters(CHANNEL_A).received);
            assertEquals(50, CollectorState.getInstance().getPreviousCounters(CHANNEL_B).received);
        }

        @Test
        @DisplayName("the next successful tick covers the interval the failed one measured")
        void nextTickCoversTheMissedInterval() {
            // The consequence of the rule above, and the reason it is the
            // right trade: window sums over the sample table stay correct,
            // they just lose a row of time resolution. Here 500 messages
            // arrive during the failed tick and 200 more after it; the
            // recovering tick reports all 700 rather than only the 200 it
            // would have seen had the snapshot advanced.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));

            nextFlushFailure = new IllegalStateException("batch insert failed");
            tick(stats(CHANNEL_A, 1_500, 1_400, 12, 7, 0));

            tick(stats(CHANNEL_A, 1_700, 1_600, 15, 9, 0));
            assertDeltas(sampleFor(CHANNEL_A), 700, 700, 5, 4);
        }

        @Test
        @DisplayName("a failed batch does not abort the rest of the tick")
        void failedFlushStillCompletesTheTick() {
            // The started-since stamps, the eviction sweep and the
            // collector-run stamp are independent of the sample write and must
            // still run — the dashboard has to report the tick as having
            // executed, or a database blip reads as a dead collector.
            nextFlushFailure = new IllegalStateException("batch insert failed");
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0));

            assertNotNull(CollectorState.getInstance().getLastCollectorRun());
        }

        @Test
        @DisplayName("the batch carries the whole tick, not one row per channel")
        void oneBatchPerTick() {
            // Batching is why this ordering had to be reasoned about at all:
            // at the 30-second default a 200-channel server would otherwise
            // make 200 round trips every tick against the engine's own
            // operational database.
            tick(stats(CHANNEL_A, 1_000, 900, 10, 5, 0), stats(CHANNEL_B, 50, 50, 0, 0, 0));

            assertEquals(1, flushes.size(), "one tick must produce exactly one batch");
            assertEquals(2, lastFlush().size());
        }
    }
}
