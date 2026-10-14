/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.TriggerStateRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.CollectorState;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivityAggregate;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerState;
import org.openintegrationengine.plugins.sentinel.shared.model.TriggerStatus;

/**
 * INACTIVITY evaluation — the evaluator that infers a problem from the
 * <em>absence</em> of evidence, which is precisely why most of its logic is
 * about refusing to.
 *
 * <p>Coverage requires a continuous persisted cadence and a continuous
 * STARTED stretch. Existing incidents stay open through insufficient data;
 * an unmeasured window must not invent another breach.</p>
 */
@DisplayName("InactivityEvaluator")
class InactivityEvaluatorTest {

    private static final String CHANNEL_ID = "7e91c3a5-8d02-46bf-a3c9-4b6e1f7a2d58";

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    /** One hour — both the configured and the default window in these cases. */
    private static final int WINDOW_SECONDS = 3600;
    private static final Instant WINDOW_START = NOW.minusSeconds(WINDOW_SECONDS);

    /** Default30s interval plus15s scheduling tolerance. */
    private static final int COLLECTOR_SLACK_SECONDS = 45;

    private MockedStatic<org.openintegrationengine.plugins.sentinel.server.service.SettingsService> settings;
    private MockedStatic<org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler> scheduler;
    private MockedStatic<ActivityRepository> activityRepository;
    private MockedStatic<TriggerStateRepository> triggerStateRepository;

