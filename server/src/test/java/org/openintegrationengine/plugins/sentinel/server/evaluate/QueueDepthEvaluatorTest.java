/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import com.fasterxml.jackson.databind.JsonNode;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * QUEUE_DEPTH evaluation — the one monitor built on a gauge rather than a
 * counter, which changes every rule the other activity evaluators live by.
 *
 * <p>Three consequences of gauge semantics are pinned here. <b>Only the
 * latest reading is the depth</b>: a queue that was deep an hour ago and is
 * shallow now is shallow, full stop — history feeds the duration question,
 * never the depth. <b>A stale reading is no reading</b>: a counter sum ages
 * gracefully, but a gauge measured ten minutes ago describes the past, and
 * alerting (or resolving) on it would be reporting history as the present —
 * so anything older than the maximum legal collector interval (600s) is
 * INSUFFICIENT_DATA. <b>Duration is reconstructed from rows, not
 * remembered</b>: the sustained-run walk starts at the newest sample and
 * extends backwards until a below-threshold sample ends it, so the reported
 * duration is a lower bound that survives a plugin restart — a backlog is
 * exactly the condition that outlives one.</p>
 *
 * <p>The repository is stubbed with exact range arguments ({@code now -
 * (minDurationSeconds + 600s)}), pinning the read-window arithmetic alongside
 * the verdicts, and sample lists are built ascending by time exactly as the
 * real query orders them.</p>
 */
@DisplayName("QueueDepthEvaluator")
class QueueDepthEvaluatorTest {

    private static final String CHANNEL_ID = "b4c8e2d0-6f13-4a97-8e5b-d20a91c7f364";

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    /** The config used by most cases: depth 100 held for 300s. */
    private static final String CONFIG = "{'threshold':100,'minDurationSeconds':300}";

    /** Mirrors the evaluator's read-window slack / staleness bound. */
    private static final int COLLECTOR_SLACK_SECONDS = 600;

    private MockedStatic<ActivityRepository> activityRepository;

    @BeforeEach
    void setUp() {
        activityRepository = Mockito.mockStatic(ActivityRepository.class);
    }

    @AfterEach
    void tearDown() {
        activityRepository.close();
    }

    // ---------------------------------------------------------------- helpers

    private static Monitor monitor(String configJson) {
        Monitor m = new Monitor();
        m.setId(51);
        m.setName("Backlog watch");
        m.setMonitorType(MonitorType.QUEUE_DEPTH);
        m.setSeverity(Severity.AVERAGE);
        m.setEnabled(true);
        m.setConfigJson(configJson != null ? configJson.replace('\'', '"') : null);
        return m;
    }

    private static ActivitySample sample(Long secondsAgo, long queuedSnapshot) {
        ActivitySample sample = new ActivitySample();
        sample.setChannelId(CHANNEL_ID);
        sample.setSampleTime(secondsAgo != null ? NOW.minusSeconds(secondsAgo) : null);
        sample.setQueuedSnapshot(queuedSnapshot);
        return sample;
    }

    /**
     * Stubs the exact read window for {@link #CONFIG}: {@code minDuration
     * (300) + slack (600)} seconds back from {@link #NOW}. Samples must be
     * passed ascending by time (oldest first), as the real query returns them.
     */
    private void givenSamples(ActivitySample... ascending) {
        activityRepository.when(() -> ActivityRepository.listActivitySamples(
                CHANNEL_ID, NOW.minusSeconds(300 + COLLECTOR_SLACK_SECONDS), NOW))
                .thenReturn(new ArrayList<>(List.of(ascending)));
    }

    private static EvaluationOutcome evaluate() {
        return QueueDepthEvaluator.evaluate(monitor(CONFIG), CHANNEL_ID, NOW);
    }

    private static JsonNode value(EvaluationOutcome outcome) {
        try {
            return Json.mapper().readTree(outcome.getValueJson());
        } catch (Exception e) {
            throw new AssertionError("value JSON is not parseable: " + outcome.getValueJson(), e);
        }
    }

