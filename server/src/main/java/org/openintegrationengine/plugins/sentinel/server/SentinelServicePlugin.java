/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;

import org.openintegrationengine.plugins.sentinel.server.db.SentinelMigrator;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelConnectorStatusListener;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;

/**
 * Sentinel's engine lifecycle hook — the {@code <serverClasses>} entry in
 * plugin.xml. Owns exactly two runtime resources: the connector-status
 * {@code EventListener} and the private Quartz scheduler, wired up in
 * {@link #start()} and torn down in {@link #stop()}. Everything else in the
 * plugin is stateless (static services/repositories) or a self-managing
 * singleton, so there is deliberately nothing else to manage here.
 *
 * <p>No XStream {@code allowTypes} registration happens here, unlike the
 * RBAC precedent: Sentinel's REST layer uses the raw-JSON pattern (every
 * servlet method returns a Jackson-produced {@code String}), so no Sentinel
 * DTO ever crosses the engine's XStream serialization boundary.</p>
 *
 * <p>Permission wiring: {@link #getExtensionPermissions()} is invoked by the
 * extension controller once, after every plugin's {@code start()} has run,
 * and feeds the authorization controller's permission map. The operation
 * names are harvested reflectively from the {@code @MirthOperation}
 * annotations on {@link SentinelServletInterface} so the servlet interface
 * remains the single source of truth — adding an endpoint with an existing
 * permission never requires touching this class. Task names are what the
 * web UI's permission editor groups under each permission.</p>
 */
public class SentinelServicePlugin implements ServicePlugin {

    private static final Logger log = LoggerFactory.getLogger(SentinelServicePlugin.class);

    /**
     * Kept as a field because the exact instance registered with the event
     * controller in {@link #start()} must be handed back to
     * {@code removeListener} in {@link #stop()} — the controller keys its
     * queues by listener identity.
     */
    private SentinelConnectorStatusListener connectorStatusListener;

    /**
     * True once {@link #start()} has confirmed the Sentinel schema is
     * installed. Everything downstream keys off this: when the extension
     * migration fails at startup the engine still calls {@code start()} and
     * still mounts the REST servlet, so without this gate the scheduler,
     * listener, and every API call would hammer the database with
     * "table does not exist" errors on every tick (observed in the field on
     * the first Derby deploy). Static because {@code SentinelServlet} is
     * instantiated per-request by Jersey and needs a cheap check.
     */
    private static volatile boolean schemaReady;

    /** Whether the schema migration has completed successfully on this server. */
    public static boolean isSchemaReady() {
        return schemaReady;
    }

    /**
     * Must return exactly plugin.xml's {@code <name>} ("OIE Sentinel") —
     * the engine keys the service-plugin map, extension permissions, and
     * the servlet's {@code ExtensionOperation} names by this string, so a
     * mismatch silently breaks permission enforcement.
     */
    @Override
    public String getPluginPointName() {
        return SentinelServletInterface.PLUGIN_POINT;
    }

    /**
     * No-op: Sentinel keeps its configuration in its own settings store
     * (CONFIGURATION table via {@code SettingsService}), not in the
     * extension-properties mechanism this hook serves.
     */
    @Override
    public void init(Properties properties) {
        log.debug("SentinelServicePlugin init (no extension properties used)");
    }

    /**
     * No-op for the same reason as {@link #init(Properties)}; logged so an
     * operator poking the extension-properties API can see the call arrived
     * and was deliberately ignored.
     */
    @Override
    public void update(Properties properties) {
        log.debug("SentinelServicePlugin update ignored (no extension properties used)");
    }

    /** Empty: see {@link #init(Properties)} — Sentinel does not use extension properties. */
    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    /**
     * Starts the two runtime resources: the connector-status listener first
     * (so no state transitions are missed while the scheduler spins up),
     * then the scheduler with the persisted settings.
     *
     * <p>The whole body is guarded: a failure here (typically the settings
     * read against an unavailable database) must degrade Sentinel to "not
     * monitoring" rather than disrupt engine startup — a monitoring plugin
     * that takes the integration engine down with it would be worse than
     * the outages it exists to catch.</p>
     */
    @Override
    public void start() {
        try {
            schemaReady = checkSchemaInstalled();
            if (!schemaReady) {
                log.error("Sentinel schema is not installed — the extension migration failed at engine "
                        + "startup (see the migration error earlier in this log). The collector, evaluator, "
                        + "connector-status listener, and REST API stay disabled until the migration "
                        + "completes on a future startup.");
                return;
            }

            connectorStatusListener = new SentinelConnectorStatusListener();
            ControllerFactory.getFactory().createEventController().addListener(connectorStatusListener);

            SentinelScheduler.getInstance().start(SettingsService.get());

            log.info("OIE Sentinel started");
        } catch (Exception e) {
            log.error("OIE Sentinel failed to start; monitoring is inactive until the server is restarted", e);
        }
    }

