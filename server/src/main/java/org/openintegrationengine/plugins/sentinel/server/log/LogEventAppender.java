/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * The log4j2 appender on the engine's root logger that copies lines into the
 * {@link LogQueue}. It runs inside the logging call of whatever thread
 * logged, so it never throws and never logs (a line logged here would come
 * straight back, and the engine routes standard error into log4j too).
 *
 * <p>Why the root: the engine's {@code conf/log4j2.properties} (the same in
 * OIE 4.6.0 and 4.7.0) puts the root at ERROR and the script loggers at DEBUG
 * with no additivity flags. A logger's own level decides whether an event is
 * created, and the event then reaches every ancestor's appenders, so the root
 * sees what {@code mirth.log} receives, script DEBUG lines included.</p>
 *
 * <p>A log4j reconfiguration drops appenders added in code, so the flush job
 * calls {@link #ensureAttached()} on every run. Every log4j call here is in
 * the 2.17.2 API the plugin compiles against and in the 2.25.3 jars OIE 4.6.0
 * ships.</p>
 */
public final class LogEventAppender extends AbstractAppender {

    /** The appender's name in the engine's configuration, and how it is found again. */
    public static final String NAME = LogEventAppender.class.getName();

    private static LoggerContext attachedContext;
    private static LogQueue attachedQueue;

    private final LogQueue queue;

    private LogEventAppender(LogQueue queue) {
        super(NAME, null, null, true, Property.EMPTY_ARRAY);
        this.queue = queue;
    }

    @Override
    public void append(LogEvent event) {
        try {
            queue.offer(event);
        } catch (Throwable t) {
            queue.internalError();
        }
    }

    /**
     * Attaches to the engine's logging context. The plugin's classes share the
     * engine's class loader, so the default selector returns the engine's
     * context, as it does for the engine's own {@code ServerLogProvider}.
     *
     * @throws IllegalStateException if log4j is not backed by log4j-core here
     */
    static synchronized void attachToEngine(LogQueue queue) {
        attach(engineContext(), queue);
    }

    static LoggerContext engineContext() {
        org.apache.logging.log4j.spi.LoggerContext context = LogManager.getContext(false);
        if (context instanceof LoggerContext core) {
            return core;
        }
        throw new IllegalStateException("The engine's logging context is "
                + (context == null ? "missing" : context.getClass().getName())
                + ", not log4j-core, so Log monitors cannot see log lines");
    }

    /** @return whether an appender was added; {@code false} when one feeding this queue was already there */
    static synchronized boolean attach(LoggerContext context, LogQueue queue) {
        if (attachedContext != null && (attachedContext != context || attachedQueue != queue)) {
            detachFrom(attachedContext);
        }
        attachedContext = context;
        attachedQueue = queue;
        return attachIfMissing(context, queue);
    }

    /** @return whether the appender had to be put back after a reconfiguration dropped it */
    public static synchronized boolean ensureAttached() {
        return attachedContext != null && attachIfMissing(attachedContext, attachedQueue);
    }

    /** @return whether the appender is on the remembered context's current root logger */
    public static synchronized boolean isAttached() {
        return attachedContext != null && isAttached(attachedContext);
    }

    /** Removes the appender and forgets the context. Safe when nothing is attached. */
    public static synchronized void detach() {
        LoggerContext context = attachedContext;
        attachedContext = null;
        attachedQueue = null;
        if (context != null) {
            detachFrom(context);
        }
    }

    static boolean isAttached(LoggerContext context) {
        Appender present = context.getConfiguration().getRootLogger().getAppenders().get(NAME);
        return present != null && present.isStarted();
    }

    private static boolean attachIfMissing(LoggerContext context, LogQueue queue) {
        if (isAttached(context)) {
            return false;
        }
        // A stopped instance can only be left by a failure part way through; clear it first.
        detachFrom(context);
        Configuration config = context.getConfiguration();
        LogEventAppender appender = new LogEventAppender(queue);
        appender.start();
        // Registered with the configuration too, as the engine does, so a reconfiguration stops it.
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, null, null);
        return true;
    }

    private static void detachFrom(LoggerContext context) {
        Configuration config = context.getConfiguration();
        LoggerConfig root = config.getRootLogger();
        Appender present = root.getAppenders().get(NAME);
        root.removeAppender(NAME);
        if (config instanceof AbstractConfiguration abstractConfig) {
            abstractConfig.removeAppender(NAME);
        }
        if (present != null && present.isStarted()) {
            present.stop();
        }
    }
}
