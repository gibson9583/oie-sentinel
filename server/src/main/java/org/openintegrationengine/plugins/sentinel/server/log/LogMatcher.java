/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;

/**
 * One {@code LOG_EVENTS} monitor's filters: the level and logger prefix,
 * which the logging thread checks, and the pattern, which only Sentinel's
 * matcher thread runs.
 *
 * <p>The pattern runs under a per-line time limit so one slow pattern cannot
 * hold up every other monitor. The limit is checked as characters are read
 * ({@link DeadlineCharSequence}), so a pattern that loops without reading,
 * such as a large repeat count of an empty lookahead, is not stopped; it
 * slows only Sentinel's thread, and the matcher reports itself stuck.</p>
 */
public final class LogMatcher {

    /**
     * How long one line's pattern checks may run, shared by every monitor's
     * pattern for that line. A plain pattern scans a few kilobytes in well
     * under a millisecond; a backtracking one is stopped here instead of
     * running for seconds.
     */
    public static final long TIME_LIMIT_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    /** The outcome of one pattern check. */
    public enum Result {
        MATCH,
        NO_MATCH,
        /** Stopped at the time limit, or by the regex engine running out of stack; counts as no match. */
        TIMED_OUT
    }

    private final int minIntLevel;
    private final String loggerPrefix;
    private final Pattern pattern;

    private LogMatcher(int minIntLevel, String loggerPrefix, Pattern pattern) {
        this.minIntLevel = minIntLevel;
        this.loggerPrefix = loggerPrefix;
        this.pattern = pattern;
    }

    public static LogMatcher of(LogEventsConfig config) {
        return new LogMatcher(config.minLevel().intLevel(), config.loggerPrefix(), config.pattern());
    }

    /** @return the log4j {@link Level#intLevel()} of the least specific level accepted */
    int minIntLevel() {
        return minIntLevel;
    }

    /** @return whether the level is at least the minimum and the logger starts with the prefix */
    public boolean accepts(Level level, String loggerName) {
        return level.intLevel() <= minIntLevel && (loggerPrefix == null || loggerName.startsWith(loggerPrefix));
    }

    public boolean hasPattern() {
        return pattern != null;
    }

    /**
     * Runs the pattern under a deadline. Call only when {@link #hasPattern()}.
     *
     * @param deadline the {@code nanoClock} reading after which the check is abandoned
     */
    public Result find(String text, LongSupplier nanoClock, long deadline) {
        try {
            return pattern.matcher(new DeadlineCharSequence(text, nanoClock, deadline)).find()
                    ? Result.MATCH : Result.NO_MATCH;
        } catch (DeadlineCharSequence.Exceeded e) {
            return Result.TIMED_OUT;
        } catch (StackOverflowError e) {
            // The regex engine recurses per repetition of some groups; the stack unwinds to here intact.
            return Result.TIMED_OUT;
        }
    }
}
