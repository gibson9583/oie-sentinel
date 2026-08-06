/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p>The tick's rows are accumulated in memory and written in a single
 * batched flush, not one insert per channel: this is the plugin's highest-
 * frequency write, it targets the engine's operational database (the same
 * one carrying message data), and at the 30-second default a 200-channel
 * server produces 576,000 rows a day. Per-channel inserts made that 200
 * round trips every 30 seconds; the batch makes it one. See
 * {@link #flushSamples} for why the flush must precede every snapshot
 * advance.</p>
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
 * <p>Leadership-gated: every node of a multi-node engine runs its own
 * scheduler and fires its own collector ticks, so the body opens with a
 * {@link SentinelLeadership#isLeader()} check and a non-leader's tick returns
 * without reading a counter or writing a row. Without it two nodes would each
 * write a full set of samples against the same database every tick, doubling
 * every delta and every rollup bucket built from them. The consequence for
 * this job specifically is that a non-leader's {@link CollectorState} stays
 * cold — no counter snapshots, no started-since stamps — so on failover the
 * new leader's first tick sees no previous counters and records zero deltas,
 * exactly as it does after a server restart. That loses one tick of delta
 * baseline and is the right outcome: diffing against nothing would bank each
 * channel's entire cumulative counter as one tick of traffic. The cold
 * started-since map has the same shape of effect on the window evaluators,
 * which report INSUFFICIENT_DATA until a channel has been observed started
 * across a full window rather than reporting a breach they cannot yet
 * substantiate. A standby node also never advances its collector-run stamp,
 * so a dashboard served from it reports the collector as not having run
 * here — which is accurate for that node.</p>
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
     * Runs one collector tick, unless another node holds the Sentinel leader
     * lease — see the class Javadoc for why the guard is here rather than in
     * the scheduler, and what a non-leader's cold {@link CollectorState} costs
     * at failover. Transitions in and out of leadership are logged by
     * {@link SentinelLeadership}, so a skipped tick needs no log line of its
     * own here (this one fires every few seconds).
     *
     * <p>Never throws: Quartz would log and, worse, a repeated exception says
     * nothing a log line doesn't — the schedule must keep firing so a
     * transient DB or engine hiccup self-heals on the next tick.</p>
     */
    @Override
    public void execute(JobExecutionContext context) {
        if (!SentinelLeadership.isLeader()) {
            return;
        }
        try {
            EngineController engineController = ControllerFactory.getFactory().createEngineController();
            Set<String> deployedIds = engineController.getDeployedIds();
            List<ChannelStatistics> statisticsList = engineController.getChannelStatisticsList(null, false);

            Instant now = Instant.now();
            CollectorState state = CollectorState.getInstance();

            List<ActivitySample> samples = new ArrayList<>();
            // Insertion-ordered so the snapshot advance in flushSamples walks
            // channels in the same order the rows were queued, which keeps
            // debug logs of a partial tick readable.
            Map<String, CollectorState.Counters> observed = new LinkedHashMap<>();

            for (ChannelStatistics statistics : statisticsList) {
                String channelId = statistics.getChannelId();
                // The stats list can lag the deployed set during an
                // undeploy; skip anything not currently deployed so we never
                // sample a channel mid-teardown.
                if (channelId == null || !deployedIds.contains(channelId)) {
                    continue;
                }

                // Per-channel try/catch: one channel's unreadable statistics
                // must not cost every other channel its sample for this tick.
                // A channel that throws here contributes neither a row nor a
                // pending snapshot, so it simply retries next tick.
                try {
                    ChannelReading reading = readChannel(state, statistics, now);
                    samples.add(reading.sample());
                    observed.put(channelId, reading.counters());
                } catch (Exception e) {
                    log.error("Failed to read activity sample for channel {}", channelId, e);
                }
            }

            flushSamples(state, samples, observed);

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
     * One channel's tick result: the row to write, paired with the exact
     * cumulative counter reading its deltas were computed from. The two
     * travel together because the snapshot later stored in
     * {@link CollectorState} must be that same reading — see
     * {@link #flushSamples}.
     */
    private record ChannelReading(ActivitySample sample, CollectorState.Counters counters) {
    }

    /**
     * Diffs one channel's counters against the previous tick and builds the
     * row to write, without persisting anything or mutating any state. Both
     * side effects are deferred to {@link #flushSamples}, which performs them
     * in the one order that is safe.
     *
     * <p>Returning the counters alongside the sample — rather than re-reading
     * {@code statistics} when the snapshot is stored — keeps the two provably
     * consistent: whatever reading produced the persisted deltas is exactly
     * what the next tick diffs against, so no traffic can fall between two
     * readings of the same channel.</p>
     */
    private static ChannelReading readChannel(CollectorState state, ChannelStatistics statistics, Instant now) {
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

        return new ChannelReading(sample, new CollectorState.Counters(
                statistics.getReceived(), statistics.getSent(),
                statistics.getError(), statistics.getFiltered()));
    }

    /**
     * Writes the tick's samples in one batched round trip, then — and only
     * then — advances every sampled channel's previous-counters snapshot.
     *
     * <p><b>This ordering is load-bearing, and batching does not relax it.</b>
     * A snapshot must never move forward over a delta that was not persisted.
     * {@link ActivityRepository#insertActivitySamples(List)} commits the
     * batch as a single transaction, so the flush is all-or-nothing: on
     * failure this method returns without touching {@link CollectorState},
     * and every channel keeps the counters it was last successfully sampled
     * at. The next successful tick then diffs against those older counters
     * and its delta covers the missed interval as well — window sums over the
     * sample table stay correct, they just lose a row of time resolution
     * instead of silently dropping traffic. Advancing the snapshots before
     * the flush (or advancing each channel's as its row was queued) would
     * discard whatever traffic the failed batch measured, permanently
     * understating every volume baseline and rollup computed from those
     * rows.</p>
     *
     * <p>Failure is logged and swallowed rather than rethrown: the rest of
     * the tick's bookkeeping — started-since stamps and the eviction sweep —
     * is independent of the sample write and must still run, and the
     * collector-run stamp must still advance so the dashboard reports the
     * tick as having executed.</p>
     *
     * @param state    the shared collector state whose snapshots are advanced
     * @param samples  the tick's rows, in queue order
     * @param observed each sampled channel's counter reading, keyed by
     *                 channel id — the snapshots to install once the write
     *                 commits
     */
    private static void flushSamples(CollectorState state, List<ActivitySample> samples,
            Map<String, CollectorState.Counters> observed) {
        if (samples.isEmpty()) {
            return;
        }

        try {
            ActivityRepository.insertActivitySamples(samples);
        } catch (Exception e) {
            log.error("Failed to insert activity sample batch of {} rows; leaving all previous-counter "
                    + "snapshots unadvanced so the next successful tick covers this interval",
                    samples.size(), e);
            return;
        }

        for (Map.Entry<String, CollectorState.Counters> entry : observed.entrySet()) {
            state.putCounters(entry.getKey(), entry.getValue());
        }
    }
}
