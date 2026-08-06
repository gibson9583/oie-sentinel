/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared.model;

import java.time.Instant;

/**
 * A leader lease: the record of which engine node currently owns a named piece
 * of exclusive work, and until when.
 *
 * <p>Maps to {@code sentinel_node_lease}, added in schema v4. Sentinel's
 * collector and evaluator must run on exactly one node of a multi-node engine
 * deployment — two nodes sampling the same channels would double-count deltas,
 * and two nodes evaluating the same triggers would open duplicate problems and
 * page twice. The scheduler stays on Quartz's {@code RAMJobStore} and every
 * node still fires its own timers; leadership is checked inside the job body
 * instead, which keeps the change confined to a lease lookup.</p>
 *
 * <p>The table holds one row per lease name, not one per node — the row's
 * existence with an unexpired {@link #getExpiresTime()} <em>is</em> the
 * leadership claim, and the primary key on {@code lease_name} is what makes
 * acquisition atomic: two nodes racing to insert the same lease name means one
 * insert succeeds and the other hits the key violation and stands down. There
 * is no separate "am I leader?" flag to drift out of sync with it.</p>
 *
 * <p>Expiry rather than an explicit release is what makes this survive a node
 * dying mid-tick: the holder renews by pushing {@link #getExpiresTime()}
 * forward on a heartbeat, and if it stops doing so any other node may take the
 * lease over once the expiry passes. The cost of a failover is one lost tick of
 * collector delta baseline, since a non-leader's in-memory
 * {@code CollectorState} stays cold.</p>
 */
public class NodeLease {

    private String leaseName;
    private String nodeId;
    private Instant acquiredTime;
    private Instant expiresTime;

    public NodeLease() {
    }

    /**
     * @return the name of the work this lease guards (the primary key — one row
     *         per name, so distinct workloads can be led by different nodes)
     */
    public String getLeaseName() {
        return leaseName;
    }

    /**
     * @param leaseName the name of the work this lease guards; must be unique
     *                  and at most 64 characters, enforced by the primary key
     *                  on {@code sentinel_node_lease.lease_name}
     */
    public void setLeaseName(String leaseName) {
        this.leaseName = leaseName;
    }

    /**
     * @return the id of the node currently holding the lease. Compared against
     *         the local node's own id to answer "am I the leader?", so it must
     *         be stable for a node's lifetime and distinct across nodes
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * @param nodeId the id of the node holding the lease
     */
    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * @return when the current holder first took the lease. Not used to decide
     *         leadership — {@link #getExpiresTime()} alone does that — but it
     *         makes "how long has this node been leader?" answerable from the
     *         row, which is the question asked when diagnosing a flapping
     *         failover
     */
    public Instant getAcquiredTime() {
        return acquiredTime;
    }

    /**
     * @param acquiredTime when the current holder first took the lease; reset
     *                     on takeover, left alone on renewal
     */
    public void setAcquiredTime(Instant acquiredTime) {
        this.acquiredTime = acquiredTime;
    }

    /**
     * When the lease lapses and becomes available to any node.
     *
     * <p>The holder is expected to push this forward well before it arrives; a
     * heartbeat interval comfortably shorter than the lease duration is what
     * keeps a slow tick from handing leadership away. Time is compared against
     * values the application supplies, not the database's clock, so nodes must
     * agree on the time to within much less than the lease duration.</p>
     *
     * @return the instant this lease stops being valid
     */
    public Instant getExpiresTime() {
        return expiresTime;
    }

    /**
     * @param expiresTime the instant this lease stops being valid
     */
    public void setExpiresTime(Instant expiresTime) {
        this.expiresTime = expiresTime;
    }
}
