/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.junit.jupiter.api.Test;

import com.mirth.connect.server.logging.MirthLog4jFilter;

/** What the matcher thread counts, and its start, stop and stuck states. */
class LogMatcherThreadTest {

    private final LogQueue queue = new LogQueue(1_000);
    private final LogMatchCounter counter = new LogMatchCounter();

    @Test
    void theMatcherAppliesTheEngineFilterThenEachMonitorsLevelPrefixAndPattern() {
        LogMatcherThread matcher = new LogMatcherThread(queue, counter, new MirthLog4jFilter(), System::nanoTime);
        queue.setGate(counter.refresh(List.of(LogMatchCounterTest.monitor(1, "{}"),
                LogMatchCounterTest.monitor(2, "{\"minLevel\": \"DEBUG\", \"loggerPrefix\": \"transformer\","
                        + " \"pattern\": \"MSH\\\\|\"}"),
                LogMatchCounterTest.monitor(3, "{\"pattern\": \"SocketTimeoutException\"}"))));
        // The engine routes standard error to the "Server" logger at ERROR and keeps this out of mirth.log.
        queue.offer(LogQueueTest.event(Level.ERROR, "Server", "Compiler warnings:"));
        queue.offer(LogQueueTest.event(Level.ERROR, "Server", "java.lang.IllegalStateException: something real"));
        queue.offer(LogQueueTest.event(Level.DEBUG, "transformer", "MSH|^~\\&|A"));
        queue.offer(LogQueueTest.event(Level.DEBUG, "transformer", "PID|1"));
        queue.offer(LogQueueTest.event(Level.ERROR, "x", "send failed", new SocketTimeoutException("read timed out")));
        assertEquals(5, matcher.processQueued());

        Map<Integer, LogMatchCounter.Drained> drained = counter.drain();
        assertEquals(2, drained.get(1).count(), "the engine filter and monitor 1's ERROR level");
        assertEquals(1, drained.get(2).count(), "level, prefix and pattern");
        assertEquals(1, drained.get(3).count(), "the exception summary is part of what the pattern sees");
    }

    @Test
    void startStopRestartAndALineThatNeverFinishesMeansNotAlive() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AbstractFilter holding = new AbstractFilter() {
            @Override
            public Result filter(LogEvent event) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Result.NEUTRAL;
            }
        };
        LogMatcherThread matcher = new LogMatcherThread(queue, counter, holding, clock::get);
        queue.setGate(counter.refresh(List.of(LogMatchCounterTest.monitor(1, "{}"))));
        assertFalse(matcher.isAlive());
        matcher.start();
        try {
            assertTrue(matcher.isAlive());
            queue.offer(LogQueueTest.event(Level.ERROR, "x", "held"));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the thread picked the line up");
            clock.addAndGet(LogMatcherThread.STALL_NANOS + 1);
            assertTrue(matcher.isStalled());
            assertFalse(matcher.isAlive());
            release.countDown();
            waitFor(() -> !matcher.isStalled());
            assertTrue(matcher.isAlive());
        } finally {
            release.countDown();
            matcher.stop(2_000);
        }
        assertFalse(matcher.isAlive());
        assertEquals(0, liveMatcherThreads(), "stop ends the thread");
        matcher.stop(2_000);

        matcher.start();
        assertTrue(matcher.isAlive(), "a stopped matcher starts again");
        matcher.stop(2_000);
        assertEquals(0, liveMatcherThreads());
        assertEquals(1, counter.drain().get(1).count());
    }

    private static long liveMatcherThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("Sentinel log matcher") && thread.isAlive()).count();
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not met within 5 s");
            Thread.sleep(10);
        }
    }
}
