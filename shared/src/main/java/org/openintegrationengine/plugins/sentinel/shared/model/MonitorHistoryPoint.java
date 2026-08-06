/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * One day in a monitor's history: how many alerts that monitor opened, and how
 * long they took to resolve on average.
 *
 * <p>Produced by {@code AlertEventRepository.listMonitorHistory}, which
 * aggregates {@code sentinel_alert_event} in the database rather than pulling
 * raw events into Java — a monitor with months of history under the 180-day
 * resolved-alert retention default can carry far more rows than a chart needs
 * points. Response-only; never persisted.</p>
 *
 * <p>Buckets are only emitted for days that actually had an alert. A day with
 * no alerts produces no row at all rather than a zero row, because the SQL is a
 * {@code GROUP BY} over the events themselves and has no calendar to outer-join
 * against. A caller that needs a continuous series — most charts do, so a quiet
 * week reads as a flat line rather than a gap — must fill the missing days
 * itself from the range it asked for.</p>
 */
public class MonitorHistoryPoint {

    private Instant bucket;
    private long alertCount;
    private Double avgResolveSeconds;

    public MonitorHistoryPoint() {
    }

    /**
     * The start of the day this point covers.
     *
     * <p>Day boundaries are the database server's, not UTC's: the underlying
     * SQL truncates {@code opened_time} with each vendor's own date function
     * ({@code date_trunc}, {@code TRUNC}, {@code CONVERT(date, ...)},
     * {@code DATE()}), which operates on the stored local-time value. The
     * repository then interprets that calendar date at midnight in the JVM's
     * default zone, matching how every other timestamp in this plugin crosses
     * the JDBC boundary. In a normal single-timezone deployment that is simply
     * "local midnight"; it is called out because a chart axis rendered in a
     * different zone will show the boundary shifted.</p>
     *
     * @return the instant of midnight starting this point's day
     */
    public Instant getBucket() {
        return bucket;
    }

    /**
     * @param bucket the instant of midnight starting this point's day
     */
    public void setBucket(Instant bucket) {
        this.bucket = bucket;
    }

    /**
     * @return how many alert events this monitor opened during the day —
     *         counted by {@code opened_time}, so an alert that opened on one
     *         day and resolved on the next is counted on the day it opened, and
     *         only once
     */
    public long getAlertCount() {
        return alertCount;
    }

    /**
     * @param alertCount how many alert events this monitor opened during the
     *                   day
     */
    public void setAlertCount(long alertCount) {
        this.alertCount = alertCount;
    }

    /**
     * Mean time to resolve for this day's alerts, in seconds.
     *
     * <p>Averaged over the day's <em>resolved</em> alerts only; still-open ones
     * contribute to {@link #getAlertCount()} but are excluded here, since an
     * unresolved problem has no resolve time to average and counting it as zero
     * would drag MTTR down exactly when things are going worst. Boxed and
     * nullable rather than a primitive for the same reason: a day whose alerts
     * are all still open has an undefined MTTR, which is a different statement
     * from "resolved instantly".</p>
     *
     * @return mean seconds from open to resolve, or {@code null} when none of
     *         the day's alerts have been resolved
     */
    public Double getAvgResolveSeconds() {
        return avgResolveSeconds;
    }

    /**
     * @param avgResolveSeconds mean seconds from open to resolve; {@code null}
     *                          when none of the day's alerts have been resolved
     */
    public void setAvgResolveSeconds(Double avgResolveSeconds) {
        this.avgResolveSeconds = avgResolveSeconds;
    }
}
