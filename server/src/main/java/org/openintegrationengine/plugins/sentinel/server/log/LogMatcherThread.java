/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;

/**
 * Sentinel's thread that drains the {@link LogQueue}, drops what the engine's
 * {@code mirth.log} filter drops, and hands the rest to the
 * {@link LogMatchCounter}. A low-priority daemon, so it yields to message
 * processing and never holds the JVM open.
 *
 * <p>The engine's {@code MirthLog4jFilter} is called rather than copied, so a
 * change to its rule is followed. It reads only the level, the logger name
 * and the formatted message (the same in OIE 4.6.0 and 4.7.0), which is all
 * the rebuilt event carries.</p>
 */
final class LogMatcherThread {

    /** The nap on an empty queue; the queue wakes the thread early when a burst fills half of it. */
    private static final long IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    /** One line taking longer than this means a pattern loops without reading, so the matcher is not alive. */
    static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final long IDLE = Long.MIN_VALUE;

    private final LogQueue queue;
    private final LogMatchCounter counter;
    private final Filter engineFilter;
    private final LongSupplier nanoClock;
    private volatile Thread thread;
    private volatile long busySince = IDLE;

    LogMatcherThread(LogQueue queue, LogMatchCounter counter, Filter engineFilter, LongSupplier nanoClock) {
        this.queue = queue;
        this.counter = counter;
        this.engineFilter = engineFilter;
        this.nanoClock = nanoClock;
    }

    synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        Thread started = new Thread(this::run, "Sentinel log matcher");
        started.setDaemon(true);
        started.setPriority(Thread.MIN_PRIORITY);
        thread = started;
        queue.setConsumer(started);
        started.start();
    }

    /**
     * Interrupts the thread and waits up to {@code joinMillis} for it to finish its current line.
     *
     * @return false when the thread is still running, stuck on one line
     */
    synchronized boolean stop(long joinMillis) {
        Thread running = thread;
        thread = null;
        queue.setConsumer(null);
        if (running == null) {
            return true;
        }
        running.interrupt();
        try {
            running.join(joinMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !running.isAlive();
    }

    /** @return whether the thread is running and not stuck on one line */
    boolean isAlive() {
        Thread running = thread;
        return running != null && running.isAlive() && !isStalled();
    }

    boolean isStalled() {
        long since = busySince;
        return since != IDLE && nanoClock.getAsLong() - since > STALL_NANOS;
    }

    private void run() {
        Thread self = Thread.currentThread();
        while (thread == self && !self.isInterrupted()) {
            if (processQueued() == 0) {
                LockSupport.parkNanos(this, IDLE_NANOS);
            }
        }
    }

    /** Matches everything queued, on the calling thread. @return how many lines it took */
    int processQueued() {
        int processed = 0;
        LogQueue.Line line;
        while (!Thread.currentThread().isInterrupted() && (line = queue.poll()) != null) {
            process(line);
            processed++;
        }
        return processed;
    }

    private void process(LogQueue.Line line) {
        busySince = nanoClock.getAsLong();
        try {
            if (engineFilter.filter(Log4jLogEvent.newBuilder().setLevel(line.level()).setLoggerName(line.logger())
                    .setMessage(new SimpleMessage(line.message())).build()) != Filter.Result.DENY) {
                counter.record(line);
            }
        } catch (Throwable t) {
            counter.internalError();
        } finally {
            busySince = IDLE;
        }
    }
}
