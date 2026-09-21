/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mirth.connect.model.util.MigrationException;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.migration.Migrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Versioned migrator for the Sentinel schema.
 *
 * <p>OIE's {@link Migrator} base class does not track per-plugin schema
 * versions, so we store our own under the {@link ConfigurationController}
 * key/value store. Each {@code migrate()} run reads the current version,
 * applies every {@code applyVN} step beyond it, and persists the new
 * version.</p>
 *
 * <h3>Versions</h3>
 * <ul>
 *   <li><b>1</b> — Initial schema: {@code sentinel_monitor}, {@code sentinel_alert_event},
 *       {@code sentinel_trigger_state}, {@code sentinel_channel_activity_sample},
 *       {@code sentinel_channel_activity_trend}, {@code sentinel_connector_status_event},
 *       {@code sentinel_action}, {@code sentinel_action_dispatch_log},
 *       {@code sentinel_maintenance_window}.</li>
 *   <li><b>2</b> — Window modes and recurrence: adds {@code window_mode},
 *       {@code repeat_type}, {@code days_of_week}, {@code days_of_month},
 *       {@code start_time}, {@code end_time} to
 *       {@code sentinel_maintenance_window} and relaxes
 *       {@code active_from}/{@code active_until} to nullable (recurring
 *       windows may be unbounded). The base DDL stays v1-shaped: a fresh
 *       install runs applyV1 then applyV2, so upgraded and fresh schemas are
 *       byte-for-byte the same.</li>
 *   <li><b>3</b> — Window timezones: adds a nullable {@code timezone} to
 *       {@code sentinel_maintenance_window}. Null means "the server's zone",
 *       so every row written before v3 keeps exactly the behavior it had —
 *       which is why the column is nullable rather than defaulted to a
 *       concrete zone id: backfilling one would be a guess, and a wrong guess
 *       silently shifts a live alerting schedule.</li>
 *   <li><b>4</b> — Runbooks, storm control, escalation, and the node lease.
 *       Four features ship in the same release, so they share one migration
 *       rather than four: adds nullable {@code runbook_url} to
 *       {@code sentinel_monitor}; adds nullable
 *       {@code max_notifications_per_window}, {@code rollup_window_seconds},
 *       {@code escalate_after_seconds} and {@code escalate_to_action_id} to
 *       {@code sentinel_action}; and creates {@code sentinel_node_lease}.
 *       Every added column is nullable with no default, so an existing row
 *       reads back as "feature not configured" and keeps precisely the
 *       behavior it had before the upgrade.
 *
 *       <p>{@code escalate_to_action_id} points at another
 *       {@code sentinel_action} row but deliberately carries <b>no</b> foreign
 *       key. A hard FK would make an escalation target undeletable (or, with
 *       {@code ON DELETE SET NULL}, would silently rewrite the escalating
 *       action's configuration behind the operator's back). Deleting an action
 *       must never be blocked by an unrelated action that happens to point at
 *       it, so the reference is resolved defensively in Java instead: the
 *       chain-walking code looks the target up and treats a missing row as
 *       "escalation ends here". See {@code Action#getEscalateToActionId()}.</p></li>
 *   <li><b>5</b> — Retention-prune indexes. The nightly prune filters on
 *       {@code sample_time}, {@code hour_bucket} and
 *       {@code status}/{@code resolved_time}, but every pre-v5 index on those
 *       tables led with {@code channel_id}, so none of them could serve the
 *       predicate and each prune degenerated to a full scan — tolerable at the
 *       7-day sample default, not at the 90-day maximum, where the sample table
 *       is tens of millions of rows. Adds
 *       {@code idx_sentinel_activity_sample_time},
 *       {@code idx_sentinel_activity_trend_hour} and
 *       {@code idx_sentinel_alert_event_resolved (status, resolved_time)}.
 *       Index-only, so nothing about existing rows changes.</li>
 *   <li><b>6</b> — Connector-status prune index. v5 covered the three tables
 *       the nightly prune deleted from at the time; the prune now also covers
 *       {@code sentinel_connector_status_event}, whose only index leads with
 *       {@code channel_id} and cannot serve a bare {@code changed_time}
 *       cutoff. Adds {@code idx_sentinel_connector_status_time
 *       (changed_time)}. Index-only, like v5.</li>
 *   <li><b>7</b> — Node lease fencing epoch.</li>
 *   <li><b>8</b> — Per-node connector state and its lookup index.</li>
 *   <li><b>9</b> — Durable resolution dispatch outbox.</li>
 *   <li><b>10</b> — Durable problem dispatch outbox, backfilled for open events.</li>
 *   <li><b>11</b> — Per-node deployed-channel inventory for cluster-wide evaluation.</li>
 *   <li><b>12</b> — Connector observations carry their deployment identity; legacy rows stay untagged.</li>
 * </ul>
 * <p>Versions 7–12 are this release's sequence after released schema v6.
 * Unpublished hardening-branch versions are intentionally not the release
 * migration sequence; unrelated trigger identity and latency changes are excluded.</p>
 *
 * <h3>Backfill</h3>
 *
 * <p>When {@code schema_version} is null, {@link #detectAndAlignSchemaVersion()}
 * detects the actual state of the database via {@link DatabaseMetaData} and
 * sets the version accordingly so the version loop only runs the steps that
 * haven't been applied. Identifier case (PostgreSQL lowercases unquoted names;
 * Derby/Oracle/SQL Server uppercase) is handled by querying both cases.</p>
 */
public class SentinelMigrator extends Migrator {

    /** Matches plugin.xml's {@code <name>}; the group key under which schema_version is stored. */
    public static final String PLUGIN_NAME = "OIE Sentinel";

    /** Bump when adding a new {@code applyVN} step. */
    public static final int LATEST_VERSION = 12;

    /**
     * CONFIGURATION property key holding the applied schema version. Public
     * because {@code SentinelServicePlugin} reads it as its migration-success
     * gate: {@link #detectAndAlignSchemaVersion()} re-aligns the stored value
     * to the actual table state on every startup, so the property is a
     * trustworthy "schema is installed" signal even after a failed migration
     * or a manual table drop.
     */
    public static final String VERSION_PROPERTY = "schema_version";
    private static final Logger log = LoggerFactory.getLogger(SentinelMigrator.class);

    /**
     * {@inheritDoc}
     *
     * <p>Always detects the schema state from {@link DatabaseMetaData} and
     * aligns the stored {@code schema_version} property before running the
     * version loop. Detecting on every call (rather than only when the
     * property is null) makes us robust to operational quirks: pre-versioning
     * installs, manual edits to {@code CONFIGURATION}, and — since
     * {@link #getUninstallStatements()} stopped dropping tables — reinstalling
     * over a schema a previous uninstall left in place. On uninstall the engine
     * clears every CONFIGURATION property under the plugin's group name,
     * including {@code schema_version}, so after a reinstall the stored version
     * is always null while the tables may be anywhere from absent (they were
     * renamed aside) to fully at {@link #LATEST_VERSION} (the rename was not
     * possible on this vendor). Detection is what tells those two apart.</p>
     */
    @Override
    public void migrate() throws MigrationException {
        migrateTo(LATEST_VERSION);
    }

    /** Bounded migration runner used to verify upgrades from every historical schema. */
    void migrateTo(int targetVersion) throws MigrationException {
        if (targetVersion < 1 || targetVersion > LATEST_VERSION) {
            throw new MigrationException("Invalid Sentinel migration target: " + targetVersion);
        }
        int current = detectAndAlignSchemaVersion();
        if (current > targetVersion) {
            throw new MigrationException("Cannot migrate Sentinel schema backward from "
                    + current + " to " + targetVersion);
        }
        if (current < 1 && targetVersion >= 1) {
            applyV1();
        }
        if (current < 2 && targetVersion >= 2) {
            applyV2();
        }
        if (current < 3 && targetVersion >= 3) {
            applyV3();
        }
        if (current < 4 && targetVersion >= 4) {
            applyV4();
        }
        if (current < 5 && targetVersion >= 5) {
            applyV5();
        }
        if (current < 6 && targetVersion >= 6) {
            applyV6();
        }
        if (current < 7 && targetVersion >= 7) {
            applyV7();
        }
        if (current < 8 && targetVersion >= 8) {
            applyV8();
        }
        if (current < 9 && targetVersion >= 9) {
            applyV9();
        }
        if (current < 10 && targetVersion >= 10) {
            applyV10();
        }
        if (current < 11 && targetVersion >= 11) {
            applyV11();
        }
        if (current < 12 && targetVersion >= 12) {
            applyV12();
        }
        writeSchemaVersion(targetVersion);
        log.info("Sentinel schema at version {}", targetVersion);
    }

    /**
     * Detects the actual schema version from {@link DatabaseMetaData} (table
     * existence) and writes it to the {@code schema_version} property if it
     * differs from what's stored.
     *
     * @return the detected current version (0 = fresh install, 1 = all nine
     *         tables present, 2 = window mode/recurrence columns present,
     *         3 = window timezone column present, 4 = monitor runbook column
     *         present, 5 = retention-prune indexes present, 6 =
     *         connector-status prune index present, 7 = lease epoch present,
     *         8 = per-node connector state complete, 9 = resolution outbox complete,
     *         10 = problem outbox and legacy backfill complete, 11 = deployed-channel inventory present,
     *         12 = connector observation deployment identity present)
     */
    private int detectAndAlignSchemaVersion() throws MigrationException {
        int detected = detectFromState();
        Integer stored = readSchemaVersionOrNull();
        if (stored == null) {
            log.info("Sentinel schema state detected as version {} (no stored version)", detected);
            writeSchemaVersion(detected);
        } else if (stored != detected) {
            log.warn("Sentinel schema stored version ({}) does not match actual state ({}); realigning",
                    stored, detected);
            writeSchemaVersion(detected);
        }
        return detected;
    }

    private int detectFromState() throws MigrationException {
        try {
            if (!tableExists("sentinel_monitor")) {
                return 0;
            }
            // sentinel_monitor exists, so v1 was at least started. Verify the other eight
            // tables actually landed: a partial applyV1 failure would otherwise be reported
            // as a healthy v1 schema (detectAndAlign writes that version and start() marks
            // migration OK), and every later query against the missing tables would fail at
            // runtime with no migration signal. Fail loud instead — re-running applyV1 is not
            // a safe repair because the engine's Migrator does not ignore errors, so the
            // CREATE for the already-present sentinel_monitor would throw.
            List<String> missing = new ArrayList<>();
            for (String table : new String[]{
                    "sentinel_alert_event",
                    "sentinel_trigger_state",
                    "sentinel_channel_activity_sample",
                    "sentinel_channel_activity_trend",
                    "sentinel_connector_status_event",
                    "sentinel_action",
                    "sentinel_action_dispatch_log",
                    "sentinel_maintenance_window"}) {
                if (!tableExists(table)) {
                    missing.add(table);
                }
            }
            if (!missing.isEmpty()) {
                throw new MigrationException("Sentinel schema is partially applied: sentinel_monitor exists but "
                        + "table(s) " + missing + " are missing; manual repair required");
            }
            // v2, v3 and v4 are column-detected, newest first: window_mode is v2's first
            // ALTER, timezone is v3's only one, and runbook_url is v4's first, so each
            // column's presence means that version's script at least started. A partial v2
            // or v4 (the first ALTER landed, later ones did not) would mis-detect as
            // complete — acceptable because every ALTER in those scripts is idempotent to
            // re-run manually and the failed migrate() already surfaced loudly at startup.
            // v3 cannot be partial: it is a single statement.
            //
            // Note v4 is probed on sentinel_monitor rather than on its own new table
            // sentinel_node_lease: the CREATE TABLE is v4's LAST statement, so testing for
            // it would report a v4 that stopped halfway as a v3 and re-run the ALTERs,
            // which fail on the columns that already exist. Probing the first statement's
            // column keeps "detected version" monotonic with script progress.
            // v5 and v6 add no columns, only indexes, so they are index-detected — on
            // the FIRST index each script creates, for the same monotonicity reason as
            // v4's column. v6 is a single statement, so it cannot be partial.
            if (columnExists("sentinel_node_lease", "lease_epoch")) {
                if (columnExists("sentinel_connector_status_event", "node_id")
                        && indexExists("sentinel_connector_status_event", "idx_sentinel_conn_node_id",
                                "channel_id, metadata_id, node_id, id")) {
                    if (columnExists("sentinel_alert_event", "resolution_pending")
                            && indexExists("sentinel_alert_event", "idx_sentinel_alert_resolution",
                                    "resolution_pending, status, resolved_time")) {
                        return columnExists("sentinel_alert_event", "problem_pending")
                                && indexExists("sentinel_alert_event", "idx_sentinel_alert_problem",
                                        "problem_pending, status, opened_time")
                                ? (tableExists("sentinel_channel_presence")
                                        ? (columnExists("sentinel_connector_status_event", "deployment_time") ? 12 : 11) : 10) : 9;
                    }
                    return 8;
                }
                return 7;
            }
            if (indexExists("sentinel_connector_status_event", "idx_sentinel_connector_status_time")) {
                return 6;
            }
            if (indexExists("sentinel_channel_activity_sample", "idx_sentinel_activity_sample_time")) {
                return 5;
            }
            if (columnExists("sentinel_monitor", "runbook_url")) {
                return 4;
            }
            if (columnExists("sentinel_maintenance_window", "timezone")) {
                return 3;
            }
            if (columnExists("sentinel_maintenance_window", "window_mode")) {
                return 2;
            }
            return 1;
        } catch (MigrationException e) {
            throw e; // already descriptive — do not double-wrap
        } catch (Exception e) {
            throw new MigrationException("Failed to detect Sentinel schema state", e);
        }
    }

    /** A single atomic ALTER adds the monotonically increasing lease epoch. */
    private void applyV7() throws MigrationException {
        log.info("Applying Sentinel schema v7 (node lease fencing epoch)");
        executeScript("/" + getDatabaseType() + "-sentinel-v7.sql");
    }

    /** Column and index have independent guards because vendor DDL can auto-commit. */
    private void applyV8() throws MigrationException {
        log.info("Applying Sentinel schema v8 (per-node connector state)");
        try {
            if (!columnExists("sentinel_connector_status_event", "node_id")) {
                executeScript("/" + getDatabaseType() + "-sentinel-v8.sql");
            }
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Failed to inspect Sentinel connector node identity", e);
        }
        createIndexIfAbsent("sentinel_connector_status_event", "idx_sentinel_conn_node_id",
                "channel_id, metadata_id, node_id, id");
    }

    /** The index marks completion so a retry resumes after a committed column ALTER. */
    private void applyV9() throws MigrationException {
        log.info("Applying Sentinel schema v9 (durable resolution dispatch outbox)");
        String databaseType = getDatabaseType();
        String bool = "oracle".equalsIgnoreCase(databaseType) ? "NUMBER(1)"
                : "sqlserver".equalsIgnoreCase(databaseType) ? "BIT" : "BOOLEAN";
        addColumnIfAbsent("sentinel_alert_event", "resolution_pending", bool);
        createIndexIfAbsent("sentinel_alert_event", "idx_sentinel_alert_resolution",
                "resolution_pending, status, resolved_time");
    }

    /** Adds the durable one-shot problem dispatch outbox flag. */
    private void applyV10() throws MigrationException {
        log.info("Applying Sentinel schema v10 (durable problem dispatch outbox)");
        String databaseType = getDatabaseType();
        String bool = "oracle".equalsIgnoreCase(databaseType) ? "NUMBER(1)"
                : "sqlserver".equalsIgnoreCase(databaseType) ? "BIT" : "BOOLEAN";
        addColumnIfAbsent("sentinel_alert_event", "problem_pending", bool);

        // The index is the migration-complete marker, so a retry after a
        // failed backfill repeats this idempotent NULL-only repair. Treat all
        // legacy open events as pending: dispatch-log reconciliation will
        // suppress actions already accounted for and recover missing ones.
        String trueLiteral = "oracle".equalsIgnoreCase(databaseType)
                || "sqlserver".equalsIgnoreCase(databaseType) ? "1" : "TRUE";
        try (PreparedStatement statement = getConnection().prepareStatement(
                "UPDATE sentinel_alert_event SET problem_pending = " + trueLiteral
                        + " WHERE problem_pending IS NULL AND status = 'PROBLEM'")) {
            statement.executeUpdate();
        } catch (Exception e) {
            throw new MigrationException("Failed to backfill Sentinel problem dispatch outbox", e);
        }
        createIndexIfAbsent("sentinel_alert_event", "idx_sentinel_alert_problem",
                "problem_pending, status, opened_time");
    }

    /** The inventory is published atomically by each node's heartbeat after installation. */
    private void applyV11() throws MigrationException {
        try {
            if (!tableExists("sentinel_channel_presence")) {
                executeScript("/" + getDatabaseType() + "-sentinel-v11.sql");
            }
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Failed to create Sentinel channel presence inventory", e);
        }
    }

    /** A nullable identity distinguishes old observations from the current deployment. */
    private void applyV12() throws MigrationException {
        try {
            if (!columnExists("sentinel_connector_status_event", "deployment_time")) {
                executeScript("/" + getDatabaseType() + "-sentinel-v12.sql");
            }
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Failed to add Sentinel observation deployment identity", e);
        }
    }

    private void addColumnIfAbsent(String table, String column, String sqlType) throws MigrationException {
        try {
            if (columnExists(table, column)) {
                return;
            }
            String databaseType = getDatabaseType();
            String addClause = "oracle".equalsIgnoreCase(databaseType)
                    || "sqlserver".equalsIgnoreCase(databaseType) ? " ADD " : " ADD COLUMN ";
            try (PreparedStatement statement = getConnection().prepareStatement(
                    "ALTER TABLE " + table + addClause + column + " " + sqlType)) {
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new MigrationException("Failed to add Sentinel column " + table + "." + column, e);
        }
    }

    private void createIndexIfAbsent(String table, String index, String column) throws MigrationException {
        try {
            if (indexExists(table, index, column)) {
                return;
            }
            try (PreparedStatement statement = getConnection().prepareStatement(
                    "CREATE INDEX " + index + " ON " + table + " (" + column + ")")) {
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new MigrationException("Failed to create Sentinel index " + index, e);
        }
    }

    /** Creates the nine base tables. */
    private void applyV1() throws MigrationException {
        log.info("Applying Sentinel schema v1 (create tables)");
        executeScript("/" + getDatabaseType() + "-sentinel-tables.sql");
    }

    /** Creates the retention-prune indexes (see class Javadoc). */
    private void applyV5() throws MigrationException {
        log.info("Applying Sentinel schema v5 (retention prune indexes)");
        executeScript("/" + getDatabaseType() + "-sentinel-v5.sql");
    }

    /** Creates the connector-status prune index (see class Javadoc). */
    private void applyV6() throws MigrationException {
        log.info("Applying Sentinel schema v6 (connector-status prune index)");
        executeScript("/" + getDatabaseType() + "-sentinel-v6.sql");
    }

    /** Adds the maintenance-window mode/recurrence columns (see class Javadoc). */
    private void applyV2() throws MigrationException {
        log.info("Applying Sentinel schema v2 (maintenance-window modes and recurrence)");
        executeScript("/" + getDatabaseType() + "-sentinel-v2.sql");
    }

    /** Adds the nullable maintenance-window {@code timezone} column (see class Javadoc). */
    private void applyV3() throws MigrationException {
        log.info("Applying Sentinel schema v3 (maintenance-window timezone)");
        executeScript("/" + getDatabaseType() + "-sentinel-v3.sql");
    }

    /**
     * Adds the monitor runbook URL, the action storm-control and escalation
     * columns, and the {@code sentinel_node_lease} table (see class Javadoc).
     */
    private void applyV4() throws MigrationException {
        log.info("Applying Sentinel schema v4 (runbook URL, storm control, escalation, node lease)");
        executeScript("/" + getDatabaseType() + "-sentinel-v4.sql");
    }

    // ========== Schema version persistence ==========

    /**
     * @return the stored schema_version as an integer, or {@code null} when
     *         the property is unset or unparseable
     */
    private Integer readSchemaVersionOrNull() {
        String raw = ConfigurationController.getInstance().getProperty(PLUGIN_NAME, VERSION_PROPERTY);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("Sentinel schema_version property '{}' is not an integer; treating as unset", raw);
            return null;
        }
    }

    private void writeSchemaVersion(int version) throws MigrationException {
        try {
            ConfigurationController.getInstance().saveProperty(PLUGIN_NAME, VERSION_PROPERTY, String.valueOf(version));
        } catch (Exception e) {
            throw new MigrationException("Failed to persist schema_version=" + version, e);
        }
    }

    // ========== DatabaseMetaData helpers ==========

    /**
     * Checks for a table by name across the conventional identifier cases
     * different JDBC drivers normalise to.
     */
    private boolean tableExists(String tableName) throws Exception {
        Connection conn = getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        for (String candidate : namingCandidates(tableName)) {
            try (ResultSet rs = meta.getTables(null, null, candidate, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks for a column on a table across the conventional identifier cases
     * different JDBC drivers normalise to. The detection signal for the
     * column-only versions: {@code sentinel_maintenance_window.window_mode}
     * for v2, {@code sentinel_maintenance_window.timezone} for v3,
     * {@code sentinel_monitor.runbook_url} for v4.
     */
    private boolean columnExists(String tableName, String columnName) throws Exception {
        Connection conn = getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        for (String tableCandidate : namingCandidates(tableName)) {
            for (String columnCandidate : namingCandidates(columnName)) {
                try (ResultSet rs = meta.getColumns(null, null, tableCandidate, columnCandidate)) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks for an index by name on a table, across the conventional
     * identifier cases different JDBC drivers normalise to. v5's detection
     * signal — unlike every earlier version it adds no column to probe.
     *
     * <p>{@code getIndexInfo} needs the table name in the case the driver
     * stores it and throws on some drivers when the table does not resolve,
     * so each candidate is tried independently and a failure moves on to the
     * next rather than aborting detection. {@code approximate = true} lets
     * the driver answer from cached statistics instead of forcing an
     * analyze, which on a large sample table would be an expensive way to
     * answer a yes/no question at startup.</p>
     */
    private boolean indexExists(String tableName, String indexName) throws Exception {
        return indexExists(tableName, indexName, null);
    }

    /**
     * Accepts an equivalent physical index even when a database owns its name.
     * Derby silently reuses the system-named backing index for a foreign key
     * instead of creating a redundant named index, so name-only detection can
     * otherwise strand an entirely current schema at an older version.
     */
    private boolean indexExists(String tableName, String indexName, String columns) throws Exception {
        Connection conn = getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        for (String tableCandidate : namingCandidates(tableName)) {
            Map<String, Map<Integer, String>> foundColumns = new HashMap<>();
            // Derby returns no index rows when catalog is null even though its
            // table/column metadata accepts that wildcard. The current catalog
            // is also the narrowest correct scope for MySQL, PostgreSQL and SQL
            // Server; Oracle reports null here and keeps its existing behavior.
            try (ResultSet rs = meta.getIndexInfo(
                    conn.getCatalog(), null, tableCandidate, false, true)) {
                while (rs.next()) {
                    String found = rs.getString("INDEX_NAME");
                    if (found != null && found.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                    String column = rs.getString("COLUMN_NAME");
                    int ordinal = rs.getInt("ORDINAL_POSITION");
                    if (found != null && column != null && ordinal > 0) {
                        foundColumns.computeIfAbsent(found, (ignored) -> new HashMap<>())
                                .put(ordinal, column);
                    }
                }
                if (columns != null && foundColumns.values().stream()
                        .anyMatch((actual) -> columnsMatch(actual, columns))) {
                    return true;
                }
            } catch (Exception e) {
                // Unknown table in this case form; try the next candidate.
                log.trace("getIndexInfo failed for table candidate {}", tableCandidate, e);
            }
        }
        return false;
    }

    private static boolean columnsMatch(Map<Integer, String> actual, String expected) {
        String[] columns = expected.split(",");
        if (actual.size() != columns.length) {
            return false;
        }
        for (int i = 0; i < columns.length; i++) {
            String found = actual.get(i + 1);
            if (found == null || !found.equalsIgnoreCase(columns[i].trim())) {
                return false;
            }
        }
        return true;
    }

    private static List<String> namingCandidates(String name) {
        List<String> out = new ArrayList<>(3);
        out.add(name);
        out.add(name.toUpperCase(Locale.ROOT));
        out.add(name.toLowerCase(Locale.ROOT));
        return out;
    }

    // ========== Engine contract ==========

    /**
     * {@inheritDoc}
     * <p>Sentinel has no legacy serialized data to migrate — its data is
     * normalized across nine tables, not stored as blobs.</p>
     */
    @Override
    public void migrateSerializedData() throws MigrationException {
        // No serialized data migration needed
    }

    // ========== Uninstall ==========

    /**
     * The plugin's tables, child-first. Order is irrelevant to a rename (unlike
     * the DROP TABLE list this replaced, where a parent had to go last), but is
     * kept so the emitted script still reads in dependency order.
     */
    private static final String[] UNINSTALL_TABLES = {
            "sentinel_action_dispatch_log",
            "sentinel_alert_event",
            "sentinel_trigger_state",
            "sentinel_connector_status_event",
            "sentinel_channel_presence",
            "sentinel_channel_activity_trend",
            "sentinel_channel_activity_sample",
            "sentinel_action",
            "sentinel_maintenance_window",
            "sentinel_monitor",
            "sentinel_node_lease"};

    /**
     * Explicit secondary indexes created by Sentinel. The naming convention is
     * {@code idx_sentinel_<short-entity>_<purpose>}; names are kept short enough
     * for Oracle and for the archive suffix. Constraint names use the analogous
     * {@code pk_}, {@code uq_}, {@code fk_}, and {@code df_} prefixes.
     */
    private static final NamedObject[] UNINSTALL_INDEXES = {
            object("sentinel_monitor", "idx_sentinel_monitor_scope"),
            object("sentinel_monitor", "idx_sentinel_monitor_enabled"),
            object("sentinel_alert_event", "idx_sentinel_alert_event_monitor_channel"),
            object("sentinel_alert_event", "idx_sentinel_alert_event_status"),
            object("sentinel_alert_event", "idx_sentinel_alert_event_opened"),
            object("sentinel_alert_event", "idx_sentinel_alert_event_resolved"),
            object("sentinel_alert_event", "idx_sentinel_alert_resolution"),
            object("sentinel_alert_event", "idx_sentinel_alert_problem"),
            object("sentinel_trigger_state", "idx_sentinel_trigger_state_monitor"),
            object("sentinel_channel_activity_sample", "idx_sentinel_activity_sample_channel_time"),
            object("sentinel_channel_activity_sample", "idx_sentinel_activity_sample_time"),
            object("sentinel_channel_activity_trend", "idx_sentinel_activity_trend_channel_hour"),
            object("sentinel_channel_activity_trend", "idx_sentinel_activity_trend_hour"),
            object("sentinel_connector_status_event", "idx_sentinel_connector_status_event_channel"),
            object("sentinel_connector_status_event", "idx_sentinel_connector_status_time"),
            object("sentinel_connector_status_event", "idx_sentinel_conn_node_id"),
            object("sentinel_action", "idx_sentinel_action_enabled"),
            object("sentinel_action_dispatch_log", "idx_sentinel_action_dispatch_log_event"),
            object("sentinel_maintenance_window", "idx_sentinel_maintenance_window_scope")};

    /** PostgreSQL SERIAL sequences do not follow their table through a rename. */
    private static final NamedObject[] POSTGRES_SEQUENCES = {
            object("sentinel_monitor", "sentinel_monitor_id_seq"),
            object("sentinel_alert_event", "sentinel_alert_event_id_seq"),
            object("sentinel_trigger_state", "sentinel_trigger_state_id_seq"),
            object("sentinel_channel_activity_sample", "sentinel_channel_activity_sample_id_seq"),
            object("sentinel_channel_activity_trend", "sentinel_channel_activity_trend_id_seq"),
            object("sentinel_connector_status_event", "sentinel_connector_status_event_id_seq"),
            object("sentinel_action", "sentinel_action_id_seq"),
            object("sentinel_action_dispatch_log", "sentinel_action_dispatch_log_id_seq"),
            object("sentinel_maintenance_window", "sentinel_maintenance_window_id_seq")};

    /**
     * PostgreSQL's generated names are deterministic and its PK/UNIQUE names
     * also name schema-global backing indexes. Archive all of them, plus the
     * one explicitly named FK, so a fresh v1 script cannot collide.
     */
    private static final NamedObject[] POSTGRES_CONSTRAINTS = {
            object("sentinel_monitor", "sentinel_monitor_pkey"),
            object("sentinel_monitor", "sentinel_monitor_name_key"),
            object("sentinel_monitor", "sentinel_monitor_suppressed_by_monitor_id_fkey"),
            object("sentinel_alert_event", "sentinel_alert_event_pkey"),
            object("sentinel_alert_event", "sentinel_alert_event_monitor_id_fkey"),
            object("sentinel_trigger_state", "sentinel_trigger_state_pkey"),
            object("sentinel_trigger_state", "sentinel_trigger_state_monitor_id_fkey"),
            object("sentinel_trigger_state", "sentinel_trigger_state_monitor_id_channel_id_metadata_id_key"),
            object("sentinel_trigger_state", "fk_sentinel_trigger_state_alert_event"),
            object("sentinel_channel_activity_sample", "sentinel_channel_activity_sample_pkey"),
            object("sentinel_channel_activity_trend", "sentinel_channel_activity_trend_pkey"),
            object("sentinel_channel_activity_trend", "sentinel_channel_activity_trend_channel_id_hour_bucket_key"),
            object("sentinel_connector_status_event", "sentinel_connector_status_event_pkey"),
            object("sentinel_channel_presence", "sentinel_channel_presence_pkey"),
            object("sentinel_action", "sentinel_action_pkey"),
            object("sentinel_action", "sentinel_action_name_key"),
            object("sentinel_action_dispatch_log", "sentinel_action_dispatch_log_pkey"),
            object("sentinel_action_dispatch_log", "sentinel_action_dispatch_log_alert_event_id_fkey"),
            object("sentinel_action_dispatch_log", "sentinel_action_dispatch_log_action_id_fkey"),
            object("sentinel_maintenance_window", "sentinel_maintenance_window_pkey"),
            object("sentinel_node_lease", "sentinel_node_lease_pkey")};

    /** Oracle constraints that were explicitly named by shipped scripts. */
    private static final NamedObject[] ORACLE_CONSTRAINTS = {
            object("sentinel_trigger_state", "uq_sentinel_trigger_state"),
            object("sentinel_trigger_state", "fk_sentinel_trigger_state_alert_event"),
            object("sentinel_channel_activity_trend", "uq_sentinel_activity_trend")};

    /** SQL Server schema-global constraint names introduced by v7/v8. */
    private static final NamedObject[] SQLSERVER_CONSTRAINTS = {
            object("sentinel_node_lease", "df_sentinel_node_lease_epoch"),
            object("sentinel_connector_status_event", "df_sentinel_connector_node_id")};

    /**
     * MySQL cannot rename a foreign-key constraint. Drop both the names emitted
     * by historical inline DDL and Sentinel's explicit name, then recreate the
     * relationships on the archived tables under archived names.
     */
    private static final NamedObject[] MYSQL_FOREIGN_KEYS_TO_DROP = {
            object("sentinel_monitor", "sentinel_monitor_ibfk_1"),
            object("sentinel_alert_event", "sentinel_alert_event_ibfk_1"),
            object("sentinel_trigger_state", "sentinel_trigger_state_ibfk_1"),
            object("sentinel_trigger_state", "fk_sentinel_trigger_state_alert_event"),
            object("sentinel_action_dispatch_log", "sentinel_action_dispatch_log_ibfk_1"),
            object("sentinel_action_dispatch_log", "sentinel_action_dispatch_log_ibfk_2")};

    private static final ForeignKey[] MYSQL_ARCHIVE_FOREIGN_KEYS = {
            foreignKey("sentinel_monitor", "fk_sentinel_monitor_parent", "suppressed_by_monitor_id",
                    "sentinel_monitor", "ON DELETE SET NULL"),
            foreignKey("sentinel_alert_event", "fk_sentinel_alert_monitor", "monitor_id",
                    "sentinel_monitor", "ON DELETE CASCADE"),
            foreignKey("sentinel_trigger_state", "fk_sentinel_trigger_monitor", "monitor_id",
                    "sentinel_monitor", "ON DELETE CASCADE"),
            foreignKey("sentinel_trigger_state", "fk_sentinel_trigger_alert", "open_alert_event_id",
                    "sentinel_alert_event", "ON DELETE SET NULL"),
            foreignKey("sentinel_action_dispatch_log", "fk_sentinel_dispatch_alert", "alert_event_id",
                    "sentinel_alert_event", "ON DELETE CASCADE"),
            foreignKey("sentinel_action_dispatch_log", "fk_sentinel_dispatch_action", "action_id",
                    "sentinel_action", "ON DELETE SET NULL")};

    /**
     * How many same-day archive targets to offer per table: the bare
     * {@code _u<yyyyMMdd>} name plus {@code _2} and {@code _3}. See
     * {@link #getUninstallStatements()} for why a counter has to be expressed
     * as alternatives rather than computed by probing the database.
     */
    private static final int RENAME_ATTEMPTS = 3;

    private record NamedObject(String table, String name) {
    }

    private record ForeignKey(String table, String name, String column, String parentTable, String deleteRule) {
    }

    private static NamedObject object(String table, String name) {
        return new NamedObject(table, name);
    }

    private static ForeignKey foreignKey(
            String table, String name, String column, String parentTable, String deleteRule) {
        return new ForeignKey(table, name, column, parentTable, deleteRule);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Renames the plugin's tables to an archive name ending in
     * {@code _u<yyyyMMdd>} instead of dropping them. Names that would exceed a
     * vendor's identifier limit are shortened with a stable hash, and repeat
     * uninstalls use {@code _2} and {@code _3} variants.
     * Uninstall is a routine operator gesture with no undo, and alert history
     * is exactly the kind of record an audit asks for six months later, so the
     * data survives and cleanup becomes an explicit operator decision. The
     * README carries the manual DROP statements.</p>
     *
     * <h3>Why this method branches on the vendor</h3>
     *
     * <p>{@code DROP TABLE} is portable across all five supported databases;
     * renaming is not. The engine constructs a <em>fresh</em> migrator for the
     * uninstall path and calls {@code setDatabaseType(...)} on it before
     * calling this method (see
     * {@code DefaultExtensionController.prepareExtensionForUninstallation}), so
     * {@link #getDatabaseType()} is populated here and vendor branching is
     * safe. {@link #getConnection()} is <b>not</b>: that path never calls
     * {@code setConnection(...)}, so the field is null and the accessor
     * dereferences it. Nothing in this method may touch the database — which is
     * why the same-day counter below is expressed as a chain of alternative
     * statements rather than a "does this name already exist?" probe.</p>
     *
     * <p>The engine executes the returned statements once, on the next startup,
     * each in its own transaction and with errors ignored
     * ({@code DatabaseUtil.executeScript(script, true)}). That is what makes
     * the alternatives work: each vendor gets one complete-table-set move for
     * the plain dated name, then {@code _2} and {@code _3}. MySQL moves the set
     * with atomic {@code RENAME TABLE}; PostgreSQL and SQL Server use one
     * transactional block; Oracle preflights every target and compensates
     * successful moves in reverse if its auto-committing DDL fails midway. On
     * a first uninstall the first set succeeds and the other two fail
     * harmlessly because the source tables no longer exist; on a repeat
     * uninstall the first fails because a target exists and the next free set
     * takes over.
     * Expect a handful of ignored errors in the startup log after any
     * uninstall — they are the mechanism, not a fault. Each successful table
     * set move is followed by independently retried vendor-specific renames for the indexes,
     * constraints, and PostgreSQL sequences that otherwise retain live schema
     * names and make reinstall fail. MySQL has no FK rename operation, so only
     * archived-table candidates have their old/generated FKs dropped and
     * recreated under archive names; exhausting all targets cannot alter the
     * still-live schema.</p>
     *
     * <h3>Derby returns nothing at all</h3>
     *
     * <p>Derby cannot rename a table that another table's foreign key
     * references (SQLSTATE X0Y25), and three of ours are referenced:
     * {@code sentinel_monitor}, {@code sentinel_alert_event} and
     * {@code sentinel_action}. The blocking constraints were created inline via
     * {@code REFERENCES} and therefore carry system-generated names
     * ({@code SQL240806...}) that a statically emitted script cannot drop.
     * Renaming only the seven that Derby permits would be actively harmful: the
     * next install would find {@code sentinel_monitor} present but its siblings
     * missing and {@link #detectFromState()} would — correctly — refuse to
     * start with "schema is partially applied". So on Derby this returns an
     * empty list: every table keeps its name and its data, which satisfies the
     * non-destructive goal completely. The visible difference is that a
     * reinstall on Derby adopts the existing tables (detection reports
     * {@link #LATEST_VERSION} and the version loop does nothing) rather than
     * building fresh ones.</p>
     *
     * <p>Independently of what this returns, the engine records the plugin name
     * in {@code extension_uninstall.properties} and, on the next startup,
     * {@code removePropertiesForUninstalledExtensions()} DELETES every
     * CONFIGURATION property under the plugin's group name — which includes
     * {@code schema_version} (persisted under {@link #PLUGIN_NAME}), the ONLY
     * property the plugin writes. The stored version is therefore always absent
     * after a reinstall, and {@link #detectAndAlignSchemaVersion()} is what
     * establishes the truth.</p>
     *
     * @return archive statements in the current vendor's dialect, or an empty
     *         list on Derby and for an unrecognized vendor — never DROP
     */
    @Override
    public List<String> getUninstallStatements() {
        String databaseType = getDatabaseType();
        if (databaseType == null || "derby".equalsIgnoreCase(databaseType)) {
            log.info("Sentinel uninstall: leaving all tables in place under their current names "
                    + "(database type '{}' cannot rename them safely); drop them manually if the data is not wanted",
                    databaseType);
            return new ArrayList<>();
        }

        String vendor = databaseType.toLowerCase(Locale.ROOT);
        if (!Arrays.asList("postgres", "mysql", "oracle", "sqlserver").contains(vendor)) {
            log.info("Sentinel uninstall: leaving all tables in place under their current names "
                    + "(unrecognized database type '{}'); drop them manually if the data is not wanted",
                    databaseType);
            return new ArrayList<>();
        }

        LocalDate archiveDate = LocalDate.now(ZoneId.systemDefault());
        int identifierLimit = identifierLimit(vendor);

        List<String> statements = new ArrayList<>();
        // Each suffix moves the complete related table set or none of it. This
        // prevents unevenly cleaned archives from producing a partial live
        // schema that reinstall would correctly reject.
        for (int attempt = 1; attempt <= RENAME_ATTEMPTS; attempt++) {
            statements.add(tableSetArchiveStatement(
                    vendor, archiveDate, attempt, identifierLimit));
        }

        addObjectArchiveStatements(
                statements, vendor, archiveDate, identifierLimit);
        if ("mysql".equals(vendor)) {
            addMySqlArchiveForeignKeys(
                    statements, archiveDate, identifierLimit);
        }

        log.info("Sentinel uninstall: archiving {} tables and their named schema objects with date {}; "
                + "drop them manually once the data is no longer needed",
                UNINSTALL_TABLES.length, archiveDate.format(DateTimeFormatter.BASIC_ISO_DATE));
        for (int i = RENAME_ATTEMPTS; i < statements.size(); i++) {
            String statement = statements.get(i);
            if (statement.startsWith("ALTER INDEX ") || statement.startsWith("ALTER SEQUENCE ")
                    || statement.startsWith("DECLARE @sentinel_object")) {
                statements.set(i, guardArchivedObjects(vendor, statement));
            }
        }
        return statements;
    }

    private static String guardArchivedObjects(String vendor, String statement) {
        String quoted = statement.replace("'", "''");
        return switch (vendor) {
            case "postgres" -> "DO $sentinel$ BEGIN IF to_regclass('sentinel_monitor') IS NULL THEN "
                    + "EXECUTE '" + quoted + "'; END IF; END $sentinel$";
            case "oracle" -> "DECLARE sentinel_live PLS_INTEGER; BEGIN SELECT COUNT(*) INTO sentinel_live "
                    + "FROM user_tables WHERE table_name = 'SENTINEL_MONITOR'; IF sentinel_live = 0 THEN "
                    + "EXECUTE IMMEDIATE '" + quoted + "'; END IF; END;";
            case "sqlserver" -> "IF OBJECT_ID(N'sentinel_monitor', N'U') IS NULL BEGIN " + statement + "; END";
            default -> throw new IllegalArgumentException("Unsupported global object guard: " + vendor);
        };
    }

    private static String tableSetArchiveStatement(
            String vendor, LocalDate date, int attempt, int identifierLimit) {
        switch (vendor) {
            case "mysql":
                return mysqlTableSetArchiveStatement(date, attempt, identifierLimit);
            case "postgres":
                return postgresTableSetArchiveStatement(date, attempt, identifierLimit);
            case "sqlserver":
                return sqlServerTableSetArchiveStatement(date, attempt, identifierLimit);
            case "oracle":
                return oracleTableSetArchiveStatement(date, attempt, identifierLimit);
            default:
                throw new IllegalArgumentException("Unsupported database type: " + vendor);
        }
    }

    private static String mysqlTableSetArchiveStatement(
            LocalDate date, int attempt, int identifierLimit) {
        List<String> renames = new ArrayList<>();
        for (String table : UNINSTALL_TABLES) {
            renames.add(table + " TO " + archiveName(table, date, attempt, identifierLimit));
        }
        // RENAME TABLE is atomic when it contains multiple rename pairs.
        return "RENAME TABLE " + String.join(", ", renames);
    }

    private static String postgresTableSetArchiveStatement(
            LocalDate date, int attempt, int identifierLimit) {
        StringBuilder sql = new StringBuilder("DO $sentinel$ BEGIN ");
        sql.append("IF ");
        for (int i = 0; i < UNINSTALL_TABLES.length; i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("to_regclass('")
                    .append(archiveName(UNINSTALL_TABLES[i], date, attempt, identifierLimit))
                    .append("') IS NOT NULL");
        }
        sql.append(" THEN RAISE EXCEPTION 'Sentinel archive target set is occupied'; END IF; ");
        for (String table : UNINSTALL_TABLES) {
            sql.append("ALTER TABLE ").append(table).append(" RENAME TO ")
                    .append(archiveName(table, date, attempt, identifierLimit)).append("; ");
        }
        // PostgreSQL DDL is transactional, so any source/target race rolls the
        // complete block back before DatabaseUtil tries the next suffix.
        return sql.append("END $sentinel$").toString();
    }

    private static String sqlServerTableSetArchiveStatement(
            LocalDate date, int attempt, int identifierLimit) {
        StringBuilder sql = new StringBuilder("SET XACT_ABORT ON; IF ");
        for (int i = 0; i < UNINSTALL_TABLES.length; i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("OBJECT_ID(N'")
                    .append(archiveName(UNINSTALL_TABLES[i], date, attempt, identifierLimit))
                    .append("', N'U') IS NOT NULL");
        }
        sql.append(" BEGIN THROW 51000, 'Sentinel archive target set is occupied', 1; END; ");
        for (String table : UNINSTALL_TABLES) {
            sql.append(renameStatement("sqlserver", table,
                    archiveName(table, date, attempt, identifierLimit))).append("; ");
        }
        // sp_rename participates in the surrounding JDBC transaction. XACT_ABORT
        // ensures an unexpected mid-batch error cannot leave a partial set.
        return sql.toString().trim();
    }

    private static String oracleTableSetArchiveStatement(
            LocalDate date, int attempt, int identifierLimit) {
        StringBuilder sql = new StringBuilder(
                "DECLARE sentinel_conflicts PLS_INTEGER; sentinel_step PLS_INTEGER := 0; BEGIN ");
        sql.append("SELECT COUNT(*) INTO sentinel_conflicts FROM user_tables WHERE table_name IN (");
        for (int i = 0; i < UNINSTALL_TABLES.length; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("'")
                    .append(archiveName(UNINSTALL_TABLES[i], date, attempt, identifierLimit)
                            .toUpperCase(Locale.ROOT))
                    .append("'");
        }
        sql.append("); IF sentinel_conflicts > 0 THEN "
                + "RAISE_APPLICATION_ERROR(-20068, 'Sentinel archive target set is occupied'); END IF; ");
        for (int i = 0; i < UNINSTALL_TABLES.length; i++) {
            String table = UNINSTALL_TABLES[i];
            sql.append("EXECUTE IMMEDIATE 'ALTER TABLE ").append(table).append(" RENAME TO ")
                    .append(archiveName(table, date, attempt, identifierLimit))
                    .append("'; sentinel_step := ").append(i + 1).append("; ");
        }
        // Oracle DDL auto-commits, so transaction rollback cannot protect the
        // set. Preflight all targets and compensate successful moves in reverse
        // order if a source disappears or another unexpected DDL error occurs.
        sql.append("EXCEPTION WHEN OTHERS THEN ");
        for (int i = UNINSTALL_TABLES.length - 1; i >= 0; i--) {
            String table = UNINSTALL_TABLES[i];
            sql.append("IF sentinel_step >= ").append(i + 1).append(" THEN BEGIN EXECUTE IMMEDIATE '")
                    .append("ALTER TABLE ")
                    .append(archiveName(table, date, attempt, identifierLimit))
                    .append(" RENAME TO ").append(table)
                    .append("'; EXCEPTION WHEN OTHERS THEN NULL; END; END IF; ");
        }
        return sql.append("RAISE; END;").toString();
    }

    private static void addObjectArchiveStatements(List<String> statements, String vendor,
            LocalDate date, int identifierLimit) {
        for (NamedObject index : UNINSTALL_INDEXES) {
            switch (vendor) {
                case "postgres":
                case "oracle":
                    addGlobalRenameAlternatives(statements, "ALTER INDEX " + index.name()
                            + " RENAME TO ", index.name(), date, identifierLimit);
                    break;
                case "mysql":
                    addTableIndexRenameAlternatives(
                            statements, vendor, index, date, identifierLimit);
                    break;
                case "sqlserver":
                    addTableIndexRenameAlternatives(
                            statements, vendor, index, date, identifierLimit);
                    break;
                default:
                    break;
            }
        }

        if ("postgres".equals(vendor)) {
            addConstraintArchiveStatements(
                    statements, POSTGRES_CONSTRAINTS, date, identifierLimit, true);
            for (NamedObject sequence : POSTGRES_SEQUENCES) {
                addGlobalRenameAlternatives(statements, "ALTER SEQUENCE " + sequence.name()
                        + " RENAME TO ", sequence.name(), date, identifierLimit);
            }
        } else if ("oracle".equals(vendor)) {
            addConstraintArchiveStatements(
                    statements, ORACLE_CONSTRAINTS, date, identifierLimit, true);
        } else if ("sqlserver".equals(vendor)) {
            for (NamedObject constraint : SQLSERVER_CONSTRAINTS) {
                for (int objectAttempt = 1; objectAttempt <= RENAME_ATTEMPTS; objectAttempt++) {
                    String target = archiveName(
                            constraint.name(), date, objectAttempt, identifierLimit);
                    // Constraints are schema objects in SQL Server. Qualifying
                    // them as table.constraint is invalid; resolve the current
                    // user's default schema explicitly for installations that
                    // do not use dbo.
                    statements.add("DECLARE @sentinel_object NVARCHAR(776) = "
                            + "QUOTENAME(SCHEMA_NAME()) + N'.' + QUOTENAME(N'"
                            + constraint.name() + "'); EXEC sp_rename @sentinel_object, N'"
                            + target + "', N'OBJECT'");
                }
            }
        }
    }

    private static void addConstraintArchiveStatements(List<String> statements, NamedObject[] constraints,
            LocalDate date, int identifierLimit, boolean renameBackingIndex) {
        for (NamedObject constraint : constraints) {
            for (int tableAttempt = 1; tableAttempt <= RENAME_ATTEMPTS; tableAttempt++) {
                String archivedTable = archiveName(
                        constraint.table(), date, tableAttempt, identifierLimit);
                for (int objectAttempt = 1; objectAttempt <= RENAME_ATTEMPTS; objectAttempt++) {
                    statements.add("ALTER TABLE " + archivedTable + " RENAME CONSTRAINT "
                            + constraint.name() + " TO "
                            + archiveName(constraint.name(), date, objectAttempt, identifierLimit));
                }
            }
            if (renameBackingIndex) {
                // Only PK/UNIQUE constraints have a backing index. Trying this
                // for an FK is harmless because uninstall scripts ignore errors.
                addGlobalRenameAlternatives(statements, "ALTER INDEX " + constraint.name()
                        + " RENAME TO ", constraint.name(), date, identifierLimit);
            }
        }
    }

    private static void addTableIndexRenameAlternatives(List<String> statements, String vendor,
            NamedObject index, LocalDate date, int identifierLimit) {
        for (int tableAttempt = 1; tableAttempt <= RENAME_ATTEMPTS; tableAttempt++) {
            String archivedTable = archiveName(index.table(), date, tableAttempt, identifierLimit);
            for (int objectAttempt = 1; objectAttempt <= RENAME_ATTEMPTS; objectAttempt++) {
                String target = archiveName(index.name(), date, objectAttempt, identifierLimit);
                if ("mysql".equals(vendor)) {
                    statements.add("ALTER TABLE " + archivedTable + " RENAME INDEX "
                            + index.name() + " TO " + target);
                } else {
                    statements.add("EXEC sp_rename '" + archivedTable + "." + index.name()
                            + "', '" + target + "', 'INDEX'");
                }
            }
        }
    }

    private static void addGlobalRenameAlternatives(List<String> statements, String prefix,
            String objectName, LocalDate date, int identifierLimit) {
        for (int objectAttempt = 1; objectAttempt <= RENAME_ATTEMPTS; objectAttempt++) {
            statements.add(prefix + archiveName(objectName, date, objectAttempt, identifierLimit));
        }
    }

    private static void addMySqlArchiveForeignKeys(List<String> statements,
            LocalDate date, int identifierLimit) {
        for (int tableAttempt = 1; tableAttempt <= RENAME_ATTEMPTS; tableAttempt++) {
            Map<String, String> tables = new HashMap<>();
            for (String table : UNINSTALL_TABLES) {
                tables.put(table, archiveName(table, date, tableAttempt, identifierLimit));
            }

            // These statements address archived candidates only. If all three
            // table targets are occupied the still-live schema is never altered.
            for (NamedObject foreignKey : MYSQL_FOREIGN_KEYS_TO_DROP) {
                statements.add("ALTER TABLE " + tables.get(foreignKey.table())
                        + " DROP FOREIGN KEY " + foreignKey.name());
                String generatedPrefix = foreignKey.table() + "_ibfk_";
                if (foreignKey.name().startsWith(generatedPrefix)) {
                    String ordinal = foreignKey.name().substring(foreignKey.table().length());
                    statements.add("ALTER TABLE " + tables.get(foreignKey.table())
                            + " DROP FOREIGN KEY " + tables.get(foreignKey.table()) + ordinal);
                }
            }
            for (ForeignKey foreignKey : MYSQL_ARCHIVE_FOREIGN_KEYS) {
                statements.add("ALTER TABLE " + tables.get(foreignKey.table())
                        + " ADD CONSTRAINT "
                        + archiveName(foreignKey.name(), date, tableAttempt, identifierLimit)
                        + " FOREIGN KEY (" + foreignKey.column() + ") REFERENCES "
                        + tables.get(foreignKey.parentTable()) + "(id) " + foreignKey.deleteRule());
            }
        }
    }

    private static int identifierLimit(String vendor) {
        switch (vendor) {
            case "oracle":
                // Compatible with pre-12.2 Oracle as well as current releases.
                return 30;
            case "postgres":
                return 63;
            case "mysql":
                return 64;
            case "sqlserver":
                return 128;
            default:
                throw new IllegalArgumentException("Unsupported database type: " + vendor);
        }
    }

    /**
     * Builds an archive identifier without relying on vendor truncation. The
     * stable hash prevents two long names with the same prefix from collapsing
     * to one identifier under Oracle's 30-character limit.
     */
    static String archiveName(String original, LocalDate date, int attempt, int maxLength) {
        if (attempt < 1 || maxLength < 16) {
            throw new IllegalArgumentException("Invalid archive naming bounds");
        }
        String marker = "_u" + date.format(DateTimeFormatter.BASIC_ISO_DATE)
                + (attempt == 1 ? "" : "_" + attempt);
        int available = maxLength - marker.length();
        if (original.length() <= available) {
            return original + marker;
        }
        String hash = String.format(Locale.ROOT, "%08x", original.hashCode());
        int prefixLength = available - hash.length() - 1;
        if (prefixLength < 1) {
            throw new IllegalArgumentException("Identifier limit too small for archive name");
        }
        return original.substring(0, prefixLength) + "_" + hash + marker;
    }

    /**
     * Renders one "rename {@code from} to {@code to}" statement in the given
     * vendor's dialect.
     *
     * <p>The dialects genuinely differ in shape, not just in keywords:
     * PostgreSQL and Oracle spell it as an {@code ALTER TABLE} sub-command,
     * Derby and MySQL as a top-level {@code RENAME TABLE} statement, and SQL
     * Server has no rename DDL at all — it exposes the operation as the
     * {@code sp_rename} system stored procedure, whose second argument is a
     * bare name (schema-qualifying it is an error). Derby's {@code RENAME
     * TABLE} exists but is unusable for us; see
     * {@link #getUninstallStatements()}.</p>
     *
     * @param databaseType the engine's configured database type
     * @param from         the current table name
     * @param to           the target table name
     * @return the statement, or {@code null} when this vendor cannot be renamed
     *         safely (Derby) or is not recognized
     */
    private static String renameStatement(String databaseType, String from, String to) {
        if (databaseType == null) {
            return null;
        }
        switch (databaseType.toLowerCase(Locale.ROOT)) {
            case "postgres":
            case "oracle":
                return "ALTER TABLE " + from + " RENAME TO " + to;
            case "mysql":
                return "RENAME TABLE " + from + " TO " + to;
            case "sqlserver":
                return "EXEC sp_rename '" + from + "', '" + to + "'";
            default:
                // Derby (cannot rename FK-referenced tables) and anything unrecognized:
                // change nothing rather than risk a half-renamed schema.
                return null;
        }
    }
}
