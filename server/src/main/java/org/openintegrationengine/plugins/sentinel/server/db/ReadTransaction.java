/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

import org.apache.commons.dbcp2.DelegatingConnection;
import org.apache.commons.dbcp2.PoolableConnection;
import org.apache.ibatis.session.SqlSessionManager;

import com.mirth.connect.server.util.SqlConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Shares a read transaction while preserving the engine pool's connection defaults. */
final class ReadTransaction {
    private ReadTransaction() { }

    static <T> T execute(int isolation, Supplier<T> read) {
        SqlSessionManager sessions = SqlConfig.getInstance().getSqlSessionManager();
        if (sessions.isManagedSessionStarted()) {
            throw new IllegalStateException("Nested read transaction");
        }
        // Borrow before changing isolation: MyBatis cannot restore the original
        // level, and the engine's DBCP pool does not reset it when returned.
        sessions.startManagedSession(false);
        Connection connection = null;
        Integer originalIsolation = null;
        Throwable failure = null;
        boolean discard = false;
        T result = null;
        try {
            connection = sessions.getConnection();
            originalIsolation = connection.getTransactionIsolation();
            connection.setTransactionIsolation(isolation);
            result = read.get();
            sessions.commit(true);
        } catch (SQLException error) {
            failure = new RepositoryException(error);
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        if (failure != null) {
            try {
                sessions.rollback(true);
            } catch (RuntimeException | Error rollback) {
                failure.addSuppressed(rollback);
                discard = true;
            }
        }
        // Finish the transaction before restoring isolation: some drivers commit
        // when the level changes. A failed rollback must instead discard it.
        if (connection != null && originalIsolation != null && !discard) {
            try {
                connection.setTransactionIsolation(originalIsolation);
            } catch (SQLException | RuntimeException | Error reset) {
                failure = transactionFailure(failure, reset);
                discard = true;
            }
        }
        if (connection != null && discard) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException | Error abort) {
                failure.addSuppressed(abort);
                try {
                    discardConnection(sessions, connection, failure);
                } catch (SQLException | RuntimeException | Error discardFailure) {
                    failure.addSuppressed(discardFailure);
                }
            }
        }
        try {
            sessions.close();
        } catch (RuntimeException | Error close) {
            failure = transactionFailure(failure, close);
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (RuntimeException) failure;
        return result;
    }

    private static void discardConnection(SqlSessionManager sessions, Connection connection,
                                          Throwable failure) throws SQLException {
        PoolableConnection pooled = connection.isWrapperFor(PoolableConnection.class)
                ? connection.unwrap(PoolableConnection.class) : null;
        Connection physical = pooled == null ? connection.unwrap(Connection.class) : pooled.getInnermostDelegate();
        // A pool wrapper may have failed before reaching the driver. End that
        // transaction directly so drivers such as Derby will allow physical close.
        try {
            physical.rollback();
        } catch (SQLException | RuntimeException | Error rollback) {
            failure.addSuppressed(rollback);
        }
        if (pooled != null) {
            // Close the delegate, not the pool wrapper. DBCP marks this delegate
            // closed even if the driver throws, so poolable.close invalidates
            // the pool entry and releases its borrowed slot in either case.
            DelegatingConnection<Connection> discarded = new DelegatingConnection<>(physical);
            pooled.setDelegate(discarded);
            try {
                discarded.close();
            } catch (SQLException | RuntimeException | Error close) {
                failure.addSuppressed(close);
            }
            pooled.close();
        } else {
            // BridgeDataSourceFactory exposes the engine's actual Hikari source.
            ((HikariDataSource) sessions.getConfiguration().getEnvironment().getDataSource())
                    .evictConnection(connection);
        }
    }

    private static Throwable transactionFailure(Throwable failure, Throwable cleanup) {
        if (failure == null) {
            return cleanup instanceof SQLException ? new RepositoryException(cleanup) : cleanup;
        }
        failure.addSuppressed(cleanup);
        return failure;
    }
}
