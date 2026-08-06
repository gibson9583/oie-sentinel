/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.util.SqlConfig;

import org.openintegrationengine.plugins.sentinel.shared.model.NodeLease;

/**
 * Persistence for {@code sentinel_node_lease} — the leader lease that keeps the
 * collector and evaluator running on exactly one node of a multi-node engine
 * deployment. See {@link NodeLease} for the design.
 *
 * <p>Stateless static methods, like the sibling repositories, and every method
 * is a single round trip. That is not merely a convenience here: it is the
 * correctness argument. Each mutating method below is one conditional statement
 * whose {@code WHERE} clause carries the precondition, so the database's own
 * row locking decides the race and the returned row count reports who won.
 * There is deliberately no read-then-write pair anywhere in this class — a
 * "check whether the lease is free, then take it" sequence would be exactly the
 * check-then-act race the lease exists to prevent, and no isolation level short
 * of serializable would save it.</p>
 *
 * <p>Callers should treat every method's row count as the authoritative answer
 * to "am I the leader?" and re-check on every tick rather than caching it: a
 * node that pauses long enough (a long GC, a stalled query) can lose its lease
 * to a peer without ever being told.</p>
 */
public final class NodeLeaseRepository {

    private static final String NAMESPACE = "Sentinel";
    private static final Logger log = LoggerFactory.getLogger(NodeLeaseRepository.class);

    private NodeLeaseRepository() {
    }

