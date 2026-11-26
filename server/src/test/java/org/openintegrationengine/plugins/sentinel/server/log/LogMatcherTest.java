/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

/** The per-line time limit on pattern checks. */
class LogMatcherTest {

    @Test
    void patternChecksStopAtTheLineTimeLimitAndOtherMonitorsStillCount() {
        AtomicLong now = new AtomicLong(0);
        DeadlineCharSequence text = new DeadlineCharSequence("a".repeat(1_000), now::get, 100);
        for (int i = 0; i < 1_000; i++) {
            text.charAt(i);
        }
        now.set(101);
        assertThrows(DeadlineCharSequence.Exceeded.class, () -> {
            for (int i = 0; i < 256; i++) {
                text.charAt(0);
            }
        }, "the clock is read every 256 characters");
        assertTrue(DeadlineCharSequence.expired(() -> Long.MIN_VALUE, Long.MAX_VALUE), "one tick past the wrap");
        assertFalse(DeadlineCharSequence.expired(() -> Long.MAX_VALUE, Long.MAX_VALUE));

        // Java's matcher recurses once per repetition of a group like this one.
        LogMatcher deep = LogQueueTest.matcher("{\"pattern\": \"(a|b)*c\"}");
        assertEquals(LogMatcher.Result.TIMED_OUT,
                deep.find("ab".repeat(500_000), System::nanoTime, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)));

        // Two backtracking patterns share one line's limit; the plain monitor still counts the line.
        LogMatchCounter counter = new LogMatchCounter();
        counter.refresh(List.of(LogMatchCounterTest.monitor(1, "{\"pattern\": \"((x+)+)+y\"}"),
                LogMatchCounterTest.monitor(2, "{\"pattern\": \"((x+)+)+z\"}"), LogMatchCounterTest.monitor(3, "{}")));
        long started = System.nanoTime();
        counter.record(new LogQueue.Line(1L, Level.ERROR, "x", "x".repeat(40), null));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMillis < 1_000, "stopped after " + elapsedMillis + " ms; the limit is 20 ms");
        assertEquals(Map.of(1, 1L, 2, 1L), counter.drainTimedOut());
        assertEquals(1, counter.drain().get(3).count());
    }
}