    /**
     * Reads the schema version the migrator persisted. {@code SentinelMigrator}
     * re-detects the actual table state and re-aligns this property on every
     * startup (before plugins start), so a null or stale value here reliably means
     * the tables are absent — whether because the migration script failed or
     * an operator dropped them by hand.
     */
    private static boolean checkSchemaInstalled() {
        try {
            String raw = ConfigurationController.getInstance()
                    .getProperty(SentinelMigrator.PLUGIN_NAME, SentinelMigrator.VERSION_PROPERTY);
            return raw != null && Integer.parseInt(raw.trim()) >= SentinelMigrator.LATEST_VERSION;
        } catch (Exception e) {
            log.error("Could not determine whether the Sentinel schema is installed; leaving Sentinel disabled", e);
            return false;
        }
    }

    /**
     * Tears down in reverse start order: scheduler first so no job tick can
     * race the listener removal, then the listener
     * ({@code removeListener} also shuts down its consumer thread). Each
     * step is independently guarded so a failure in one never leaks the
     * other resource.
     */
    @Override
    public void stop() {
        try {
            SentinelScheduler.getInstance().shutdown();
        } catch (Exception e) {
            log.warn("Failed to shut down the Sentinel scheduler", e);
        }

        if (connectorStatusListener != null) {
            try {
                EventController eventController = ControllerFactory.getFactory().createEventController();
                eventController.removeListener(connectorStatusListener);
            } catch (Exception e) {
                log.warn("Failed to remove the Sentinel connector status listener", e);
            } finally {
                connectorStatusListener = null;
            }
        }

        log.info("OIE Sentinel stopped");
    }

    /**
     * Declares Sentinel's five permissions — View / Acknowledge /
     * Maintenance / Manage / Settings — with their REST operations resolved
     * from the servlet interface's annotations (see class Javadoc). Deliberately has no dependency on
     * {@link #start()} having succeeded: the extension controller calls this
     * exactly once after all plugin starts, and permissions must register
     * even when Sentinel started degraded, so the UI still renders and
     * reports the problem instead of hard-403ing everyone.
     */
    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
                new ExtensionPermission(
                        SentinelServletInterface.PLUGIN_POINT,
                        SentinelServletInterface.PERMISSION_VIEW,
                        "Displays Sentinel monitors, problems, dashboards and channel activity.",
                        OperationUtil.getOperationNamesForPermission(
                                SentinelServletInterface.PERMISSION_VIEW, SentinelServletInterface.class),
                        new String[] { SentinelServletInterface.TASK_SHOW }),
                new ExtensionPermission(
                        SentinelServletInterface.PLUGIN_POINT,
                        SentinelServletInterface.PERMISSION_ACKNOWLEDGE,
                        "Allows acknowledging and manually resolving Sentinel problems.",
                        OperationUtil.getOperationNamesForPermission(
                                SentinelServletInterface.PERMISSION_ACKNOWLEDGE, SentinelServletInterface.class),
                        new String[] { SentinelServletInterface.TASK_ACKNOWLEDGE }),
                new ExtensionPermission(
                        SentinelServletInterface.PLUGIN_POINT,
                        SentinelServletInterface.PERMISSION_MAINTENANCE,
                        "Allows creating, modifying and activating Sentinel maintenance windows.",
                        OperationUtil.getOperationNamesForPermission(
                                SentinelServletInterface.PERMISSION_MAINTENANCE, SentinelServletInterface.class),
                        new String[] { SentinelServletInterface.TASK_MAINTENANCE }),
                new ExtensionPermission(
                        SentinelServletInterface.PLUGIN_POINT,
                        SentinelServletInterface.PERMISSION_MANAGE,
                        "Allows creating and modifying Sentinel monitors and actions, "
                                + "including test sends.",
                        OperationUtil.getOperationNamesForPermission(
                                SentinelServletInterface.PERMISSION_MANAGE, SentinelServletInterface.class),
                        new String[] { SentinelServletInterface.TASK_MANAGE }),
                new ExtensionPermission(
                        SentinelServletInterface.PLUGIN_POINT,
                        SentinelServletInterface.PERMISSION_SETTINGS,
                        "Allows changing Sentinel settings: scheduler intervals, retention, "
                                + "and alert delivery credentials.",
                        OperationUtil.getOperationNamesForPermission(
                                SentinelServletInterface.PERMISSION_SETTINGS, SentinelServletInterface.class),
                        new String[] { SentinelServletInterface.TASK_SETTINGS }) };
    }
}
