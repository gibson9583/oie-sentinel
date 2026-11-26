/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

/** What the logging thread lets in, what a full queue does, and what a resize keeps. */
class LogQueueTest {

    static LogEvent event(Level level, String logger, String message) {
        return event(level, logger, message, null);
    }

    static LogEvent event(Level level, String logger, String message, Throwable thrown) {
        return Log4jLogEvent.newBuilder().setLevel(level).setLoggerName(logger).setMessage(new SimpleMessage(message))
                .setThrown(thrown).setTimeMillis(1_700_000_000_000L).build();
    }

    static LogMatcher matcher(String configJson) {
        return LogMatcher.of(LogEventsConfig.parse(configJson));
    }

    private static List<String> drain(LogQueue queue) {
        List<String> messages = new ArrayList<>();
        for (LogQueue.Line line = queue.poll(); line != null; line = queue.poll()) {
            messages.add(line.message());
        }
        return messages;
    }

    @Test
    void onlyLinesSomeMonitorAcceptsByLevelAndLoggerGetIn() {
        LogQueue queue = new LogQueue(100);
        queue.offer(event(Level.FATAL, "x", "before any monitor"));
        queue.setGate(List.of(matcher("{\"minLevel\": \"DEBUG\", \"loggerPrefix\": \"transformer\"}"), matcher("{}")));
        queue.offer(event(Level.DEBUG, "transformer.child", "script debug"));
        queue.offer(event(Level.DEBUG, "db-connector", "debug from another logger"));
        queue.offer(event(Level.TRACE, "transformer", "below every monitor's level"));
        queue.offer(event(Level.ERROR, "com.example.Engine", "engine error"));
        assertEquals(List.of("script debug", "engine error"), drain(queue));
    }

    @Test
    void aFullQueueDropsAndCountsUnderConcurrentLogging() throws Exception {
        LogQueue queue = new LogQueue(100);
        queue.setGate(List.of(matcher("{}")));
        int threads = 4;
        int perThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                writers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        queue.offer(event(Level.ERROR, "x", "line"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(100, queue.depth());
        assertEquals(threads * perThread - 100, queue.dropped());
        assertEquals(100, drain(queue).size());
    }

    @Test
    void aResizeKeepsWhatIsQueued() {
        LogQueue queue = new LogQueue(200);
        queue.setGate(List.of(matcher("{}")));
        for (int i = 0; i < 150; i++) {
            queue.offer(event(Level.ERROR, "x", "line " + i));
        }
        queue.resize(100);
        queue.offer(event(Level.ERROR, "x", "over the new capacity"));
        assertEquals(150, queue.depth());
        assertEquals(1, queue.dropped());
        List<String> kept = drain(queue);
        assertEquals(150, kept.size());
        assertEquals("line 149", kept.get(149));

        queue.resize(300);
        for (int i = 0; i < 300; i++) {
            queue.offer(event(Level.ERROR, "x", "line " + i));
        }
        assertEquals(300, queue.depth());
        assertEquals(1, queue.dropped());
    }
}
