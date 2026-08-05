/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.evaluate;

/**
 * The result of evaluating one monitor rule against one channel (or one
 * connector) at a single instant: a three-way verdict plus the raw value
 * snapshot that produced it.
 *
 * <p>The verdict is deliberately three-way rather than boolean. {@code
 * INSUFFICIENT_DATA} exists so the evaluators can distinguish "the condition
 * is fine" from "we cannot know yet" — a brand-new ANOMALY monitor with no
 * baseline, or an INACTIVITY monitor on a channel the collector has only just
 * started watching, must not open (or resolve) alerts on guesswork. The
 * {@code TriggerEvaluatorJob} state machine treats the three results very
 * differently: only {@code BREACH} counts toward hysteresis, only a confirmed
 * {@code OK} resolves an open problem, and {@code INSUFFICIENT_DATA} freezes
 * the trigger without touching any open alert.</p>
 *
 * <p>{@link #getValueJson()} is the JSON snapshot of everything the evaluator
 * looked at (current value, threshold, baseline mean/stddev/z, tier, ...). It
 * is persisted verbatim to {@code sentinel_trigger_state.last_value_json} and,
 * on alert creation, to {@code sentinel_alert_event.details_json}, so the
 * problem-detail UI can show operators exactly why an alert fired without the
 * evaluator having to be re-run. {@link #getMessage()} is the human sentence
 * used as the alert event's message; it is only meaningful for {@code BREACH}
 * outcomes.</p>
 */
public final class EvaluationOutcome {

    /** The three-way verdict of a single evaluation. */
    public enum Result {
        /** The monitored condition is violated right now. */
        BREACH,
        /** The monitored condition holds right now. */
        OK,
        /**
         * There is not enough history to evaluate the condition at all —
         * neither a breach nor a pass may be inferred from it.
         */
        INSUFFICIENT_DATA
    }

    private final Result result;
    private final String valueJson;
    private final String message;

    /**
     * Private — instances are only created through the three static
     * factories, which enforce that a message accompanies exactly the one
     * result ({@code BREACH}) that can turn into an alert event.
     */
    private EvaluationOutcome(Result result, String valueJson, String message) {
        this.result = result;
        this.valueJson = valueJson;
        this.message = message;
    }

    /**
     * Creates a breaching outcome.
     *
     * @param valueJson JSON snapshot of the values that produced the breach,
     *                  persisted as the alert's {@code details_json}
     * @param message   human-readable one-liner describing the breach; becomes
     *                  the alert event's message, so it must make sense on its
     *                  own in an email subject or problem list
     * @return a {@code BREACH} outcome
     */
    public static EvaluationOutcome breach(String valueJson, String message) {
        return new EvaluationOutcome(Result.BREACH, valueJson, message);
    }

    /**
     * Creates a passing outcome. No message is carried — an {@code OK} never
     * produces an alert of its own; on a PROBLEM-to-OK transition the
     * resolution notification reuses the original alert's message.
     *
     * @param valueJson JSON snapshot of the values evaluated, kept on the
     *                  trigger state so the UI can show the latest reading
     *                  even for healthy triggers
     * @return an {@code OK} outcome
     */
    public static EvaluationOutcome ok(String valueJson) {
        return new EvaluationOutcome(Result.OK, valueJson, null);
    }

    /**
     * Creates an outcome meaning "cannot evaluate yet". Carries no message
     * because it never notifies — it neither opens nor resolves alerts.
     *
     * @param valueJson JSON snapshot of whatever partial values were
     *                  available, so the UI can show why evaluation was
     *                  skipped (e.g. current value present but baseline null)
     * @return an {@code INSUFFICIENT_DATA} outcome
     */
    public static EvaluationOutcome insufficientData(String valueJson) {
        return new EvaluationOutcome(Result.INSUFFICIENT_DATA, valueJson, null);
    }

    /** @return the three-way verdict of this evaluation */
    public Result getResult() {
        return result;
    }

    /**
     * @return the JSON snapshot of the values considered, destined for
     *         {@code sentinel_trigger_state.last_value_json} and (on breach)
     *         {@code sentinel_alert_event.details_json}
     */
    public String getValueJson() {
        return valueJson;
    }

    /**
     * @return the human-readable breach description, or {@code null} for
     *         {@code OK}/{@code INSUFFICIENT_DATA} outcomes which never
     *         notify
     */
    public String getMessage() {
        return message;
    }
}