    /** Qualifies a mapped-statement id with this plugin's MyBatis namespace. */
    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    /**
     * Reads the current holder of a lease.
     *
     * <p>Informational only — for logging, diagnostics, and the "who is
     * leader?" question an operator asks. Do not use it to decide whether to
     * acquire: by the time the caller acts on the answer another node may have
     * taken the lease. {@link #insertNodeLease(NodeLease)} and
     * {@link #stealExpiredNodeLease(String, String, Instant, Instant, Instant)}
     * make that decision atomically instead.</p>
     *
     * @param leaseName the lease to look up
     * @return the lease row, or {@code null} if nobody has ever held it. A
     *         non-null row may still be expired; compare
     *         {@link NodeLease#getExpiresTime()} against now
     * @throws RepositoryException on persistence failure
     */
    public static NodeLease getNodeLease(String leaseName) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("leaseName", leaseName);
            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getNodeLease"), params);
            return row != null ? buildNodeLease(row) : null;
        } catch (Exception e) {
            log.error("Failed to get node lease '{}'", leaseName, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Claims a lease nobody holds yet, by inserting its row.
     *
     * <p>This is the first-acquisition path, and the primary key on
     * {@code lease_name} is what makes it safe: when several nodes start
     * together and all try to insert the same lease name, exactly one insert
     * lands and the rest violate the key. A violation therefore means "another
     * node got there first", which is an ordinary outcome and not a fault —
     * hence the {@code false} return rather than a thrown exception. Any other
     * failure still throws.</p>
     *
     * @param lease the lease to claim, fully populated
     * @return {@code true} if this node now holds the lease, {@code false} if a
     *         row for that name already existed
     * @throws RepositoryException on persistence failure other than the row
     *                             already existing
     */
    public static boolean insertNodeLease(NodeLease lease) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("lease_name", lease.getLeaseName());
            params.put("node_id", lease.getNodeId());
            params.put("acquired_time", toTimestamp(lease.getAcquiredTime()));
            params.put("expires_time", toTimestamp(lease.getExpiresTime()));
            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertNodeLease"), params);
            return true;
        } catch (Exception e) {
            // A duplicate key here is the normal "lost the race" outcome, not an error worth
            // propagating. The vendors report it with different exception types and messages,
            // so rather than pattern-match on any of them, confirm by reading the row back:
            // if the lease exists and is not ours, we simply lost.
            NodeLease existing = getNodeLease(lease.getLeaseName());
            if (existing != null) {
                log.debug("Node lease '{}' already held by node {}; not acquired by {}",
                        lease.getLeaseName(), existing.getNodeId(), lease.getNodeId());
                return false;
            }
            log.error("Failed to insert node lease '{}' for node {}", lease.getLeaseName(), lease.getNodeId(), e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Extends a lease this node already holds — the heartbeat.
     *
     * <p>Conditional on {@code node_id} still matching, so a node that lost its
     * lease while stalled cannot renew its way back into leadership behind the
     * new holder's back; it gets a zero row count and must stand down. Leaves
     * {@code acquired_time} alone so it keeps reporting when this node's
     * leadership actually began.</p>
     *
     * @param leaseName   the lease to renew
     * @param nodeId      the renewing node; must match the current holder
     * @param expiresTime the new expiry
     * @return {@code true} if the lease was renewed, {@code false} if this node
     *         no longer holds it
     * @throws RepositoryException on persistence failure
     */
    public static boolean renewNodeLease(String leaseName, String nodeId, Instant expiresTime) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("leaseName", leaseName);
            params.put("nodeId", nodeId);
            params.put("expiresTime", toTimestamp(expiresTime));
            int updated = SqlConfig.getInstance().getSqlSessionManager()
                    .update(stmt("renewNodeLease"), params);
            return updated > 0;
        } catch (Exception e) {
            log.error("Failed to renew node lease '{}' for node {}", leaseName, nodeId, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Takes over a lease whose holder stopped renewing it — the failover path.
     *
     * <p>Conditional on the stored {@code expires_time} being strictly in the
     * past relative to the {@code now} the caller supplies, which is what makes
     * this safe to run on every node on every tick: only one node's update can
     * find the row still expired, so the rest come away with a zero row count.
     * Resets {@code acquired_time} because leadership genuinely restarts
     * here.</p>
     *
     * <p>Comparison uses the application's clock, not the database's, so the
     * usual distributed-systems caveat applies: nodes whose clocks disagree by
     * an appreciable fraction of the lease duration can both believe the lease
     * is theirs. Keep the lease comfortably longer than any expected clock
     * skew.</p>
     *
     * @param leaseName    the lease to take over
     * @param nodeId       the node taking it over
     * @param acquiredTime when this node is taking leadership
     * @param expiresTime  the new expiry
     * @param now          the instant to test the existing expiry against
     * @return {@code true} if this node took the lease over, {@code false} if
     *         the lease was still live (or absent — use
     *         {@link #insertNodeLease(NodeLease)} for that case)
     * @throws RepositoryException on persistence failure
     */
    public static boolean stealExpiredNodeLease(String leaseName, String nodeId, Instant acquiredTime,
            Instant expiresTime, Instant now) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("leaseName", leaseName);
            params.put("nodeId", nodeId);
            params.put("acquiredTime", toTimestamp(acquiredTime));
            params.put("expiresTime", toTimestamp(expiresTime));
            params.put("now", toTimestamp(now));
            int updated = SqlConfig.getInstance().getSqlSessionManager()
                    .update(stmt("stealExpiredNodeLease"), params);
            if (updated > 0) {
                log.info("Node {} took over expired Sentinel lease '{}'", nodeId, leaseName);
            }
            return updated > 0;
        } catch (Exception e) {
            log.error("Failed to take over node lease '{}' for node {}", leaseName, nodeId, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Releases a lease this node holds, so a peer can pick the work up
     * immediately instead of waiting out the expiry. Called on orderly
     * shutdown; an unclean stop simply lets the lease lapse.
     *
     * <p>Conditional on {@code node_id}, so a node cannot release a lease it
     * has already lost and hand leadership away from whoever holds it now.</p>
     *
     * @param leaseName the lease to release
     * @param nodeId    the releasing node; must match the current holder
     * @return {@code true} if the lease was released, {@code false} if this
     *         node did not hold it
     * @throws RepositoryException on persistence failure
     */
    public static boolean deleteNodeLease(String leaseName, String nodeId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("leaseName", leaseName);
            params.put("nodeId", nodeId);
            int deleted = SqlConfig.getInstance().getSqlSessionManager()
                    .delete(stmt("deleteNodeLease"), params);
            return deleted > 0;
        } catch (Exception e) {
            log.error("Failed to release node lease '{}' for node {}", leaseName, nodeId, e);
            throw new RepositoryException(e);
        }
    }

    // ========== Map <-> DTO Conversion ==========

    private static NodeLease buildNodeLease(Map<String, Object> row) {
        NodeLease lease = new NodeLease();
        lease.setLeaseName((String) row.get("lease_name"));
        lease.setNodeId((String) row.get("node_id"));
        lease.setAcquiredTime(toInstant(row.get("acquired_time")));
        lease.setExpiresTime(toInstant(row.get("expires_time")));
        return lease;
    }

    /** Converts an {@link Instant} to the {@link Timestamp} MyBatis/JDBC expects as a bound parameter. */
    private static Timestamp toTimestamp(Instant value) {
        return value != null ? Timestamp.from(value) : null;
    }

    /** Converts the {@link Timestamp} JDBC hands back from a SELECT to an {@link Instant}. */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        return ((Timestamp) value).toInstant();
    }
}
