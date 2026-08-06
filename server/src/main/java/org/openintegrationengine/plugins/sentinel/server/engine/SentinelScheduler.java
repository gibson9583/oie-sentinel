/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.util.Properties;

import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/**
 * Owns Sentinel's private Quartz scheduler and the four background jobs:
 * the activity collector and trigger evaluator (interval-driven,
 * operator-configurable), the hourly activity rollup, and the daily
 * retention prune.
 *
 * <p>Why a private scheduler instead of piggybacking on an engine one: the
 * engine has no shared scheduler to join — its own precedent
 * ({@code PollConnectorJobHandler}, the Data Pruner) is one privately-built
 * {@code StdSchedulerFactory} per owner, each with a unique
 * {@code instanceName} so they coexist in Quartz's scheduler repository.
 * Sentinel follows suit with instance name "SentinelScheduler", the default
 * in-memory RAMJobStore (job state is rebuilt from settings at every plugin
 * start, so persistence would only preserve stale schedules), and a small
 * fixed thread pool of 3 — enough for the worst case of a slow evaluator
 * tick overlapping the collector and a cron job, without ever competing
 * with message processing for more threads.</p>
 *
 * <p>Multi-node engines: every node builds this same scheduler and fires its
 * own ticks. Exclusivity is enforced one level down, by the
 * {@link SentinelLeadership} check each job body opens with, so a non-leader
 * node ticks and returns immediately. That is deliberate — the alternative,
 * a clustered Quartz {@code JDBCJobStore}, would couple Sentinel's scheduling
 * to the engine's database configuration (Quartz's own DDL, per-vendor
 * delegate classes, cluster clock requirements) to buy an exclusivity that a
 * single lease row already provides. Note the corollary: the scheduler knows
 * nothing about leadership, so nothing here changes when it moves — the
 * triggers keep firing on every node either way.</p>
 *
 * <p>Stateful singleton (like {@link CollectorState}) because the scheduler
 * handle must be shared between the plugin lifecycle
 * ({@code SentinelServicePlugin.start()/stop()}) and the settings service,
 * which calls {@link #reschedule(SentinelSettings)} when an operator changes
 * the intervals. All lifecycle methods are {@code synchronized} — they are
 * rare, human-triggered operations, and serializing them removes every
 * start/stop/reschedule race outright.</p>
 *
 * <p>Misfire policy: the interval triggers use
 * {@code withMisfireHandlingInstructionNextWithRemainingCount} so that if
 * ticks are missed (thread pool saturated, clock jump, long GC pause) the
 * schedule simply resumes at the next slot rather than firing a burst of
 * catch-up ticks — a burst of collector ticks would write near-zero-delta
 * samples that distort volume baselines. The cron triggers use
 * {@code withMisfireHandlingInstructionDoNothing} for the same reason:
 * a missed rollup hour is recovered naturally (the rollup is idempotent and
 * the raw samples outlive it by days), and a missed prune night is covered
 * by the next night's run.</p>
 */
public final class SentinelScheduler {

    private static final Logger log = LoggerFactory.getLogger(SentinelScheduler.class);

    private static final SentinelScheduler INSTANCE = new SentinelScheduler();

    /** Quartz group for every Sentinel job and trigger, so nothing collides with another plugin's keys. */
    private static final String GROUP = "sentinel";

    private static final String COLLECTOR_JOB = "activityCollector";
    private static final String COLLECTOR_TRIGGER = "activityCollectorTrigger";
    private static final String EVALUATOR_JOB = "triggerEvaluator";
    private static final String EVALUATOR_TRIGGER = "triggerEvaluatorTrigger";
    private static final String ROLLUP_JOB = "activityRollup";
    private static final String ROLLUP_TRIGGER = "activityRollupTrigger";
    private static final String PRUNE_JOB = "retentionPrune";
    private static final String PRUNE_TRIGGER = "retentionPruneTrigger";

    /** Five past the hour — the rolled-up hour is fully closed and its last collector tick long written. */
    private static final String ROLLUP_CRON = "0 5 * * * ?";
    /** Daily at 03:30 — off-peak, offset from the engine Data Pruner's default window. */
    private static final String PRUNE_CRON = "0 30 3 * * ?";

    /** The live scheduler; null whenever the plugin is stopped. */
    private Scheduler scheduler;

    private SentinelScheduler() {
    }

    /** Returns the single shared instance (see class Javadoc for why this is a stateful singleton). */
    public static SentinelScheduler getInstance() {
        return INSTANCE;
    }

