/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** What a known-failure marker lets through, and what it still fails. */
class KnownFailureTest {

    @Test
    void theIssuesFailureOnItsDatabaseIsANoteAndTheScenarioGoesOn() throws Exception {
        String out = captured(() -> KnownFailure.SQL_SERVER_PAGING.check("sqlserver", "paging",
                () -> { throw new AssertionError("expected: <1> but was: <2>"); }));
        assertTrue(out.startsWith("NOTE: sqlserver paging failed as known (gibson9583/oie-sentinel#77"), out);

        out = captured(() -> KnownFailure.ORACLE_TIMESTAMP.check("oracle", "live state", () -> {
            throw new RepositoryException(new ClassCastException(
                    "class oracle.sql.TIMESTAMP cannot be cast to class java.sql.Timestamp"));
        }));
        assertTrue(out.startsWith("NOTE: oracle live state failed as known (gibson9583/oie-sentinel#76"), out);
    }

    @Test
    void aPassOnTheMarkedDatabaseSaysTheMarkerCanGo() throws Exception {
        String out = captured(() -> KnownFailure.SQL_SERVER_PAGING.check("sqlserver", "paging", () -> { }));
        assertTrue(out.startsWith("PASS: sqlserver paging. NOTE: gibson9583/oie-sentinel#77 no longer breaks it"), out);
        assertTrue(out.contains("so this marker can go"), out);
    }

    @Test
    void anyOtherFailureOrDatabaseFailsAsUsual() {
        assertThrows(AssertionError.class, () -> KnownFailure.SQL_SERVER_PAGING.check("derby", "paging",
                () -> { throw new AssertionError("a real failure"); }));
        assertThrows(IllegalStateException.class, () -> KnownFailure.SQL_SERVER_PAGING.check("sqlserver", "paging",
                () -> { throw new IllegalStateException("not the issue's failure"); }));
        assertThrows(ClassCastException.class, () -> KnownFailure.ORACLE_TIMESTAMP.check("oracle", "live state",
                () -> { throw new ClassCastException("some other cast"); }));
        assertThrows(RepositoryException.class, () -> KnownFailure.ORACLE_TIMESTAMP.check("postgres", "live state",
                () -> { throw new RepositoryException(new ClassCastException("oracle.sql.TIMESTAMP")); }));
    }

    private static String captured(KnownFailure.Check check) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            assertDoesNotThrow(check::run);
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
