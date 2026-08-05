/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;
import java.util.List;

/**
 * Everything the dashboard landing page shows above the fold, aggregated
 * server-side into one payload: the open-problem severity breakdown, the
 * noisiest channels, monitor fleet health, the latest problems, and the
 * background jobs' heartbeat timestamps.
 *
 * <p>A single composite response (rather than one endpoint per widget) keeps
 * the page to one request on load and on its refresh poll — the widgets are
 * all derived from the same open-event query anyway, so splitting them would
 * just repeat that work per call. Response-only; never persisted.</p>
 */
public class DashboardSummary {

    private List<SeverityCount> openBySeverity;
    private int openTotal;
    private int unacknowledged;
    private int monitorsEnabled;
    private int monitorsTotal;
    private int channelsWatched;
    private List<AlertEvent> recentProblems;
    private List<TopChannel> topChannels;
    private Instant lastCollectorRun;
    private Instant lastEvaluatorRun;
    private Instant lastConnectorEvent;

    public DashboardSummary() {
    }

    /**
     * @return open-problem counts per severity, all five levels present in
     *         scale order (zero-filled) so the UI renders a stable bar
     */
    public List<SeverityCount> getOpenBySeverity() {
        return openBySeverity;
    }

    /**
     * @param openBySeverity open-problem counts per severity, zero-filled
     */
    public void setOpenBySeverity(List<SeverityCount> openBySeverity) {
        this.openBySeverity = openBySeverity;
    }

    /**
     * @return total number of currently open alert events
     */
    public int getOpenTotal() {
        return openTotal;
    }

    /**
     * @param openTotal total number of currently open alert events
     */
    public void setOpenTotal(int openTotal) {
        this.openTotal = openTotal;
    }

    /**
     * @return how many open alert events nobody has acknowledged yet — the
     *         "needs eyes" number
     */
    public int getUnacknowledged() {
        return unacknowledged;
    }

    /**
     * @param unacknowledged how many open alert events are unacknowledged
     */
    public void setUnacknowledged(int unacknowledged) {
        this.unacknowledged = unacknowledged;
    }

    /**
     * @return how many monitors are currently enabled (being evaluated)
     */
    public int getMonitorsEnabled() {
        return monitorsEnabled;
    }

    /**
     * @param monitorsEnabled how many monitors are currently enabled
     */
    public void setMonitorsEnabled(int monitorsEnabled) {
        this.monitorsEnabled = monitorsEnabled;
    }

    /**
     * @return how many monitors exist in total, enabled or not
     */
    public int getMonitorsTotal() {
        return monitorsTotal;
    }

    /**
     * @param monitorsTotal how many monitors exist in total
     */
    public void setMonitorsTotal(int monitorsTotal) {
        this.monitorsTotal = monitorsTotal;
    }

    /**
     * @return number of distinct started channels covered by at least one
     *         enabled monitor — the effective monitoring footprint
     */
    public int getChannelsWatched() {
        return channelsWatched;
    }

    /**
     * @param channelsWatched number of distinct started channels covered by
     *                        at least one enabled monitor
     */
    public void setChannelsWatched(int channelsWatched) {
        this.channelsWatched = channelsWatched;
    }

    /**
     * @return the most recently opened alert events (newest first) for the
     *         dashboard's "latest problems" list
     */
    public List<AlertEvent> getRecentProblems() {
        return recentProblems;
    }

    /**
     * @param recentProblems the most recently opened alert events, newest first
     */
    public void setRecentProblems(List<AlertEvent> recentProblems) {
        this.recentProblems = recentProblems;
    }

    /**
     * @return the channels with the most open problems, worst first
     */
    public List<TopChannel> getTopChannels() {
        return topChannels;
    }

    /**
     * @param topChannels the channels with the most open problems, worst first
     */
    public void setTopChannels(List<TopChannel> topChannels) {
        this.topChannels = topChannels;
    }

    /**
     * @return when the activity collector job last completed a tick, or
     *         {@code null} if it has not run since engine start — surfaced so
     *         a silently stalled collector is visible instead of masquerading
     *         as "no problems"
     */
    public Instant getLastCollectorRun() {
        return lastCollectorRun;
    }

    /**
     * @param lastCollectorRun when the activity collector last completed a tick
     */
    public void setLastCollectorRun(Instant lastCollectorRun) {
        this.lastCollectorRun = lastCollectorRun;
    }

    /**
     * @return when the trigger evaluator job last completed a tick, or
     *         {@code null} if it has not run since engine start
     */
    public Instant getLastEvaluatorRun() {
        return lastEvaluatorRun;
    }

    /**
     * @param lastEvaluatorRun when the trigger evaluator last completed a tick
     */
    public void setLastEvaluatorRun(Instant lastEvaluatorRun) {
        this.lastEvaluatorRun = lastEvaluatorRun;
    }

    /**
     * @return when the connector-status listener last observed a state
     *         transition, or {@code null} if none has been seen since engine
     *         start (quiet is normal here — connectors only emit on change)
     */
    public Instant getLastConnectorEvent() {
        return lastConnectorEvent;
    }

    /**
     * @param lastConnectorEvent when a connector state transition was last observed
     */
    public void setLastConnectorEvent(Instant lastConnectorEvent) {
        this.lastConnectorEvent = lastConnectorEvent;
    }
}
