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
import org.openintegrationengine.plugins.sentinel.server.db.ConnectorStatusRepository;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/**
 * Daily retention pruning: keeps the four unbounded-growth Sentinel tables
 * (raw activity samples, hourly trend buckets, resolved alert history, and
 * connector status transitions) within the operator-configured retention
 * windows. Without this job a busy server accretes a sample row per channel
 * every collector tick — and a status row per connector transition —
 * forever.
 *
 * <p>Connector status events share {@code sampleRetentionDays} rather than
 * getting a knob of their own: nothing in the plugin reads them back (the
 * CONNECTION_STATUS evaluator works from in-memory collector state), so the
 * rows only serve ad-hoc operator diagnosis, the same freshness class as the
 * raw samples they sit beside.</p>
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
 * <p>Leadership-gated, like the collector and the rollup: every node of a
 * multi-node engine fires this cron, and only the holder of the Sentinel
 * leader lease runs it. Two nodes pruning together would be correct — the
 * cutoffs only move forward, so the second run finds nothing — but they would
 * be issuing overlapping bulk deletes over the same rows at 03:30, which is
 * the one time of night this job is trying to keep off the database's back.
 * A night on which the leader is down is simply covered by the next
 * night's run, since the cutoffs are recomputed from {@code now} each
 * time.</p>
 *
 * <p>{@code @DisallowConcurrentExecution} for consistency with the interval
 * jobs: a prune that runs long must never overlap a second bulk delete over
 * the same rows.</p>
 */
@DisallowConcurrentExecution
public class RetentionPruneJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(RetentionPruneJob.class);

    /**
     * Runs one prune pass, unless another node holds the Sentinel leader lease
     * (see class Javadoc). Never throws — the schedule must survive a bad
     * night (transient DB outage); rows that escape tonight's prune are
     * caught by tomorrow's, since the cutoffs only move forward.
     */
    @Override
    public void execute(JobExecutionContext context) {
        if (!SentinelLeadership.isLeader()) {
            return;
        }
        try {
            SentinelSettings settings = SettingsService.get();
            Instant now = Instant.now();

            Instant sampleCutoff = now.minus(Duration.ofDays(settings.getSampleRetentionDays()));
            int samplesDeleted = ActivityRepository.deleteActivitySamplesOlderThan(sampleCutoff);

            Instant trendCutoff = now.minus(Duration.ofDays(settings.getTrendRetentionDays()));
            int trendsDeleted = ActivityRepository.deleteActivityTrendOlderThan(trendCutoff);

            Instant alertCutoff = now.minus(Duration.ofDays(settings.getResolvedAlertRetentionDays()));
            int alertsDeleted = AlertEventRepository.deleteResolvedAlertEventsOlderThan(alertCutoff);

            int statusDeleted = ConnectorStatusRepository.deleteConnectorStatusEventsOlderThan(sampleCutoff);

            // Counts at info: pruning is destructive, so the log should
            // always answer "what did last night's run delete" without the
            // operator having to enable debug logging first.
            log.info("Retention prune complete: {} activity sample(s) older than {}d, "
                            + "{} trend bucket(s) older than {}d, {} resolved alert(s) older than {}d, "
                            + "{} connector status event(s) older than {}d",
                    samplesDeleted, settings.getSampleRetentionDays(),
                    trendsDeleted, settings.getTrendRetentionDays(),
                    alertsDeleted, settings.getResolvedAlertRetentionDays(),
                    statusDeleted, settings.getSampleRetentionDays());
        } catch (Throwable t) {
            log.error("Retention prune run failed", t);
        }
    }
}
