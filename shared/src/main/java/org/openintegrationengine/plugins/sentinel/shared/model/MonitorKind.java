/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * What a monitor watches: channels, or the engine's log. The editor's first
 * choice, and the rule for which monitors may suppress each other and which
 * types an update may switch between.
 */
public enum MonitorKind {
    CHANNEL("Channel"),
    LOG("Log");

    private final String label;

    MonitorKind(String label) {
        this.label = label;
    }

    /**
     * @return the kind's name in the editor; for {@link #LOG}, also the name a
     *         Log alert shows where a channel name would be
     */
    public String label() {
        return label;
    }
}
