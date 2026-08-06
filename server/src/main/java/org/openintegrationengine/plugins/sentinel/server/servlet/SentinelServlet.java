/*
 * OIE Sentinel — channel monitoring & alerting plugin.
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */
package org.openintegrationengine.plugins.sentinel.server.servlet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.model.User;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.openintegrationengine.plugins.sentinel.server.SentinelServicePlugin;
import org.openintegrationengine.plugins.sentinel.server.engine.ScopeResolver;
import org.openintegrationengine.plugins.sentinel.server.service.ActionService;
import org.openintegrationengine.plugins.sentinel.server.service.ActivityQueryService;
import org.openintegrationengine.plugins.sentinel.server.service.DashboardService;
import org.openintegrationengine.plugins.sentinel.server.service.ExportImportService;
import org.openintegrationengine.plugins.sentinel.server.service.MaintenanceWindowService;
import org.openintegrationengine.plugins.sentinel.server.service.MetricsService;
import org.openintegrationengine.plugins.sentinel.server.service.MonitorHistoryService;
import org.openintegrationengine.plugins.sentinel.server.service.MonitorService;
import org.openintegrationengine.plugins.sentinel.server.service.ProblemService;
import org.openintegrationengine.plugins.sentinel.server.service.SettingsService;
import org.openintegrationengine.plugins.sentinel.server.util.Json;
import org.openintegrationengine.plugins.sentinel.shared.SentinelServletInterface;
import org.openintegrationengine.plugins.sentinel.shared.model.Action;
import org.openintegrationengine.plugins.sentinel.shared.model.AlertEvent;
import org.openintegrationengine.plugins.sentinel.shared.model.ChannelInfo;
import org.openintegrationengine.plugins.sentinel.shared.model.MaintenanceWindow;
import org.openintegrationengine.plugins.sentinel.shared.model.Monitor;
import org.openintegrationengine.plugins.sentinel.shared.model.PagedResult;
import org.openintegrationengine.plugins.sentinel.shared.model.SentinelSettings;
import org.openintegrationengine.plugins.sentinel.shared.model.UserInfo;

/**
 * The HTTP boundary of the plugin: a deliberately thin JAX-RS resource that
 * translates between the wire and the service layer and contains no business
 * logic of its own.
 *
 * <p><b>Why so thin:</b> the engine constructs a fresh servlet instance for
 * every request (Jersey's default lifecycle for registered resource classes),
 * so anything stateful or expensive must live behind the static service
 * classes, not here. Permission enforcement is also not here — the engine's
 * {@code MirthResourceInvocationHandlerProvider} resolves each interface
 * method's {@code @MirthOperation} and calls {@code checkUserAuthorized()}
 * <i>before</i> the method body runs, keyed by the composite operation name
 * {@code "OIE Sentinel#sentinel..."} that the {@code PLUGIN_POINT} constructor
 * argument produces. What remains for this class is exactly three jobs:</p>
 *
 * <ol>
 * <li><b>JSON at the boundary.</b> Bodies arrive as raw {@code String}s and
 * responses leave as Jackson-produced JSON strings (the community-store
 * pattern — see {@link SentinelServletInterface}), so every method is
 * {@code Json.read} → service call → {@code Json.write}.</li>
 * <li><b>Error mapping.</b> The service layer signals bad input with
 * {@link IllegalArgumentException} and missing entities with
 * {@link NoSuchElementException}; {@link #translate} maps those to HTTP 400
 * and 404 with the human message as a plain-text entity (the web client
 * surfaces the response body as the error message), and turns anything else
 * into a 500 whose entity is nothing but a logged reference id — an
 * unexpected failure reaches the client as a structured, actionable error
 * without carrying its internal text along with it.</li>
 * <li><b>Channel-restriction redaction.</b> The engine only protects
 * channel-restricted users where a servlet opts in, so every endpoint that
 * exposes or mutates per-channel data enforces it here: {@code /core/channels}
 * filters redacted channels out of the picker list,
 * {@code /channels/{id}/activity} refuses redacted channels outright with
 * 403, {@code /problems} + {@code /activity/summary} constrain their
 * channel filter to the caller's authorized set — including the subtle case
 * where the caller sent <i>no</i> channel filter, which must become "your
 * authorized channels", never "all channels" — {@code /dashboard/summary},
 * {@code /metrics} and {@code /monitors/{id}/history} pass the authorized set
 * into their aggregate build (their recent-problems feed, per-channel numbers,
 * per-channel series, and per-monitor alert counts would otherwise leak what
 * the list endpoints hide; a scrape is a read like any other, and an aggregate
 * over channels stays an aggregate <i>of</i> those channels however few of
 * them it names), and the per-problem endpoints (get/acknowledge/resolve, plus
 * each id of a bulk acknowledge or bulk resolve) 404 redacted problems so
 * sequential alert ids cannot be enumerated around the {@code /problems}
 * redaction.</li>
 * </ol>
 */
