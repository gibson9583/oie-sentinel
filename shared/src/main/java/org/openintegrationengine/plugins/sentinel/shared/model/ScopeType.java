/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * What a monitor, action, or maintenance window's {@code scope_id} refers to.
 * Persisted as the literal enum name (VARCHAR(16)). {@code CHANNEL},
 * {@code GROUP}, and {@code TAG} pair with a non-null {@code scope_id} (a
 * core OIE channel id, channel-group id, or channel-tag id, respectively —
 * Sentinel does not define its own grouping); {@code ALL} always pairs with
 * a null {@code scope_id} and resolves to every deployed channel at
 * evaluation time. {@code GROUP} and {@code TAG} membership is resolved live
 * on every evaluation, so group/tag edits apply without touching the scoped
 * row.
 */
public enum ScopeType {
    CHANNEL,
    GROUP,
    TAG,
    ALL
}
