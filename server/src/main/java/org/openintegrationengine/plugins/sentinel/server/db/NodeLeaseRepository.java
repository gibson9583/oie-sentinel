/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.util.SqlConfig;

import org.openintegrationengine.plugins.sentinel.shared.model.NodeLease;

/**
 * Persistence for {@code sentinel_node_lease} — the leader lease that keeps the
 * collector and evaluator running on exactly one node of a multi-node engine
 * deployment. See {@link NodeLease} for the design.
 *
 * <p>Stateless static methods, like the sibling repositories. Every lease
 * mutation is a single round trip. That is not merely a convenience here: it is the
 * correctness argument. Each mutating method below is one conditional statement
 * whose {@code WHERE} clause carries the precondition, so the database's own
 * row locking decides the race and the returned row count reports who won.
 * There is deliberately no read-then-write pair anywhere in this class — a
 * "check whether the lease is free, then take it" sequence would be exactly the
 * check-then-act race the lease exists to prevent, and no isolation level short
 * of serializable would save it.</p>
 *
 * <p>Callers carry the epoch returned by acquisition into every protected
 * transaction. A row lock on that exact live epoch serializes a batch with
 * takeover: either the old leader finishes first or observes that its fence is
 * stale before it writes.</p>
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
     * taken the lease. {@link #insertNodeLease(NodeLease, int)} and
     * {@link #stealExpiredNodeLease(String, String, long, int)}
     * make that decision atomically instead.</p>
     *
     * @param leaseName the lease to look up
     * @return the lease row, or {@code null} if nobody has ever held it. A
     *         non-null row may still be expired. Only a conditional mapper
     *         statement may decide whether it is available
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
     * Reads the vendor's normalized lease clock. Mutations stamp timestamps
     * directly in SQL; this value is only a follower-side read optimization.
     */
    public static Instant getDatabaseTime() {
        try {
            Object value = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getDatabaseTime"));
            return toInstant(value);
        } catch (Exception e) {
            log.error("Failed to read database time for Sentinel leadership", e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Returns the stable identities of nodes whose Sentinel presence lease is
     * live according to the database clock. Connector-state evaluation uses
     * this registry to exclude crashed or permanently retired nodes after the
     * bounded lease timeout.
     */
    public static Set<String> listActiveSentinelNodeIds() {
        try {
            List<String> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listActiveSentinelNodeIds"));
            return rows != null ? new HashSet<>(rows) : new HashSet<>();
        } catch (Exception e) {
            log.error("Failed to list active Sentinel nodes", e);
            throw new RepositoryException(e);
        }
    }

    /** The cluster deployment union, published atomically with each node's presence lease. */
    public static Set<String> listActiveDeployedChannelIds() {
        return activeDeploymentIds("listActiveDeployedChannelIds", Map.of());
    }

    /** Active nodes that currently deploy a channel, including nodes other than the evaluator. */
    public static Set<String> listActiveDeployedNodeIds(String channelId) {
        return activeDeploymentIds("listActiveDeployedNodeIds", Map.of("channelId", channelId));
    }

    /** Runtime connector identities mapped to the active nodes that actually deploy each connector. */
    public static Map<Integer, Set<String>> listActiveConnectorNodes(String channelId) {
        Map<Integer, Set<String>> result = new HashMap<>();
        listActiveConnectorDeployments(channelId).forEach((metadata, nodes) -> result.put(metadata, nodes.keySet()));
        return result;
    }

    /** Deployment-time floors identify which persisted observations may describe the current runtime. */
    public static Map<Integer, Map<String, Instant>> listActiveConnectorDeployments(String channelId) {
        try {
            List<Map<String, Object>> rows = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("listActiveConnectorNodes"), Map.of("channelId", channelId));
            Map<Integer, Map<String, Instant>> result = new HashMap<>();
            for (Map<String, Object> row : rows) {
                int metadataId = ((Number) row.get("metadata_id")).intValue();
                Instant deployed = toInstant(row.get("deployed_time"));
                if (deployed == null) throw new IllegalStateException("Missing runtime deployment timestamp");
                result.computeIfAbsent(metadataId, ignored -> new HashMap<>()).put((String) row.get("node_id"), deployed);
            }
            return result;
        } catch (Exception e) {
            throw new RepositoryException("Failed to read the active cluster connector inventory", e);
        }
    }

    /** One complete local runtime inventory, captured from the deployed engine channel. */
    public record ChannelDeployment(Set<Integer> metadataIds, Instant deployedTime) {
        public ChannelDeployment {
            metadataIds = Set.copyOf(metadataIds);
            if (metadataIds.isEmpty() || deployedTime == null) {
                throw new IllegalArgumentException("A deployment needs connector identities and its deployment time");
            }
        }
    }

    private static Set<String> activeDeploymentIds(String statement, Map<String, Object> params) {
        try {
            List<String> rows = SqlConfig.getInstance().getSqlSessionManager().selectList(stmt(statement), params);
            return new HashSet<>(rows);
        } catch (Exception e) {
            throw new RepositoryException("Failed to read the active cluster deployment inventory", e);
        }
    }

    /**
     * Publishes one complete node deployment snapshot in the same transaction
     * that acquires or renews its presence lease. Readers can never observe an
     * active newly-started node with a missing or partial snapshot. A failed
     * controller read must not call this method with a substituted empty set.
     */
    public static boolean refreshNodePresence(String leaseName, String nodeId, int leaseSeconds,
            Map<String, ChannelDeployment> deployedChannels, BooleanSupplier stillCurrent) {
        Map<String, ChannelDeployment> channels = Map.copyOf(deployedChannels);
        SqlSession session = SqlConfig.getInstance().getSqlSessionManager().openSession(false);
        try {
            Map<String, Object> key = Map.of("leaseName", leaseName);
            Map<String, Object> row = session.selectOne(stmt("getNodeLease"), key);
            long epoch;
            if (row == null) {
                epoch = 1L;
                session.insert(stmt("insertNodeLease"), Map.of("lease_name", leaseName,
                        "node_id", nodeId, "lease_epoch", epoch, "leaseSeconds", leaseSeconds));
            } else {
                NodeLease current = buildNodeLease(row);
                if (!nodeId.equals(current.getNodeId()) || current.getLeaseEpoch() == null) {
                    throw new IllegalStateException("Node-presence lease is owned by another node");
                }
                epoch = current.getLeaseEpoch();
                Map<String, Object> renew = Map.of("leaseName", leaseName, "nodeId", nodeId,
                        "leaseEpoch", epoch, "leaseSeconds", leaseSeconds);
                int renewed = session.update(stmt("renewNodeLease"), renew);
                // Connector/J may report zero changed rows for two refreshes
                // within the same second. Lock and check the still-live claim
                // before deciding this was an expired claim needing takeover.
                if (renewed == 0 && !lockFence(session, new LeaseFence(leaseName, nodeId, epoch))) {
                    int changed = session.update(stmt("stealExpiredNodeLease"), Map.of(
                            "leaseName", leaseName, "nodeId", nodeId,
                            "expectedLeaseEpoch", epoch, "leaseSeconds", leaseSeconds));
                    if (changed != 1) throw new IllegalStateException("Node presence changed during publication");
                    epoch++;
                }
            }
            LeaseFence fence = new LeaseFence(leaseName, nodeId, epoch);
            requireFence(session, fence);
            session.delete(stmt("deleteChannelPresenceByNode"), Map.of("nodeId", nodeId));
            for (Map.Entry<String, ChannelDeployment> channel : channels.entrySet()) {
                for (Integer metadataId : channel.getValue().metadataIds()) {
                    session.insert(stmt("insertChannelPresence"), Map.of("channelId", channel.getKey(),
                            "nodeId", nodeId, "metadataId", metadataId,
                            "deployedTime", Timestamp.from(channel.getValue().deployedTime())));
                }
            }
            requireFence(session, fence);
            if (!stillCurrent.getAsBoolean()) {
                session.rollback();
                return false;
            }
            session.commit();
            return true;
        } catch (Exception error) {
            try {
                session.rollback();
            } catch (Exception rollback) {
                error.addSuppressed(rollback);
            }
            throw new RepositoryException("Failed to atomically publish node presence and channel inventory", error);
        } finally {
            session.close();
        }
    }

    /**
     * Claims a lease nobody holds yet, by inserting its row. The mapper writes
     * acquisition and expiry from one database clock expression; no JVM
     * timestamp crosses JDBC on this path.
     *
     * <p>This is the first-acquisition path, and the primary key on
     * {@code lease_name} is what makes it safe: when several nodes start
     * together and all try to insert the same lease name, exactly one insert
     * lands and the rest violate the key. A violation therefore means "another
     * node got there first", which is an ordinary outcome and not a fault —
     * hence the {@code false} return rather than a thrown exception. Any other
     * failure still throws.</p>
     *
     * @param lease the lease identity and initial epoch to claim
     * @param leaseSeconds duration the database adds to its own clock
     * @return {@code true} if this node now holds the lease, {@code false} if a
     *         row for that name already existed
     * @throws RepositoryException on persistence failure other than the row
     *                             already existing
     */
    public static boolean insertNodeLease(NodeLease lease, int leaseSeconds) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("lease_name", lease.getLeaseName());
            params.put("node_id", lease.getNodeId());
            params.put("lease_epoch", lease.getLeaseEpoch());
            params.put("leaseSeconds", leaseSeconds);
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
     * Extends a lease this node already holds — the heartbeat. The mapper
     * derives the new expiry directly from the database clock.
     *
     * <p>Conditional on {@code node_id} and epoch still matching and on the
     * lease remaining live according to the database clock, so a node that lost its
     * lease while stalled cannot renew its way back into leadership behind the
     * new holder's back; it gets a zero row count and must stand down. Leaves
     * {@code acquired_time} alone so it keeps reporting when this node's
     * leadership actually began.</p>
     *
     * @param leaseName   the lease to renew
     * @param nodeId      the renewing node; must match the current holder
     * @param leaseEpoch  the exact fencing epoch originally acquired
     * @param leaseSeconds duration the database adds to its own clock
     * @return {@code true} if the lease was renewed, {@code false} if this node
     *         no longer holds it
     * @throws RepositoryException on persistence failure
     */
    public static boolean renewNodeLease(String leaseName, String nodeId, long leaseEpoch,
            int leaseSeconds) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("leaseName", leaseName);
            params.put("nodeId", nodeId);
            params.put("leaseEpoch", leaseEpoch);
            params.put("leaseSeconds", leaseSeconds);
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
     * <p>Conditional on the stored {@code expires_time} being expired according
     * to the database clock and on the epoch observed by the caller, which makes
     * this safe to run on every node on every tick: only one node's update can
     * find the row still expired, so the rest come away with a zero row count.
     * Resets {@code acquired_time} because leadership genuinely restarts
     * here.</p>
     *
     * @param leaseName    the lease to take over
     * @param nodeId       the node taking it over
     * @param expectedLeaseEpoch epoch seen by the caller; advanced atomically
     * @param leaseSeconds duration the database adds to its own clock
     * @return {@code true} if this node took the lease over, {@code false} if
     *         the lease was still live (or absent — use
     *         {@link #insertNodeLease(NodeLease, int)} for that case)
     * @throws RepositoryException on persistence failure
     */
    public static boolean stealExpiredNodeLease(String leaseName, String nodeId, long expectedLeaseEpoch,
            int leaseSeconds) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("leaseName", leaseName);
            params.put("nodeId", nodeId);
            params.put("expectedLeaseEpoch", expectedLeaseEpoch);
            params.put("leaseSeconds", leaseSeconds);
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
     * <p>Conditional on holder and epoch. Release expires the row on the
     * database clock and advances the epoch rather than deleting it, preventing
     * a later acquisition from reusing an old fencing token.</p>
     *
     * @param leaseName the lease to release
     * @param nodeId    the releasing node; must match the current holder
     * @param leaseEpoch the exact fencing epoch originally acquired
     * @return {@code true} if the lease was released, {@code false} if this
     *         node did not hold it
     * @throws RepositoryException on persistence failure
     */
    public static boolean releaseNodeLease(String leaseName, String nodeId, long leaseEpoch) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("leaseName", leaseName);
            params.put("nodeId", nodeId);
            params.put("leaseEpoch", leaseEpoch);
            int released = SqlConfig.getInstance().getSqlSessionManager()
                    .update(stmt("releaseNodeLease"), params);
            return released > 0;
        } catch (Exception e) {
            log.error("Failed to release node lease '{}' for node {}", leaseName, nodeId, e);
            throw new RepositoryException(e);
        }
    }

    /**
     * Locks the captured lease row until the caller commits or rolls back,
     * then validates its expiry with a new database-clock statement. The
     * separate check runs after any lock wait and bypasses MyBatis's session
     * cache. A takeover therefore cannot overlap a protected transaction.
     */
    public static boolean lockFence(SqlSession session, LeaseFence fence) {
        if (fence == null) {
            return false;
        }
        if (!fence.isManaged()) {
            return true;
        }
        Map<String, Object> params = new HashMap<>();
        fence.bind(params);
        Object epoch = session.selectOne(stmt("lockNodeLeaseFence"), params);
        return epoch != null && session.selectOne(stmt("checkNodeLeaseFence"), params) != null;
    }

    /** Fails closed when the captured owner, epoch or database-clock expiry no longer holds. */
    public static void requireFence(SqlSession session, LeaseFence fence) {
        if (!lockFence(session, fence)) {
            throw new RepositoryException("Sentinel leadership expired or changed during this transaction", null);
        }
    }

    /** Database-clock recheck between independent job work units. */
    public static boolean isFenceCurrent(LeaseFence fence) {
        if (fence == null || !fence.isManaged()) {
            return fence != null;
        }
        try {
            Map<String, Object> params = new HashMap<>();
            fence.bind(params);
            Object epoch = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("checkNodeLeaseFence"), params);
            return epoch != null;
        } catch (Exception e) {
            log.error("Failed to re-check Sentinel leadership fence", e);
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
        lease.setLeaseEpoch(toLong(row.get("lease_epoch")));
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

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
