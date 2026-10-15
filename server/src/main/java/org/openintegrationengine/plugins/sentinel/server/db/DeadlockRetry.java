/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import org.apache.ibatis.session.SqlSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in recovery for activity transactions whose database confirmed a victim rollback. */
final class DeadlockRetry {
    private static final Logger log = LoggerFactory.getLogger(DeadlockRetry.class);
    private DeadlockRetry() { }

    /** An attempt grants permission only after all of its transaction cleanup succeeded. */
    static final class Attempt {
        private boolean retryAllowed;
        void allowRetry() { retryAllowed = true; }
    }

    static <T> T execute(SqlSessionManager sessions, String operation, Function<Attempt, T> transaction) {
        RuntimeException previous = null;
        for (int number = 0; number < 2; number++) {
            Attempt attempt = new Attempt();
            try {
                return transaction.apply(attempt);
            } catch (RuntimeException failure) {
                if (previous != null && previous != failure) failure.addSuppressed(previous);
                if (number != 0 || !attempt.retryAllowed || Thread.currentThread().isInterrupted()
                        || !isVictim(database(sessions), failure)) {
                    throw failure;
                }
                // No connection or row lock is held during this bounded, interruptible pause.
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(25, 101));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
                if (Thread.currentThread().isInterrupted()) throw failure;
                log.warn("Retrying {} once after a database deadlock rollback", operation);
                previous = failure;
            }
        }
        throw new AssertionError("Unreachable retry attempt");
    }

    private static String database(SqlSessionManager sessions) {
        // SqlConfig.createFactory supplies this exact mapper variable. Missing
        // configuration must not broaden the retry policy to an unknown vendor.
        var configuration = sessions.getConfiguration();
        var variables = configuration == null ? null : configuration.getVariables();
        return variables == null ? "" : variables.getProperty("database", "");
    }

    static boolean isVictim(String database, Throwable failure) {
        String vendor = database == null ? "" : database.toLowerCase(Locale.ROOT);
        if (!Set.of("derby", "mysql", "sqlserver").contains(vendor)) return false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) pending.add(failure);
        boolean victim = false;
        while (!pending.isEmpty()) {
            Throwable error = pending.removeFirst();
            if (!seen.add(error)) continue;
            if (error instanceof Error || error instanceof InterruptedException
                    || error instanceof SQLTimeoutException) return false;
            if (error instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("HYT00")
                        || state.equals("HYT01") || state.equals("57014") || state.equals("70100"))) {
                    return false;
                }
                if ((vendor.equals("mysql") && sql.getErrorCode() == 1205)
                        || (vendor.equals("sqlserver") && sql.getErrorCode() == 1222)) return false;
                victim |= switch (vendor) {
                    case "derby" -> "40001".equals(state);
                    case "mysql" -> sql.getErrorCode() == 1213 && "40001".equals(state);
                    case "sqlserver" -> sql.getErrorCode() == 1205;
                    default -> false;
                };
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (error.getCause() != null) pending.addLast(error.getCause());
            // Suppressed exceptions are cleanup/history evidence, never a reason to retry.
        }
        return victim;
    }
}
