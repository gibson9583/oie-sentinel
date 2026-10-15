/* OIE Sentinel — Published under the Mozilla Public License 2.0. */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeadlockRetryTest {
    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    @Test void recognizesOnlyTheSelectedVendorsVictimCodes() {
        assertTrue(DeadlockRetry.isVictim("derby", new SQLException("deadlock", "40001")));
        assertTrue(DeadlockRetry.isVictim("mysql", new SQLException("deadlock", "40001", 1213)));
        assertTrue(DeadlockRetry.isVictim("sqlserver", new SQLException("deadlock", "40001", 1205)));
        assertTrue(DeadlockRetry.isVictim("sqlserver", new SQLException("deadlock", "S0001", 1205)));
        assertFalse(DeadlockRetry.isVictim("mysql", new SQLException("timeout", "HY000", 1205)));
        assertFalse(DeadlockRetry.isVictim("mysql", new SQLException("other", "40001")));
        assertFalse(DeadlockRetry.isVictim("sqlserver", new SQLException("other", "40001")));
        assertFalse(DeadlockRetry.isVictim("postgres", new SQLException("serialize", "40001")));
        assertFalse(DeadlockRetry.isVictim("oracle", new SQLException("deadlock", "61000", 60)));
        assertFalse(DeadlockRetry.isVictim("", new SQLException("deadlock", "40001")));
        assertFalse(DeadlockRetry.isVictim("derby", new SQLException("duplicate", "23505")));
        assertFalse(DeadlockRetry.isVictim("derby", new SQLException("timeout", "40XL1")));
    }

    @Test void findsBatchNextExceptionThroughWrappersAndTerminatesOnCycles() {
        SQLException victim = new SQLException("victim", "40001", 1213);
        BatchUpdateException batch = new BatchUpdateException("batch", null, 0, new int[]{1});
        batch.setNextException(victim);
        RuntimeException wrapper = new RepositoryException(batch);
        victim.initCause(wrapper);
        assertTrue(DeadlockRetry.isVictim("mysql", wrapper));
        SQLException cyclic = mock(SQLException.class);
        when(cyclic.getNextException()).thenReturn(cyclic);
        when(cyclic.getCause()).thenReturn(cyclic);
        assertFalse(DeadlockRetry.isVictim("derby", cyclic));
    }

    @Test void ignoresSuppressedCodesAndVetoesAmbiguityTimeoutAndCancellation() {
        RuntimeException cleanup = new RuntimeException("close failed");
        cleanup.addSuppressed(new SQLException("victim", "40001"));
        assertFalse(DeadlockRetry.isVictim("derby", cleanup));
        for (Throwable veto : new Throwable[]{new SQLException("lost commit reply", "08006"),
                new SQLTimeoutException("timeout"), new SQLException("cancel", "57014"),
                new InterruptedException(), new AssertionError()}) {
            SQLException victim = new SQLException("victim", "40001");
            victim.initCause(veto);
            assertFalse(DeadlockRetry.isVictim("derby", victim));
        }
    }

    @Test void retriesOnlyAnExplicitlyCleanAttemptAndStopsAfterTwo() {
        SqlSessionManager sessions = sessions("derby");
        AtomicInteger calls = new AtomicInteger();
        RuntimeException first = failure(), second = failure();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> DeadlockRetry.execute(
                sessions, "test", attempt -> {
                    attempt.allowRetry();
                    throw calls.getAndIncrement() == 0 ? first : second;
                }));
        assertSame(second, thrown);
        assertArrayEquals(new Throwable[]{first}, thrown.getSuppressed());
        assertEquals(2, calls.get());
        calls.set(0);
        assertSame(first, assertThrows(RuntimeException.class, () -> DeadlockRetry.execute(
                sessions, "test", attempt -> { calls.incrementAndGet(); throw first; })));
        assertEquals(1, calls.get());
    }

    @Test void interruptedFailureDoesNotReplayAndRetainsInterruptFlag() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException failure = failure();
        assertSame(failure, assertThrows(RuntimeException.class, () -> DeadlockRetry.execute(
                sessions("derby"), "test", attempt -> {
                    calls.incrementAndGet();
                    attempt.allowRetry();
                    Thread.currentThread().interrupt();
                    throw failure;
                })));
        assertEquals(1, calls.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test void postgresOracleAndMissingVendorRemainSingleAttempt() {
        for (String vendor : new String[]{"postgres", "oracle", ""}) {
            AtomicInteger calls = new AtomicInteger();
            assertThrows(RuntimeException.class, () -> DeadlockRetry.execute(sessions(vendor), "test", attempt -> {
                calls.incrementAndGet(); attempt.allowRetry(); throw failure();
            }));
            assertEquals(1, calls.get());
        }
    }

    static SqlSessionManager sessions(String vendor) {
        SqlSessionManager sessions = mock(SqlSessionManager.class);
        Configuration configuration = new Configuration();
        Properties variables = new Properties();
        variables.setProperty("database", vendor);
        configuration.setVariables(variables);
        when(sessions.getConfiguration()).thenReturn(configuration);
        return sessions;
    }

    static RuntimeException failure() {
        return new RepositoryException(new SQLException("deadlock victim", "40001"));
    }
}