    @BeforeEach
    void setUp() {
        settings = Mockito.mockStatic(org.openintegrationengine.plugins.sentinel.server.service.SettingsService.class);
        settings.when(org.openintegrationengine.plugins.sentinel.server.service.SettingsService::get)
                .thenReturn(new org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings());
        scheduler = Mockito.mockStatic(org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler.class);
        var runtime = Mockito.mock(org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler.class);
        scheduler.when(org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler::getInstance).thenReturn(runtime);
        Mockito.when(runtime.getCollectorIntervalSeconds(org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(30);
        activityRepository = Mockito.mockStatic(ActivityRepository.class);
        activityRepository.when(() -> ActivityRepository.readActivitySnapshot(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        triggerStateRepository = Mockito.mockStatic(TriggerStateRepository.class);
        CollectorState.getInstance().forgetChannel(CHANNEL_ID);
    }

    @AfterEach
    void tearDown() {
        CollectorState.getInstance().forgetChannel(CHANNEL_ID);
        triggerStateRepository.close();
        activityRepository.close();
        settings.close();
        scheduler.close();
    }

    // ---------------------------------------------------------------- helpers

    private static Monitor monitor(String configJson) {
        Monitor m = new Monitor();
        m.setId(41);
        m.setName("Feed silence");
        m.setMonitorType(MonitorType.INACTIVITY);
        m.setSeverity(Severity.HIGH);
        m.setEnabled(true);
        m.setConfigJson(configJson != null ? configJson.replace('\'', '"') : null);
        return m;
    }

    private static void channelStartedSince(long secondsAgo) {
        CollectorState.getInstance().markChannelStarted(CHANNEL_ID, NOW.minusSeconds(secondsAgo));
    }

    private void givenWindowSum(long received) {
        ActivityAggregate aggregate = new ActivityAggregate();
        aggregate.setReceivedSum(received);
        activityRepository.when(() -> ActivityRepository.sumActivitySamplesForRange(
                CHANNEL_ID, WINDOW_START, NOW)).thenReturn(aggregate);
    }

    /**
     * Stubs the coverage probe — the exact slack-widened range the evaluator
     * queries — with samples whose oldest lies {@code oldestSecondsAgo}
     * before {@link #NOW}. The list is ascending by sample time, as the real
     * query orders it.
     */
    private void givenSamplesOldestAt(long... secondsAgoDescending) {
        List<ActivitySample> samples = new ArrayList<>();
        // Populate the complete cadence; sparse endpoint fixtures no longer prove coverage.
        for (long secondsAgo = secondsAgoDescending[0]; secondsAgo >= 0; secondsAgo -= 30) {
            ActivitySample sample = new ActivitySample();
            sample.setChannelId(CHANNEL_ID);
            sample.setSampleTime(NOW.minusSeconds(secondsAgo));
            samples.add(sample);
        }
        givenProbeReturns(samples);
    }

    private void givenProbeReturns(List<ActivitySample> samples) {
        activityRepository.when(() -> ActivityRepository.listActivitySampleTimes(
                CHANNEL_ID, WINDOW_START.minusSeconds(COLLECTOR_SLACK_SECONDS), NOW, null,
                ActivityRepository.SAMPLE_TIME_PAGE_SIZE))
                .thenAnswer(call -> samples.stream().map(ActivitySample::getSampleTime)
                        .filter(time -> time == null || !time.isBefore(WINDOW_START.minusSeconds(COLLECTOR_SLACK_SECONDS)))
                        .toList());
    }

    private static EvaluationOutcome evaluate() {
        return InactivityEvaluator.evaluate(monitor("{'noDataForSeconds':3600}"), CHANNEL_ID, NOW);
    }

    @Nested
    @DisplayName("traffic ends the question")
    class Traffic {

        @Test
        @DisplayName("any received message is OK, with no coverage proof demanded")
        void anyTrafficIsOk() {
            // Positive evidence needs no witness protection: a message that
            // was counted was received, whatever the collector's coverage
            // looked like. Demanding the started-throughout proof here would
            // hold recovery hostage to bookkeeping — an open problem could
            // not resolve until the restart aged out of the window.
            givenWindowSum(3);
            // Deliberately: channel never marked started, no samples stubbed.
            assertEquals(EvaluationOutcome.Result.OK, evaluate().getResult());
        }
    }

    @Nested
    @DisplayName("a provable silence breaches")
    class ProvableSilence {

        @Test
        @DisplayName("zero received with full coverage and a full started stretch breaches")
        void coveredAndStartedBreaches() {
            channelStartedSince(7200);
            givenWindowSum(0);
            givenSamplesOldestAt(4000, 1800, 60);

            EvaluationOutcome outcome = evaluate();
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals("No messages received in 3600s", outcome.getMessage());
        }

        @Test
        @DisplayName("an oldest sample exactly at the window start still proves coverage")
        void coverageBoundaryIsInclusive() {
            // The rule is "at or before the window start". Off by one second
            // here and a collector whose tick happens to align with the window
            // edge would leave the monitor unjudgeable forever.
            channelStartedSince(7200);
            givenWindowSum(0);
            givenSamplesOldestAt(WINDOW_SECONDS);

            assertEquals(EvaluationOutcome.Result.BREACH, evaluate().getResult());
        }
    }

    @Nested
    @DisplayName("an unprovable silence is not judged")
    class UnprovableSilence {

        @Test
        @DisplayName("collection that began mid-window cannot prove inactivity")
        void midWindowCoverageIsInsufficient() {
            // Oldest sample is one second inside the window: everything
            // before it is unobserved, and the silence may be an artifact of
            // the plugin having just been installed.
            channelStartedSince(7200);
            givenWindowSum(0);
            givenSamplesOldestAt(WINDOW_SECONDS - 1);

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate().getResult());
        }

        @Test
        @DisplayName("no samples at all cannot prove inactivity")
        void noSamplesIsInsufficient() {
            channelStartedSince(7200);
            givenWindowSum(0);
            givenProbeReturns(List.of());

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate().getResult());
        }

        @Test
        @DisplayName("a channel started mid-window is not judged even with full sample coverage")
        void startedMidWindowIsInsufficient() {
            // The zero-delta rows the collector writes for stopped channels
            // make coverage alone worthless: a channel stopped for
            // maintenance has a perfectly covered window of zeros. Breaching
            // on the first post-restart tick is the guaranteed false positive
            // the STARTED gate exists to prevent.
            channelStartedSince(1800);
            givenWindowSum(0);
            givenSamplesOldestAt(4000, 60);

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate().getResult());
        }

        @Test
        @DisplayName("an oldest sample with no timestamp proves nothing")
        void nullOldestSampleTimeIsInsufficient() {
            // A corrupt row cannot be compared against the window start, so
            // it must not be read as coverage — and must not NPE the tick.
            channelStartedSince(7200);
            givenWindowSum(0);
            ActivitySample noTime = new ActivitySample();
            noTime.setChannelId(CHANNEL_ID);
            givenProbeReturns(List.of(noTime));

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate().getResult());
        }
    }

    @Nested
    @DisplayName("existing incidents during missing coverage")
    class AlreadyProblem {

        @Test
        @DisplayName("an open problem remains unmeasured without coverage proof")
        void openProblemBreachesThroughTheGap() {
            // A restart cannot prove a fresh breach. The trigger state
            // machine retains the incident while its measurement is unknown.
            TriggerState problem = new TriggerState();
            problem.setState(TriggerStatus.PROBLEM);
            triggerStateRepository.when(() -> TriggerStateRepository.getTriggerState(41, CHANNEL_ID, null))
                    .thenReturn(problem);
            givenWindowSum(0);
            // No started stamp, no samples: the unprovable case.
            givenProbeReturns(List.of());

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate().getResult());
        }

        @Test
        @DisplayName("an OK trigger also needs coverage")
        void nonProblemStateStillNeedsProof() {
            // The carve-out is for continuity of an existing alert only. An OK
            // trigger crossing into an unprovable window must park, not open a
            // brand-new problem on no evidence.
            TriggerState ok = new TriggerState();
            ok.setState(TriggerStatus.OK);
            triggerStateRepository.when(() -> TriggerStateRepository.getTriggerState(41, CHANNEL_ID, null))
                    .thenReturn(ok);
            givenWindowSum(0);
            givenProbeReturns(List.of());

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate().getResult());
        }
    }
}
