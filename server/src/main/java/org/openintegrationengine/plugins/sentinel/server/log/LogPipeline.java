/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.core.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.logging.MirthLog4jFilter;

import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import org.openintegrationengine.plugins.sentinel.shared.model.LogPipelineStatus;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;

/**
 * This node's log capture for Log monitors: the appender, the queue, the
 * matcher thread and the counts, which the plugin starts and stops together.
 */
public final class LogPipeline {

    private static final Logger log = LoggerFactory.getLogger(LogPipeline.class);

    /** How long a stop waits for the matcher thread to finish its current line. */
    private static final long JOIN_MILLIS = 2_000;

    /** How long a stop waits for its final flush: a database that does not answer must not hold up the stop. */
    static final long FINAL_FLUSH_MILLIS = 5_000;

    private static final LogPipeline INSTANCE = new LogPipeline(new MirthLog4jFilter(), System::nanoTime);

    private final LogQueue queue;
    private final LogMatchCounter counter;
    private final LogMatcherThread matcher;
    /** Set when lines may have gone uncounted since the last flush that wrote; see {@link #takeComplete()}. */
    private final AtomicBoolean incomplete = new AtomicBoolean(true);
    private volatile long droppedAtLastTake;
    /** Guarded by this. */
    private boolean started;
    /** Runs a stop's final flush on its own thread, at most {@link #FINAL_FLUSH_MILLIS}. */
    private volatile Consumer<Runnable> finalFlushRunner = flush -> runWithin(flush, FINAL_FLUSH_MILLIS);

    LogPipeline(Filter engineFilter, LongSupplier nanoClock) {
        queue = new LogQueue(new SentinelSettings().getLogQueueCapacity());
        counter = new LogMatchCounter();
        matcher = new LogMatcherThread(queue, counter, engineFilter, nanoClock);
    }

    public static LogPipeline get() {
        return INSTANCE;
    }

    /**
     * Starts from nothing: empties the queue and counts, starts the matcher
     * thread, then attaches the appender. Nothing is queued until the first
     * flush loads the monitors.
     *
     * @throws RuntimeException or a linkage error when the appender cannot
     *         attach; the matcher thread is running by then
     */
    public synchronized void start(int queueCapacity) {
        queue.reset(queueCapacity);
        droppedAtLastTake = 0;
        incomplete.set(true);
        counter.clear();
        matcher.start();
        started = true;
        LogEventAppender.attachToEngine(queue);
    }

    /**
     * Detaches the appender and stops the matcher thread, so nothing new is
     * counted, writes what was matched since the last flush, then forgets what
     * was queued and counted. The plugin shuts the scheduler down first, which
     * waits for a running flush job, so no flush job runs alongside this one.
     * Never throws because of the final flush, and waits for it at most
     * {@link #FINAL_FLUSH_MILLIS}; its notifications stay pending until the
     * leader's evaluator delivers them after the restart.
     */
    public synchronized void stop() {
        boolean wasStarted = started;
        started = false;
        try {
            LogEventAppender.detach();
        } finally {
            boolean matcherFinished = matcher.stop(JOIN_MILLIS);
            try {
                if (wasStarted && counter.isCounting()) {
                    finalFlushRunner.accept(() -> finalFlush(matcherFinished));
                }
            } catch (Throwable t) {
                log.warn("Sentinel's final log flush failed; lines matched since the last flush are not written", t);
            } finally {
                queue.clear();
                counter.clear();
            }
        }
    }

    /**
     * Matches the lines the stopped matcher left queued, unless it is still
     * stuck on one, then writes through the flush job's own write.
     */
    private void finalFlush(boolean matcherFinished) {
        try {
            if (matcherFinished) {
                matcher.processQueued();
            }
            LogMatchFlushJob.flushOnStop(this, SentinelLeadership.nodeId());
        } catch (Throwable t) {
            log.warn("Sentinel's final log flush failed; lines matched since the last flush are not written", t);
        }
    }

    /** Runs the task on a daemon thread and waits at most {@code millis}; a task still running is interrupted. */
    static void runWithin(Runnable task, long millis) {
        Thread thread = new Thread(task, "Sentinel final log flush");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            thread.interrupt();
            log.warn("Sentinel's final log flush did not finish in {} ms; the stop goes on without it", millis);
        }
    }

    /** For tests, whose database mocks apply only on their own thread. */
    void runFinalFlushWith(Consumer<Runnable> runner) {
        finalFlushRunner = runner;
    }

    /** Records that lines may have gone uncounted, so the next rows written are not complete coverage. */
    void markIncomplete() {
        incomplete.set(true);
    }

    /**
     * Whether every line logged since the previous call was checked: none
     * dropped from a full queue, the appender attached throughout, and no
     * internal error. The first call after a start is false, because the
     * interval since the previous row includes the time Sentinel was not
     * running. A false row still counts as evidence for "at least N"; "at
     * most N" and clearing by condition need complete rows.
     */
    boolean takeComplete() {
        long dropped = queue.dropped();
        boolean complete = !incomplete.getAndSet(false) && dropped == droppedAtLastTake;
        droppedAtLastTake = dropped;
        return complete;
    }

    /** Changes the queue's capacity; lines already queued stay. */
    public void resize(int queueCapacity) {
        queue.resize(queueCapacity);
    }

    /** Loads the monitors to match and lets in only lines one of them could match. */
    void refresh(List<Monitor> monitors) {
        queue.setGate(counter.refresh(monitors));
    }

    /**
     * @return {@code null} while this node's counts can be trusted; otherwise
     *         why not, and the flush marks its rows incomplete
     */
    public String notCountingReason() {
        if (!LogEventAppender.isAttached()) {
            return "Sentinel's log appender is not attached to the engine's logging.";
        }
        if (matcher.isStalled()) {
            return "Sentinel's log matcher has spent more than "
                    + TimeUnit.NANOSECONDS.toSeconds(LogMatcherThread.STALL_NANOS)
                    + " seconds on one log line; a monitor's pattern is too slow.";
        }
        if (!matcher.isAlive()) {
            return "Sentinel's log matcher thread is not running.";
        }
        return null;
    }

    public LogPipelineStatus status(String nodeId) {
        LogPipelineStatus status = new LogPipelineStatus();
        status.setNodeId(nodeId);
        status.setQueueCapacity(queue.capacity());
        status.setQueueDepth(queue.depth());
        status.setDroppedSinceStart(queue.dropped());
        status.setMatcherAlive(matcher.isAlive());
        status.setAppenderAttached(LogEventAppender.isAttached());
        status.setNotCountingReason(notCountingReason());
        return status;
    }

    /** The counts since the last flush; the flush job is their only drainer. */
    public LogMatchCounter counter() {
        return counter;
    }

    LogQueue queue() {
        return queue;
    }

    LogMatcherThread matcher() {
        return matcher;
    }
}
