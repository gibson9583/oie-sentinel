/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Duration;
import java.time.Instant;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.server.db.AlertEventRepository;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/**
 * Daily retention pruning: keeps the three unbounded-growth Sentinel tables
 * (raw activity samples, hourly trend buckets, resolved alert history)
 * within the operator-configured retention windows. Without this job a busy
 * server accretes a sample row per channel every collector tick forever.
 *
 * <p>Retention values are re-read from {@link SettingsService} on every run
 * rather than captured at schedule time, so a settings change takes effect
 * at the next prune without any rescheduling.</p>
 *
 * <p>What is deliberately NOT pruned: open PROBLEM alert events, regardless
 * of age — an unresolved problem is live operational state, not history
 * ({@code deleteResolvedAlertEventsOlderThan} filters on RESOLVED status).
 * {@code sentinel_action_dispatch_log} needs no statement of its own: its
 * foreign key to {@code sentinel_alert_event} is {@code ON DELETE CASCADE},
 * so dispatch history follows its alert out.</p>
 *
 * <p>Scheduled daily at 03:30 (cron {@code 0 30 3 * * ?}) — off-peak for
 * most sites, and offset from the engine's own Data Pruner default so the
 * two bulk-delete jobs don't contend for the database at the same moment.</p>
 *
 * <p>{@code @DisallowConcurrentExecution} for consistency with the interval
 * jobs: a prune that runs long must never overlap a second bulk delete over
 * the same rows.</p>
 */
@DisallowConcurrentExecution
public class RetentionPruneJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(RetentionPruneJob.class);

    /**
     * Runs one prune pass. Never throws — the schedule must survive a bad
     * night (transient DB outage); rows that escape tonight's prune are
     * caught by tomorrow's, since the cutoffs only move forward.
     */
    @Override
    public void execute(JobExecutionContext context) {
        try {
            SentinelSettings settings = SettingsService.get();
            Instant now = Instant.now();

            Instant sampleCutoff = now.minus(Duration.ofDays(settings.getSampleRetentionDays()));
            int samplesDeleted = ActivityRepository.deleteActivitySamplesOlderThan(sampleCutoff);

            Instant trendCutoff = now.minus(Duration.ofDays(settings.getTrendRetentionDays()));
            int trendsDeleted = ActivityRepository.deleteActivityTrendOlderThan(trendCutoff);

            Instant alertCutoff = now.minus(Duration.ofDays(settings.getResolvedAlertRetentionDays()));
            int alertsDeleted = AlertEventRepository.deleteResolvedAlertEventsOlderThan(alertCutoff);

            // Counts at info: pruning is destructive, so the log should
            // always answer "what did last night's run delete" without the
            // operator having to enable debug logging first.
            log.info("Retention prune complete: {} activity sample(s) older than {}d, "
                            + "{} trend bucket(s) older than {}d, {} resolved alert(s) older than {}d",
                    samplesDeleted, settings.getSampleRetentionDays(),
                    trendsDeleted, settings.getTrendRetentionDays(),
                    alertsDeleted, settings.getResolvedAlertRetentionDays());
        } catch (Throwable t) {
            log.error("Retention prune run failed", t);
        }
    }
}
