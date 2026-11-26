/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;

/**
 * The bounded hand-off from the engine's logging threads to Sentinel's
 * matcher thread. {@link #offer(LogEvent)} runs inside the engine's logging
 * call, so it does only the level and logger checks, copies the event and
 * returns; a full queue drops the line and counts it.
 *
 * <p>The capacity is a plain number checked against an atomic depth, so a
 * resize never touches what is already queued. Shrinking below the current
 * depth only drops new lines until the matcher catches up.</p>
 */
public final class LogQueue {

    /**
     * Longest message, and longest exception summary, copied per line. Bounds
     * the queue's memory at about capacity x 16 KB in the worst case; a
     * pattern sees only this much of a longer message.
     */
    static final int MAX_TEXT_CHARS = 4_096;

    /** One copied log event. {@code thrown} is the exception's {@code toString()}, or {@code null}. */
    record Line(long timeMillis, Level level, String logger, String message, String thrown) {

        /** What a pattern is matched against: the message, plus a newline and the exception summary. */
        String text() {
            return thrown == null ? message : message + "\n" + thrown;
        }
    }

    private final ConcurrentLinkedQueue<Line> items = new ConcurrentLinkedQueue<>();
    private final AtomicInteger depth = new AtomicInteger();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder internalErrors = new LongAdder();
    private volatile int capacity;
    private volatile Gate gate = Gate.CLOSED;
    private volatile Thread consumer;

    LogQueue(int capacity) {
        this.capacity = capacity;
    }

    // ========== Logging thread ==========

    /**
     * Copies the event in when some monitor could match it and there is room.
     * The caller catches anything this throws.
     */
    void offer(LogEvent event) {
        Gate current = gate;
        Level level = event.getLevel();
        if (level == null || level.intLevel() > current.loosestIntLevel) {
            return;
        }
        String logger = event.getLoggerName() != null ? event.getLoggerName() : "";
        if (!current.accepts(level, logger)) {
            return;
        }
        if (DeliveryThreads.isDelivery(event.getThreadName())) {
            return; // Sentinel's own delivery; see DeliveryThreads
        }
        int cap = capacity;
        // Reserve before copying, so a full queue costs no message formatting.
        int reserved = depth.incrementAndGet();
        if (reserved > cap) {
            depth.decrementAndGet();
            dropped.increment();
            return;
        }
        boolean added = false;
        try {
            items.add(new Line(event.getTimeMillis(), level, logger, cut(formatted(event), MAX_TEXT_CHARS),
                    event.getThrown() != null ? cut(event.getThrown().toString(), MAX_TEXT_CHARS) : null));
            added = true;
        } finally {
            if (!added) {
                depth.decrementAndGet();
            }
        }
        // The matcher naps while the queue is empty; wake it early when a burst fills half.
        if (reserved == (cap + 1) / 2) {
            LockSupport.unpark(consumer);
        }
    }

    void internalError() {
        internalErrors.increment();
    }

    private static String formatted(LogEvent event) {
        Message message = event.getMessage();
        String text = message != null ? message.getFormattedMessage() : null;
        return text != null ? text : "";
    }

    // ========== Sentinel's threads ==========

    Line poll() {
        Line line = items.poll();
        if (line != null) {
            depth.decrementAndGet();
        }
        return line;
    }

    /** Lets in only lines that one of these matchers accepts by level and logger. */
    void setGate(List<LogMatcher> matchers) {
        gate = matchers.isEmpty() ? Gate.CLOSED : new Gate(matchers.toArray(new LogMatcher[0]));
    }

    void setConsumer(Thread thread) {
        consumer = thread;
    }

    void resize(int newCapacity) {
        capacity = newCapacity;
    }

    /** Empties the queue and starts the drop count again, for a plugin start. */
    void reset(int newCapacity) {
        clear();
        capacity = newCapacity;
        dropped.reset();
        internalErrors.reset();
    }

    /** Empties the queue and closes the gate. */
    void clear() {
        gate = Gate.CLOSED;
        while (poll() != null) {
            // Polling keeps the depth in step with what is left.
        }
    }

    int capacity() {
        return capacity;
    }

    int depth() {
        return depth.get();
    }

    long dropped() {
        return dropped.sum();
    }

    long drainInternalErrors() {
        return internalErrors.sumThenReset();
    }

    /** Cuts text to {@code max} characters without splitting a surrogate pair. */
    static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    /** The loosest level any monitor accepts, then each monitor's own level and prefix. */
    private static final class Gate {
        static final Gate CLOSED = new Gate(new LogMatcher[0]);

        final LogMatcher[] matchers;
        final int loosestIntLevel;

        Gate(LogMatcher[] matchers) {
            this.matchers = matchers;
            int loosest = Integer.MIN_VALUE;
            for (LogMatcher matcher : matchers) {
                loosest = Math.max(loosest, matcher.minIntLevel());
            }
            this.loosestIntLevel = loosest;
        }

        boolean accepts(Level level, String logger) {
            for (LogMatcher matcher : matchers) {
                if (matcher.accepts(level, logger)) {
                    return true;
                }
            }
            return false;
        }
    }
}
