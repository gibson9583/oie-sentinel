/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.ibatis.session.SqlSessionManager;
import com.mirth.connect.server.util.SqlConfig;

/** Shares one transaction across alert and trigger repository calls on this thread. */
public final class AlertLifecycleTransaction {
    private AlertLifecycleTransaction() { }

    public static void execute(Consumer<List<Runnable>> work) {
        execute(LeaseFence.unmanaged(), work);
    }

    /**
     * Holds the captured leadership lease row for the entire transaction.
     * A successor cannot acquire the lease until this work commits or rolls
     * back; a paused worker whose lease expired rolls back before commit.
     * Callers must carry the fence captured when their work was scheduled.
     */
    public static void execute(LeaseFence fence, Consumer<List<Runnable>> work) {
        if (fence == null) {
            throw new IllegalStateException("No leadership fence for alert lifecycle work");
        }
        SqlSessionManager sessions = SqlConfig.getInstance().getSqlSessionManager();
        if (sessions.isManagedSessionStarted()) {
            throw new IllegalStateException("Nested alert lifecycle transaction");
        }
        List<Runnable> afterCommit = new ArrayList<>();
        sessions.startManagedSession(false);
        Throwable failure = null;
        try {
            NodeLeaseRepository.requireFence(sessions, fence);
            work.accept(afterCommit);
            NodeLeaseRepository.requireFence(sessions, fence);
            sessions.commit();
        } catch (RuntimeException | Error error) {
            failure = error;
            try {
                sessions.rollback();
            } catch (RuntimeException | Error rollbackFailure) {
                error.addSuppressed(rollbackFailure);
            }
            throw error;
        } finally {
            try {
                sessions.close();
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) throw closeFailure;
                failure.addSuppressed(closeFailure);
            }
        }
        // Transport submission and payload construction must not happen before
        // both the alert and its owning trigger state have committed.
        afterCommit.forEach(Runnable::run);
    }
}
