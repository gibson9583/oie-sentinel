/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.util.function.Predicate;

/**
 * A matrix check that a filed upstream issue breaks on one database. On that
 * database a failure matching the issue's signature prints a NOTE naming the
 * issue and lets the scenario go on; a pass prints PASS and a NOTE that the
 * marker can go. Any other failure, and every other database, fails as usual.
 * The marker covers only the check the issue breaks, so the rest stays strict.
 */
final class KnownFailure {

    /** One check that may throw. */
    interface Check {
        void run() throws Exception;
    }

    private final String vendor;
    private final String issue;
    private final String breaks;
    private final Predicate<Throwable> signature;

    /**
     * @param vendor    the database the issue affects
     * @param issue     the issue, as {@code owner/repo#N}
     * @param breaks    what the issue breaks there, in a few words
     * @param signature whether a failure is the one the issue describes
     */
    KnownFailure(String vendor, String issue, String breaks, Predicate<Throwable> signature) {
        this.vendor = vendor;
        this.issue = issue;
        this.breaks = breaks;
        this.signature = signature;
    }

    /** gibson9583/oie-sentinel#77: SQL Server's DATETIME bound as datetime2 repeats the paging cursor's row. */
    static final KnownFailure SQL_SERVER_PAGING = new KnownFailure("sqlserver", "gibson9583/oie-sentinel#77",
            "timestamp paging repeats the cursor's row", failure -> failure instanceof AssertionError);

    /** gibson9583/oie-sentinel#76: the Oracle driver's oracle.sql.TIMESTAMP is cast to java.sql.Timestamp. */
    static final KnownFailure ORACLE_TIMESTAMP = new KnownFailure("oracle", "gibson9583/oie-sentinel#76",
            "repository reads of a timestamp", KnownFailure::isOracleTimestampCast);

    /** Runs the check; see the class comment for what a failure does on the marked database. */
    void check(String database, String label, Check check) throws Exception {
        if (!vendor.equals(database)) {
            check.run();
            return;
        }
        try {
            check.run();
        } catch (Exception | AssertionError failure) {
            if (!signature.test(failure)) {
                throw failure;
            }
            System.out.println("NOTE: " + database + " " + label + " failed as known (" + issue + ": " + breaks
                    + "): " + failure);
            return;
        }
        System.out.println("PASS: " + database + " " + label + ". NOTE: " + issue + " no longer breaks it ("
                + breaks + "), so this marker can go.");
    }

    private static boolean isOracleTimestampCast(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClassCastException && String.valueOf(cause.getMessage()).contains("oracle.sql.TIMESTAMP")) {
                return true;
            }
        }
        return false;
    }
}
