/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionManager;

import com.mirth.connect.server.util.SqlConfig;

/**
 * Re-executes one single-row insert for every row in a manual-commit
 * {@link ExecutorType#BATCH} session, all or nothing. See
 * {@link ActivityRepository#insertActivitySamples(List)} for why a batch
 * session and not a multi-row insert.
 */
final class BatchInsert {

    private BatchInsert() {
    }

    /**
     * @param statement fully qualified mapped insert
     * @param rows      parameter maps, frozen by the caller so a retry
     *                  writes the same values
     * @param fence     checked before and after the inserts in the same
     *                  transaction; {@link LeaseFence#unmanaged()} for a
     *                  writer that needs no leadership
     * @param what      names the batch in the retry log line
     */
    static void run(String statement, List<Map<String, Object>> rows, LeaseFence fence, String what) {
        SqlSessionManager sessions = SqlConfig.getInstance().getSqlSessionManager();
        DeadlockRetry.execute(sessions, what, attempt -> {
            insert(sessions, statement, rows, fence, what, attempt);
            return null;
        });
    }

    private static void insert(SqlSessionManager sessions, String statement, List<Map<String, Object>> rows,
            LeaseFence fence, String what, DeadlockRetry.Attempt attempt) {
        SqlSession session = null;
        Connection connection = null;
        Throwable failure = null;
        boolean committed = false;
        boolean cleanupFailed = false;
        try {
            session = sessions.openSession(ExecutorType.BATCH, false);
            connection = session.getConnection();
            if (!NodeLeaseRepository.lockFence(session, fence)) {
                throw new IllegalStateException("Sentinel leadership fence changed before " + what);
            }

            for (Map<String, Object> row : rows) {
                session.insert(statement, row);
            }

            // The fence SELECT flushes BATCH before checking expiry; commit
            // flushes it for an unmanaged fence. Revalidate on every attempt.
            NodeLeaseRepository.requireFence(session, fence);
            session.commit();
            committed = true;
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        if (session != null && failure != null) {
            try {
                // MyBatis 3.1.1 close can swallow a JDBC rollback exception.
                // Force rollback explicitly before considering any retry.
                session.rollback(true);
            } catch (RuntimeException | Error rollback) {
                if (failure != rollback) failure.addSuppressed(rollback);
                cleanupFailed = true;
                if (connection != null) ReadTransaction.abortConnection(sessions, connection, failure);
            }
        }
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException | Error close) {
                if (failure == null) failure = close;
                else if (failure != close) failure.addSuppressed(close);
                cleanupFailed = true;
            }
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) {
            if (!committed && !cleanupFailed && session != null) attempt.allowRetry();
            throw (RuntimeException) failure;
        }
    }
}
