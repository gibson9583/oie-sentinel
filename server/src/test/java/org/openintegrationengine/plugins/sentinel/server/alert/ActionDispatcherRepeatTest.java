/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import org.openintegrationengine.plugins.sentinel.server.db.ActionDispatchLogRepository;
import org.openintegrationengine.plugins.sentinel.server.db.ActionRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.ActionDispatchLog;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.OperationMode;
import org.openintegrationengine.plugins.sentinel.shared.model.Severity;

/**
 * The still-open pass: repeat pacing and escalation, both reconstructed from
 * {@code sentinel_action_dispatch_log} rows rather than from any in-memory
 * clock — which is the property that makes the pacing survive a server
 * restart mid-incident, and the property these tests pin.
 *
 * <p>The check is driven through the package-private {@code runRepeatCheck}
 * body (widened for exactly this purpose) rather than {@code onRepeatCheck},
 * which only enqueues onto the dispatch pool — an asynchronous test here
 * would trade determinism for nothing, since the body is the entire
 * decision. The decision instant is {@code Instant.now()} inside the body,
 * so every stubbed log row is placed with wide margins (seconds against
 * ten-minute intervals) rather than at exact boundaries; the inclusive/
 * exclusive edge of the interval comparison is deliberately not asserted
 * because no seam exposes it without changing production code.</p>
 *
 * <p>"A send happened" is observed at the dispatch-log insert, the row every
 * real delivery attempt writes. The actions carry a {@code null} action type
 * on purpose: the sender lookup fails immediately and the attempt is logged
 * as a failure — and since the contract counts <em>attempts</em>, not
 * successes (a permanently failing transport must exhaust its budget and go
 * quiet, not retry every tick forever), a failing transport is not a
 * shortcut around what is being tested but an instance of it. No network,
 * engine controller or template rendering is involved anywhere.</p>
 */
@DisplayName("ActionDispatcher repeat pacing and escalation")
class ActionDispatcherRepeatTest {

    private static final long EVENT_ID = 9001L;
    private static final String CHANNEL_ID = "1c8f0a7e-5b3d-4d21-9a6f-2e7c4b91d0aa";

    private MockedStatic<ActionRepository> actionRepository;
    private MockedStatic<ActionDispatchLogRepository> dispatchLogRepository;

    /** The enabled actions the pass will see. */
    private final List<Action> actions = new ArrayList<>();

    /** The dispatch-log rows already recorded for the event. */
    private final List<ActionDispatchLog> existingRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        actionRepository = Mockito.mockStatic(ActionRepository.class);
        actionRepository.when(() -> ActionRepository.listActions(Boolean.TRUE)).thenReturn(actions);

