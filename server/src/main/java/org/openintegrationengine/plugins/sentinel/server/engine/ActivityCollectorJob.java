/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.model.ChannelStatistics;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;

import org.openintegrationengine.plugins.sentinel.server.db.ActivityRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.ActivitySample;

/**
 * The collector tick: reads the engine's in-memory cumulative channel
 * counters, diffs them against the previous tick's snapshot in
 * {@link CollectorState}, and writes one
 * {@code sentinel_channel_activity_sample} row per deployed channel. Every
 * volume-based monitor (INACTIVITY, LOW_VOLUME, ANOMALY) and the activity
 * charts are downstream of these rows.
 *
 * <p>Data-source choices, all deliberate:
 * {@code getChannelStatisticsList(null, false)} — {@code false} because
 * {@code includeUndeployed=true} switches to a per-call DB read of stored
 * statistics, which is too expensive for a 30-second tick (and undeployed
 * channels process no messages anyway). Started-state filtering happens in
 * the evaluator, not here — samples are still collected for deployed-but-
 * paused channels so their queue depth history stays continuous. Connector
 * status is NOT collected here: that is event-driven via
 * {@link SentinelConnectorStatusListener}, because the engine API this job
 * was originally designed to poll ({@code getConnectionStatesForServer})
 * NPEs on non-TCP connectors.</p>
 *
 * <p>Counter-delta correctness: the engine's counters are cumulative and can
 * go backwards — channel redeploy with cleared statistics, server restart,
 * or an operator's "Clear Statistics" (a real code path through
 * {@code DonkeyEngineController -> dao.resetStatistics}). Deltas are
 * therefore clamped to {@code Math.max(0, current - previous)}, and the
 * first observation of a channel records a zero delta rather than the whole
 * cumulative counter (which would count every message the channel ever
 * processed as one tick's traffic and fire every volume monitor at once).
 * Known limitation: a "Clear Statistics" during Sentinel's uptime can
 * suppress up to one tick of real traffic in the clamped delta.</p>
 *
 * <p>Beyond sampling, each tick also maintains {@link CollectorState}'s
 * bookkeeping: per-channel started-since stamps (so window evaluators can
 * require "watched while running", not just "watched" — see
 * {@code InactivityEvaluator}), and the eviction sweep that forgets channels
 * which left the deployed set plus connector-state entries whose connector
 * was deleted in a channel edit — connector states are only written by
 * events, so without this sweep a departed connector's last (possibly
 * DISCONNECTED) state would survive forever and feed CONNECTION_STATUS
 * monitors phantom breaches.</p>
 *
 * <p>{@code @DisallowConcurrentExecution} is load-bearing: a tick slower
 * than the collector interval would otherwise overlap the next one, and two
 * overlapping ticks both diff against the same previous-counters snapshot —
 * each would insert the full delta since the last completed tick,
 * double-counting that window's traffic in the sample table (masking
 * LOW_VOLUME breaches, faking ANOMALY spikes, corrupting rollup baselines).
 * The misfire policy only covers the no-free-thread case, not overlap.</p>
 */
