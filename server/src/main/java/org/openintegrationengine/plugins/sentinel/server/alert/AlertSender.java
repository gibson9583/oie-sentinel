/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.alert;

import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;

/**
 * One delivery transport for alert notifications (email, channel routing,
 * SNS). Implementations are stateless: {@link ActionDispatcher} holds a
 * single shared instance of each and may call it concurrently, so any
 * per-send resources (SMTP connections, AWS clients) must be created and
 * released inside {@link #send}.
 *
 * <p>The interface exists so the dispatcher's fire/log/repeat bookkeeping is
 * written once, independent of transport: the dispatcher selects the sender
 * from {@code Action.actionType} and treats every transport identically.</p>
 */
public interface AlertSender {

    /**
     * Delivers one notification. Declared {@code throws Exception} on
     * purpose: transports fail in transport-specific ways
     * ({@code EmailException}, {@code SnsException}, engine
     * {@code ControllerException}, …) and the dispatcher does not care which
     * — it records any thrown exception's message in the
     * {@code sentinel_action_dispatch_log} row and moves on to the next
     * action. Implementations must therefore <em>throw</em> on failure
     * rather than swallow it, or failed deliveries would be logged as
     * successes.
     *
     * @param action  the configured action; its {@code configJson} carries
     *                the transport parameters (recipients, channel id, topic
     *                ARN, …)
     * @param event   the alert event being dispatched (synthetic for test
     *                sends); senders should prefer the resolved
     *                {@code payload} for display values
     * @param payload display-resolved snapshot of the event — names already
     *                looked up, safe to serialize as-is
     * @throws Exception when delivery fails for any reason; the message is
     *                   persisted (truncated) as the dispatch failure reason
     */
    void send(Action action, AlertEvent event, AlertPayload payload) throws Exception;

    /**
     * Flattens CR, LF, tab and any other control character to spaces — the
     * one-line-header guard every transport shares.
     *
     * <p>Operator-supplied text (monitor names, channel names, evaluator
     * messages) reaches header-shaped fields through the senders' template
     * rendering. A newline in a value that lands in an unencoded header
     * splits it, so the sanitizing has to happen at render time rather than
     * being left to whatever the transport happens to do with it: JavaMail
     * encodes the {@code Subject} header and SNS rejects multi-line subjects
     * outright, but neither is a property this code should depend on, and a
     * transport added later (a webhook writing raw headers) would inherit
     * the gap silently.</p>
     *
     * <p>Returns {@code ""} for null so callers can render an absent value
     * without a null check.</p>
     *
     * @param value the raw text to flatten; may be {@code null}
     * @return the value with every control character replaced by a space
     */
    static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
    }
}
