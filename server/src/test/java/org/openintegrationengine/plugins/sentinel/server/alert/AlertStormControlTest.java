/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

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

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * Storm control: the per-action notification ceiling and flap detection —
 * the two guards between "one shared dependency took down thirty channels"
 * and thirty pages.
 *
 * <p>Both are reconstructed from durable rows rather than remembered, and
 * both entry points take the decision instant as a parameter, so these are
 * fixed-clock tests with no tolerance games. The ceiling's snapshot cache is
 * process-wide static state; every case starts and ends with
 * {@link AlertStormControl#reset()} so no snapshot (or reservation) leaks
 * between cases, and the reservation cases exploit the reuse deliberately —
 * consecutive verdicts at one instant share a snapshot, which is exactly the
 * situation concurrent dispatch tasks are in mid-storm.</p>
 *
 * <p>The properties most worth pinning, because their failure modes are
 * silent: a rollup row counted as an ordinary send would let a window that
 * tripped once never fall back under its ceiling (each window tripping on
 * the strength of its own rollups); a second rollup slot per window turns
 * the rollup into the storm it summarizes; and any storm-control read
 * failing <em>closed</em> would swallow real pages on a bookkeeping error —
 * the one failure an operator would never think to look for.</p>
 */
@DisplayName("AlertStormControl")
class AlertStormControlTest {

    private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    private static final String CHANNEL_A = "1c8f0a7e-5b3d-4d21-9a6f-2e7c4b91d0aa";
    private static final String CHANNEL_B = "9d4e6b12-0a77-4c58-8f3b-6ac1de205f47";
    private static final String CHANNEL_C = "3f2a1c04-8e6b-4a19-b7d2-51c9a83e6f10";

    private MockedStatic<AlertEventRepository> alertEventRepository;
    private MockedStatic<ActionDispatchLogRepository> dispatchLogRepository;
    private MockedStatic<ScopeResolver> scopeResolver;

    /** The recent events the ceiling scan walks. */
    private final List<AlertEvent> recentEvents = new ArrayList<>();

    private long nextEventId = 100;

    @BeforeEach
    void setUp() {
        AlertStormControl.reset();
        alertEventRepository = Mockito.mockStatic(AlertEventRepository.class);
        alertEventRepository.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt()))
                .thenReturn(recentEvents);
        dispatchLogRepository = Mockito.mockStatic(ActionDispatchLogRepository.class);
        scopeResolver = Mockito.mockStatic(ScopeResolver.class);
        scopeResolver.when(() -> ScopeResolver.channelName(anyString()))
                .thenAnswer(invocation -> "name:" + invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        scopeResolver.close();
        dispatchLogRepository.close();
        alertEventRepository.close();
        AlertStormControl.reset();
    }

    // ---------------------------------------------------------------- helpers

    private static Action ceilingAction(Integer max, Integer windowSeconds) {
        Action action = new Action();
        action.setId(10);
        action.setName("Page on-call");
        action.setEnabled(true);
        action.setMaxNotificationsPerWindow(max);
        action.setRollupWindowSeconds(windowSeconds);
        return action;
    }

    /** An event in the scan set, opened {@code openedSecondsAgo} before {@link #NOW}. */
    private AlertEvent event(String channelId, long openedSecondsAgo, AlertStatus status,
            boolean suppressed) {
        AlertEvent event = new AlertEvent();
        event.setId(nextEventId++);
        event.setMonitorId(7);
        event.setChannelId(channelId);
        event.setSeverity(Severity.HIGH);
        event.setStatus(status);
        event.setSuppressed(suppressed);
        event.setOpenedTime(NOW.minusSeconds(openedSecondsAgo));
        recentEvents.add(event);
        return event;
    }

    /** Attaches dispatch rows for action 10 to an event: one per age, oldest first. */
    private void rowsFor(AlertEvent event, String marker, long... secondsAgo) {
        List<ActionDispatchLog> rows = new ArrayList<>();
        for (long age : secondsAgo) {
            ActionDispatchLog row = new ActionDispatchLog();
            row.setAlertEventId(event.getId());
            row.setActionId(10);
            row.setDispatchTime(NOW.minusSeconds(age));
            row.setSuccess(true);
            row.setErrorMessage(marker);
            rows.add(row);
        }
        dispatchLogRepository.when(() -> ActionDispatchLogRepository
                .listActionDispatchLogsForEvent(event.getId())).thenReturn(rows);
    }

    private static AlertStormControl.CeilingVerdict verdict(Action action) {
        return AlertStormControl.ceilingVerdict(action, NOW);
    }

    @Nested
    @DisplayName("ceiling: configuration gates")
    class CeilingConfiguration {

        @Test
        @DisplayName("either half of the pair missing means no ceiling at all")
        void halfConfiguredIsNoCeiling() {
            // A maximum with no window has nothing to count against; a window
            // with no maximum never trips. Neither half implies a default for
            // the other, and inventing one would impose rate limiting nobody
            // configured.
            event(CHANNEL_A, 60, AlertStatus.PROBLEM, false);

            assertEquals(AlertStormControl.CeilingOutcome.SEND,
                    verdict(ceilingAction(null, 600)).outcome());
            assertEquals(AlertStormControl.CeilingOutcome.SEND,
                    verdict(ceilingAction(3, null)).outcome());
            assertEquals(AlertStormControl.CeilingOutcome.SEND,
                    verdict(ceilingAction(-1, 600)).outcome());
        }

        @Test
        @DisplayName("a repository failure fails open to SEND")
        void repositoryFailureFailsOpen() {
            // Suppressing a real page because a bookkeeping read failed is
            // strictly worse than the duplicate page the ceiling prevents —
            // and it is the failure mode nobody would think to investigate.
            alertEventRepository.when(() -> AlertEventRepository.listRecentAlertEvents(anyInt()))
                    .thenThrow(new IllegalStateException("database unavailable"));

            assertEquals(AlertStormControl.CeilingOutcome.SEND,
                    verdict(ceilingAction(3, 600)).outcome());
        }

        @Test
        @DisplayName("an absurdly small window is clamped up to the floor")
        void tinyWindowIsClamped() {
            // The floor (10s) must stay above the snapshot TTL, or a
            // reservation taken against the current snapshot could outlive
            // the window it was counted for. The clamp is visible on the
            // verdict, which is what the rollup message reports to operators.
            AlertEvent e = event(CHANNEL_A, 5, AlertStatus.PROBLEM, false);
            rowsFor(e, null, 1, 2);

            AlertStormControl.CeilingVerdict verdict = verdict(ceilingAction(0, 3));
            assertEquals(AlertStormControl.CeilingOutcome.ROLLUP, verdict.outcome());
            assertEquals(10, verdict.windowSeconds());
        }
    }

    @Nested
    @DisplayName("ceiling: counting and reservation")
    class CeilingCounting {

        @Test
        @DisplayName("under the ceiling sends, and each SEND reserves its slot on the live snapshot")
        void sendsReserveTheirSlots() {
            // Two logged sends against a ceiling of three: one slot left. The
            // first verdict takes it; the second — same instant, same cached
            // snapshot, exactly the position of a concurrent dispatch task
            // whose log row is not yet committed — must see the reservation
            // and roll up. Without reservations every task between two scans
            // would spend the same last slot.
            AlertEvent e = event(CHANNEL_A, 60, AlertStatus.PROBLEM, false);
            rowsFor(e, null, 30, 40);
            Action action = ceilingAction(3, 600);

            assertEquals(AlertStormControl.CeilingOutcome.SEND, verdict(action).outcome());

            AlertStormControl.CeilingVerdict second = verdict(action);
            assertEquals(AlertStormControl.CeilingOutcome.ROLLUP, second.outcome());
            assertEquals(3, second.sent(), "the reserved send must be counted, not just logged rows");
        }

        @Test
        @DisplayName("at the ceiling: one rollup naming the affected channels, then silence")
        void atCeilingRollsUpExactlyOnce() {
            // The rollup is the entire point of the ceiling — thirty channels
            // breaking together cost one notification. The second over-limit
            // send in the same window gets SILENT: a rollup per suppressed
            // send would just be the storm with a different subject line.
            AlertEvent e = event(CHANNEL_A, 60, AlertStatus.PROBLEM, false);
            rowsFor(e, null, 10, 20, 30);
            Action action = ceilingAction(3, 600);

            AlertStormControl.CeilingVerdict first = verdict(action);
            assertEquals(AlertStormControl.CeilingOutcome.ROLLUP, first.outcome());
            assertEquals(3, first.sent());
            assertEquals(600, first.windowSeconds());
            assertEquals(List.of("name:" + CHANNEL_A), first.channels());

            assertEquals(AlertStormControl.CeilingOutcome.SILENT, verdict(action).outcome());
        }

        @Test
        @DisplayName("a rollup already in the log claims the window's slot")
        void loggedRollupSilencesTheWindow() {
            // The one-per-window guarantee must hold across snapshot rebuilds
            // and restarts, which is why the marker row exists: a fresh JVM
            // reading the log mid-storm must not send a second rollup.
            AlertEvent e = event(CHANNEL_A, 60, AlertStatus.PROBLEM, false);
            rowsFor(e, null, 10, 20, 30);
            AlertEvent rolled = event(CHANNEL_B, 90, AlertStatus.PROBLEM, false);
            rowsFor(rolled, AlertStormControl.ROLLUP_MARKER, 15);

            assertEquals(AlertStormControl.CeilingOutcome.SILENT,
                    verdict(ceilingAction(3, 600)).outcome());
        }

        @Test
        @DisplayName("rollup rows do not count toward the ceiling")
        void rollupRowsAreNotOrdinarySends() {
            // Two ordinary sends plus one rollup row, ceiling three: if the
            // rollup counted, the window would already be full — and every
            // window would trip on the strength of the previous one's rollup,
            // never falling back under its ceiling again.
            AlertEvent e = event(CHANNEL_A, 60, AlertStatus.PROBLEM, false);
            rowsFor(e, null, 30, 40);
            AlertEvent rolled = event(CHANNEL_B, 1_200, AlertStatus.PROBLEM, false);
            rowsFor(rolled, AlertStormControl.ROLLUP_MARKER, 300);

            assertEquals(AlertStormControl.CeilingOutcome.SEND,
                    verdict(ceilingAction(3, 600)).outcome());
        }

        @Test
        @DisplayName("rows older than the window are not counted")
        void staleRowsAreOutsideTheWindow() {
            // A still-open problem's dispatch history can be hours old; only
            // traffic inside [now - window, now] is budget. Counting history
            // would turn the ceiling into a lifetime quota.
            AlertEvent e = event(CHANNEL_A, 3_000, AlertStatus.PROBLEM, false);
            rowsFor(e, null, 1_200, 2_400);

            assertEquals(AlertStormControl.CeilingOutcome.SEND,
                    verdict(ceilingAction(1, 600)).outcome());
        }

        @Test
        @DisplayName("a ceiling of zero is rollup-only mode")
        void zeroCeilingMeansRollupOnly() {
            // max = 0 is a coherent operator request: never page individually,
            // give me one aggregate per window. The first verdict must go
            // straight to ROLLUP without any sends having happened.
            event(CHANNEL_A, 60, AlertStatus.PROBLEM, false);

            assertEquals(AlertStormControl.CeilingOutcome.ROLLUP,
                    verdict(ceilingAction(0, 600)).outcome());
        }
    }

    @Nested
    @DisplayName("ceiling: the affected-channel list")
    class AffectedChannels {

        @Test
        @DisplayName("the rollup names unsuppressed channels opened in the window, deduplicated by id")
        void channelListIsWindowedAndUnsuppressed() {
            // CHANNEL_A and CHANNEL_B opened problems in the window; the
            // suppressed event and the old still-open one contribute traffic
            // but not names — an operator reading the rollup wants "what
            // broke just now", and a maintenance-windowed channel did not
            // break as far as notifications are concerned.
            AlertEvent a = event(CHANNEL_A, 60, AlertStatus.PROBLEM, false);
            rowsFor(a, null, 10, 20, 30);
            event(CHANNEL_B, 90, AlertStatus.PROBLEM, false);
            event(CHANNEL_C, 120, AlertStatus.PROBLEM, true);
            event(CHANNEL_B, 9_000, AlertStatus.PROBLEM, false);

            AlertStormControl.CeilingVerdict verdict = verdict(ceilingAction(3, 600));
            assertEquals(AlertStormControl.CeilingOutcome.ROLLUP, verdict.outcome());
            assertEquals(List.of("name:" + CHANNEL_A, "name:" + CHANNEL_B), verdict.channels());
        }
    }

    @Nested
    @DisplayName("flap detection")
    class FlapDetection {

        private final AlertEvent current = new AlertEvent();

        @BeforeEach
        void currentEvent() {
            current.setId(999L);
            current.setMonitorId(7);
            current.setChannelId(CHANNEL_A);
            current.setStatus(AlertStatus.PROBLEM);
            current.setOpenedTime(NOW);
        }

        /**
         * One completed PROBLEM-&gt;OK cycle for the identity:
         * resolved {@code resolvedMinutesAgo} before {@link #NOW}, having
         * opened four minutes before resolving.
         */
        private AlertEvent cycle(long resolvedMinutesAgo, Integer metadataId) {
            AlertEvent event = new AlertEvent();
            event.setId(nextEventId++);
            event.setMonitorId(7);
            event.setChannelId(CHANNEL_A);
            event.setMetadataId(metadataId);
            event.setStatus(AlertStatus.RESOLVED);
            event.setOpenedTime(NOW.minusSeconds((resolvedMinutesAgo + 4) * 60));
            event.setResolvedTime(NOW.minusSeconds(resolvedMinutesAgo * 60));
            return event;
        }

        private void givenHistory(AlertEvent... events) {
            List<AlertEvent> history = new ArrayList<>(List.of(events));
            alertEventRepository.when(() -> AlertEventRepository.listAlertEvents(any()))
                    .thenReturn(new PagedResult<>(history, history.size(), 0, 100));
        }

        private AlertStormControl.FlapCheck check(boolean resolvedPhase) {
            return AlertStormControl.flapCheck(current, NOW, resolvedPhase);
        }

        @Test
        @DisplayName("three completed cycles in the window is not yet flapping")
        void threeCyclesIsNotFlapping() {
            // The threshold is four: three open/clear rounds in half an hour
            // is unusual but still plausibly distinct incidents an operator
            // can act on individually.
            givenHistory(cycle(5, null), cycle(10, null), cycle(15, null));
            assertEquals(AlertStormControl.FlapOutcome.NOT_FLAPPING, check(false).outcome());
        }

        @Test
        @DisplayName("the open edge that crosses the threshold is the onset — announced once")
        void fourthCycleOpenEdgeIsOnset() {
            // As of this open, four cycles sit inside the window; as of the
            // previous event's open, only three did. This edge crossed the
            // line, so this is the one notification the bout gets.
            givenHistory(cycle(5, null), cycle(10, null), cycle(15, null), cycle(20, null));

            AlertStormControl.FlapCheck check = check(false);
            assertEquals(AlertStormControl.FlapOutcome.ONSET, check.outcome());
            assertEquals(4, check.cycles());
            assertEquals(30, check.windowMinutes());
        }

        @Test
        @DisplayName("open edges after the onset stay suppressed")
        void laterOpenEdgesAreSuppressed() {
            // The previous event was already over the threshold when IT
            // opened, so it carried the bout's single notification; this
            // edge must stay quiet or "notify once" quietly becomes "notify
            // every cycle", which is the storm again.
            givenHistory(cycle(2, null), cycle(6, null), cycle(10, null), cycle(14, null),
                    cycle(18, null));
            assertEquals(AlertStormControl.FlapOutcome.SUPPRESSED, check(false).outcome());
        }

        @Test
        @DisplayName("resolve edges of a flapping identity are suppressed, never an onset")
        void resolveEdgesAreSuppressedWhileFlapping() {
            // A bout that announced one PROBLEM must not deliver a stream of
            // RESOLVED notifications — the same noise wearing different words.
            givenHistory(cycle(5, null), cycle(10, null), cycle(15, null), cycle(20, null));
            assertEquals(AlertStormControl.FlapOutcome.SUPPRESSED, check(true).outcome());
        }

        @Test
        @DisplayName("cycles aged out of the window mean normal notification resumes")
        void agedOutCyclesRecover() {
            // The "until it stabilizes" half, with no latch to reset: once
            // the churn is more than a window old, the identity is ordinary
            // again and a fresh problem pages normally.
            givenHistory(cycle(40, null), cycle(45, null), cycle(50, null), cycle(55, null));
            assertEquals(AlertStormControl.FlapOutcome.NOT_FLAPPING, check(false).outcome());
        }

        @Test
        @DisplayName("cycles of a different connector on the same channel do not count")
        void otherConnectorsCyclesDoNotCount() {
            // The identity is (monitor, channel, metadataId). A flapping
            // destination 1 must not silence a genuinely new problem on the
            // channel-level trigger, or one bad connector mutes the channel.
            givenHistory(cycle(5, 1), cycle(10, 1), cycle(15, 1), cycle(20, 1));
            assertEquals(AlertStormControl.FlapOutcome.NOT_FLAPPING, check(false).outcome());
        }

        @Test
        @DisplayName("the event being dispatched is excluded from its own history")
        void ownRowDoesNotCount() {
            // Whether the caller has already persisted the current event must
            // not change the verdict, so the check filters its own id out.
            AlertEvent self = cycle(1, null);
            self.setId(999L);
            givenHistory(self, cycle(5, null), cycle(10, null), cycle(15, null));
            assertEquals(AlertStormControl.FlapOutcome.NOT_FLAPPING, check(false).outcome());
        }

        @Test
        @DisplayName("a history read failure fails open to normal notification")
        void historyFailureFailsOpen() {
            alertEventRepository.when(() -> AlertEventRepository.listAlertEvents(any()))
                    .thenThrow(new IllegalStateException("database unavailable"));
            assertEquals(AlertStormControl.FlapOutcome.NOT_FLAPPING, check(false).outcome());
        }
    }

    @Nested
    @DisplayName("operator-facing messages")
    class Messages {

        @Test
        @DisplayName("the rollup message reports the trip, the window, and every channel up to the cap")
        void rollupMessageNamesChannels() {
            AlertStormControl.CeilingVerdict verdict = new AlertStormControl.CeilingVerdict(
                    AlertStormControl.CeilingOutcome.ROLLUP, 5, 600, List.of("ADT In", "Lab Out"));

            String message = AlertStormControl.rollupMessage(verdict);
            assertTrue(message.contains("ceiling of 5 notification(s) in 600 seconds"), message);
            assertTrue(message.contains("2 channel(s) affected: ADT In, Lab Out."), message);
        }

        @Test
        @DisplayName("past 25 channels the remainder is summarized, not pasted")
        void rollupMessageCapsTheList() {
            // A rollup that pastes four hundred channel names into an SMS
            // gateway has recreated the problem it summarizes.
            List<String> channels = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                channels.add("ch-" + i);
            }
            String message = AlertStormControl.rollupMessage(new AlertStormControl.CeilingVerdict(
                    AlertStormControl.CeilingOutcome.ROLLUP, 5, 600, channels));

            assertTrue(message.contains("ch-25"), message);
            assertTrue(!message.contains("ch-26"), message);
            assertTrue(message.contains("and 5 more."), message);
        }

        @Test
        @DisplayName("the flap onset message carries the cycle count and the underlying problem")
        void flappingMessageKeepsTheRealProblemVisible() {
            AlertStormControl.FlapCheck check =
                    new AlertStormControl.FlapCheck(AlertStormControl.FlapOutcome.ONSET, 4, 30);

            String message = AlertStormControl.flappingMessage(check, "Queue depth 5000");
            assertTrue(message.contains("opened and cleared 4 time(s) in the last 30 minutes"),
                    message);
            assertTrue(message.endsWith("Latest: Queue depth 5000"), message);

            assertTrue(AlertStormControl.flappingMessage(check, null).endsWith("(no message)"));
        }
    }
}
