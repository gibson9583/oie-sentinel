/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * Alert delivery mechanism for a {@code sentinel_action} row. Persisted as
 * the literal enum name (VARCHAR(16)) in {@code sentinel_action.action_type}
 * — every name here must stay within that budget.
 *
 * <p>{@link #CHANNEL} remains the general escape hatch: it lets an operator
 * fan out to anything at all via a normal OIE channel destination, so
 * Sentinel never has to grow a native integration for every destination.
 * {@link #WEBHOOK} exists alongside it because that escape hatch is
 * disproportionate for the most common case — building, deploying and
 * maintaining a whole channel just to POST a JSON document at Slack or
 * PagerDuty. WEBHOOK is the narrow, safe version of that one case; CHANNEL is
 * still the answer for anything needing real transformation, retry semantics
 * or a non-HTTP transport.</p>
 */
public enum ActionType {
    EMAIL,
    CHANNEL,
    SNS,
    WEBHOOK
}