    @Nested
    @DisplayName("no usable reading")
    class NoReading {

        @Test
        @DisplayName("no samples in the read window is INSUFFICIENT_DATA with a null depth")
        void noSamplesIsInsufficient() {
            // "We have no reading" and "the queue is empty" are opposite
            // conclusions; the null depth in the value JSON is what keeps
            // them from sharing a representation.
            givenSamples();
            EvaluationOutcome outcome = evaluate();

            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, outcome.getResult());
            assertTrue(value(outcome).path("queueDepth").isNull());
            assertTrue(value(outcome).path("sampleTimeIso").isNull());
        }

        @Test
        @DisplayName("a reading older than any legal collector interval is stale, not current")
        void staleReadingIsInsufficient() {
            // 601 seconds is past the slowest collector configuration the
            // settings service accepts, so the collector has stopped — the
            // depth is whatever it is NOW, and nobody knows what that is.
            // Treating the old number as current could both page on a drained
            // queue and resolve a growing one.
            givenSamples(sample(601L, 5_000));
            assertEquals(EvaluationOutcome.Result.INSUFFICIENT_DATA, evaluate().getResult());
        }

        @Test
        @DisplayName("a reading exactly at the staleness bound is still usable")
        void stalenessBoundaryIsInclusive() {
            // The bound is sized to the slowest legal collector; a strict
            // comparison one second tighter would flap every monitor on a
            // server actually configured at that interval.
            givenSamples(sample(600L, 5_000));
            // The point is that this is a judgment at all (the 600s-old deep
            // reading anchors a 600s run, past the 300s minimum), not the
            // INSUFFICIENT_DATA a one-second-tighter staleness check would
            // have produced.
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate().getResult());
        }

        @Test
        @DisplayName("rows with no timestamp are skipped when finding the latest reading")
        void nullTimestampRowsAreNotTheLatestReading() {
            // The newest row is corrupt (no time) and deep; the newest USABLE
            // row is shallow. The verdict must come from the usable one — a
            // gauge reading that cannot be dated cannot be judged fresh.
            givenSamples(sample(60L, 5), sample(null, 90_000));
            assertEquals(EvaluationOutcome.Result.OK, evaluate().getResult());
        }
    }

    @Nested
    @DisplayName("depth is the latest reading only")
    class GaugeSemantics {

        @Test
        @DisplayName("a drained queue is OK no matter how deep it was earlier in the window")
        void historyDoesNotInflateTheDepth() {
            // The counter mistake this guards against: summing or maxing the
            // window would keep a recovered queue in breach for a full window
            // after it drained.
            givenSamples(sample(500L, 90_000), sample(300L, 50_000), sample(30L, 40));
            EvaluationOutcome outcome = evaluate();

            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
            assertEquals(40, value(outcome).path("queueDepth").asLong());
        }

        @Test
        @DisplayName("the threshold is inclusive: a depth exactly at it counts as deep")
        void thresholdIsInclusive() {
            // "at or above 100" — with a run long enough, exactly 100 must
            // breach, or the configured number quietly means 101.
            givenSamples(sample(400L, 100), sample(30L, 100));
            assertEquals(EvaluationOutcome.Result.BREACH, evaluate().getResult());
        }
    }

    @Nested
    @DisplayName("the sustained-duration requirement")
    class SustainedDuration {

        @Test
        @DisplayName("a fresh spike is OK — deep but not yet for long enough — and that OK is real")
        void freshSpikeIsOk() {
            // A queue absorbing a burst while a destination reconnects is
            // doing its job. And this is a measured OK, not a data gap: an
            // open problem from an earlier backlog is entitled to resolve on
            // it (contrast the INSUFFICIENT_DATA cases, which never resolve
            // anything).
            givenSamples(sample(500L, 10), sample(299L, 90_000), sample(30L, 90_000));
            EvaluationOutcome outcome = evaluate();

            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
            assertEquals(299, value(outcome).path("sustainedSeconds").asLong());
        }

        @Test
        @DisplayName("the duration boundary is inclusive: a run of exactly minDurationSeconds breaches")
        void durationBoundaryIsInclusive() {
            givenSamples(sample(500L, 10), sample(300L, 200), sample(30L, 200));
            EvaluationOutcome outcome = evaluate();

            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals("Queue depth 200 has been at or above 100 for 300s", outcome.getMessage());
        }

        @Test
        @DisplayName("one below-threshold sample resets the run")
        void shallowSampleBreaksTheRun() {
            // The requirement is CONTINUOUS depth. A dip below the threshold
            // 200s ago means the current stretch is only 200s old, however
            // deep the queue was before the dip — a queue that oscillates
            // across the threshold is the monitor's own hysteresis problem,
            // not a sustained backlog.
            givenSamples(sample(700L, 90_000), sample(200L, 50), sample(150L, 90_000), sample(30L, 90_000));
            EvaluationOutcome outcome = evaluate();

            assertEquals(EvaluationOutcome.Result.OK, outcome.getResult());
            assertEquals(150, value(outcome).path("sustainedSeconds").asLong());
        }

        @Test
        @DisplayName("an undated row mid-run neither extends nor breaks it")
        void nullTimestampRowIsNeutralInTheRun() {
            // A corrupt row cannot be placed in time, so it must be skipped:
            // counting it as deep would fabricate duration, counting it as
            // shallow would erase real duration. With it skipped, the run
            // reaches the 400s-old sample and breaches.
            givenSamples(sample(400L, 500), sample(null, 1), sample(30L, 500));
            EvaluationOutcome outcome = evaluate();

            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals(400, value(outcome).path("sustainedSeconds").asLong());
        }

        @Test
        @DisplayName("the reported duration is measured from the run's first sample to now")
        void durationIsALowerBound() {
            // The crossing happened somewhere between the shallow sample and
            // the first deep one, unobserved. Reporting from the first deep
            // sample makes the number a lower bound — the evaluator may
            // under-claim a backlog's age but never exaggerate it.
            givenSamples(sample(600L, 10), sample(450L, 300), sample(30L, 300));
            assertEquals(450, value(evaluate()).path("sustainedSeconds").asLong());
        }
    }

    @Nested
    @DisplayName("config handling")
    class ConfigHandling {

        @Test
        @DisplayName("defaults: threshold 1000 held for 300s, read window sized to match")
        void defaultsApply() {
            // The exact-argument stub doubles as the read-window assertion:
            // default minDuration (300) + slack (600). A depth of 1000 at the
            // default threshold of 1000, held for 400s, breaches.
            activityRepository.when(() -> ActivityRepository.listActivitySamples(
                    CHANNEL_ID, NOW.minusSeconds(900), NOW))
                    .thenReturn(List.of(sample(400L, 1_000), sample(30L, 1_000)));

            EvaluationOutcome outcome = QueueDepthEvaluator.evaluate(monitor(null), CHANNEL_ID, NOW);
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals(1_000, value(outcome).path("threshold").asLong());
        }

        @Test
        @DisplayName("negative config values clamp to zero instead of inverting the comparisons")
        void negativeConfigClampsToZero() {
            // threshold -5 -> 0 and minDuration -10 -> 0: every reading is at
            // or above the floor and every duration suffices, so any fresh
            // sample breaches. Degenerate, but predictable — and the clamped
            // values are what the UI is told.
            activityRepository.when(() -> ActivityRepository.listActivitySamples(
                    CHANNEL_ID, NOW.minusSeconds(COLLECTOR_SLACK_SECONDS), NOW))
                    .thenReturn(List.of(sample(30L, 0)));

            EvaluationOutcome outcome = QueueDepthEvaluator.evaluate(
                    monitor("{'threshold':-5,'minDurationSeconds':-10}"), CHANNEL_ID, NOW);
            assertEquals(EvaluationOutcome.Result.BREACH, outcome.getResult());
            assertEquals(0, value(outcome).path("threshold").asLong());
            assertEquals(0, value(outcome).path("minDurationSeconds").asLong());
        }
    }
}
