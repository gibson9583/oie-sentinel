/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.shared;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST contract for OIE Sentinel, mounted at {@code /api/extensions/sentinel}
 * and consumed by the embedded web-administrator dashboard.
 *
 * <p><b>Wire format — raw JSON strings, deliberately.</b> Every method
 * returns a Jackson-produced JSON {@code String} and receives request bodies
 * as raw {@code String} parameters, following the proven
 * {@code oie-community-store} pattern. The engine's own JSON providers are
 * XStream/StaxON-derived (root-key wrapping, single-element-list collapse,
 * XStream security allow-listing of every DTO class); returning raw strings
 * keeps Sentinel's types out of that pipeline entirely and hands the web
 * frontend clean, predictable JSON. Even void-shaped mutations return a
 * {@code String} ({@code "{}"} or the updated entity) so the client can treat
 * every call uniformly.</p>
 *
 * <p><b>Permissions.</b> Five tiers, wider than the usual view/manage pair
 * so RBAC roles can mirror real operational roles: {@link #PERMISSION_VIEW}
 * gates all reads, {@link #PERMISSION_ACKNOWLEDGE} gates problem
 * acknowledge/resolve (NOC operator), {@link #PERMISSION_MAINTENANCE} gates
 * maintenance-window mutation incl. "activate now" (on-call — silencing
 * alerts mid-incident must not require monitor-authoring rights),
 * {@link #PERMISSION_MANAGE} gates monitor/action mutation and test sends,
 * and {@link #PERMISSION_SETTINGS} gates settings mutation (scheduler,
 * retention, delivery credentials — administration, not authoring). The
 * engine applies the gate declared on each {@code @MirthOperation} before the
 * servlet implementation runs; without an authorization plugin (e.g. RBAC)
 * the default controller permits everything.</p>
 *
 * <p><b>Auditing.</b> Mutations keep {@code @MirthOperation}'s default
 * {@code auditable = true} for a free framework-level audit trail; reads and
 * {@link #testMonitor(String)} (a read-only dry run despite being a POST) are
 * {@code auditable = false} to keep the event log signal-bearing. Body
 * parameters are excluded from the audit event ({@code excludeFromAudit})
 * because they can carry secrets (SNS keys) or large JSON blobs.</p>
 */
@Path("/extensions/sentinel")
@Tag(name = "OIE Sentinel")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SentinelServletInterface extends BaseServletInterface {

    /**
     * The plugin's registered name — must equal plugin.xml's {@code <name>}
     * exactly, because the engine keys extension permissions and the
     * {@code ExtensionOperation} composite operation name
     * ({@code "OIE Sentinel#sentinelGetMonitors"}) by it.
     */
    String PLUGIN_POINT = "OIE Sentinel";

    /** Extension permission gating every read (lists, detail, dashboard, activity). */
    String PERMISSION_VIEW = "View Monitoring";

    /** Extension permission gating problem acknowledge/resolve (NOC-operator tier). */
    String PERMISSION_ACKNOWLEDGE = "Acknowledge Problems";

    /** Extension permission gating monitor/action mutation and test sends. */
    String PERMISSION_MANAGE = "Manage Monitoring";

    /**
     * Extension permission gating maintenance-window mutation, including
     * "activate now". Split from {@link #PERMISSION_MANAGE} for the on-call
     * tier: an operator silencing alerts mid-incident should not need the
     * right to reconfigure monitors or actions.
     */
    String PERMISSION_MAINTENANCE = "Manage Maintenance Windows";

    /**
     * Extension permission gating settings mutation (scheduler intervals,
     * retention, mail/SNS delivery credentials). Split from
     * {@link #PERMISSION_MANAGE} because settings are administration —
     * authoring monitors must not imply the right to re-point alert
     * delivery or touch stored credentials.
     */
    String PERMISSION_SETTINGS = "Manage Settings";

    /**
     * Task name gating the Sentinel nav item in the web administrator.
     * Registered via {@code ExtensionPermission}'s taskNames so RBAC can map
     * it; deliberately prefixed "Sentinel" because task registration across
     * plugins is last-writer-wins on bare names.
     */
    String TASK_SHOW = "doShowSentinel";

    /** Task name gating the acknowledge/resolve controls in the Problems view. */
    String TASK_ACKNOWLEDGE = "doAcknowledgeSentinelProblem";

    /** Task name gating the monitor/action editors in the web UI. */
    String TASK_MANAGE = "doManageSentinel";

    /** Task name gating the maintenance-window editor and "activate now" in the web UI. */
    String TASK_MAINTENANCE = "doManageSentinelMaintenance";

    /** Task name gating the settings editor in the web UI. */
    String TASK_SETTINGS = "doManageSentinelSettings";

    // ========== Monitors ==========

    /**
     * Lists every monitor definition.
     *
     * @return JSON array of Monitor
     * @throws ClientException if the underlying persistence call fails
     */
    @GET
    @Path("/monitors")
    @Operation(summary = "Returns all monitor definitions")
    @MirthOperation(name = "sentinelGetMonitors", display = "Get Sentinel monitors", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getMonitors() throws ClientException;

    /**
     * Fetches a single monitor by id.
     *
     * @param id database id of the monitor
     * @return JSON Monitor
     * @throws ClientException if no monitor exists with that id or persistence fails
     */
    @GET
    @Path("/monitors/{id}")
    @Operation(summary = "Returns a single monitor definition")
    @MirthOperation(name = "sentinelGetMonitor", display = "Get Sentinel monitor", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getMonitor(@Param("id") @PathParam("id") int id) throws ClientException;

    /**
     * Creates a new monitor. The body's {@code id} is ignored; the database
     * assigns one.
     *
     * @param monitorJson JSON Monitor to create
     * @return JSON of the created Monitor including its assigned id
     * @throws ClientException on validation failure (bad config JSON,
     *                         duplicate name, suppression cycle) or persistence failure
     */
    @POST
    @Path("/monitors")
    @Operation(summary = "Creates a monitor definition")
    @MirthOperation(name = "sentinelCreateMonitor", display = "Create Sentinel monitor", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC)
    String createMonitor(@Param(value = "body", excludeFromAudit = true) String monitorJson) throws ClientException;

    /**
     * Updates an existing monitor.
     *
     * @param id          database id of the monitor to update
     * @param monitorJson JSON Monitor carrying the new state (its id field is ignored)
     * @return JSON of the updated Monitor
     * @throws ClientException if the monitor doesn't exist, validation fails,
     *                         or persistence fails
     */
    @PUT
    @Path("/monitors/{id}")
    @Operation(summary = "Updates a monitor definition")
    @MirthOperation(name = "sentinelUpdateMonitor", display = "Update Sentinel monitor", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC)
    String updateMonitor(@Param("id") @PathParam("id") int id,
            @Param(value = "body", excludeFromAudit = true) String monitorJson) throws ClientException;

    /**
     * Deletes a monitor and (via FK cascade) its trigger states. Deletion is
     * refused while another monitor references it through
     * {@code suppressedByMonitorId}.
     *
     * @param id database id of the monitor to delete
     * @return {@code "{}"}
     * @throws ClientException if the monitor doesn't exist, is depended upon,
     *                         or persistence fails
     */
    @DELETE
    @Path("/monitors/{id}")
    @Operation(summary = "Deletes a monitor definition")
    @MirthOperation(name = "sentinelRemoveMonitor", display = "Delete Sentinel monitor", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC)
    String removeMonitor(@Param("id") @PathParam("id") int id) throws ClientException;

    /**
     * Enables or disables a monitor without touching the rest of its
     * definition — the dashboard's one-click toggle.
     *
     * @param id      database id of the monitor
     * @param enabled {@code true} to enable, {@code false} to disable
     * @return JSON of the updated Monitor
     * @throws ClientException if the monitor doesn't exist or persistence fails
     */
    @POST
    @Path("/monitors/{id}/_setEnabled")
    @Operation(summary = "Enables or disables a monitor")
    @MirthOperation(name = "sentinelSetMonitorEnabled", display = "Enable/disable Sentinel monitor", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC)
    String setMonitorEnabled(@Param("id") @PathParam("id") int id,
            @Param("enabled") @QueryParam("enabled") boolean enabled) throws ClientException;

    /**
     * Dry-runs a monitor definition against its currently started target
     * channels without persisting anything — lets an operator sanity-check a
     * rule before saving it. Not auditable: it is a read-only evaluation
     * despite the POST verb (the body is a definition, not an id).
     *
     * @param monitorJson JSON Monitor to evaluate (need not be persisted)
     * @return JSON MonitorTestResult with one outcome per resolved channel
     * @throws ClientException on validation failure or internal error
     */
    @POST
    @Path("/monitors/_test")
    @Operation(summary = "Evaluates a monitor definition against its target channels without saving it")
    @MirthOperation(name = "sentinelTestMonitor", display = "Test Sentinel monitor", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC, auditable = false)
    String testMonitor(@Param(value = "body", excludeFromAudit = true) String monitorJson) throws ClientException;

    // ========== Actions ==========

    /**
     * Lists every alert action. Secret material in the action config (SNS
     * secret keys) is redacted server-side before serialization.
     *
     * @return JSON array of Action with redacted config
     * @throws ClientException if the underlying persistence call fails
     */
    @GET
    @Path("/actions")
    @Operation(summary = "Returns all alert actions with secrets redacted")
    @MirthOperation(name = "sentinelGetActions", display = "Get Sentinel actions", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getActions() throws ClientException;

    /**
     * Fetches a single alert action by id, with secrets redacted.
     *
     * @param id database id of the action
     * @return JSON Action with redacted config
     * @throws ClientException if no action exists with that id or persistence fails
     */
    @GET
    @Path("/actions/{id}")
    @Operation(summary = "Returns a single alert action with secrets redacted")
    @MirthOperation(name = "sentinelGetAction", display = "Get Sentinel action", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getAction(@Param("id") @PathParam("id") int id) throws ClientException;

    /**
     * Creates a new alert action. Secrets in the config are encrypted at rest
     * before persisting; the response is redacted.
     *
     * @param actionJson JSON Action to create
     * @return JSON of the created Action (redacted) including its assigned id
     * @throws ClientException on validation failure or persistence failure
     */
    @POST
    @Path("/actions")
    @Operation(summary = "Creates an alert action")
    @MirthOperation(name = "sentinelCreateAction", display = "Create Sentinel action", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC)
    String createAction(@Param(value = "body", excludeFromAudit = true) String actionJson) throws ClientException;

    /**
     * Updates an existing alert action. A secret field equal to the redaction
     * marker means "keep the stored secret", so a round-tripped redacted GET
     * response saves cleanly.
     *
     * @param id         database id of the action to update
     * @param actionJson JSON Action carrying the new state
     * @return JSON of the updated Action (redacted)
     * @throws ClientException if the action doesn't exist, validation fails,
     *                         or persistence fails
     */
    @PUT
    @Path("/actions/{id}")
    @Operation(summary = "Updates an alert action")
    @MirthOperation(name = "sentinelUpdateAction", display = "Update Sentinel action", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC)
    String updateAction(@Param("id") @PathParam("id") int id,
            @Param(value = "body", excludeFromAudit = true) String actionJson) throws ClientException;

    /**
     * Deletes an alert action. Existing dispatch-log rows survive (their FK
     * is ON DELETE SET NULL) so alert history stays intact.
     *
     * @param id database id of the action to delete
     * @return {@code "{}"}
     * @throws ClientException if the action doesn't exist or persistence fails
     */
    @DELETE
    @Path("/actions/{id}")
    @Operation(summary = "Deletes an alert action")
    @MirthOperation(name = "sentinelRemoveAction", display = "Delete Sentinel action", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC)
    String removeAction(@Param("id") @PathParam("id") int id) throws ClientException;

    /**
     * Sends a real test notification through a persisted action (synthetic
     * payload, real delivery) so an operator can verify SMTP/SNS/channel
     * wiring before a 3 AM page depends on it. Auditable: it produces an
     * externally visible side effect.
     *
     * @param id database id of the action to test
     * @return JSON ActionTestResult with the delivery outcome
     * @throws ClientException if the action doesn't exist or internal error
     */
    @POST
    @Path("/actions/{id}/_test")
    @Operation(summary = "Sends a test notification through an alert action")
    @MirthOperation(name = "sentinelTestAction", display = "Test Sentinel action", permission = PERMISSION_MANAGE, type = ExecuteType.ASYNC)
    String testAction(@Param("id") @PathParam("id") int id) throws ClientException;

    // ========== Maintenance windows ==========

    /**
     * Lists every maintenance window.
     *
     * @return JSON array of MaintenanceWindow
     * @throws ClientException if the underlying persistence call fails
     */
    @GET
    @Path("/maintenanceWindows")
    @Operation(summary = "Returns all maintenance windows")
    @MirthOperation(name = "sentinelGetMaintenanceWindows", display = "Get Sentinel maintenance windows", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getMaintenanceWindows() throws ClientException;

    /**
     * Fetches a single maintenance window by id.
     *
     * @param id database id of the window
     * @return JSON MaintenanceWindow
     * @throws ClientException if no window exists with that id or persistence fails
     */
    @GET
    @Path("/maintenanceWindows/{id}")
    @Operation(summary = "Returns a single maintenance window")
    @MirthOperation(name = "sentinelGetMaintenanceWindow", display = "Get Sentinel maintenance window", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getMaintenanceWindow(@Param("id") @PathParam("id") int id) throws ClientException;

    /**
     * Creates a new maintenance window. Alerts raised for channels covered by
     * an active window are suppressed (recorded but not dispatched).
     *
     * @param windowJson JSON MaintenanceWindow to create
     * @return JSON of the created MaintenanceWindow including its assigned id
     * @throws ClientException on validation failure or persistence failure
     */
    @POST
    @Path("/maintenanceWindows")
    @Operation(summary = "Creates a maintenance window")
    @MirthOperation(name = "sentinelCreateMaintenanceWindow", display = "Create Sentinel maintenance window", permission = PERMISSION_MAINTENANCE, type = ExecuteType.ASYNC)
    String createMaintenanceWindow(@Param(value = "body", excludeFromAudit = true) String windowJson) throws ClientException;

    /**
     * Updates an existing maintenance window.
     *
     * @param id         database id of the window to update
     * @param windowJson JSON MaintenanceWindow carrying the new state
     * @return JSON of the updated MaintenanceWindow
     * @throws ClientException if the window doesn't exist, validation fails,
     *                         or persistence fails
     */
    @PUT
    @Path("/maintenanceWindows/{id}")
    @Operation(summary = "Updates a maintenance window")
    @MirthOperation(name = "sentinelUpdateMaintenanceWindow", display = "Update Sentinel maintenance window", permission = PERMISSION_MAINTENANCE, type = ExecuteType.ASYNC)
    String updateMaintenanceWindow(@Param("id") @PathParam("id") int id,
            @Param(value = "body", excludeFromAudit = true) String windowJson) throws ClientException;

    /**
     * Deletes a maintenance window.
     *
     * @param id database id of the window to delete
     * @return {@code "{}"}
     * @throws ClientException if the window doesn't exist or persistence fails
     */
    @DELETE
    @Path("/maintenanceWindows/{id}")
    @Operation(summary = "Deletes a maintenance window")
    @MirthOperation(name = "sentinelRemoveMaintenanceWindow", display = "Delete Sentinel maintenance window", permission = PERMISSION_MAINTENANCE, type = ExecuteType.ASYNC)
    String removeMaintenanceWindow(@Param("id") @PathParam("id") int id) throws ClientException;

    /**
     * Starts a maintenance window immediately for the given duration — the
     * "silence this now, we're deploying" button. Rewrites the window's
     * active range to [now, now + durationMinutes] and enables it.
     *
     * @param id              database id of the window to activate
     * @param durationMinutes how long the window should stay active from now
     * @return JSON of the updated MaintenanceWindow
     * @throws ClientException if the window doesn't exist or persistence fails
     */
    @POST
    @Path("/maintenanceWindows/{id}/_activateNow")
    @Operation(summary = "Activates a maintenance window immediately for a fixed duration")
    @MirthOperation(name = "sentinelActivateMaintenanceWindowNow", display = "Activate Sentinel maintenance window now", permission = PERMISSION_MAINTENANCE, type = ExecuteType.ASYNC)
    String activateMaintenanceWindowNow(@Param("id") @PathParam("id") int id,
            @Param("durationMinutes") @QueryParam("durationMinutes") @DefaultValue("60") int durationMinutes) throws ClientException;

    // ========== Problems ==========

    /**
     * Lists alert events (problems) with filtering, sorting, and pagination —
     * the Problems grid's single backing query. CSV-valued filters
     * ({@code severity}, {@code channelId}) are split server-side; timestamps
     * are epoch millis because query params have no Instant coercion.
     *
     * @param status       filter on AlertStatus name (PROBLEM/RESOLVED), or null for all
     * @param severity     CSV of Severity names, or null for all
     * @param channelId    CSV of channel ids, or null for all
     * @param monitorId    filter on the raising monitor's id, or null for all
     * @param monitorType  filter on MonitorType name, or null for all
     * @param acknowledged filter on acknowledged state, or null for all
     * @param from         earliest openedTime as epoch millis, or null for unbounded
     * @param to           latest openedTime as epoch millis, or null for unbounded
     * @param q            free-text search over message/channel, or null
     * @param sort         sort column (validated against an allow-list server-side), or null for default
     * @param sortDir      "asc" or "desc", or null for default
     * @param page         zero-based page index
     * @param pageSize     rows per page
     * @return JSON PagedResult of AlertEvent ({items, total, page, pageSize})
     * @throws ClientException on invalid filter values or persistence failure
     */
    @GET
    @Path("/problems")
    @Operation(summary = "Returns a filtered, sorted, paginated list of alert events")
    @MirthOperation(name = "sentinelGetProblems", display = "Get Sentinel problems", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getProblems(@Param("status") @QueryParam("status") String status,
            @Param("severity") @QueryParam("severity") String severity,
            @Param("channelId") @QueryParam("channelId") String channelId,
            @Param("monitorId") @QueryParam("monitorId") Integer monitorId,
            @Param("monitorType") @QueryParam("monitorType") String monitorType,
            @Param("acknowledged") @QueryParam("acknowledged") Boolean acknowledged,
            @Param("from") @QueryParam("from") Long from,
            @Param("to") @QueryParam("to") Long to,
            @Param("q") @QueryParam("q") String q,
            @Param("sort") @QueryParam("sort") String sort,
            @Param("sortDir") @QueryParam("sortDir") String sortDir,
            @Param("page") @QueryParam("page") @DefaultValue("0") int page,
            @Param("pageSize") @QueryParam("pageSize") @DefaultValue("25") int pageSize) throws ClientException;

    /**
     * Fetches one problem with its display context (monitor name/type,
     * channel and connector names, dispatch history) resolved server-side so
     * the detail drawer needs a single round trip.
     *
     * @param id database id of the alert event
     * @return JSON ProblemDetail
     * @throws ClientException if no event exists with that id or persistence fails
     */
    @GET
    @Path("/problems/{id}")
    @Operation(summary = "Returns one alert event with resolved display context and dispatch history")
    @MirthOperation(name = "sentinelGetProblem", display = "Get Sentinel problem", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getProblem(@Param("id") @PathParam("id") long id) throws ClientException;

    /**
     * Acknowledges an open problem — records who has eyes on it without
     * closing it. Re-acknowledging an already-acknowledged problem is
     * rejected.
     *
     * @param id       database id of the alert event
     * @param bodyJson JSON {@code {"comment": "..."}}; comment may be null/empty
     * @return JSON of the updated AlertEvent
     * @throws ClientException if the event doesn't exist, is not an open
     *                         unacknowledged problem, or persistence fails
     */
    @POST
    @Path("/problems/{id}/_acknowledge")
    @Operation(summary = "Acknowledges an open alert event")
    @MirthOperation(name = "sentinelAcknowledgeProblem", display = "Acknowledge Sentinel problem", permission = PERMISSION_ACKNOWLEDGE, type = ExecuteType.ASYNC)
    String acknowledgeProblem(@Param("id") @PathParam("id") long id,
            @Param(value = "body", excludeFromAudit = true) String bodyJson) throws ClientException;

    /**
     * Manually resolves a problem — for conditions the evaluator can't clear
     * itself (e.g. the monitor was misconfigured). Also resets the owning
     * trigger state so the evaluator doesn't immediately re-open it on
     * hysteresis.
     *
     * @param id       database id of the alert event
     * @param bodyJson JSON {@code {"comment": "..."}}; comment may be null/empty
     * @return JSON of the updated AlertEvent
     * @throws ClientException if the event doesn't exist or persistence fails
     */
    @POST
    @Path("/problems/{id}/_resolve")
    @Operation(summary = "Manually resolves an alert event")
    @MirthOperation(name = "sentinelResolveProblem", display = "Resolve Sentinel problem", permission = PERMISSION_ACKNOWLEDGE, type = ExecuteType.ASYNC)
    String resolveProblem(@Param("id") @PathParam("id") long id,
            @Param(value = "body", excludeFromAudit = true) String bodyJson) throws ClientException;

    /**
     * Acknowledges a batch of problems in one call — the Problems grid's
     * multi-select action. Already-acknowledged ids are skipped silently.
     *
     * @param bodyJson JSON {@code {"ids": [1, 2, ...], "comment": "..."}}
     * @return JSON {@code {"acknowledged": n}} — how many were newly acknowledged
     * @throws ClientException on malformed body or persistence failure
     */
    @POST
    @Path("/problems/_bulkAcknowledge")
    @Operation(summary = "Acknowledges a batch of alert events")
    @MirthOperation(name = "sentinelBulkAcknowledgeProblems", display = "Bulk acknowledge Sentinel problems", permission = PERMISSION_ACKNOWLEDGE, type = ExecuteType.ASYNC)
    String bulkAcknowledgeProblems(@Param(value = "body", excludeFromAudit = true) String bodyJson) throws ClientException;

    // ========== Dashboard & activity ==========

    /**
     * Returns the dashboard's entire top fold (severity counts, top problem
     * channels, monitor health, recent problems, collector heartbeats) in one
     * payload so the landing page renders with a single request.
     *
     * @return JSON DashboardSummary
     * @throws ClientException on persistence failure
     */
    @GET
    @Path("/dashboard/summary")
    @Operation(summary = "Returns the aggregated dashboard summary")
    @MirthOperation(name = "sentinelGetDashboardSummary", display = "Get Sentinel dashboard summary", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getDashboardSummary() throws ClientException;

    /**
     * Returns one channel's message-activity time series for charting.
     * AUTO granularity picks raw collector samples for short ranges and the
     * hourly rollup for long ones, so the client never has to know where the
     * data lives.
     *
     * @param channelId   the channel to chart
     * @param from        range start as epoch millis, or null for the default window
     * @param to          range end as epoch millis, or null for now
     * @param granularity AUTO, RAW, or HOURLY
     * @return JSON ChannelActivity
     * @throws ClientException on invalid range/granularity or persistence failure
     */
    @GET
    @Path("/channels/{channelId}/activity")
    @Operation(summary = "Returns a channel's activity time series")
    @MirthOperation(name = "sentinelGetChannelActivity", display = "Get Sentinel channel activity", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getChannelActivity(@Param("channelId") @PathParam("channelId") String channelId,
            @Param("from") @QueryParam("from") Long from,
            @Param("to") @QueryParam("to") Long to,
            @Param("granularity") @QueryParam("granularity") @DefaultValue("AUTO") String granularity) throws ClientException;

    /**
     * Returns per-channel activity totals plus a compact sparkline series for
     * a set of channels in one call — batched deliberately so the dashboard's
     * channel cards don't issue N+1 activity requests.
     *
     * @param channelIds    CSV of channel ids, or null/empty for all watched channels
     * @param windowSeconds how far back to aggregate
     * @param buckets       number of sparkline points to reduce the window into
     * @return JSON array of ChannelActivitySummary
     * @throws ClientException on persistence failure
     */
    @GET
    @Path("/activity/summary")
    @Operation(summary = "Returns batched per-channel activity totals with sparkline series")
    @MirthOperation(name = "sentinelGetActivitySummary", display = "Get Sentinel activity summary", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getActivitySummary(@Param("channelIds") @QueryParam("channelIds") String channelIds,
            @Param("windowSeconds") @QueryParam("windowSeconds") @DefaultValue("3600") int windowSeconds,
            @Param("buckets") @QueryParam("buckets") @DefaultValue("20") int buckets) throws ClientException;

    // ========== Core passthrough ==========

    /**
     * Returns the engine's channels (id, name, deployed state, started flag)
     * as clean JSON for Sentinel's scope pickers — a passthrough so the web
     * UI doesn't have to normalize the engine's XStream-shaped core API.
     * Channel-restricted users only see channels they are authorized for.
     *
     * @return JSON array of ChannelInfo
     * @throws ClientException on engine controller failure
     */
    @GET
    @Path("/core/channels")
    @Operation(summary = "Returns engine channels for Sentinel's scope pickers")
    @MirthOperation(name = "sentinelGetCoreChannels", display = "Get Sentinel channel list", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getCoreChannels() throws ClientException;

    /**
     * Returns the engine's channel groups (id, name, member channel ids) as
     * clean JSON for Sentinel's group-scope pickers.
     *
     * @return JSON array of ChannelGroupInfo
     * @throws ClientException on engine controller failure
     */
    @GET
    @Path("/core/channelGroups")
    @Operation(summary = "Returns engine channel groups for Sentinel's scope pickers")
    @MirthOperation(name = "sentinelGetCoreChannelGroups", display = "Get Sentinel channel group list", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getCoreChannelGroups() throws ClientException;

    /**
     * Returns the engine's channel tags (id, name, member channel ids,
     * background color as hex) for Sentinel's tag-scope pickers and
     * condition editors.
     *
     * @return JSON array of TagInfo
     * @throws ClientException on engine controller failure
     */
    @GET
    @Path("/core/tags")
    @Operation(summary = "Returns engine channel tags for Sentinel's scope pickers")
    @MirthOperation(name = "sentinelGetCoreTags", display = "Get Sentinel channel tag list", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getCoreTags() throws ClientException;

    /**
     * Returns the engine's users as id/username pairs so the UI can render
     * "acknowledged by {name}" instead of a bare user id. Intentionally
     * carries nothing beyond id and username — see {@code UserInfo} — and is
     * view-gated like the other core passthroughs, because the engine's own
     * {@code /users} endpoint needs user-management permission a Sentinel
     * viewer may not hold.
     *
     * @return JSON array of UserInfo
     * @throws ClientException on engine controller failure
     */
    @GET
    @Path("/core/users")
    @Operation(summary = "Returns engine users (id + username) for Sentinel's display lookups")
    @MirthOperation(name = "sentinelGetCoreUsers", display = "Get Sentinel user list", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getCoreUsers() throws ClientException;

    // ========== Settings ==========

    /**
     * Returns Sentinel's scheduler and retention settings. Contains no
     * secrets, so it is view-gated like other reads.
     *
     * @return JSON SentinelSettings
     * @throws ClientException on configuration read failure
     */
    @GET
    @Path("/settings")
    @Operation(summary = "Returns Sentinel scheduler and retention settings")
    @MirthOperation(name = "sentinelGetSettings", display = "Get Sentinel settings", permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = false)
    String getSettings() throws ClientException;

    /**
     * Updates Sentinel's scheduler and retention settings, persisting them and
     * rescheduling the collector/evaluator jobs in place (no restart needed).
     *
     * @param settingsJson JSON SentinelSettings
     * @return JSON of the applied SentinelSettings
     * @throws ClientException on out-of-range values or persistence failure
     */
    @PUT
    @Path("/settings")
    @Operation(summary = "Updates Sentinel scheduler and retention settings")
    @MirthOperation(name = "sentinelUpdateSettings", display = "Update Sentinel settings", permission = PERMISSION_SETTINGS, type = ExecuteType.ASYNC)
    String updateSettings(@Param(value = "body", excludeFromAudit = true) String settingsJson) throws ClientException;
}