        dispatchLogRepository = Mockito.mockStatic(ActionDispatchLogRepository.class);
        dispatchLogRepository.when(() -> ActionDispatchLogRepository
                .listActionDispatchLogsForEvent(EVENT_ID)).thenReturn(existingRows);
    }

    @AfterEach
    void tearDown() {
        dispatchLogRepository.close();
        actionRepository.close();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * An enabled ON_PROBLEM action with no condition (matches everything) and
     * a {@code null} action type — see the class Javadoc for why the type is
     * deliberately unresolvable.
     */
    private Action action(int id, Integer repeatIntervalSeconds, Integer maxRepeats) {
        Action action = new Action();
        action.setId(id);
        action.setName("action-" + id);
        action.setEnabled(true);
        action.setOperationMode(OperationMode.ON_PROBLEM);
        action.setRepeatIntervalSeconds(repeatIntervalSeconds);
        action.setMaxRepeats(maxRepeats);
        actions.add(action);
        return action;
    }

    /** A prior delivery attempt for {@code actionId}, {@code secondsAgo} before roughly-now. */
    private void row(Integer actionId, Long secondsAgo, boolean success) {
        ActionDispatchLog row = new ActionDispatchLog();
        row.setAlertEventId(EVENT_ID);
        row.setActionId(actionId);
        row.setDispatchTime(secondsAgo != null ? Instant.now().minusSeconds(secondsAgo) : null);
        row.setSuccess(success);
        existingRows.add(row);
    }

    private static AlertEvent openEvent(long openedSecondsAgo) {
        AlertEvent event = new AlertEvent();
        event.setId(EVENT_ID);
        event.setMonitorId(7);
        event.setChannelId(CHANNEL_ID);
        event.setSeverity(Severity.HIGH);
        event.setStatus(AlertStatus.PROBLEM);
        event.setMessage("No messages received in 3600s");
        event.setOpenedTime(Instant.now().minusSeconds(openedSecondsAgo));
        return event;
    }

    /**
     * Runs one still-open pass. The payload is built through the
     * package-private constructor so no engine cache is consulted.
     */
    private static void runPass(AlertEvent event) {
        AlertPayload payload = new AlertPayload(EVENT_ID, 7, "Nightly ADT feed",
                MonitorType.INACTIVITY, CHANNEL_ID, "ADT Inbound", null, Severity.HIGH,
                "PROBLEM", event.getMessage(), event.getOpenedTime(), null);
        ActionDispatcher.runRepeatCheck(event, payload);
    }

    /** Every dispatch-log row this pass wrote, in write order. */
    private List<ActionDispatchLog> insertedRows() {
        ArgumentCaptor<ActionDispatchLog> captor = ArgumentCaptor.forClass(ActionDispatchLog.class);
        dispatchLogRepository.verify(
                () -> ActionDispatchLogRepository.insertActionDispatchLog(captor.capture()),
                Mockito.atLeast(0));
        return captor.getAllValues();
    }

    private void assertNothingSent() {
        dispatchLogRepository.verify(
                () -> ActionDispatchLogRepository.insertActionDispatchLog(Mockito.any()), never());
    }

    private void assertSentOnceTo(int actionId) {
        List<ActionDispatchLog> rows = insertedRows();
        assertEquals(1, rows.size(), "expected exactly one delivery attempt");
        assertEquals(Integer.valueOf(actionId), rows.get(0).getActionId(),
                "the attempt must be logged against the action that was notified");
        assertEquals(EVENT_ID, rows.get(0).getAlertEventId());
    }

    @Nested
    @DisplayName("repeat pacing from the dispatch log")
    class RepeatPacing {

        @Test
        @DisplayName("an action with no rows for the event sends immediately")
        void noRowsMeansSendNow() {
            // This rule is what lets an action created (or re-enabled) AFTER
            // the alert opened still notify: with no rows, the log says this
            // action has never been told, so it is told now rather than
            // staying silent for the problem's whole lifetime.
            action(1, 600, null);
            runPass(openEvent(5_000));
            assertSentOnceTo(1);
        }

        @Test
        @DisplayName("a repeat whose interval has not elapsed stays quiet")
        void intervalNotElapsedIsSilent() {
            // The newest attempt is seconds old against a ten-minute
            // interval. Re-sending here would turn every evaluator tick into
            // a notification — the repeat interval IS the pacing.
            action(1, 600, null);
            row(1, 5L, true);
            runPass(openEvent(5_000));
            assertNothingSent();
        }

        @Test
        @DisplayName("a repeat whose interval has elapsed sends again")
        void intervalElapsedSends() {
            action(1, 600, null);
            row(1, 3_600L, true);
            runPass(openEvent(5_000));
            assertSentOnceTo(1);
        }

        @Test
        @DisplayName("pacing keys on the newest row, not the first")
        void newestRowDrivesThePacing() {
            // An old initial send plus a fresh repeat: the fresh one is what
            // must gate. Pacing off the oldest row would make the interval
            // only ever delay the SECOND notification and none after it.
            action(1, 600, null);
            row(1, 3_600L, true);
            row(1, 5L, true);
            runPass(openEvent(5_000));
            assertNothingSent();
        }

        @Test
        @DisplayName("an action that never opted into repeats does not re-send")
        void noRepeatIntervalMeansNoRepeat() {
            // maxRepeats and rows are irrelevant without the opt-in; the
            // still-open pass must not invent re-notification for an action
            // configured to speak once.
            action(1, null, null);
            row(1, 3_600L, true);
            runPass(openEvent(5_000));
            assertNothingSent();
        }
    }

    @Nested
    @DisplayName("the maxRepeats budget")
    class RepeatBudget {

        @Test
        @DisplayName("the budget is 1 + maxRepeats attempts, then silence however much time passes")
        void budgetExhaustedIsSilent() {
            // maxRepeats 2 means one initial send plus two repeats. Three
            // rows exist, all long past the interval — the budget, not the
            // clock, is what must hold.
            action(1, 600, 2);
            row(1, 9_000L, true);
            row(1, 6_000L, true);
            row(1, 3_000L, true);
            runPass(openEvent(20_000));
            assertNothingSent();
        }

        @Test
        @DisplayName("the last budgeted repeat is still delivered")
        void lastRepeatInBudgetSends() {
            // Two rows against a budget of three attempts: one repeat left.
            // Off-by-one here silently shrinks every operator's configured
            // budget by a notification.
            action(1, 600, 2);
            row(1, 6_000L, true);
            row(1, 3_000L, true);
            runPass(openEvent(20_000));
            assertSentOnceTo(1);
        }

        @Test
        @DisplayName("failed attempts consume the budget too")
        void failedAttemptsCount() {
            // Attempts are counted, not successes: a transport that fails
            // every time must exhaust its budget and go quiet, not retry on
            // every tick forever while filling the log.
            action(1, 600, 2);
            row(1, 9_000L, false);
            row(1, 6_000L, false);
            row(1, 3_000L, false);
            runPass(openEvent(20_000));
            assertNothingSent();
        }

        @Test
        @DisplayName("a null maxRepeats is unlimited")
        void nullBudgetIsUnlimited() {
            action(1, 600, null);
            for (long secondsAgo = 90_000; secondsAgo > 0; secondsAgo -= 3_000) {
                row(1, secondsAgo, true);
            }
            runPass(openEvent(100_000));
            assertSentOnceTo(1);
        }
    }

    @Nested
    @DisplayName("rows that must not count")
    class RowHygiene {

        @Test
        @DisplayName("rows whose action was deleted pace nobody")
        void deletedActionRowsAreExcluded() {
            // The FK is ON DELETE SET NULL, so orphaned rows are normal. They
            // belong to no live action: counting them against this action
            // would burn its budget on someone else's history, and pacing on
            // their timestamps would delay it for someone else's sends. With
            // only orphans present, this action has no rows and sends now.
            action(1, 600, 1);
            row(null, 5L, true);
            row(null, 10L, true);
            runPass(openEvent(5_000));
            assertSentOnceTo(1);
        }

        @Test
        @DisplayName("a row with no dispatch time reads as ancient, never as recent")
        void nullDispatchTimeCannotSuppress() {
            // A corrupt timestamp must fail toward re-notifying: treating it
            // as "just sent" would silence the pairing until a fresh row
            // happened to be written, which for a quiet action is never.
            action(1, 600, null);
            row(1, null, true);
            runPass(openEvent(5_000));
            assertSentOnceTo(1);
        }
    }

    @Nested
    @DisplayName("gates ahead of any pacing")
    class Gates {

        @Test
        @DisplayName("an acknowledged problem neither repeats nor escalates")
        void acknowledgedIsFullySilent() {
            // Ack means "someone is on it". Escalating a problem somebody is
            // actively working — to a wider audience, on the theory nobody is
            // looking — is the failure mode this gate exists for, so the
            // escalation config here is due and must still not fire.
            Action source = action(1, 600, null);
            source.setEscalateAfterSeconds(300);
            source.setEscalateToActionId(2);
            action(2, null, null);

            AlertEvent event = openEvent(5_000);
            event.setAcknowledgedBy(42);
            runPass(event);
            assertNothingSent();
        }

        @Test
        @DisplayName("a suppressed alert produces nothing, including a first send")
        void suppressedIsFullySilent() {
            // The no-rows-yet rule would otherwise deliver a "first"
            // notification for an alert born under a maintenance window —
            // through the one path that skips the at-creation suppression.
            action(1, 600, null);
            AlertEvent event = openEvent(5_000);
            event.setSuppressed(true);
            runPass(event);
            assertNothingSent();
        }

        @Test
        @DisplayName("an ON_RESOLVE action takes no part in the still-open pass")
        void resolveOnlyActionsAreSkipped() {
            Action resolveOnly = action(1, 600, null);
            resolveOnly.setOperationMode(OperationMode.ON_RESOLVE);
            runPass(openEvent(5_000));
            assertNothingSent();
        }
    }

    @Nested
    @DisplayName("escalation")
    class Escalation {

        @Test
        @DisplayName("a problem open past escalateAfterSeconds notifies the target, logged as the target")
        void dueEscalationNotifiesTarget() {
            // Logging against the TARGET's id is load-bearing: it starts the
            // target's own repeat clock and charges the target's ceiling, and
            // it is what the next tick reads to know the target has been told.
            Action source = action(1, null, null);
            source.setEscalateAfterSeconds(600);
            source.setEscalateToActionId(2);
            action(2, null, null);

            runPass(openEvent(1_000));
            assertSentOnceTo(2);
        }

        @Test
        @DisplayName("an escalation not yet due stays quiet")
        void notDueEscalationIsSilent() {
            Action source = action(1, null, null);
            source.setEscalateAfterSeconds(2_000);
            source.setEscalateToActionId(2);
            action(2, null, null);

            runPass(openEvent(1_000));
            assertNothingSent();
        }

        @Test
        @DisplayName("a target already told is not told again")
        void alreadyToldTargetIsNotRepeatedByTheSource() {
            // The target's row ends the source's responsibility; from here the
            // target's own repeatIntervalSeconds owns its cadence. Re-sending
            // from the source side would double-page the escalation audience
            // on every tick.
            Action source = action(1, null, null);
            source.setEscalateAfterSeconds(600);
            source.setEscalateToActionId(2);
            action(2, null, null);
            row(2, 30L, true);

            runPass(openEvent(1_000));
            assertNothingSent();
        }

        @Test
        @DisplayName("one pass never notifies the same target twice")
        void escalationAndOwnRepeatCollapseToOneSend() {
            // The target is both an escalation destination AND due for its own
            // "no rows yet" first send this tick. The pass-level dedupe must
            // collapse the two into one notification — the log rows are read
            // once up front, so nothing else can.
            Action source = action(1, null, null);
            source.setEscalateAfterSeconds(600);
            source.setEscalateToActionId(2);
            action(2, 300, null);

            runPass(openEvent(1_000));
            assertSentOnceTo(2);
        }

        @Test
        @DisplayName("an action escalating to itself is ignored rather than looping")
        void selfEscalationIsIgnored() {
            // escalate_to_action_id has no FK and no validation path can stop
            // a direct DB edit; the walk must end, not spin or notify the
            // source as its own escalation audience.
            Action source = action(1, null, null);
            source.setEscalateAfterSeconds(600);
            source.setEscalateToActionId(1);

            runPass(openEvent(5_000));
            assertNothingSent();
        }
    }
}
