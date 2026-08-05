/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * Sentinel's tunable scheduler and retention settings, persisted as engine
 * configuration properties (not a Sentinel table) and edited on the web UI's
 * Settings tab.
 *
 * <p>Field initializers carry the shipped defaults so a fresh install — or a
 * settings read that finds missing/corrupt properties — yields a fully
 * working configuration without null handling. Interval changes are applied
 * to the running Quartz scheduler in place; retention changes take effect on
 * the next nightly prune. Range validation (e.g. collector 10–600s) is the
 * settings service's job, not this DTO's.</p>
 */
public class SentinelSettings {

    private int collectorIntervalSeconds = 30;
    private int evaluatorIntervalSeconds = 60;
    private int sampleRetentionDays = 7;
    private int trendRetentionDays = 90;
    private int resolvedAlertRetentionDays = 180;

    public SentinelSettings() {
    }

    /**
     * @return how often (seconds) the activity collector samples channel
     *         statistics; smaller values sharpen inactivity detection at the
     *         cost of more {@code sentinel_channel_activity_sample} rows
     */
    public int getCollectorIntervalSeconds() {
        return collectorIntervalSeconds;
    }

    /**
     * @param collectorIntervalSeconds how often the activity collector runs
     */
    public void setCollectorIntervalSeconds(int collectorIntervalSeconds) {
        this.collectorIntervalSeconds = collectorIntervalSeconds;
    }

    /**
     * @return how often (seconds) the trigger evaluator re-evaluates every
     *         enabled monitor — the worst-case detection latency added on top
     *         of a monitor's own window
     */
    public int getEvaluatorIntervalSeconds() {
        return evaluatorIntervalSeconds;
    }

    /**
     * @param evaluatorIntervalSeconds how often the trigger evaluator runs
     */
    public void setEvaluatorIntervalSeconds(int evaluatorIntervalSeconds) {
        this.evaluatorIntervalSeconds = evaluatorIntervalSeconds;
    }

    /**
     * @return days raw activity samples are kept before the nightly prune;
     *         kept short because the hourly trend table carries the long
     *         history
     */
    public int getSampleRetentionDays() {
        return sampleRetentionDays;
    }

    /**
     * @param sampleRetentionDays days raw activity samples are kept
     */
    public void setSampleRetentionDays(int sampleRetentionDays) {
        this.sampleRetentionDays = sampleRetentionDays;
    }

    /**
     * @return days hourly trend rows are kept; this bounds how much history
     *         anomaly baselines can draw on, so it should comfortably exceed
     *         the largest configured baseline window
     */
    public int getTrendRetentionDays() {
        return trendRetentionDays;
    }

    /**
     * @param trendRetentionDays days hourly trend rows are kept
     */
    public void setTrendRetentionDays(int trendRetentionDays) {
        this.trendRetentionDays = trendRetentionDays;
    }

    /**
     * @return days RESOLVED alert events are kept before pruning; open
     *         PROBLEM events are never pruned regardless of age
     */
    public int getResolvedAlertRetentionDays() {
        return resolvedAlertRetentionDays;
    }

    /**
     * @param resolvedAlertRetentionDays days resolved alert events are kept
     */
    public void setResolvedAlertRetentionDays(int resolvedAlertRetentionDays) {
        this.resolvedAlertRetentionDays = resolvedAlertRetentionDays;
    }
}
