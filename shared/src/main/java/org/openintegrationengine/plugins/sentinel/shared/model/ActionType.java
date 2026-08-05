/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * Alert delivery mechanism for a {@code sentinel_action} row. Persisted as
 * the literal enum name (VARCHAR(16)) in {@code sentinel_action.action_type}.
 *
 * <p>Deliberately just these three for v1: {@link #CHANNEL} lets an operator
 * fan out to Slack, Teams, PagerDuty, SMS, etc. themselves via a normal OIE
 * channel destination (e.g. an HTTP Sender), so Sentinel does not need a
 * native integration for every possible destination.</p>
 */
public enum ActionType {
    EMAIL,
    CHANNEL,
    SNS
}
