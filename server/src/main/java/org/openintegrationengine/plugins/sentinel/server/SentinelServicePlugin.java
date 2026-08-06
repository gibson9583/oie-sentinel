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

import org.openintegrationengine.plugins.sentinel.server.alert.ActionDispatcher;
import org.openintegrationengine.plugins.sentinel.server.db.SentinelMigrator;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelConnectorStatusListener;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelLeadership;
import org.openintegrationengine.plugins.sentinel.server.engine.SentinelScheduler;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;

/**
 * Sentinel's engine lifecycle hook — the {@code <serverClasses>} entry in
 * plugin.xml. Owns exactly four runtime resources: the connector-status
 * {@code EventListener}, the leader-lease heartbeat, the private Quartz
 * scheduler, and the alert dispatch thread pool, wired up in {@link #start()}
 * and torn down in {@link #stop()}. Everything else in the plugin is
 * stateless (static services/repositories) or a self-managing singleton, so
 * there is deliberately nothing else to manage here.
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
     * Starts the four runtime resources: the alert dispatch pool first (the
     * scheduler's evaluator hands every notification to it), then the
     * connector-status listener (so no state transitions are missed while the
     * scheduler spins up), then the leader-lease heartbeat, then the scheduler
     * with the persisted settings.
     *
     * <p>The heartbeat must precede the scheduler, and not merely by
     * convention: the job bodies ask {@link SentinelLeadership} whether this
     * node may work, and a scheduler started first would fire its opening
     * collector and evaluator ticks against a subsystem that had not yet
     * decided. {@code startHeartbeat} settles the first acquire-or-renew
     * synchronously before returning, so by the time the scheduler exists
     * every tick has a real answer to consult.</p>
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

            probeSnsClasspath();

            // Before the scheduler: the evaluator hands every notification to
            // this pool, and a tick that found no pool would drop its alerts.
            ActionDispatcher.startDispatchExecutor();

            connectorStatusListener = new SentinelConnectorStatusListener();
            ControllerFactory.getFactory().createEventController().addListener(connectorStatusListener);

            // Before the scheduler: the job bodies consult this to decide
            // whether this node is the one that does the work.
            SentinelLeadership.startHeartbeat();

            SentinelScheduler.getInstance().start(SettingsService.get());

            log.info("OIE Sentinel started");
        } catch (Exception e) {
            log.error("OIE Sentinel failed to start; monitoring is inactive until the server is restarted", e);
        }
    }

    /**
     * Verifies at startup that the AWS SDK classes the SNS action needs are
     * actually reachable, and says so loudly if they are not.
     *
     * <p>Sentinel bundles only {@code sns} and relies on the engine's
     * {@code server-lib/aws/} for the SDK core and {@code apache-client}
     * (see the AWS block in {@code server/pom.xml}). If that assumption ever
     * breaks — an engine repackaging, a stripped distribution — the natural
     * failure point is deep inside {@code SnsAlertSender} at the moment an
     * alert fires, which is the worst possible time for a monitoring plugin
     * to discover a missing class. Loading the two entry points here converts
     * that into one actionable line in the startup log.</p>
     *
     * <p>Deliberately non-fatal: SNS is one of three transports, so a missing
     * SDK must not stop the collector, the evaluator, or email and channel
     * alerting from working.</p>
     */
    private static void probeSnsClasspath() {
        // Class.forName over a direct reference: this must report the problem,
        // not become another site that throws NoClassDefFoundError.
        String missing = null;
        for (String className : new String[] {
                "software.amazon.awssdk.services.sns.SnsClient",
                "software.amazon.awssdk.http.apache.ApacheHttpClient" }) {
            try {
                Class.forName(className, false, SentinelServicePlugin.class.getClassLoader());
            } catch (Throwable t) {
                missing = className;
                break;
            }
        }
        if (missing != null) {
            log.error("AWS SDK class {} is not on the extension classpath — SNS alert actions will fail "
                    + "when they fire. Sentinel bundles only sns-*.jar and expects the SDK core and "
                    + "apache-client from the engine's server-lib/aws/ directory; check that this engine "
                    + "build ships them. Email and channel actions are unaffected.", missing);
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
     * race the listener removal, then the leader-lease heartbeat, then the
     * listener ({@code removeListener} also shuts down its consumer thread),
     * then the alert dispatch pool. The pool goes last on purpose — with the
     * evaluator already stopped nothing is producing notifications any more,
     * so its short drain window is spent delivering the final tick's alerts
     * rather than chasing new arrivals. Each step is independently guarded so
     * a failure in one never leaks the other resources.
     *
     * <p>The heartbeat's position is the mirror of its position in
     * {@link #start()}, and load-bearing for the same reason: the scheduler's
     * shutdown waits for any in-flight job, so by the time the lease is
     * released no tick is left that could still believe this node leads. The
     * release is what lets a peer pick the work up within a heartbeat instead
     * of waiting out the lease — the difference between a rolling restart
     * costing seconds of monitoring and costing minutes.</p>
     */
    @Override
    public void stop() {
        try {
            SentinelScheduler.getInstance().shutdown();
        } catch (Exception e) {
            log.warn("Failed to shut down the Sentinel scheduler", e);
        }

        try {
            SentinelLeadership.stopHeartbeat();
        } catch (Exception e) {
            log.warn("Failed to stop the Sentinel leadership heartbeat", e);
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

        try {
            ActionDispatcher.shutdownDispatchExecutor();
        } catch (Exception e) {
            log.warn("Failed to shut down the Sentinel alert dispatch executor", e);
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
