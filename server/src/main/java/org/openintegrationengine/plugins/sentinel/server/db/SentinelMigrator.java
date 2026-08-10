/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * </ul>
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
    public static final int LATEST_VERSION = 6;

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
        int current = detectAndAlignSchemaVersion();
        if (current < 1) {
            applyV1();
        }
        if (current < 2) {
            applyV2();
        }
        if (current < 3) {
            applyV3();
        }
        if (current < 4) {
            applyV4();
        }
        if (current < 5) {
            applyV5();
        }
        if (current < 6) {
            applyV6();
        }
        writeSchemaVersion(LATEST_VERSION);
        log.info("Sentinel schema at version {}", LATEST_VERSION);
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
     *         connector-status prune index present)
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
        Connection conn = getConnection();
        DatabaseMetaData meta = conn.getMetaData();
        for (String tableCandidate : namingCandidates(tableName)) {
            try (ResultSet rs = meta.getIndexInfo(null, null, tableCandidate, false, true)) {
                while (rs.next()) {
                    String found = rs.getString("INDEX_NAME");
                    if (found != null && found.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // Unknown table in this case form; try the next candidate.
                log.trace("getIndexInfo failed for table candidate {}", tableCandidate, e);
            }
        }
        return false;
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
            "sentinel_channel_activity_trend",
            "sentinel_channel_activity_sample",
            "sentinel_action",
            "sentinel_maintenance_window",
            "sentinel_monitor",
            "sentinel_node_lease"};

    /**
     * How many same-day rename targets to offer per table: the bare
     * {@code _uninstalled_<yyyyMMdd>} name plus {@code _2} and {@code _3}. See
     * {@link #getUninstallStatements()} for why a counter has to be expressed
     * as alternatives rather than computed by probing the database.
     */
    private static final int RENAME_ATTEMPTS = 3;

    /**
     * {@inheritDoc}
     *
     * <p>Renames the plugin's tables to
     * {@code sentinel_<table>_uninstalled_<yyyyMMdd>} instead of dropping them.
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
     * the alternatives work: for each table this emits the plain dated name
     * first, then {@code _2} and {@code _3}. On a first uninstall the first
     * statement succeeds and the other two fail harmlessly because the source
     * table no longer exists; on a repeat uninstall the same day the first
     * fails because its target already exists and the next one takes over.
     * Expect a handful of ignored errors in the startup log after any
     * uninstall — they are the mechanism, not a fault.</p>
     *
     * <h3>Derby returns nothing at all</h3>
     *
     * <p>Derby cannot rename a table that another table's foreign key
     * references (SQLSTATE X0Y25), and three of ours are referenced:
     * {@code sentinel_monitor}, {@code sentinel_alert_event} and
     * {@code sentinel_action}. The blocking constraints were created inline via
     * {@code REFERENCES} and therefore carry system-generated names
     * ({@code SQL240806...}) that a statically emitted script cannot drop.
     * Renaming only the six that Derby permits would be actively harmful: the
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
     * @return rename statements in the current vendor's dialect, or an empty
     *         list on Derby and for an unrecognized vendor — never DROP
     */
    @Override
    public List<String> getUninstallStatements() {
        String databaseType = getDatabaseType();
        String suffix = "_uninstalled_" + LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.BASIC_ISO_DATE);

        List<String> statements = new ArrayList<>();
        for (String table : UNINSTALL_TABLES) {
            for (int attempt = 1; attempt <= RENAME_ATTEMPTS; attempt++) {
                String target = table + suffix + (attempt == 1 ? "" : "_" + attempt);
                String statement = renameStatement(databaseType, table, target);
                if (statement != null) {
                    statements.add(statement);
                }
            }
        }

        if (statements.isEmpty()) {
            log.info("Sentinel uninstall: leaving all tables in place under their current names "
                    + "(database type '{}' cannot rename them safely); drop them manually if the data is not wanted",
                    databaseType);
        } else {
            log.info("Sentinel uninstall: renaming {} tables to *{} rather than dropping them; "
                    + "drop them manually once the data is no longer needed",
                    UNINSTALL_TABLES.length, suffix);
        }
        return statements;
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
