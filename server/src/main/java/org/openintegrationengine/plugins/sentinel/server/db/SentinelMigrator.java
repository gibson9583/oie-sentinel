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
import java.util.ArrayList;
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
 *   <li><b>13</b> — Dispatch-time index for complete notification-budget accounting.</li>
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
    public static final int LATEST_VERSION = 13;

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
     * installs, manual edits to {@code CONFIGURATION}, and reinstalling after
     * an uninstall. On uninstall the engine drops Sentinel's tables and clears
     * every CONFIGURATION property under the plugin's group name, including
     * {@code schema_version}. Detection also supports schemas retained by older
     * releases: a fresh install creates absent tables, while an upgrade or a
     * reinstall over a complete retained schema applies only missing migrations.
     * A partial schema is rejected instead of being silently overwritten.</p>
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
        // Older releases inferred v2 completion from its first ALTER. Repair
        // every v2 prerequisite even when a later version was recorded.
        if (targetVersion >= 2) {
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
        if (current < 13 && targetVersion >= 13) {
            applyV13();
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
            // complete. v2 is therefore reconciled operation-by-operation on
            // every startup, including schemas already detected at a later version.
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
                                        ? (columnExists("sentinel_connector_status_event", "deployment_time")
                                                ? (indexExists("sentinel_action_dispatch_log", "idx_sentinel_dispatch_time",
                                                        "dispatch_time") ? 13 : 12) : 11) : 10) : 9;
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

    /** Dispatch-time accounting includes deliveries on arbitrarily old incidents. */
    private void applyV13() throws MigrationException {
        createIndexIfAbsent("sentinel_action_dispatch_log", "idx_sentinel_dispatch_time", "dispatch_time");
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
        // DDL can auto-commit. Each step must resume independently after an
        // interruption; replaying the complete historical script would fail
        // on the first column already present and never repair the rest.
        String table = "sentinel_maintenance_window";
        String varchar = "oracle".equalsIgnoreCase(getDatabaseType()) ? "VARCHAR2" : "VARCHAR";
        addColumnIfAbsent(table, "window_mode", varchar + "(16) DEFAULT 'SUPPRESS' NOT NULL");
        addColumnIfAbsent(table, "repeat_type", varchar + "(16) DEFAULT 'NONE' NOT NULL");
        addColumnIfAbsent(table, "days_of_week", varchar + "(64)");
        addColumnIfAbsent(table, "days_of_month", varchar + "(128)");
        addColumnIfAbsent(table, "start_time", varchar + "(5)");
        addColumnIfAbsent(table, "end_time", varchar + "(5)");
        makeWindowBoundNullable("active_from");
        makeWindowBoundNullable("active_until");
    }

    private void makeWindowBoundNullable(String column) throws MigrationException {
        try {
            boolean found = false;
            for (String table : namingCandidates("sentinel_maintenance_window")) {
                for (String name : namingCandidates(column)) {
                    try (ResultSet columns = getConnection().getMetaData().getColumns(
                            getConnection().getCatalog(), null, table, name)) {
                        if (columns.next()) {
                            int nullable = columns.getInt("NULLABLE");
                            if (nullable == DatabaseMetaData.columnNullable) return;
                            if (nullable != DatabaseMetaData.columnNoNulls) {
                                throw new MigrationException("Unknown nullability for Sentinel window " + column);
                            }
                            found = true;
                        }
                    }
                    if (found) break;
                }
                if (found) break;
            }
            if (!found) throw new MigrationException("Missing Sentinel window column " + column);
            String change = switch (getDatabaseType().toLowerCase(Locale.ROOT)) {
                case "derby" -> "ALTER COLUMN " + column + " NULL";
                case "postgres" -> "ALTER COLUMN " + column + " DROP NOT NULL";
                case "mysql" -> "MODIFY " + column + " DATETIME NULL";
                case "sqlserver" -> "ALTER COLUMN " + column + " DATETIME NULL";
                case "oracle" -> "MODIFY (" + column + " NULL)";
                default -> throw new MigrationException("Unsupported Sentinel database " + getDatabaseType());
            };
            try (PreparedStatement statement = getConnection().prepareStatement(
                    "ALTER TABLE sentinel_maintenance_window " + change)) {
                statement.executeUpdate();
            }
        } catch (MigrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MigrationException("Failed to repair Sentinel window bound " + column, e);
        }
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
     * normalized across relational tables, not stored as blobs.</p>
     */
    @Override
    public void migrateSerializedData() throws MigrationException {
        // No serialized data migration needed
    }

    // ========== Uninstall ==========

    /**
     * All live Sentinel tables, with foreign-key dependents before their parents.
     * In particular, trigger state references both monitor and alert event, and
     * dispatch log references alert event and action. Dropping a table also
     * removes its indexes, constraints, and owned identity/serial sequences.
     */
    private static final String[] UNINSTALL_TABLES = {
            "sentinel_action_dispatch_log",
            "sentinel_trigger_state",
            "sentinel_alert_event",
            "sentinel_connector_status_event",
            "sentinel_channel_presence",
            "sentinel_channel_activity_trend",
            "sentinel_channel_activity_sample",
            "sentinel_action",
            "sentinel_maintenance_window",
            "sentinel_monitor",
            "sentinel_node_lease"};

    /**
     * {@inheritDoc}
     *
     * <p>Explicit uninstall permanently removes Sentinel configuration and
     * history. Normal installation and upgrade use {@link #migrate()} and do
     * not call this hook. The engine queues these statements for the next
     * startup; uninstall followed by install before that startup still deletes
     * the old schema before installing a fresh one.</p>
     *
     * <p>The uninstall path supplies the database type but no connection, so
     * this method only emits SQL. {@code DROP TABLE} works on every supported
     * database, including Derby. Oracle additionally uses {@code PURGE} so the
     * tables are not retained in its recycle bin. No cascading drop is used:
     * only the named live Sentinel tables are targets. Archives produced by
     * older releases are outside this list.</p>
     *
     * <p>The engine executes each statement independently with errors ignored
     * ({@code DatabaseUtil.executeScript(script, true)}). Missing tables after
     * a partial or repeated uninstall therefore do not prevent the remaining
     * tables from being removed. The engine also removes CONFIGURATION
     * properties under {@link #PLUGIN_NAME}, including {@code schema_version}.
     * Errors such as insufficient privileges or external foreign keys still
     * require operator attention; there is no automatic retry after startup.</p>
     *
     * @return table drops in foreign-key dependency order
     */
    @Override
    public List<String> getUninstallStatements() {
        boolean oracle = "oracle".equalsIgnoreCase(getDatabaseType());
        List<String> statements = new ArrayList<>(UNINSTALL_TABLES.length);
        for (String table : UNINSTALL_TABLES) {
            statements.add("DROP TABLE " + table + (oracle ? " PURGE" : ""));
        }
        log.info("Sentinel uninstall: queued removal of all {} live tables and their data on next startup",
                UNINSTALL_TABLES.length);
        return statements;
    }
}