    /**
     * Builds the private scheduler, registers all four jobs with triggers
     * derived from the given settings, and starts it. Called from
     * {@code SentinelServicePlugin.start()}.
     *
     * <p>Idempotence guard: if a scheduler is already live this logs and
     * returns rather than building a second one — {@code StdSchedulerFactory}
     * would otherwise hand back the existing instance by name and the job
     * registrations would fail on duplicate keys.</p>
     *
     * @param settings current settings supplying the collector/evaluator intervals
     * @throws RuntimeException wrapping any {@link SchedulerException}; the
     *         caller (plugin start) decides whether that is fatal — this
     *         class does not swallow a failure that means "no monitoring
     *         will ever run"
     */
    public synchronized void start(SentinelSettings settings) {
        if (scheduler != null) {
            log.warn("Sentinel scheduler already started; ignoring duplicate start request");
            return;
        }
        try {
            Properties properties = new Properties();
            properties.setProperty("org.quartz.scheduler.instanceName", "SentinelScheduler");
            properties.setProperty("org.quartz.threadPool.threadCount", "3");
            properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");

            StdSchedulerFactory factory = new StdSchedulerFactory();
            factory.initialize(properties);
            Scheduler newScheduler = factory.getScheduler();

            JobDetail collectorJob = durableJob(ActivityCollectorJob.class, COLLECTOR_JOB);
            newScheduler.scheduleJob(collectorJob,
                    intervalTrigger(COLLECTOR_TRIGGER, collectorJob, settings.getCollectorIntervalSeconds()));

            JobDetail evaluatorJob = durableJob(TriggerEvaluatorJob.class, EVALUATOR_JOB);
            newScheduler.scheduleJob(evaluatorJob,
                    intervalTrigger(EVALUATOR_TRIGGER, evaluatorJob, settings.getEvaluatorIntervalSeconds()));

            JobDetail rollupJob = durableJob(ActivityRollupJob.class, ROLLUP_JOB);
            newScheduler.scheduleJob(rollupJob, cronTrigger(ROLLUP_TRIGGER, rollupJob, ROLLUP_CRON));

            JobDetail pruneJob = durableJob(RetentionPruneJob.class, PRUNE_JOB);
            newScheduler.scheduleJob(pruneJob, cronTrigger(PRUNE_TRIGGER, pruneJob, PRUNE_CRON));

            newScheduler.start();
            scheduler = newScheduler;

            log.info("Sentinel scheduler started (collector every {}s, evaluator every {}s, "
                            + "rollup cron '{}', prune cron '{}')",
                    settings.getCollectorIntervalSeconds(), settings.getEvaluatorIntervalSeconds(),
                    ROLLUP_CRON, PRUNE_CRON);
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to start the Sentinel scheduler", e);
        }
    }

    /**
     * Swaps the two interval triggers in place to pick up changed collector
     * and evaluator intervals. Called by the settings service after a
     * successful settings update; the cron jobs are untouched because their
     * schedules are fixed by design, not operator-configurable.
     *
     * <p>{@code rescheduleJob} replaces the trigger atomically inside the
     * live scheduler — no job unregistration, no missed or doubled tick
     * beyond the interval change itself.</p>
     *
     * @param settings the just-persisted settings with the new intervals
     * @throws RuntimeException wrapping any {@link SchedulerException} so
     *         the settings API call that requested the change surfaces the
     *         failure instead of silently keeping the old intervals
     */
    public synchronized void reschedule(SentinelSettings settings) {
        if (scheduler == null) {
            log.warn("Sentinel scheduler not running; reschedule request ignored "
                    + "(the new intervals will apply at next plugin start)");
            return;
        }
        try {
            scheduler.rescheduleJob(TriggerKey.triggerKey(COLLECTOR_TRIGGER, GROUP),
                    intervalTrigger(COLLECTOR_TRIGGER,
                            scheduler.getJobDetail(JobKey.jobKey(COLLECTOR_JOB, GROUP)),
                            settings.getCollectorIntervalSeconds()));
            scheduler.rescheduleJob(TriggerKey.triggerKey(EVALUATOR_TRIGGER, GROUP),
                    intervalTrigger(EVALUATOR_TRIGGER,
                            scheduler.getJobDetail(JobKey.jobKey(EVALUATOR_JOB, GROUP)),
                            settings.getEvaluatorIntervalSeconds()));

            log.info("Sentinel scheduler rescheduled (collector every {}s, evaluator every {}s)",
                    settings.getCollectorIntervalSeconds(), settings.getEvaluatorIntervalSeconds());
        } catch (SchedulerException e) {
            throw new RuntimeException("Failed to reschedule the Sentinel jobs", e);
        }
    }

    /**
     * Shuts the scheduler down, waiting for any in-flight job to finish so a
     * half-written collector tick or rollup is never abandoned mid-insert,
     * then drops the handle so a later {@link #start(SentinelSettings)}
     * builds a fresh scheduler. Called from
     * {@code SentinelServicePlugin.stop()}.
     *
     * <p>Never throws: stop() runs during server shutdown/redeploy, where an
     * exception could interfere with the rest of the extension teardown; a
     * scheduler that failed to stop cleanly dies with the JVM anyway.</p>
     */
    public synchronized void shutdown() {
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.shutdown(true);
            log.info("Sentinel scheduler shut down");
        } catch (SchedulerException e) {
            log.warn("Failed to shut down the Sentinel scheduler cleanly", e);
        } finally {
            scheduler = null;
        }
    }

    /**
     * Builds a durable JobDetail in the sentinel group. Durable so the job
     * definition survives trigger replacement during
     * {@link #reschedule(SentinelSettings)} — a non-durable job is deleted
     * the moment it has no triggers, which would race the swap.
     */
    private static JobDetail durableJob(Class<? extends Job> jobClass, String name) {
        return JobBuilder.newJob(jobClass)
                .withIdentity(name, GROUP)
                .storeDurably()
                .build();
    }

    /**
     * Builds a fixed-interval trigger with the skip-missed-ticks misfire
     * policy (see class Javadoc for why bursts of catch-up ticks are
     * harmful here).
     */
    private static Trigger intervalTrigger(String name, JobDetail job, int intervalSeconds) {
        return TriggerBuilder.newTrigger()
                .withIdentity(name, GROUP)
                .forJob(job)
                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(intervalSeconds)
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    /** Builds a cron trigger with the do-nothing misfire policy (missed runs are recovered naturally). */
    private static Trigger cronTrigger(String name, JobDetail job, String cronExpression) {
        return TriggerBuilder.newTrigger()
                .withIdentity(name, GROUP)
                .forJob(job)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}
