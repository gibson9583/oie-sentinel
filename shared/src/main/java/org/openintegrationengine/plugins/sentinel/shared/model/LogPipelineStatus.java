/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

/**
 * The serving node's log capture for Log monitors: the queue between the
 * engine's logging threads and Sentinel's matcher thread, and whether the node
 * is counting. Response-only; shown to unrestricted users only.
 */
public class LogPipelineStatus {

    private String nodeId;
    private int queueCapacity;
    private int queueDepth;
    private long droppedSinceStart;
    private boolean matcherAlive;
    private boolean appenderAttached;
    private String notCountingReason;

    public LogPipelineStatus() {
    }

    /** @return the node this status describes */
    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    /** @return how many log lines the queue holds before it drops new ones */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    /** @return log lines waiting for the matcher thread */
    public int getQueueDepth() {
        return queueDepth;
    }

    public void setQueueDepth(int queueDepth) {
        this.queueDepth = queueDepth;
    }

    /** @return log lines dropped because the queue was full, since Sentinel started on this node */
    public long getDroppedSinceStart() {
        return droppedSinceStart;
    }

    public void setDroppedSinceStart(long droppedSinceStart) {
        this.droppedSinceStart = droppedSinceStart;
    }

    /** @return whether the matcher thread is running and not stuck on one line */
    public boolean isMatcherAlive() {
        return matcherAlive;
    }

    public void setMatcherAlive(boolean matcherAlive) {
        this.matcherAlive = matcherAlive;
    }

    /** @return whether Sentinel's appender is on the engine's root logger */
    public boolean isAppenderAttached() {
        return appenderAttached;
    }

    public void setAppenderAttached(boolean appenderAttached) {
        this.appenderAttached = appenderAttached;
    }

    /**
     * @return {@code null} while the node counts; otherwise why it writes no
     *         counts, so its Log monitors read as having no data
     */
    public String getNotCountingReason() {
        return notCountingReason;
    }

    public void setNotCountingReason(String notCountingReason) {
        this.notCountingReason = notCountingReason;
    }
}
