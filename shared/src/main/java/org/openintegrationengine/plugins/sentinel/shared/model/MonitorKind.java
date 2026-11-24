/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * What a monitor watches: channels, the engine's log, or engine events. The
 * editor's first choice, and the rule for which monitors may suppress each
 * other and which types an update may switch between.
 */
public enum MonitorKind {
    CHANNEL("Channel"),
    LOG("Log"),
    EVENT("Event");

    private final String label;

    MonitorKind(String label) {
        this.label = label;
    }

    /** @return the name shown wherever a problem's channel name would be */
    public String label() {
        return label;
    }
}