public class SentinelServlet extends MirthServlet implements SentinelServletInterface {

    private static final Logger log = LoggerFactory.getLogger(SentinelServlet.class);

    /** The empty-object response every void-shaped mutation returns for client uniformity. */
    private static final String EMPTY_JSON = "{}";

    /**
     * Per-request constructor invoked by Jersey with injected context.
     * Passing {@link SentinelServletInterface#PLUGIN_POINT} to the base class
     * is what scopes every operation name to this extension
     * ({@code "OIE Sentinel#sentinelGetMonitors"}) — the key an authorization
     * plugin (e.g. RBAC) grants permissions against; omitting it would
     * silently detach the interface's declared permissions from enforcement.
     *
     * @param request the current HTTP request, injected by Jersey
     * @param sc      the security context, injected by Jersey
     */
    public SentinelServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_POINT);
        // When the schema migration failed at startup every endpoint would
        // otherwise surface as an opaque table-not-found 500; a 503 from the
        // per-request constructor turns that into one clear, actionable
        // message (MirthApiException is a WebApplicationException, so Jersey
        // maps it even from a resource constructor).
        if (!SentinelServicePlugin.isSchemaReady()) {
            throw apiError(Status.SERVICE_UNAVAILABLE,
                    "The Sentinel schema is not installed — the extension migration failed at engine startup; check the server log.");
        }
    }

    // ========== Monitors ==========

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code MonitorService.list()}.</p>
     */
    @Override
    public String getMonitors() {
        try {
            return Json.write(MonitorService.list());
        } catch (Exception e) {
            throw translate("getMonitors", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code MonitorService.get(int)}.</p>
     */
    @Override
    public String getMonitor(int id) {
        try {
            return Json.write(MonitorService.get(id));
        } catch (Exception e) {
            throw translate("getMonitor", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body as a Monitor and delegates to
     * {@code MonitorService.create(Monitor, int)}.</p>
     */
    @Override
    public String createMonitor(String monitorJson) {
        try {
            return Json.write(MonitorService.create(readBody(monitorJson, Monitor.class), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("createMonitor", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body as a Monitor and delegates to
     * {@code MonitorService.update(int, Monitor, int)}.</p>
     */
    @Override
    public String updateMonitor(int id, String monitorJson) {
        try {
            return Json.write(MonitorService.update(id, readBody(monitorJson, Monitor.class), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("updateMonitor", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code MonitorService.delete(int, int)}.</p>
     */
    @Override
    public String removeMonitor(int id) {
        try {
            MonitorService.delete(id, getCurrentUserId());
            return EMPTY_JSON;
        } catch (Exception e) {
            throw translate("removeMonitor", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code MonitorService.setEnabled(int, boolean, int)}.</p>
     */
    @Override
    public String setMonitorEnabled(int id, boolean enabled) {
        try {
            return Json.write(MonitorService.setEnabled(id, enabled, getCurrentUserId()));
        } catch (Exception e) {
            throw translate("setMonitorEnabled", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body as a Monitor and delegates to
     * {@code MonitorService.test(Monitor)} — a dry run, so no user id is
     * involved and nothing is persisted.</p>
     */
    @Override
    public String testMonitor(String monitorJson) {
        try {
            return Json.write(MonitorService.test(readBody(monitorJson, Monitor.class)));
        } catch (Exception e) {
            throw translate("testMonitor", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to
     * {@link MonitorHistoryService#build(int, Long, Long, java.util.Set)},
     * passing the caller's authorized channel set ({@code null} for unrestricted
     * users) — the same argument and the same contract as
     * {@link #getDashboardSummary()}. Every alert counted into a monitor's
     * history belongs to a channel, and a monitor scoped to a group, a tag, or
     * all channels aggregates across channels a restricted caller cannot see, so
     * an unconstrained series would report daily alert volume and resolve times
     * for exactly what {@code /problems} and the activity endpoints redact.</p>
     *
     * <p>Not a 403 or a 404 like the per-channel and per-problem endpoints: the
     * monitor itself is legitimately readable (monitor definitions are not
     * channel-filtered), so the narrowing belongs to the data rather than to the
     * lookup.</p>
     */
    @Override
    public String getMonitorHistory(int id, Long from, Long to) {
        try {
            return Json.write(MonitorHistoryService.build(id, from, to, authorizedChannelIds(null)));
        } catch (Exception e) {
            throw translate("getMonitorHistory", e);
        }
    }

    // ========== Actions ==========

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code ActionService.list()}, which returns
     * secret-redacted copies — this servlet never sees stored credentials.</p>
     */
    @Override
    public String getActions() {
        try {
            return Json.write(ActionService.list());
        } catch (Exception e) {
            throw translate("getActions", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code ActionService.get(int)} (redacted copy).</p>
     */
    @Override
    public String getAction(int id) {
        try {
            return Json.write(ActionService.get(id));
        } catch (Exception e) {
            throw translate("getAction", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body as an Action and delegates to
     * {@code ActionService.create(Action, int)}, which encrypts secrets
     * before persisting and redacts the returned copy.</p>
     */
    @Override
    public String createAction(String actionJson) {
        try {
            return Json.write(ActionService.create(readBody(actionJson, Action.class), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("createAction", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body as an Action and delegates to
     * {@code ActionService.update(int, Action, int)}; a redaction-marker
     * secret in the body means "keep the stored secret", so round-tripping a
     * GET response saves cleanly.</p>
     */
    @Override
    public String updateAction(int id, String actionJson) {
        try {
            return Json.write(ActionService.update(id, readBody(actionJson, Action.class), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("updateAction", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code ActionService.delete(int, int)}.</p>
     */
    @Override
    public String removeAction(int id) {
        try {
            ActionService.delete(id, getCurrentUserId());
            return EMPTY_JSON;
        } catch (Exception e) {
            throw translate("removeAction", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code ActionService.test(int)}, which performs a real
     * delivery with a synthetic payload.</p>
     */
    @Override
    public String testAction(int id) {
        try {
            return Json.write(ActionService.test(id));
        } catch (Exception e) {
            throw translate("testAction", e);
        }
    }

    // ========== Maintenance windows ==========

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code MaintenanceWindowService.list()}.</p>
     */
    @Override
    public String getMaintenanceWindows() {
        try {
            return Json.write(MaintenanceWindowService.list());
        } catch (Exception e) {
            throw translate("getMaintenanceWindows", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code MaintenanceWindowService.get(int)}.</p>
     */
    @Override
    public String getMaintenanceWindow(int id) {
        try {
            return Json.write(MaintenanceWindowService.get(id));
        } catch (Exception e) {
            throw translate("getMaintenanceWindow", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body as a MaintenanceWindow and delegates to
     * {@code MaintenanceWindowService.create(MaintenanceWindow, int)}.</p>
     */
    @Override
    public String createMaintenanceWindow(String windowJson) {
        try {
            return Json.write(MaintenanceWindowService.create(
                    readBody(windowJson, MaintenanceWindow.class), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("createMaintenanceWindow", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body as a MaintenanceWindow and delegates to
     * {@code MaintenanceWindowService.update(int, MaintenanceWindow, int)}.</p>
     */
    @Override
    public String updateMaintenanceWindow(int id, String windowJson) {
        try {
            return Json.write(MaintenanceWindowService.update(
                    id, readBody(windowJson, MaintenanceWindow.class), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("updateMaintenanceWindow", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code MaintenanceWindowService.delete(int, int)}.</p>
     */
    @Override
    public String removeMaintenanceWindow(int id) {
        try {
            MaintenanceWindowService.delete(id, getCurrentUserId());
            return EMPTY_JSON;
        } catch (Exception e) {
            throw translate("removeMaintenanceWindow", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to
     * {@code MaintenanceWindowService.activateNow(int, int, int)}.</p>
     */
    @Override
    public String activateMaintenanceWindowNow(int id, int durationMinutes) {
        try {
            return Json.write(MaintenanceWindowService.activateNow(id, durationMinutes, getCurrentUserId()));
        } catch (Exception e) {
            throw translate("activateMaintenanceWindowNow", e);
        }
    }

    // ========== Problems ==========

    /**
     * {@inheritDoc}
     * <p>Applies the caller's channel restrictions to the channel filter
     * before delegating to {@code ProblemService.list(...)}: requested ids
     * that the caller may not see are dropped, and an absent filter is
     * constrained to the caller's authorized set rather than passed through
     * as "all channels". A caller whose authorized set is empty gets an empty
     * page directly — forwarding an empty filter would mean "no filter" and
     * leak every channel's problems.</p>
     */
    @Override
    public String getProblems(String status, String severity, String channelId, Integer monitorId,
            String monitorType, Boolean acknowledged, Long from, Long to, String q, String sort,
            String sortDir, int page, int pageSize) {
        try {
            Set<String> authorized = authorizedChannelIds(channelId);
            if (authorized != null) {
                if (authorized.isEmpty()) {
                    return Json.write(new PagedResult<AlertEvent>(List.of(), 0L, page, pageSize));
                }
                channelId = String.join(",", authorized);
            }
            return Json.write(ProblemService.list(status, severity, channelId, monitorId, monitorType,
                    acknowledged, from, to, q, sort, sortDir, page, pageSize));
        } catch (Exception e) {
            throw translate("getProblems", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Rejects redacted channels' problems (see
     * {@link #assertProblemVisible}) before delegating to
     * {@code ProblemService.getDetail(long)} — without this check, the
     * sequential alert-event ids would let a channel-restricted caller
     * enumerate every problem the {@code /problems} redaction hides.</p>
     */
    @Override
    public String getProblem(long id) {
        try {
            assertProblemVisible(id);
            return Json.write(ProblemService.getDetail(id));
        } catch (Exception e) {
            throw translate("getProblem", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Rejects redacted channels' problems, then extracts the optional
     * comment from the body and delegates to
     * {@code ProblemService.acknowledge(long, String, int)}.</p>
     */
    @Override
    public String acknowledgeProblem(long id, String bodyJson) {
        try {
            assertProblemVisible(id);
            return Json.write(ProblemService.acknowledge(id, parseComment(bodyJson), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("acknowledgeProblem", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Rejects redacted channels' problems (a manual resolve also resets
     * the owning trigger state — a channel-restricted operator must not be
     * able to silence monitoring on a channel they are not entrusted with),
     * then extracts the optional comment from the body and delegates to
     * {@code ProblemService.resolve(long, String, int)}.</p>
     */
    @Override
    public String resolveProblem(long id, String bodyJson) {
        try {
            assertProblemVisible(id);
            return Json.write(ProblemService.resolve(id, parseComment(bodyJson), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("resolveProblem", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses {@code {ids, comment}} from the body and delegates to
     * {@code ProblemService.bulkAcknowledge(List, String, int)}. The ids are
     * validated here (must be a numeric array) because a malformed body is a
     * wire concern, while "already acknowledged" skipping is a business rule
     * the service owns.</p>
     */
    @Override
    public String bulkAcknowledgeProblems(String bodyJson) {
        try {
            JsonNode root = readBodyTree(bodyJson);
            List<Long> ids = dropRedactedProblemIds(parseProblemIds(root));
            int acknowledged = ProblemService.bulkAcknowledge(ids, textOrNull(root, "comment"), getCurrentUserId());
            return Json.write(Map.of("acknowledged", acknowledged));
        } catch (Exception e) {
            throw translate("bulkAcknowledgeProblems", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>The exact shape of {@link #bulkAcknowledgeProblems(String)}, sharing
     * both its body parsing and — critically — its
     * {@link #dropRedactedProblemIds} pass. Redaction matters more here, not
     * less: a bulk resolve closes problems <i>and</i> resets their trigger
     * states, so an unfiltered batch would let a channel-restricted caller
     * silence monitoring on channels they are not entrusted with, and would
     * let the returned count reveal which of a sweep of guessed ids exist.</p>
     */
    @Override
    public String bulkResolveProblems(String bodyJson) {
        try {
            JsonNode root = readBodyTree(bodyJson);
            List<Long> ids = dropRedactedProblemIds(parseProblemIds(root));
            int resolved = ProblemService.bulkResolve(ids, textOrNull(root, "comment"), getCurrentUserId());
            return Json.write(Map.of("resolved", resolved));
        } catch (Exception e) {
            throw translate("bulkResolveProblems", e);
        }
    }

    // ========== Dashboard & activity ==========

    /**
     * {@inheritDoc}
     * <p>Delegates to {@code DashboardService.build(Set)}, passing the
     * caller's authorized channel set ({@code null} for unrestricted users)
     * so the landing page's recent-problems feed, top channels, and open
     * counts cannot leak channel names and alert messages that the filtered
     * list endpoints redact.</p>
     */
    @Override
    public String getDashboardSummary() {
        try {
            return Json.write(DashboardService.build(authorizedChannelIds(null)));
        } catch (Exception e) {
            throw translate("getDashboardSummary", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Refuses redacted channels with 403 before delegating to
     * {@code ActivityQueryService.getChannelActivity(...)} — activity data is
     * per-channel message flow, exactly what channel restrictions exist to
     * hide, and a hard 403 (rather than an empty series) tells the client the
     * request was disallowed, not that the channel is idle.</p>
     */
    @Override
    public String getChannelActivity(String channelId, Long from, Long to, String granularity) {
        try {
            if (isChannelRedacted(channelId)) {
                throw new MirthApiException(Status.FORBIDDEN);
            }
            return Json.write(ActivityQueryService.getChannelActivity(channelId, from, to, granularity));
        } catch (Exception e) {
            throw translate("getChannelActivity", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Applies the caller's channel restrictions to the requested channel
     * set (same rules as {@link #getProblems}: drop redacted ids, constrain
     * an absent filter to the authorized set, empty authorized set short-
     * circuits to an empty array) before delegating to
     * {@code ActivityQueryService.getActivitySummary(...)}.</p>
     */
    @Override
    public String getActivitySummary(String channelIds, int windowSeconds, int buckets) {
        try {
            Set<String> authorized = authorizedChannelIds(channelIds);
            if (authorized != null) {
                if (authorized.isEmpty()) {
                    return Json.write(List.of());
                }
                channelIds = String.join(",", authorized);
            }
            return Json.write(ActivityQueryService.getActivitySummary(channelIds, windowSeconds, buckets));
        } catch (Exception e) {
            throw translate("getActivitySummary", e);
        }
    }

    // ========== Metrics ==========

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link MetricsService#render(Set)}, passing the caller's
     * authorized channel set ({@code null} for unrestricted users) — the same
     * argument and the same contract as {@link #getDashboardSummary()}. A
     * scrape is a read like any other, so a channel-restricted user must not
     * be able to recover per-channel throughput or problem counts here that
     * {@code /problems} and the activity endpoints redact.</p>
     *
     * <p>The response is Prometheus text, not JSON, so it is returned as-is
     * rather than through {@code Json.write} — see the interface method for
     * why this one endpoint overrides the class-level {@code @Produces}.</p>
     */
    @Override
    public String getMetrics() {
        try {
            return MetricsService.render(authorizedChannelIds(null));
        } catch (Exception e) {
            throw translate("getMetrics", e);
        }
    }

    // ========== Core passthrough ==========

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link ScopeResolver#listChannels()} and, for
     * channel-restricted callers, filters out redacted channels — the base
     * class's redaction helpers are opt-in, and a scope picker that listed
     * unauthorized channels would leak channel names across the restriction
     * boundary.</p>
     */
    @Override
    public String getCoreChannels() {
        try {
            List<ChannelInfo> channels = ScopeResolver.listChannels();
            if (doesUserHaveChannelRestrictions()) {
                List<ChannelInfo> visible = new ArrayList<>();
                for (ChannelInfo channel : channels) {
                    if (!isChannelRedacted(channel.getChannelId())) {
                        visible.add(channel);
                    }
                }
                channels = visible;
            }
            return Json.write(channels);
        } catch (Exception e) {
            throw translate("getCoreChannels", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link ScopeResolver#listGroups()}. Groups are not
     * channel-filtered: the contract scopes redaction to the channel-data
     * endpoints, and group membership is needed intact for
     * group-scope pickers to make sense.</p>
     */
    @Override
    public String getCoreChannelGroups() {
        try {
            return Json.write(ScopeResolver.listGroups());
        } catch (Exception e) {
            throw translate("getCoreChannelGroups", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link ScopeResolver#listTags()}. Like groups, tags
     * are not channel-filtered: the redaction contract covers the
     * channel-data endpoints, and tag membership is needed intact for
     * tag-scope pickers to make sense.</p>
     */
    @Override
    public String getCoreTags() {
        try {
            return Json.write(ScopeResolver.listTags());
        } catch (Exception e) {
            throw translate("getCoreTags", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Maps the engine's user list down to id/username pairs. Not
     * channel-filtered — usernames are display metadata, and the redaction
     * contract covers channel data only.</p>
     */
    @Override
    public String getCoreUsers() {
        try {
            List<UserInfo> users = new ArrayList<>();
            for (User user : ControllerFactory.getFactory().createUserController().getAllUsers()) {
                UserInfo info = new UserInfo();
                info.setUserId(user.getId());
                info.setUsername(user.getUsername());
                users.add(info);
            }
            return Json.write(users);
        } catch (Exception e) {
            throw translate("getCoreUsers", e);
        }
    }

    // ========== Settings ==========

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link SettingsService#get()}.</p>
     */
    @Override
    public String getSettings() {
        try {
            return Json.write(SettingsService.get());
        } catch (Exception e) {
            throw translate("getSettings", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body as SentinelSettings and delegates to
     * {@link SettingsService#update(SentinelSettings, int)}, which validates,
     * persists, and retimes the live scheduler.</p>
     */
    @Override
    public String updateSettings(String settingsJson) {
        try {
            return Json.write(SettingsService.update(
                    readBody(settingsJson, SentinelSettings.class), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("updateSettings", e);
        }
    }

    // ========== Export / import ==========

    /**
     * {@inheritDoc}
     * <p>Delegates to {@link ExportImportService#export()}. No channel
     * filtering: the document is the union of {@code /monitors},
     * {@code /actions}, and {@code /maintenanceWindows}, none of which is
     * channel-filtered (the redaction contract covers per-channel <i>data</i>,
     * not the definitions that reference channels), and this endpoint sits at
     * {@code PERMISSION_MANAGE} while those three sit at {@code
     * PERMISSION_VIEW} — so it exposes nothing a caller reaching it cannot
     * already read one list at a time.</p>
     */
    @Override
    public String exportConfiguration() {
        try {
            return Json.write(ExportImportService.export());
        } catch (Exception e) {
            throw translate("exportConfiguration", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>Parses the body with the shared {@link #readBodyTree} helper — "a
     * body is required" and "the body is not JSON" are wire concerns this
     * layer owns, exactly as for the bulk endpoints — and delegates the
     * document's own validity, the name matching, and every write to
     * {@link ExportImportService#importDocument(JsonNode, int)}.</p>
     */
    @Override
    public String importConfiguration(String bodyJson) {
        try {
            return Json.write(ExportImportService.importDocument(
                    readBodyTree(bodyJson), getCurrentUserId()));
        } catch (Exception e) {
            throw translate("importConfiguration", e);
        }
    }

    // ========== Boundary helpers ==========

    /**
     * Maps a service-layer failure onto the HTTP status contract:
     * {@link IllegalArgumentException} (validation / bad input) → 400,
     * {@link NoSuchElementException} (missing entity) → 404, an already-built
     * {@link MirthApiException} passes through untouched (e.g. the 403 from
     * channel redaction), and anything unexpected → an opaque 500. Returned
     * rather than thrown so call sites read {@code throw translate(...)} and
     * the compiler knows the catch block never falls through.
     *
     * <p><b>Why the 500 says nothing:</b> wrapping the cause
     * ({@code new MirthApiException(e)}) serializes it to the client, and the
     * web client digs the {@code detailMessage} out of that payload and renders
     * it verbatim in the browser. A repository failure's cause chain carries
     * SQL text, constraint names, and vendor error codes, so that wrapping put
     * database internals in front of anyone holding nothing more than View
     * Monitoring. Instead a random reference id is minted, the full cause is
     * logged against it here — the only place with full request context — and
     * the client is told just the id. Support correlates the browser message to
     * the server log by that id without the caller ever learning why the call
     * failed. The 400 and 404 messages skip this treatment because the service
     * layer hand-writes them for the operator to read; they contain no
     * internals by construction.</p>
     */
    private RuntimeException translate(String operationName, Exception e) {
        if (e instanceof MirthApiException) {
            return (MirthApiException) e;
        }
        if (e instanceof IllegalArgumentException) {
            return apiError(Status.BAD_REQUEST, e.getMessage());
        }
        if (e instanceof NoSuchElementException) {
            return apiError(Status.NOT_FOUND, e.getMessage());
        }
        // Operation name + ref id + full stack on one record: everything
        // support needs to answer "what was ref 4f3c...?" from the log alone.
        String ref = UUID.randomUUID().toString();
        log.error("Sentinel operation '{}' failed unexpectedly (ref: {})", operationName, ref, e);
        return apiError(Status.INTERNAL_SERVER_ERROR, "Sentinel operation failed (ref: " + ref + ")");
    }

    /**
     * Builds a {@link MirthApiException} carrying a specific status and a
     * plain-text message entity. {@code MirthApiException} has no
     * {@code (Status, String)} constructor, so the message must ride a
     * hand-built {@link Response}; plain text is deliberate — the web
     * client's error handling uses the raw response body as the displayed
     * message when it isn't JSON.
     */
    private static MirthApiException apiError(Status status, String message) {
        String entity = message != null && !message.isBlank() ? message : status.getReasonPhrase();
        return new MirthApiException(Response.status(status).type(MediaType.TEXT_PLAIN).entity(entity).build());
    }

    /**
     * Parses a required JSON request body into a DTO, converting an absent
     * body into the same {@link IllegalArgumentException} → 400 path as
     * malformed JSON (which {@link Json#read} already raises), so clients get
     * one consistent failure shape for every kind of bad body.
     */
    private static <T> T readBody(String bodyJson, Class<T> type) {
        if (bodyJson == null || bodyJson.isBlank()) {
            throw new IllegalArgumentException("A JSON request body is required");
        }
        return Json.read(bodyJson, type);
    }

    /**
     * Parses a required JSON request body into a tree, for endpoints whose
     * body is an ad-hoc shape ({@code {ids, comment}}) that doesn't warrant a
     * DTO. Failure semantics match {@link #readBody}.
     */
    private static JsonNode readBodyTree(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            throw new IllegalArgumentException("A JSON request body is required");
        }
        try {
            return Json.mapper().readTree(bodyJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON body: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Extracts the {@code ids} array shared by the two bulk endpoints. The
     * shape is validated here rather than in the service because a malformed
     * body is a wire concern (400), while what happens to a
     * well-formed-but-uninteresting id — missing, already acknowledged,
     * already resolved — is a business rule the service owns and answers by
     * skipping. Kept in one place so bulk acknowledge and bulk resolve cannot
     * drift into accepting different bodies.
     *
     * @param root the parsed request body
     * @return the requested ids, in body order
     * @throws IllegalArgumentException if {@code ids} is missing, not an
     *                                  array, or holds a non-numeric element
     */
    private static List<Long> parseProblemIds(JsonNode root) {
        JsonNode idsNode = root.path("ids");
        if (!idsNode.isArray()) {
            throw new IllegalArgumentException("Body must contain an 'ids' array");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode idNode : idsNode) {
            if (!idNode.canConvertToLong()) {
                throw new IllegalArgumentException("'ids' must contain only numeric alert event ids");
            }
            ids.add(idNode.asLong());
        }
        return ids;
    }

    /**
     * Extracts the optional acknowledge/resolve comment: the whole body is
     * optional (a bare acknowledge is legal), but a body that is present and
     * malformed is still a 400 — silently ignoring it would discard a comment
     * the operator typed.
     */
    private static String parseComment(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            return null;
        }
        return textOrNull(readBodyTree(bodyJson), "comment");
    }

    /** Reads a text field from a parsed body, treating missing and JSON-null alike as absent. */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Resolves the channel-id set a restricted caller is actually allowed to
     * query, from a CSV filter parameter.
     *
     * <p>Returns {@code null} when the caller has no channel restrictions —
     * the filter should pass through verbatim, including the "no filter"
     * case. Otherwise returns the authorized subset of the requested ids, or,
     * when no ids were requested, the authorized subset of <i>every</i>
     * channel (via {@code redactChannelIds}) — because for a restricted user
     * "no filter" must mean "my channels", never "all channels". The result
     * may be empty; callers must short-circuit rather than forward an empty
     * filter, which the service layer would read as unfiltered.</p>
     */
    private Set<String> authorizedChannelIds(String channelIdCsv) {
        if (!doesUserHaveChannelRestrictions()) {
            return null;
        }
        Set<String> requested = splitCsv(channelIdCsv);
        if (requested.isEmpty()) {
            for (ChannelInfo channel : ScopeResolver.listChannels()) {
                requested.add(channel.getChannelId());
            }
        }
        return redactChannelIds(requested);
    }

    /**
     * Enforces channel restrictions on a single problem before it is read or
     * mutated: when the caller is restricted and the problem's channel is
     * redacted, throws 404 — deliberately indistinguishable from "no such
     * problem", because alert-event ids are sequential and a 403 would
     * confirm to an enumerating caller that the id exists. No-op (and no
     * extra fetch) for unrestricted callers. Runs BEFORE the service call so
     * a mutation can never take effect on a channel the caller may not see.
     *
     * @param id the alert-event id about to be read or mutated
     */
    private void assertProblemVisible(long id) {
        if (!doesUserHaveChannelRestrictions()) {
            return;
        }
        AlertEvent event = ProblemService.get(id); // NoSuchElementException -> 404 via translate
        if (isChannelRedacted(event.getChannelId())) {
            throw new MirthApiException(Status.NOT_FOUND);
        }
    }

    /**
     * Bulk-acknowledge companion to {@link #assertProblemVisible}: silently
     * drops ids whose problem is on a redacted channel (mirroring the
     * service's skip-don't-fail semantics for the batch), along with ids
     * that don't resolve at all — the service would skip those anyway. The
     * returned count then honestly reports how many the caller was actually
     * allowed to acknowledge.
     */
    private List<Long> dropRedactedProblemIds(List<Long> ids) {
        if (!doesUserHaveChannelRestrictions()) {
            return ids;
        }
        List<Long> visible = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            try {
                if (!isChannelRedacted(ProblemService.get(id).getChannelId())) {
                    visible.add(id);
                }
            } catch (NoSuchElementException e) {
                // Missing id: the service's bulk loop skips these silently;
                // dropping here preserves identical behavior.
            }
        }
        return visible;
    }

    /**
     * Splits a CSV query parameter into a set, tolerating null, blanks, and
     * stray commas — query strings are hand-editable and the grid's filter
     * bar should not 400 over {@code "a,,b,"}. Insertion order is kept so a
     * rebuilt CSV stays recognizable in logs.
     */
    private static Set<String> splitCsv(String csv) {
        Set<String> values = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
        }
        return values;
    }
}