@DisallowConcurrentExecution
public class ActivityCollectorJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ActivityCollectorJob.class);

    /**
     * Runs one collector tick. Never throws: Quartz would log and, worse, a
     * repeated exception says nothing a log line doesn't — the schedule must
     * keep firing so a transient DB or engine hiccup self-heals on the next
     * tick.
     */
    @Override
    public void execute(JobExecutionContext context) {
        try {
            EngineController engineController = ControllerFactory.getFactory().createEngineController();
            Set<String> deployedIds = engineController.getDeployedIds();
            List<ChannelStatistics> statisticsList = engineController.getChannelStatisticsList(null, false);

            Instant now = Instant.now();
            CollectorState state = CollectorState.getInstance();

            for (ChannelStatistics statistics : statisticsList) {
                String channelId = statistics.getChannelId();
                // The stats list can lag the deployed set during an
                // undeploy; skip anything not currently deployed so we never
                // sample a channel mid-teardown.
                if (channelId == null || !deployedIds.contains(channelId)) {
                    continue;
                }

                // Per-channel try/catch: one channel's failed insert must not
                // starve every other channel of its sample for this tick.
                try {
                    collectChannel(state, statistics, now);
                } catch (Exception e) {
                    log.error("Failed to collect activity sample for channel {}", channelId, e);
                }
            }

            maintainStartedStamps(state, deployedIds, now);
            evictDepartedState(state, deployedIds);

            state.recordCollectorRun(now);
        } catch (Throwable t) {
            log.error("Activity collector tick failed", t);
        }
    }

    /**
     * Updates the per-channel started-since stamps from the engine's live
     * runtime state. Driven from here (not the evaluator) because the
     * collector is the highest-frequency observer, minimizing how long a
     * stop/start can go unnoticed between observations.
     */
    private static void maintainStartedStamps(CollectorState state, Set<String> deployedIds, Instant now) {
        for (String channelId : deployedIds) {
            if (ScopeResolver.isChannelStarted(channelId)) {
                state.markChannelStarted(channelId, now);
            } else {
                state.markChannelStopped(channelId);
            }
        }
    }

    /**
     * The eviction sweep: forgets all in-memory state for channels no longer
     * deployed, and prunes connector-state entries whose connector no longer
     * exists on a still-deployed channel (deleted destination + redeploy).
     * Without it the connector-state map only ever grows, and stale
     * DISCONNECTED entries for connectors that no longer exist would breach
     * CONNECTION_STATUS monitors forever ({@code forgetChannel} existed for
     * exactly this but was never called). {@code getConnectorNames} reads
     * the engine's channel cache — no DB hit per tick.
     */
    private static void evictDepartedState(CollectorState state, Set<String> deployedIds) {
        for (String knownId : state.knownChannelIds()) {
            try {
                if (!deployedIds.contains(knownId)) {
                    state.forgetChannel(knownId);
                    continue;
                }
                Map<Integer, String> connectorNames =
                        ChannelController.getInstance().getConnectorNames(knownId);
                if (connectorNames != null) {
                    state.retainConnectorStates(knownId, connectorNames.keySet());
                }
            } catch (Exception e) {
                log.warn("Failed to prune collector state for channel {}", knownId, e);
            }
        }
    }

    /**
     * Diffs one channel's counters against the previous tick and persists
     * the sample.
     *
     * <p>Ordering matters: the previous-counters snapshot is only advanced
     * AFTER the insert succeeds. If the insert fails, the old snapshot stays
     * in place, so the next successful sample's delta covers the missed
     * interval too — window sums over the sample table stay correct, they
     * just lose one row of time resolution instead of silently dropping
     * traffic.</p>
     */
    private void collectChannel(CollectorState state, ChannelStatistics statistics, Instant now) {
        String channelId = statistics.getChannelId();
        CollectorState.Counters previous = state.getPreviousCounters(channelId);

        ActivitySample sample = new ActivitySample();
        sample.setChannelId(channelId);
        sample.setSampleTime(now);

        if (previous == null) {
            // First observation (plugin just started, or channel newly
            // seen): zero deltas — see class Javadoc.
            sample.setReceivedDelta(0L);
            sample.setSentDelta(0L);
            sample.setErrorDelta(0L);
            sample.setFilteredDelta(0L);
        } else {
            sample.setReceivedDelta(Math.max(0L, statistics.getReceived() - previous.received));
            sample.setSentDelta(Math.max(0L, statistics.getSent() - previous.sent));
            sample.setErrorDelta(Math.max(0L, statistics.getError() - previous.error));
            sample.setFilteredDelta(Math.max(0L, statistics.getFiltered() - previous.filtered));
        }
        // Queue depth is an instantaneous gauge, not a cumulative counter —
        // stored as-is, no diffing.
        sample.setQueuedSnapshot(statistics.getQueued());

        ActivityRepository.insertActivitySample(sample);

        state.putCounters(channelId, new CollectorState.Counters(
                statistics.getReceived(), statistics.getSent(),
                statistics.getError(), statistics.getFiltered()));
    }
}
