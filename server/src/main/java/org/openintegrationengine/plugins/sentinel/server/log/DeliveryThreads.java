/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.log;

/**
 * The names of the threads Sentinel delivers alerts on, and the rule that
 * Log monitors do not count what is logged on them.
 *
 * <p>A dispatch thread decides and records each send; a transport thread
 * makes the call into the engine or out to SMTP, SNS or a webhook. When a
 * Channel action routes to an undeployed channel, the engine logs the routing
 * error on the calling thread, which is a transport thread. If a Log monitor
 * counted that line, a failed delivery could raise a notification that is
 * delivered the same way and fails again, for as long as the target is down.
 * The dispatcher's own warnings about failed sends are logged on dispatch
 * threads, for the same reason.</p>
 *
 * <p>The check reads the thread name the log event carries, which is the
 * logging thread's while the engine logs synchronously, as its shipped log4j
 * configuration does. Every Sentinel delivery thread is created by
 * {@code ActionDispatcher} with one of these prefixes.</p>
 */
public final class DeliveryThreads {

    /** Prefix of the dispatch pool's threads, numbered from 1. */
    public static final String DISPATCH_PREFIX = "sentinel-dispatch-";

    /** Prefix of the transport pool's threads, numbered from 1. */
    public static final String TRANSPORT_PREFIX = "sentinel-transport-";

    private DeliveryThreads() {
    }

    /** @return whether a line logged on this thread is about Sentinel's own delivery */
    static boolean isDelivery(String threadName) {
        return threadName != null
                && (threadName.startsWith(TRANSPORT_PREFIX) || threadName.startsWith(DISPATCH_PREFIX));
    }
}
