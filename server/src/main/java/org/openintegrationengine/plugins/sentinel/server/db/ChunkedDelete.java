/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.server.util.SqlConfig;

/**
 * Runs a retention delete as a series of bounded passes instead of one
 * statement, so pruning stays safe on a database that has real data in it.
 *
 * <p><b>Why this exists.</b> Night to night a prune removes one day of rows
 * and a single {@code DELETE ... WHERE time &lt; cutoff} would be fine. The
 * dangerous case is an operator <em>lowering</em> retention: dropping
 * {@code sampleRetentionDays} from the 90-day maximum to the 7-day default
 * asks one statement to remove 83 days at once, which for 200 channels on a
 * 30-second collector is roughly 48 million rows. In one transaction that
 * means a table-wide lock held for minutes, a transaction log sized for the
 * whole delete, and on PostgreSQL enough dead tuples to require a manual
 * VACUUM afterwards — on the operational database that is also carrying
 * message traffic. Deleting in capped passes bounds all three: each pass is
 * its own transaction, so locks and log are released between them and the
 * engine's own work is never blocked for more than one chunk.</p>
 *
 * <p><b>Termination.</b> A pass that deletes fewer rows than the chunk size
 * proves the predicate is exhausted, so the loop ends. {@link #MAX_PASSES}
 * is a second, independent bound: it is not expected to be reached, and if
 * it is, that means the delete is not making progress (a row the predicate
 * matches but the statement cannot remove) and looping forever inside a
 * scheduled job would be worse than stopping and saying so. The next night's
 * prune resumes from wherever this one stopped, because the cutoff is
 * recomputed from {@code now} each run.</p>
 *
 * <p><b>Interruption is safe.</b> There is no ordering requirement between
 * chunks — every row older than the cutoff is equally deletable — so a prune
 * that dies halfway (engine shutdown, lost leadership) simply leaves the
 * remainder for the next run. Nothing needs to be resumed or rolled back.</p>
 */
final class ChunkedDelete {

    private static final Logger log = LoggerFactory.getLogger(ChunkedDelete.class);

    /**
     * Rows removed per pass. Large enough that pruning a normal night's data
     * is a handful of round trips, small enough that one pass is a short
     * transaction on every supported vendor — SQL Server escalates a row lock
     * to a table lock somewhere north of a few thousand rows, which is the
     * practical ceiling here.
     */
    static final int CHUNK_SIZE = 5_000;

    /**
     * Hard stop at 100 million rows per table per run. A prune that has not
     * finished by then is not going to, and a scheduled job must not spin.
     */
    private static final int MAX_PASSES = 20_000;

    private ChunkedDelete() {
    }

    /**
     * Deletes every row matching the mapped statement's cutoff predicate, in
     * passes of {@link #CHUNK_SIZE}.
     *
     * <p>The statement must accept {@code cutoff} (a bound parameter) and
     * {@code chunkSize} (a literal substitution — see the mapper comments for
     * why the row limit cannot be a bind parameter on every vendor). The
     * value is this class's own constant and is asserted positive below, so
     * it is never operator- or request-supplied.</p>
     *
     * @param statementId fully-qualified mapped statement id
     * @param cutoff      rows older than this are removed
     * @param what        plural noun for the log line ("activity samples")
     * @return the total number of rows deleted across all passes
     * @throws RepositoryException if a pass fails; rows already deleted by
     *                             earlier passes stay deleted, which is
     *                             correct — they were past retention
     */
    static int run(String statementId, Instant cutoff, String what) {
        if (CHUNK_SIZE < 1) {
            throw new IllegalStateException("CHUNK_SIZE must be positive");
        }
        long started = System.nanoTime();
        int total = 0;
        try {
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                Map<String, Object> params = new HashMap<>();
                params.put("cutoff", cutoff != null ? Timestamp.from(cutoff) : null);
                // Literal, not a bind: see the class Javadoc and the mappers.
                params.put("chunkSize", CHUNK_SIZE);

                // Each pass is its own committed transaction (the session
                // manager auto-commits per statement), which is the entire
                // point — locks and undo are released between chunks.
                int deleted = SqlConfig.getInstance().getSqlSessionManager()
                        .delete(statementId, params);
                total += deleted;

                if (deleted < CHUNK_SIZE) {
                    if (total > CHUNK_SIZE) {
                        log.info("Pruned {} {} older than {} in {} passes ({} ms)",
                                total, what, cutoff, pass + 1,
                                (System.nanoTime() - started) / 1_000_000L);
                    }
                    return total;
                }
            }
            log.warn("Prune of {} older than {} stopped at the {}-pass ceiling after {} rows; "
                    + "the remainder will be removed by the next run", what, cutoff, MAX_PASSES, total);
            return total;
        } catch (Exception e) {
            log.error("Failed to prune {} older than {} (removed {} before failing)", what, cutoff, total, e);
            throw new RepositoryException(e);
        }
    }
}
