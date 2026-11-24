/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.util.function.LongSupplier;

/**
 * Text as the regex engine sees it, with a clock check every 256 character
 * reads. {@code java.util.regex} backtracks, so a pattern such as
 * {@code ((a+)+)+b} can run for seconds on 30 characters, and only a check
 * made while it reads can stop it.
 *
 * <p>Adapted from {@code DeadlineCharSequence} in oie-log-viewer (also
 * MPL-2.0), with the clock read four times as often for a deadline in
 * milliseconds.</p>
 */
final class DeadlineCharSequence implements CharSequence {

    private static final int CLOCK_CHECK_MASK = 256 - 1;

    /** Thrown from inside the regex engine when the deadline passes. No stack trace: it is control flow. */
    static final class Exceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Exceeded() {
            super(null, null, false, false);
        }
    }

    private final String text;
    private final LongSupplier nanoClock;
    private final long deadline;
    private int reads;

    DeadlineCharSequence(String text, LongSupplier nanoClock, long deadline) {
        this.text = text;
        this.nanoClock = nanoClock;
        this.deadline = deadline;
    }

    /** Overflow-safe comparison of two {@link System#nanoTime()} readings. */
    static boolean expired(LongSupplier nanoClock, long deadline) {
        return nanoClock.getAsLong() - deadline > 0;
    }

    @Override
    public char charAt(int index) {
        if ((++reads & CLOCK_CHECK_MASK) == 0 && expired(nanoClock, deadline)) {
            throw new Exceeded();
        }
        return text.charAt(index);
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return text.subSequence(start, end);
    }

    @Override
    public String toString() {
        return text;
    }
}
