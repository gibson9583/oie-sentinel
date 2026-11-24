/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.service;

import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorKind;
import org.openintegrationengine.plugins.sentinel.shared.model.MonitorType;
import org.openintegrationengine.plugins.sentinel.shared.model.ScopeType;

/**
 * Save-time rules that keep a monitor's kind consistent, called from
 * {@code MonitorService.validate}, so imports get them too. Each rejection is
 * an {@link IllegalArgumentException} with a message for the operator.
 *
 * <ul>
 *   <li><b>Scope.</b> A Log monitor watches no channel; its scope is stored
 *       as ALL so the ALL-scope visibility rules treat it as the widest
 *       definition there is.</li>
 *   <li><b>No change of kind.</b> Trigger states and problems are keyed by
 *       kind, so switching would strand open problems under a subject the
 *       monitor no longer evaluates. This also keeps every suppression
 *       dependency valid across an update.</li>
 *   <li><b>Dependencies stay within a kind.</b> A dependency matches the
 *       parent's open problem on the same subject, which a different kind
 *       never has.</li>
 * </ul>
 */
final class MonitorSubjectRules {

    private MonitorSubjectRules() {
    }

    /** A blank scope id, which a form may send, is stored as {@code null}. */
    static void requireScope(Monitor monitor) {
        if (kind(monitor.getMonitorType()) == MonitorKind.CHANNEL) {
            return;
        }
        String label = kind(monitor.getMonitorType()).label();
        if (monitor.getScopeType() != ScopeType.ALL) {
            throw new IllegalArgumentException("A " + label + " monitor watches no channel, so its scope must be ALL");
        }
        if (monitor.getScopeId() != null && !monitor.getScopeId().isBlank()) {
            throw new IllegalArgumentException("A " + label + " monitor takes no scope id; its scope is ALL");
        }
        monitor.setScopeId(null);
    }

    static void requireSameKind(Monitor existing, Monitor updated) {
        MonitorKind from = kind(existing.getMonitorType());
        MonitorKind to = kind(updated.getMonitorType());
        if (from != to) {
            throw new IllegalArgumentException("Monitor '" + existing.getName() + "' is a " + from.label()
                    + " monitor and cannot become a " + to.label() + " monitor. Create a new monitor instead");
        }
    }

    static void requireSameKindParent(MonitorType type, Monitor parent) {
        MonitorKind kind = kind(type);
        MonitorKind parentKind = kind(parent.getMonitorType());
        if (kind != parentKind) {
            throw new IllegalArgumentException("Monitor '" + parent.getName() + "' is a " + parentKind.label()
                    + " monitor; a " + kind.label() + " monitor can only be suppressed by another " + kind.label()
                    + " monitor");
        }
    }

    /** A missing type reads as a channel type, the only kind a stored row without one can be. */
    private static MonitorKind kind(MonitorType type) {
        return type != null ? type.kind() : MonitorKind.CHANNEL;
    }
}
