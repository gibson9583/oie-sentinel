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
}
