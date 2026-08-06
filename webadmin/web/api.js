// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// REST bindings for SentinelServletInterface at /extensions/sentinel.
//
// Wire format (community-store raw-JSON pattern): every Sentinel endpoint
// returns a Jackson-produced JSON *string*, which the engine wraps as
// {"string": "..."}; platform.api unwraps the single root key, so the client
// receives a JS string and parseMaybeJson JSON.parses it into the real object.
// POST/PUT bodies are plain JS objects — platform.api JSON-stringifies them and
// the servlet reads the raw JSON body.
//
// Instants arrive as ISO-8601 UTC strings (parse with new Date(s)); enums as
// bare names ("HIGH", "PROBLEM"). Query params: platform.api drops
// null/undefined/'' values, so optional filters can be passed as-is.

import { platform } from '@oie/web-shell';

export const BASE = '/extensions/sentinel';

/* ---- helpers (community-store plugin.jsx precedent) --------------------- */

export function parseMaybeJson(value) {
    if (typeof value === 'string') {
        try { return JSON.parse(value); } catch (e) { return value; }
    }
    return value;
}

/* Engine-side failures arrive as a serialized ClientException XML blob — stack
   trace and all. Surface only the human message (the outermost <detailMessage>). */
export function errText(e) {
    let text = (e && (e.message || e.statusText)) ? (e.message || e.statusText) : String(e);
    const m = /<detailMessage>([\s\S]*?)<\/detailMessage>/.exec(text);
    if (m) {
        text = m[1]
            .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
            .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
            .trim();
    }
    return text;
}

export async function apiGet(path, params) {
    return parseMaybeJson(await platform.api.get(path, params));
}

export async function apiPost(path, body, params) {
    return parseMaybeJson(await platform.api.post(path, body, params ? { params } : undefined));
}

export async function apiPut(path, body) {
    return parseMaybeJson(await platform.api.put(path, body));
}

export async function apiDel(path) {
    return parseMaybeJson(await platform.api.del(path));
}

const enc = encodeURIComponent;

/* ---- Monitors ----------------------------------------------------------- */

/** GET /monitors -> Monitor[] */
export const listMonitors = () => apiGet(`${BASE}/monitors`);

/** GET /monitors/{id} -> Monitor */
export const getMonitor = (id) => apiGet(`${BASE}/monitors/${enc(id)}`);

/** POST /monitors -> created Monitor (with id) */
export const createMonitor = (monitor) => apiPost(`${BASE}/monitors`, monitor);

/** PUT /monitors/{id} -> updated Monitor */
export const updateMonitor = (id, monitor) => apiPut(`${BASE}/monitors/${enc(id)}`, monitor);

/** DELETE /monitors/{id} -> {} */
export const deleteMonitor = (id) => apiDel(`${BASE}/monitors/${enc(id)}`);

/** POST /monitors/{id}/_setEnabled?enabled= -> Monitor */
export const setMonitorEnabled = (id, enabled) =>
    apiPost(`${BASE}/monitors/${enc(id)}/_setEnabled`, null, { enabled: !!enabled });

/** POST /monitors/_test (body: monitor draft) -> MonitorTestResult
    { ok, message, outcomes: [{ channelId, channelName, status, valueSummary }] } */
export const testMonitor = (monitor) => apiPost(`${BASE}/monitors/_test`, monitor);

/** GET /monitors/{id}/history -> daily alerting history
    { monitorId, from, to, bucket: 'DAY', truncated,
      points: [{ bucket, alertCount, avgResolveSeconds }] }.
    opts: { from (epoch millis), to (epoch millis) }; defaults to the last 30
    days, and a range wider than 366 days is a 400.

    The series is CONTINUOUS — one point per calendar day across the whole
    range, oldest first, including days with no alerts. Read the two measures
    differently on an empty day:
      alertCount        0, a real measurement — plot it
      avgResolveSeconds null (a day with nothing to resolve has no MTTR, which
                        is NOT the same as resolving instantly) — leave the gap
                        in the line rather than substituting 0, or the chart
                        draws a dive to the floor on every quiet day
    A populated day whose alerts are all still open reports null the same way.
    Buckets are server-local midnights.

    `truncated` is true when the response could not count every matching event
    (only reachable for channel-restricted callers, whose series is computed
    over their authorized channels rather than from the server-side aggregate);
    the earliest days then understate. Say so in the UI rather than presenting
    a partial count as complete. */
export const getMonitorHistory = (id, opts) =>
    apiGet(`${BASE}/monitors/${enc(id)}/history`, opts || {});

