/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.sentinel.server.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
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
    public static final int LATEST_VERSION = 2;

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
     * installs and manual edits to {@code CONFIGURATION}. (Note the
     * uninstall-then-reinstall case is NOT one of these: on uninstall the engine
     * drops the plugin's tables AND, on the next startup, clears its CONFIGURATION
     * properties — including {@code schema_version} — so that path already starts
     * clean.)</p>
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
        writeSchemaVersion(LATEST_VERSION);
        log.info("Sentinel schema at version {}", LATEST_VERSION);
    }

    /**
     * Detects the actual schema version from {@link DatabaseMetaData} (table
     * existence) and writes it to the {@code schema_version} property if it
     * differs from what's stored.
     *
     * @return the detected current version (0 = fresh install, 1 = all nine
     *         tables present, 2 = window mode/recurrence columns present)
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
            // v2 is column-detected: window_mode is the version's first ALTER, so its
            // presence means applyV2 at least started. A partial v2 (window_mode present,
            // later columns missing) would mis-detect as complete — acceptable because
            // every ALTER in the script is idempotent to re-run manually and the failed
            // migrate() already surfaced loudly at startup.
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

    /** Adds the maintenance-window mode/recurrence columns (see class Javadoc). */
    private void applyV2() throws MigrationException {
        log.info("Applying Sentinel schema v2 (maintenance-window modes and recurrence)");
        executeScript("/" + getDatabaseType() + "-sentinel-v2.sql");
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
     * different JDBC drivers normalise to. v2's detection signal
     * ({@code sentinel_maintenance_window.window_mode}).
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

    /**
     * {@inheritDoc}
     * @return DROP TABLE statements in child-first order: {@code sentinel_action_dispatch_log},
     *         {@code sentinel_alert_event}, {@code sentinel_trigger_state},
     *         {@code sentinel_connector_status_event}, {@code sentinel_channel_activity_trend},
     *         {@code sentinel_channel_activity_sample}, {@code sentinel_action},
     *         {@code sentinel_maintenance_window}, then {@code sentinel_monitor} last —
     *         everything else FK-references it, directly or via {@code sentinel_alert_event}.
     *         On uninstall the engine records the plugin name in
     *         {@code extension_uninstall.properties} and, on the next startup,
     *         {@code removePropertiesForUninstalledExtensions()} DELETES every CONFIGURATION
     *         property under the plugin's group name — which includes our {@code schema_version}
     *         (persisted under {@link #PLUGIN_NAME}). So an uninstall+restart already yields a
     *         clean slate; it is the ONLY property the plugin writes.
     *         {@link #detectAndAlignSchemaVersion()} still earns its keep for the other cases —
     *         pre-versioning installs and manual CONFIGURATION edits — where the stored version
     *         cannot be trusted.
     */
    @Override
    public List<String> getUninstallStatements() {
        List<String> statements = new ArrayList<>();
        statements.add("DROP TABLE sentinel_action_dispatch_log");
        statements.add("DROP TABLE sentinel_alert_event");
        statements.add("DROP TABLE sentinel_trigger_state");
        statements.add("DROP TABLE sentinel_connector_status_event");
        statements.add("DROP TABLE sentinel_channel_activity_trend");
        statements.add("DROP TABLE sentinel_channel_activity_sample");
        statements.add("DROP TABLE sentinel_action");
        statements.add("DROP TABLE sentinel_maintenance_window");
        statements.add("DROP TABLE sentinel_monitor");
        return statements;
    }
}
