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

/* ---- Dashboard / activity ----------------------------------------------- */

/** GET /dashboard/summary -> DashboardSummary
    { openBySeverity, openTotal, unacknowledged, monitorsEnabled, monitorsTotal,
      channelsWatched, recentProblems, topChannels, lastCollectorRun,
      lastEvaluatorRun, lastConnectorEvent } */
export const getDashboardSummary = () => apiGet(`${BASE}/dashboard/summary`);

/** GET /channels/{channelId}/activity -> ChannelActivity
    { channelId, granularity, points: [{ time, received, sent, error, filtered, queued }] }.
    opts: { from (epoch millis), to (epoch millis), granularity AUTO|RAW|HOURLY } */
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