/* ---- Actions ------------------------------------------------------------ */

/** GET /actions -> Action[] (configJson redacted; secretAccessKey arrives as
    the "••••••••" marker — send it back unchanged to keep the stored secret) */
export const listActions = () => apiGet(`${BASE}/actions`);

/** GET /actions/{id} -> Action (redacted) */
export const getAction = (id) => apiGet(`${BASE}/actions/${enc(id)}`);

/** POST /actions -> created Action (redacted) */
export const createAction = (action) => apiPost(`${BASE}/actions`, action);

/** PUT /actions/{id} -> updated Action (redacted) */
export const updateAction = (id, action) => apiPut(`${BASE}/actions/${enc(id)}`, action);

/** DELETE /actions/{id} -> {} */
export const deleteAction = (id) => apiDel(`${BASE}/actions/${enc(id)}`);

/** POST /actions/{id}/_test -> ActionTestResult { success, message } */
export const testAction = (id) => apiPost(`${BASE}/actions/${enc(id)}/_test`);

/* ---- Maintenance windows ------------------------------------------------ */

/** GET /maintenanceWindows -> MaintenanceWindow[] */
export const listMaintenanceWindows = () => apiGet(`${BASE}/maintenanceWindows`);

/** GET /maintenanceWindows/{id} -> MaintenanceWindow */
export const getMaintenanceWindow = (id) => apiGet(`${BASE}/maintenanceWindows/${enc(id)}`);

/** POST /maintenanceWindows -> created MaintenanceWindow */
export const createMaintenanceWindow = (window_) => apiPost(`${BASE}/maintenanceWindows`, window_);

/** PUT /maintenanceWindows/{id} -> updated MaintenanceWindow */
export const updateMaintenanceWindow = (id, window_) =>
    apiPut(`${BASE}/maintenanceWindows/${enc(id)}`, window_);

/** DELETE /maintenanceWindows/{id} -> {} */
export const deleteMaintenanceWindow = (id) => apiDel(`${BASE}/maintenanceWindows/${enc(id)}`);

/** POST /maintenanceWindows/{id}/_activateNow?durationMinutes= -> MaintenanceWindow */
export const activateMaintenanceWindowNow = (id, durationMinutes) =>
    apiPost(`${BASE}/maintenanceWindows/${enc(id)}/_activateNow`, null,
        { durationMinutes: durationMinutes == null ? 60 : durationMinutes });

/* ---- Problems ----------------------------------------------------------- */

/** GET /problems -> PagedResult<AlertEvent> { items, total, page, pageSize }.
    params (all optional; nullish values are dropped):
    { status, severity (CSV string or array), channelId (CSV string or array),
      monitorId, monitorType, acknowledged (boolean), from (epoch millis),
      to (epoch millis), q, sort, sortDir, page, pageSize }
    Arrays expand to repeated query keys; the server also accepts CSV — pass
    multi-selects as arr.join(','). */
export const getProblems = (params) => apiGet(`${BASE}/problems`, params);

/** GET /problems/{id} -> ProblemDetail
    { event, monitorName, monitorType, channelName, connectorName, dispatches } */
export const getProblem = (id) => apiGet(`${BASE}/problems/${enc(id)}`);

/** POST /problems/{id}/_acknowledge {comment} -> AlertEvent */
export const acknowledgeProblem = (id, comment) =>
    apiPost(`${BASE}/problems/${enc(id)}/_acknowledge`, { comment: comment || '' });

/** POST /problems/{id}/_resolve {comment} -> AlertEvent */
export const resolveProblem = (id, comment) =>
    apiPost(`${BASE}/problems/${enc(id)}/_resolve`, { comment: comment || '' });

/** POST /problems/_bulkAcknowledge {ids, comment} -> { acknowledged: n } */
export const bulkAcknowledgeProblems = (ids, comment) =>
    apiPost(`${BASE}/problems/_bulkAcknowledge`, { ids: ids || [], comment: comment || '' });

/** POST /problems/_bulkResolve {ids, comment} -> { resolved: n }.
    Note the response key is `resolved`, not `acknowledged` — same shape, verb-matched name.
    Ids the caller cannot see, or that are already RESOLVED, are skipped, so the returned
    count can be lower than ids.length. */
export const bulkResolveProblems = (ids, comment) =>
    apiPost(`${BASE}/problems/_bulkResolve`, { ids: ids || [], comment: comment || '' });

/* ---- Dashboard / activity ----------------------------------------------- */

