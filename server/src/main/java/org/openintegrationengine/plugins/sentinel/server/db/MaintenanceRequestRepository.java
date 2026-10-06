/* OIE Sentinel. Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionManager;
import com.mirth.connect.server.util.SqlConfig;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;

/** Durable idempotency ledger; keys are retained when a schedule is removed. */
public final class MaintenanceRequestRepository {
    private MaintenanceRequestRepository() { }
    public record Result(Map<String,Object> request, boolean created) { }

    public static Map<String,Object> get(String requestId) {
        return SqlConfig.getInstance().getSqlSessionManager().selectOne(
                "Sentinel.getMaintenanceRequest", Map.of("request_id", requestId));
    }

    public static Result createOnce(String requestId, String fingerprint, MaintenanceWindow window) {
        Map<String,Object> existing = get(requestId);
        if (existing != null) return replay(existing, fingerprint);
        SqlSessionManager sessions = SqlConfig.getInstance().getSqlSessionManager();
        if (sessions.isManagedSessionStarted()) throw new IllegalStateException("Nested maintenance request transaction");
        Map<String,Object> params = new HashMap<>();
        params.put("request_id", requestId); params.put("fingerprint", fingerprint);
        params.put("channel_id", window.getScopeId());
        sessions.startManagedSession(false);
        Throwable failure = null;
        try {
            // The primary key serializes retries across nodes before any schedule write.
            sessions.insert("Sentinel.insertMaintenanceRequest", params);
            MaintenanceWindowRepository.insertMaintenanceWindow(window);
            if (window.getId() == null) throw new IllegalStateException("Schedule insert returned no identity");
            params.put("window_id", window.getId());
            if (sessions.update("Sentinel.completeMaintenanceRequest", params) != 1) {
                throw new IllegalStateException("Maintenance request receipt was not completed");
            }
            sessions.commit();
        } catch (RuntimeException | Error e) {
            failure = e;
            try { sessions.rollback(); } catch (RuntimeException | Error rollback) { e.addSuppressed(rollback); }
        } finally {
            try { sessions.close(); } catch (RuntimeException | Error close) {
                if (failure == null) throw close;
                failure.addSuppressed(close);
            }
        }
        if (failure != null) {
            // Duplicate key or uncertain commit: reconcile outside the rolled-back transaction.
            existing = get(requestId);
            if (existing != null) return replay(existing, fingerprint);
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
        return new Result(params, true);
    }

    public static int disable(Map<String,Object> request) {
        return SqlConfig.getInstance().getSqlSessionManager().update("Sentinel.disableChannelMaintenanceWindow", request);
    }

    private static Result replay(Map<String,Object> existing, String fingerprint) {
        if (!fingerprint.equals(existing.get("fingerprint"))) {
            throw new IllegalArgumentException("Request identity was already used with different maintenance parameters");
        }
        return new Result(existing, false);
    }
}
