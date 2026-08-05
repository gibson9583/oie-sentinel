/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * One severity bucket in the dashboard's open-problem breakdown: how many
 * open alert events currently sit at a given {@link Severity}.
 *
 * <p>Modeled as an explicit pair (rather than a map keyed by severity) so
 * the dashboard service can emit all five severities zero-filled in scale
 * order and the web UI can render the severity bar without knowing the enum.
 * Response-only; never persisted.</p>
 */
public class SeverityCount {

    private Severity severity;
    private int count;

    public SeverityCount() {
    }

    /**
     * @return the severity level this bucket counts
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * @param severity the severity level this bucket counts
     */
    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    /**
     * @return number of currently open alert events at this severity; zero
     *         buckets are included so the UI always shows the full scale
     */
    public int getCount() {
        return count;
    }

    /**
     * @param count number of currently open alert events at this severity
     */
    public void setCount(int count) {
        this.count = count;
    }
}