/** GET /dashboard/summary -> DashboardSummary
    { openBySeverity, openTotal, unacknowledged, monitorsEnabled, monitorsTotal,
      channelsWatched, recentProblems, topChannels, lastCollectorRun,
      lastEvaluatorRun, lastConnectorEvent } */
export const getDashboardSummary = () => apiGet(`${BASE}/dashboard/summary`);

/** GET /channels/{channelId}/activity -> ChannelActivity
    { channelId, granularity, points: [{ time, received, sent, error, filtered, queued }] }.
    opts: { from (epoch millis), to (epoch millis), granularity AUTO|RAW|HOURLY }.

    The series is capped at 2000 points, so any range is safe to request; how
    the server met that cap is reported back on `granularity`, which describes
    what was DRAWN and not what was asked for (it is never 'AUTO'):
      'RAW' / 'HOURLY'              source read one-for-one
      '<SOURCE>_<ISO-8601 width>'   source folded into equal buckets, e.g.
                                    'RAW_PT5M2.4S', 'HOURLY_PT1H4M48S'
    AUTO also promotes RAW to HOURLY on a range too wide to read raw. So label
    charts from this field (ui.jsx describeGranularity) and never test it for
    equality with 'RAW' — folded five-minute buckets are not tick resolution,
    and their queued value is an average rather than an instantaneous depth. */
export const getChannelActivity = (channelId, opts) =>
    apiGet(`${BASE}/channels/${enc(channelId)}/activity`, opts || {});

/** GET /activity/summary -> ChannelActivitySummary[]
    { channelId, channelName, received, sent, error, sparkline: number[] }.
    opts: { channelIds (array or CSV string), windowSeconds, buckets } */
export const getActivitySummary = (opts) => {
    const o = opts || {};
    return apiGet(`${BASE}/activity/summary`, {
        ...o,
        channelIds: Array.isArray(o.channelIds) ? o.channelIds.join(',') : o.channelIds,
    });
};

/* ---- Core passthroughs (channel/group pickers) -------------------------- */

/** GET /core/channels -> ChannelInfo[] { channelId, name, state, started } */
export const getCoreChannels = () => apiGet(`${BASE}/core/channels`);

/** GET /core/channelGroups -> ChannelGroupInfo[] { id, name, channelIds } */
export const getCoreChannelGroups = () => apiGet(`${BASE}/core/channelGroups`);

/** GET /core/tags -> TagInfo[] { id, name, channelIds, colorHex } */
export const getCoreTags = () => apiGet(`${BASE}/core/tags`);

/** GET /core/users -> UserInfo[] { userId, username } */
export const getCoreUsers = () => apiGet(`${BASE}/core/users`);

/* ---- Settings ----------------------------------------------------------- */

/** GET /settings -> SentinelSettings
    { collectorIntervalSeconds, evaluatorIntervalSeconds, sampleRetentionDays,
      trendRetentionDays, resolvedAlertRetentionDays } */
export const getSettings = () => apiGet(`${BASE}/settings`);

/** PUT /settings -> SentinelSettings (MANAGE-gated) */
export const updateSettings = (settings) => apiPut(`${BASE}/settings`, settings);

/* ---- Export / import ---------------------------------------------------- */

/** GET /export -> portable configuration document
    { schemaVersion, exportedAt, monitors[], actions[], maintenanceWindows[] }.

    MANAGE-gated, not VIEW-gated like the other reads: it is the whole action
    config surface in one downloadable file. Database ids and audit stamps are
    stripped (the import matches on NAME — ids are per-install serials), a
    monitor's suppression parent travels as `suppressedByMonitorName`, and an
    action's MONITOR condition rows carry monitor names rather than ids.
    Secrets leave as the "••••••••" marker and must be re-entered on the
    target. */
export const exportConfiguration = () => apiGet(`${BASE}/export`);

/** POST /import (body: an export document) -> ImportResult
    { schemaVersion, exportedAt, created, updated, skipped, secretsNotice,
      entries: [{ entityType: 'MONITOR'|'ACTION'|'MAINTENANCE_WINDOW', name,
                  outcome: 'CREATED'|'UPDATED'|'SKIPPED', reason,
                  secretsKept: [], secretsRequired: [], secretsNote }] }

    Writes immediately — confirm before calling. Entities match on name: known
    name -> update, unknown -> create, nothing is ever deleted, and an entity
    already matching the server is skipped as unchanged, which is what makes a
    re-run safe after a partial failure. The per-entry outcome is the point of
    the response: an entity the server's own validation rejected comes back
    SKIPPED with its reason rather than failing the whole document. */
export const importConfiguration = (document_) => apiPost(`${BASE}/import`, document_);
