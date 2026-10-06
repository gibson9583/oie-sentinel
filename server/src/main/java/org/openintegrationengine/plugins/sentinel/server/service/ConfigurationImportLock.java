/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.service;

import java.util.UUID;
import java.util.function.Supplier;
import org.apache.ibatis.session.SqlSession;
import com.mirth.connect.server.util.SqlConfig;
import org.openintegrationengine.plugins.sentinel.server.db.LeaseFence;
import org.openintegrationengine.plugins.sentinel.server.db.NodeLeaseRepository;
import org.openintegrationengine.plugins.sentinel.shared.model.NodeLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serializes imports across plugin nodes using a separate shared lease row.
 * The row lock is held on a separate read transaction while entity services
 * commit individually. Thus an expired lease cannot permit overlapping imports,
 * and a crash releases the row lock and leaves at most a bounded lease wait.
 * Editor writes remain independent; this is not an enclosing config transaction. */
public final class ConfigurationImportLock {
    private static final String LEASE = "sentinel-config-import";
    private static final int SECONDS = 60;
    private static final Logger log = LoggerFactory.getLogger(ConfigurationImportLock.class);
    private ConfigurationImportLock() { }

    public static <T> T withLock(Supplier<T> work) {
        String owner = UUID.randomUUID().toString();
        NodeLease candidate = new NodeLease(); candidate.setLeaseName(LEASE); candidate.setNodeId(owner); candidate.setLeaseEpoch(1L);
        if (!NodeLeaseRepository.insertNodeLease(candidate, SECONDS)) {
            NodeLease current = NodeLeaseRepository.getNodeLease(LEASE);
            if (current == null || current.getLeaseEpoch() == null
                    || !NodeLeaseRepository.stealExpiredNodeLease(LEASE, owner, current.getLeaseEpoch(), SECONDS)) {
                throw new IllegalArgumentException("Another configuration import is in progress; wait and preview again");
            }
        }
        NodeLease acquired = NodeLeaseRepository.getNodeLease(LEASE);
        if (acquired == null || !owner.equals(acquired.getNodeId()) || acquired.getLeaseEpoch() == null) {
            throw new IllegalArgumentException("Import ownership changed before apply; preview again");
        }
        long epoch = acquired.getLeaseEpoch();
        SqlSession lockSession = null;
        try {
            lockSession = SqlConfig.getInstance().getSqlSessionManager().openSession(false);
            NodeLeaseRepository.requireFence(lockSession, new LeaseFence(LEASE, owner, epoch));
            return work.get();
        } finally {
            try { if (lockSession != null) lockSession.close(); }
            finally {
                try { NodeLeaseRepository.releaseNodeLease(LEASE, owner, epoch); }
                catch (Exception e) { log.warn("Could not release import lease; it will expire on the database clock", e); }
            }
        }
    }
}
