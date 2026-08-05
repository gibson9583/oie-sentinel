/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.util.List;

/**
 * Result of dry-running a monitor definition ({@code POST /monitors/_test}):
 * the evaluators are executed for real against the monitor's currently
 * started target channels, but nothing is persisted and no alerts fire.
 *
 * <p>Exists so an operator can see what a rule would do — which channels it
 * resolves to and what each evaluation returns — before saving it, instead
 * of discovering a misconfigured threshold via a 3 AM page. Response-only;
 * never persisted.</p>
 */
public class MonitorTestResult {

    private boolean ok;
    private String message;
    private List<ChannelTestOutcome> outcomes;

    public MonitorTestResult() {
    }

    /**
     * @return {@code true} if every target channel evaluated without an
     *         internal error (breaches still count as ok — a breach is a
     *         correct evaluation, not a failure of the test)
     */
    public boolean isOk() {
        return ok;
    }

    /**
     * @param ok whether every target channel evaluated without error
     */
    public void setOk(boolean ok) {
        this.ok = ok;
    }

    /**
     * @return human-readable summary of the run (e.g. "3 channels evaluated")
     */
    public String getMessage() {
        return message;
    }

    /**
     * @param message human-readable summary of the run
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * @return one entry per resolved target channel; empty when the
     *         monitor's scope currently resolves to no started channels
     */
    public List<ChannelTestOutcome> getOutcomes() {
        return outcomes;
    }

    /**
     * @param outcomes one entry per resolved target channel
     */
    public void setOutcomes(List<ChannelTestOutcome> outcomes) {
        this.outcomes = outcomes;
    }
}
