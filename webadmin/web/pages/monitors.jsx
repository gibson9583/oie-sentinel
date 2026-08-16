// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Monitors page: DataTable list (name, type, scope, severity, enabled toggle,
// updated) with a page-local MonitorEditor sub-view (common fields + per-type
// configJson fieldsets matching the server's exact config shapes), a dry-run
// Test panel (detailModal) and confirmDialog-gated delete. All mutations are
// canManage()-gated; the servlet's MANAGE permission is the real enforcement.

import { platform } from '@oie/web-shell';
import { errorModal, confirmDialog, detailModal } from '@oie/web-ui';
import {
    ResponsiveContainer, BarChart, Bar, LineChart, Line, XAxis, YAxis,
    CartesianGrid, Tooltip,
} from 'recharts';
import {
    listMonitors, createMonitor, updateMonitor, deleteMonitor,
    setMonitorEnabled, testMonitor, getMonitorHistory, getCoreChannels,
    getCoreChannelGroups, getCoreTags, errText,
} from '../api.js';
import {
    canManage, toast, useApi, useDataTable, SeverityChip, SEVERITY_ORDER,
    SEVERITY_META, MONITOR_TYPE_ORDER, MONITOR_TYPE_META, ChannelPicker,
    ChannelGroupPicker, TagPicker, fmtNum, fmtTime, fmtAgo,
} from '../ui.jsx';

const React = platform.React;
const { h } = platform.ui;

const SCOPE_TYPES = [
    { key: 'ALL', label: 'All channels' },
    { key: 'GROUP', label: 'Channel group' },
    { key: 'TAG', label: 'Channel tag' },
    { key: 'CHANNEL', label: 'Single channel' },
];

// The ConnectionStatusEventType names Sentinel's connector listener records —
// everything except the transient INFO/FAILURE message types, which never
// enter the live state map, so they are the only names a CONNECTION_STATUS
// monitor cannot match.
const CONNECTION_STATES = [
    'IDLE', 'READING', 'WRITING', 'POLLING', 'RECEIVING', 'SENDING',
    'WAITING_FOR_RESPONSE', 'CONNECTED', 'CONNECTING', 'DISCONNECTED',
];

// CONNECTION_STATUS `rollup`: how many problems (and therefore how many
// notifications) one channel's connectors may open at once. The server's enum
// also reserves SCOPE, which it rejects until scope-level rollup ships, so it
// is deliberately not offered here.
const ROLLUP_MODES = [
    { value: 'CONNECTOR', label: 'Alert per connector' },
    { value: 'CHANNEL', label: 'Alert once per channel' },
];

// The donkey DeployedState names a CHANNEL_STATE monitor can match, split by
// whether a channel rests there or merely passes through. The split is the
// whole usability of this editor: the resting states are what an operator
// wants ("stopped and nobody noticed"), the transitional ones are what every
// ordinary redeploy walks through, so offering them as one flat list invites
// a monitor that pages on every deployment.
const CHANNEL_RESTING_STATES = ['STOPPED', 'PAUSED', 'UNDEPLOYED'];
const CHANNEL_TRANSITIONAL_STATES = [
    'DEPLOYING', 'UNDEPLOYING', 'STARTING', 'STOPPING', 'PAUSING', 'SYNCING',
];
// STARTED is matchable but deliberately absent from both lists: a monitor that
// alerts because a channel is running is a monitor nobody wants, and offering
// the checkbox is an invitation to page the whole estate by accident.
const CHANNEL_STATES = [...CHANNEL_RESTING_STATES, ...CHANNEL_TRANSITIONAL_STATES];

/** "WAITING_FOR_RESPONSE" -> "Waiting for response". */
function stateLabel(s) {
    return String(s).toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
}

/* ---- list helpers -------------------------------------------------------- */

function scopeLabel(monitor, channelNames, groupNames, tagNames) {
    if (monitor.scopeType === 'CHANNEL') {
        return `Channel: ${channelNames[monitor.scopeId] || monitor.scopeId || '?'}`;
    }
    if (monitor.scopeType === 'GROUP') {
        return `Group: ${groupNames[monitor.scopeId] || monitor.scopeId || '?'}`;
    }
    if (monitor.scopeType === 'TAG') {
        return `Tag: ${tagNames[monitor.scopeId] || monitor.scopeId || '?'}`;
    }
    return 'All channels';
}

/** DOM twin of ui.jsx's SeverityChip — DataTable cell renderers must return
    DOM nodes, not React elements. */
function severityChipNode(severity) {
    const meta = SEVERITY_META[severity] || { label: severity || '—', color: 'var(--text-faint)' };
    return h('span.tag.sn-sev',
        { style: { borderColor: `color-mix(in srgb, ${meta.color} 55%, transparent)` } },
        h('span.sn-sev-dot', { style: { background: meta.color } }),
        meta.label);
}

/* Columns/options are captured ONCE by DataTable at mount; every callback
   dereferences handlersRef.current so it always sees the latest handlers. */
function buildColumns(handlersRef) {
    return [
        {
            key: 'name', label: 'Name',
            render: (r) => h('span', {
                style: {
                    color: 'var(--accent)', cursor: 'pointer',
                    opacity: r.enabled ? '' : '0.6',
                },
                title: r.description || 'Open editor',
                onclick: (e) => { e.stopPropagation(); handlersRef.current.open(r); },
            }, r.name),
            sortValue: (r) => (r.name || '').toLowerCase(),
        },
        {
            key: 'type', label: 'Type',
            render: (r) => (MONITOR_TYPE_META[r.monitorType] || { label: r.monitorType }).label,
            sortValue: (r) => r.monitorType || '',
        },
        {
            key: 'scope', label: 'Scope',
            render: (r) => r._scopeLabel,
            sortValue: (r) => r._scopeLabel || '',
        },
        {
            key: 'severity', label: 'Severity',
            render: (r) => severityChipNode(r.severity),
            sortValue: (r) => (SEVERITY_META[r.severity] || { rank: -1 }).rank,
        },
        {
            key: 'enabled', label: 'Enabled', width: '80px',
            render: (r) => h('input', {
                type: 'checkbox',
                checked: !!r.enabled,
                disabled: !handlersRef.current.manage,
                title: r.enabled ? 'Disable this monitor' : 'Enable this monitor',
                onclick: (e) => e.stopPropagation(),
                ondblclick: (e) => e.stopPropagation(),
                onchange: () => handlersRef.current.toggle(r),
            }),
            sortValue: (r) => (r.enabled ? 0 : 1),
        },
        {
            key: 'updated', label: 'Updated',
            render: (r) => h('span', { title: fmtTime(r.updatedTime) }, fmtAgo(r.updatedTime)),
            sortValue: (r) => (r.updatedTime ? new Date(r.updatedTime).getTime() : 0),
        },
    ];
}

function buildOptions(handlersRef) {
    return {
        selectable: false,
        rowKey: (r) => String(r.id),
        onActivate: (r) => handlersRef.current.open(r),
        emptyText: 'No monitors yet.',
    };
}

/* ---- config seeding / building ------------------------------------------ */

/** Numbers become strings so they can live in controlled inputs. */
function editableConfig(cfg) {
    const out = {};
    for (const k of Object.keys(cfg || {})) {
        const v = cfg[k];
        out[k] = typeof v === 'number' ? String(v) : v;
    }
    return out;
}

/**
 * Normalizes enum-ish config fields to the casing the editors render
 * (server-side parsing is case-insensitive, so this is display-only). For
 * CONNECTION_STATUS, `keepUnknownStates` preserves non-state names found in
 * an existing monitor (round-trip fidelity) but strips them from seeded
 * defaults — they can never match live connector state.
 */
function normalizeTypeConfig(type, cfg, keepUnknownStates) {
    const out = { ...cfg };
    if (type === 'LOW_VOLUME' && typeof out.compareTo === 'string') {
        out.compareTo = out.compareTo.toUpperCase();
    }
    if (type === 'ANOMALY') {
        if (typeof out.metric === 'string') out.metric = out.metric.toLowerCase();
        if (typeof out.direction === 'string') out.direction = out.direction.toUpperCase();
    }
    if (type === 'CONNECTION_STATUS') {
        const names = (Array.isArray(out.alertOnStates) ? out.alertOnStates : [])
            .map((s) => String(s).trim().toUpperCase()).filter(Boolean);
        out.alertOnStates = keepUnknownStates
            ? names
            : names.filter((s) => CONNECTION_STATES.includes(s));
        if (typeof out.rollup === 'string') out.rollup = out.rollup.trim().toUpperCase();
    }
    if (type === 'CHANNEL_STATE') {
        // Same rule as CONNECTION_STATUS, against the deployed-state list:
        // an existing monitor's unrecognized name survives a round trip (it
        // may be a state a newer engine reports), a seeded default is filtered.
        const names = (Array.isArray(out.alertOnStates) ? out.alertOnStates : [])
            .map((s) => String(s).trim().toUpperCase()).filter(Boolean);
        out.alertOnStates = keepUnknownStates
            ? names
            : names.filter((s) => CHANNEL_STATES.includes(s));
    }
    return out;
}

/** One editable config per type: defaults for every type, overlaid with the
    monitor's parsed configJson for its own type. */
function seedConfigs(monitor) {
    const configs = {};
    for (const t of MONITOR_TYPE_ORDER) {
        configs[t] = normalizeTypeConfig(t, editableConfig(MONITOR_TYPE_META[t].defaultConfig), false);
    }
    if (monitor && monitor.configJson) {
        try {
            const parsed = JSON.parse(monitor.configJson);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                configs[monitor.monitorType] = normalizeTypeConfig(monitor.monitorType,
                    { ...configs[monitor.monitorType], ...editableConfig(parsed) }, true);
            }
        } catch (e) { /* unparseable stored config: defaults; a save rewrites it */ }
    }
    return configs;
}

/**
 * Builds the wire configJson object for one type from editor state. Blank
 * numeric inputs are omitted (the server/evaluators default every field);
 * malformed numbers throw an Error whose message goes to errorModal.
 */
function buildConfig(type, cfg) {
    const out = {};
    const num = (key, label, opts = {}) => {
        const raw = cfg[key];
        const s = raw == null ? '' : String(raw).trim();
        if (s === '') return;
        const n = Number(s);
        if (!isFinite(n)) throw new Error(`${label} must be a number.`);
        if (opts.integer && !Number.isInteger(n)) throw new Error(`${label} must be a whole number.`);
        if (opts.min != null && n < opts.min) throw new Error(`${label} must be at least ${opts.min}.`);
        if (opts.max != null && n > opts.max) throw new Error(`${label} must be at most ${opts.max}.`);
        out[key] = n;
    };
    switch (type) {
        case 'INACTIVITY':
            num('noDataForSeconds', 'No data for (seconds)', { min: 1, integer: true });
            break;
        case 'LOW_VOLUME': {
            num('windowSeconds', 'Window (seconds)', { min: 1, integer: true });
            const compareTo = String(cfg.compareTo || 'FIXED').toUpperCase();
            out.compareTo = compareTo;
            if (compareTo === 'FIXED') {
                num('minCount', 'Minimum count', { min: 0, integer: true });
            } else {
                num('baselinePercent', 'Baseline percent', { min: 1 });
                num('baselineLookbackDays', 'Baseline lookback (days)', { min: 1, integer: true });
            }
            break;
        }
        case 'ANOMALY':
            out.metric = String(cfg.metric || 'received').toLowerCase();
            num('zScoreThreshold', 'Z-score threshold', { min: 0.1 });
            out.direction = String(cfg.direction || 'BOTH').toUpperCase();
            num('baselineWindowDays', 'Baseline window (days)', { min: 1, integer: true });
            out.useWeekendBucket = cfg.useWeekendBucket !== false;
            break;
        case 'CONNECTION_STATUS': {
            const states = Array.from(new Set(
                (Array.isArray(cfg.alertOnStates) ? cfg.alertOnStates : [])
                    .map((s) => String(s).trim().toUpperCase()).filter(Boolean)));
            // The server falls back to DISCONNECTED for an empty set, but an
            // explicit choice keeps what the operator sees in the editor and
            // what the evaluator matches from ever drifting apart.
            if (!states.length) {
                throw new Error('Select at least one connector state to alert on.');
            }
            out.alertOnStates = states;
            num('minDurationSeconds', 'Minimum duration (seconds)', { min: 0, integer: true });
            // Always written, like compareTo/direction: the server rejects an
            // out-of-enum rollup rather than defaulting it, so sending the
            // editor's own value keeps a monitor saved before rollup existed
            // from quietly acquiring a mode nobody chose.
            out.rollup = String(cfg.rollup || 'CONNECTOR').toUpperCase();
            break;
        }
        case 'ERROR_RATE':
            num('windowSeconds', 'Window (seconds)', { min: 1, integer: true });
            // The server rejects anything outside 0–100 rather than clamping,
            // so bound it here too and fail with the friendlier message.
            num('thresholdPercent', 'Error rate threshold (%)', { min: 0, max: 100 });
            num('minMessages', 'Minimum messages', { min: 0, integer: true });
            break;
        case 'QUEUE_DEPTH':
            num('threshold', 'Queue depth threshold', { min: 0, integer: true });
            num('minDurationSeconds', 'Minimum duration (seconds)', { min: 0, integer: true });
            break;
        case 'CHANNEL_STATE': {
            const states = Array.from(new Set(
                (Array.isArray(cfg.alertOnStates) ? cfg.alertOnStates : [])
                    .map((s) => String(s).trim().toUpperCase()).filter(Boolean)));
            // The server rejects an empty array outright (the evaluator would
            // fall back to its default set, which is not what an operator who
            // cleared every box asked for), so fail here with better words.
            if (!states.length) {
                throw new Error('Select at least one channel state to alert on.');
            }
            out.alertOnStates = states;
            num('minDurationSeconds', 'Minimum duration (seconds)', { min: 0, integer: true });
            break;
        }
        default:
            break;
    }
    return out;
}

/* ---- small form pieces --------------------------------------------------- */

function NumField({ label, value, onChange, hint, min, max, step }) {
    return (
        <div className="field">
            <label>{label}</label>
            <input type="number" value={value == null ? '' : value}
                min={min} max={max} step={step == null ? 1 : step}
                onChange={(e) => onChange(e.target.value)} />
            {hint ? <div className="hint">{hint}</div> : null}
        </div>
    );
}

function SelectField({ label, value, options, onChange, hint }) {
    return (
        <div className="field">
            <label>{label}</label>
            <select value={value} onChange={(e) => onChange(e.target.value)}>
                {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            {hint ? <div className="hint">{hint}</div> : null}
        </div>
    );
}

/* ---- per-type config fieldsets ------------------------------------------- */

function ConfigFields({ type, cfg, setCfg }) {
    switch (type) {
        case 'INACTIVITY':
            return (
                <div className="form-grid">
                    <NumField label="No data for (seconds)" value={cfg.noDataForSeconds} min={1}
                        onChange={(v) => setCfg({ noDataForSeconds: v })}
                        hint="Opens a problem when the channel receives nothing for this long." />
                </div>
            );
        case 'LOW_VOLUME': {
            const compareTo = String(cfg.compareTo || 'FIXED').toUpperCase();
            return (
                <div className="form-grid">
                    <NumField label="Window (seconds)" value={cfg.windowSeconds} min={1}
                        onChange={(v) => setCfg({ windowSeconds: v })}
                        hint="Sliding window whose received count is compared to the floor." />
                    <SelectField label="Compare to" value={compareTo}
                        options={[
                            { value: 'FIXED', label: 'Fixed minimum count' },
                            { value: 'BASELINE_RELATIVE', label: 'Percent of learned baseline' },
                        ]}
                        onChange={(v) => setCfg({ compareTo: v })} />
                    {compareTo === 'FIXED' ? (
                        <NumField label="Minimum count" value={cfg.minCount} min={0}
                            onChange={(v) => setCfg({ minCount: v })}
                            hint="Breach when fewer messages than this arrive in the window." />
                    ) : (
                        <>
                            <NumField label="Baseline percent" value={cfg.baselinePercent} min={1}
                                onChange={(v) => setCfg({ baselinePercent: v })}
                                hint="Breach when volume drops below this percentage of the learned hourly baseline." />
                            <NumField label="Baseline lookback (days)" value={cfg.baselineLookbackDays} min={1}
                                onChange={(v) => setCfg({ baselineLookbackDays: v })}
                                hint="How much hourly rollup history feeds the baseline." />
                        </>
                    )}
                </div>
            );
        }
        case 'ANOMALY':
            return (
                <div className="form-grid">
                    <SelectField label="Metric" value={String(cfg.metric || 'received').toLowerCase()}
                        options={[
                            { value: 'received', label: 'Received' },
                            { value: 'sent', label: 'Sent' },
                            { value: 'error', label: 'Errors' },
                        ]}
                        onChange={(v) => setCfg({ metric: v })} />
                    <NumField label="Z-score threshold" value={cfg.zScoreThreshold} min={0.1} step={0.1}
                        onChange={(v) => setCfg({ zScoreThreshold: v })}
                        hint="Standard deviations from the hour-of-day baseline before a breach." />
                    <SelectField label="Direction" value={String(cfg.direction || 'BOTH').toUpperCase()}
                        options={[
                            { value: 'BOTH', label: 'Both directions' },
                            { value: 'LOW_ONLY', label: 'Low only (volume drops)' },
                            { value: 'HIGH_ONLY', label: 'High only (volume spikes)' },
                        ]}
                        onChange={(v) => setCfg({ direction: v })} />
                    <NumField label="Baseline window (days)" value={cfg.baselineWindowDays} min={1}
                        onChange={(v) => setCfg({ baselineWindowDays: v })}
                        hint="How much hourly rollup history feeds the baseline." />
                    <div className="field span-2">
                        <label>Weekend handling</label>
                        <label className="check">
                            <input type="checkbox" checked={cfg.useWeekendBucket !== false}
                                onChange={(e) => setCfg({ useWeekendBucket: e.target.checked })} />
                            Keep separate weekend and weekday baselines
                        </label>
                    </div>
                </div>
            );
        case 'CONNECTION_STATUS': {
            const selected = Array.isArray(cfg.alertOnStates) ? cfg.alertOnStates : [];
            // Names carried over from an existing monitor that are not live
            // connection states (e.g. FAILURE) stay visible and removable but
            // are flagged — they will never match.
            const extras = selected.filter((s) => !CONNECTION_STATES.includes(s));
            const toggle = (s, on) => setCfg({
                alertOnStates: on ? [...selected, s] : selected.filter((x) => x !== s),
            });
            return (
                <div className="form-grid">
                    <div className="field span-2">
                        <label>Alert on connector states</label>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px' }}>
                            {CONNECTION_STATES.map((s) => (
                                <label key={s} className="check">
                                    <input type="checkbox" checked={selected.includes(s)}
                                        onChange={(e) => toggle(s, e.target.checked)} />
                                    {stateLabel(s)}
                                </label>
                            ))}
                            {extras.map((s) => (
                                <label key={s} className="check"
                                    title="Not a recorded connection state — never matches.">
                                    <input type="checkbox" checked
                                        onChange={(e) => toggle(s, e.target.checked)} />
                                    {stateLabel(s)} (transient)
                                </label>
                            ))}
                        </div>
                        <div className="hint">
                            Breach while any connector sits in a selected state for the minimum duration.
                        </div>
                    </div>
                    <NumField label="Minimum duration (seconds)" value={cfg.minDurationSeconds} min={0}
                        onChange={(v) => setCfg({ minDurationSeconds: v })}
                        hint="The state must persist at least this long before it counts as a breach." />
                    <SelectField label="Alerting" value={String(cfg.rollup || 'CONNECTOR').toUpperCase()}
                        options={ROLLUP_MODES} onChange={(v) => setCfg({ rollup: v })}
                        hint={'Per connector opens one problem per failing connector; once per '
                            + 'channel opens a single problem that clears only when every '
                            + 'connector recovers.'} />
                </div>
            );
        }
        case 'ERROR_RATE':
            return (
                <div className="form-grid">
                    <NumField label="Error rate threshold (%)" value={cfg.thresholdPercent}
                        min={0} max={100} step={0.5}
                        onChange={(v) => setCfg({ thresholdPercent: v })}
                        hint="Breach when errored messages reach this share of received messages in the window." />
                    <NumField label="Window (seconds)" value={cfg.windowSeconds} min={1}
                        onChange={(v) => setCfg({ windowSeconds: v })}
                        hint="Sliding window the error and received counts are summed over." />
                    <NumField label="Minimum messages" value={cfg.minMessages} min={0}
                        onChange={(v) => setCfg({ minMessages: v })}
                        hint="Windows with fewer received messages than this report insufficient data, never OK." />
                </div>
            );
        case 'QUEUE_DEPTH':
            return (
                <div className="form-grid">
                    <NumField label="Queue depth threshold" value={cfg.threshold} min={0}
                        onChange={(v) => setCfg({ threshold: v })}
                        hint="Breach when the channel's queued-message count is at or above this." />
                    <NumField label="Minimum duration (seconds)" value={cfg.minDurationSeconds} min={0}
                        onChange={(v) => setCfg({ minDurationSeconds: v })}
                        hint="The depth must hold at least this long before it counts as a breach." />
                    <div className="field span-2">
                        <div className="hint">
                            Depth is read from the latest sample; with no recent sample the monitor
                            reports insufficient data.
                        </div>
                    </div>
                </div>
            );
        case 'CHANNEL_STATE': {
            const selected = Array.isArray(cfg.alertOnStates) ? cfg.alertOnStates : [];
            const extras = selected.filter((s) => !CHANNEL_STATES.includes(s));
            const toggle = (s, on) => setCfg({
                alertOnStates: on ? [...selected, s] : selected.filter((x) => x !== s),
            });
            const box = (s, suffix, title) => (
                <label key={s} className="check" title={title}>
                    <input type="checkbox" checked={selected.includes(s)}
                        onChange={(e) => toggle(s, e.target.checked)} />
                    {stateLabel(s)}{suffix || ''}
                </label>
            );
            return (
                <div className="form-grid">
                    <div className="field span-2">
                        <label>Alert on channel states</label>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px' }}>
                            {CHANNEL_RESTING_STATES.map((s) => box(s))}
                        </div>
                        <div className="hint">
                            A channel does not leave these on its own — this is the
                            &ldquo;stopped and nobody noticed&rdquo; set.
                        </div>
                    </div>
                    <div className="field span-2">
                        <label>Also alert on transitional states</label>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px' }}>
                            {CHANNEL_TRANSITIONAL_STATES.map((s) => box(s))}
                            {extras.map((s) => box(s, ' (unknown)',
                                'Not a state this engine reports — it will never match.'))}
                        </div>
                        <div className="hint">
                            Every redeploy passes through these. Only select one with a minimum
                            duration long enough to clear a normal deployment — that is how you
                            catch a channel stuck mid-start.
                        </div>
                    </div>
                    <NumField label="Minimum duration (seconds)" value={cfg.minDurationSeconds} min={0}
                        onChange={(v) => setCfg({ minDurationSeconds: v })}
                        hint="The state must persist at least this long before it counts as a breach." />
                    <div className="field span-2">
                        <div className="hint">
                            The only monitor type evaluated against channels that are not started —
                            that is the point of it. Duration is counted from the first tick that saw
                            the current state, so a monitor created while a channel is already
                            stopped breaches one minimum-duration later rather than immediately.
                        </div>
                    </div>
                </div>
            );
        }
        default:
            return null;
    }
}

/* ---- test result panel --------------------------------------------------- */

function showTestResult(name, result) {
    const r = result || {};
    const outcomes = Array.isArray(r.outcomes) ? r.outcomes : [];
    detailModal({
        title: `Monitor test — ${name}`,
        badge: { text: r.ok ? 'OK' : 'ERRORS', tone: r.ok ? 'ok' : 'err' },
        meta: r.message || `${outcomes.length} channel(s) evaluated`,
        sections: outcomes.length
            ? outcomes.map((o) => ({
                label: o.channelName || o.channelId || '(unknown channel)',
                text: `${o.status || '—'}${o.valueSummary ? ' — ' + o.valueSummary : ''}`,
            }))
            : [{
                label: 'No channels',
                text: 'No started channels matched this monitor\'s scope.',
            }],
    });
}

/* ---- alerting history panel ---------------------------------------------- */

/* Ranges the history chart offers. Days, not hours: the server buckets by
   calendar day, so anything under a couple of weeks is a handful of bars. 1y
   sits just inside the server's 366-day ceiling. */
const HISTORY_RANGES = [
    { key: '7d', label: '7d', days: 7 },
    { key: '30d', label: '30d', days: 30 },
    { key: '90d', label: '90d', days: 90 },
    { key: '1y', label: '1y', days: 365 },
];

const HISTORY_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/* Both charts share these so their plot areas line up pixel-for-pixel under one
   x-axis — a fixed y-axis width is what makes that true regardless of how wide
   the tick labels happen to render. */
const HISTORY_AXIS_WIDTH = 52;
const HISTORY_MARGIN = { top: 6, right: 12, bottom: 0, left: 0 };

/* Local twin of ui.jsx's chart tooltip style (not exported from there). */
const HISTORY_TOOLTIP_STYLE = {
    background: 'var(--bg2)',
    border: '1px solid var(--line)',
    borderRadius: 'var(--radius)',
    color: 'var(--text)',
    fontSize: 11,
    padding: '6px 9px',
};

/** Seconds -> "45s" / "12m" / "3h 20m" / "2d 4h". Two units at most. */
function fmtSeconds(seconds) {
    const s = Number(seconds);
    if (!isFinite(s) || s < 0) return '—';
    if (s < 60) return `${Math.round(s)}s`;
    if (s < 3600) return `${Math.round(s / 60)}m`;
    if (s < 86400) {
        const hours = Math.floor(s / 3600);
        const mins = Math.round((s % 3600) / 60);
        return mins ? `${hours}h ${mins}m` : `${hours}h`;
    }
    const days = Math.floor(s / 86400);
    const hours = Math.round((s % 86400) / 3600);
    return hours ? `${days}d ${hours}h` : `${days}d`;
}

/* Bucket instants are server-local midnights; the labels render in the
   browser's zone, the same documented compromise ui.jsx's ActivityChart ticks
   make. The tooltip carries the fuller label. */
function dayTick(t) {
    const d = new Date(t);
    return `${HISTORY_MONTHS[d.getMonth()]} ${d.getDate()}`;
}

function dayLabel(t) {
    const d = new Date(t);
    return `${HISTORY_MONTHS[d.getMonth()]} ${d.getDate()}, ${d.getFullYear()}`;
}

/**
 * A monitor's alerting history: alerts opened per day, and mean time to
 * resolve per day.
 *
 * <p>Two stacked single-series charts sharing one x-axis, never one chart with
 * two y-scales. A count and a duration have no common unit, so plotting them
 * against two axes would let the arbitrary alignment of those axes invent a
 * correlation that is not in the data — the classic way a monitoring chart
 * lies. Stacked, the reader compares the two by looking down a column, which is
 * the comparison they actually want ("we fired a lot AND took a long time").
 *
 * <p>The MTTR line deliberately breaks wherever a day has no resolve time:
 * either nothing fired, or everything that fired is still open. The server
 * sends null for both cases and this passes it straight through with
 * connectNulls off, because the alternative — substituting 0 — draws a dive to
 * the floor that reads as "we resolved everything instantly" on precisely the
 * days when either nothing happened or nothing has been fixed yet.
 *
 * <p>Fetch/label/error idiom follows ui.jsx's ChannelActivityPanel: failures
 * stay inline with a Retry rather than becoming toasts (host 'warn' toasts are
 * acknowledge-to-dismiss modals), and the range control sits once in the panel
 * header above everything it scopes.
 */
function MonitorHistoryPanel({ monitorId }) {
    const [rangeKey, setRangeKey] = React.useState('30d');
    // Memoised: these are useApi deps, so a fresh Date.now() per render would
    // refetch on every render.
    const window_ = React.useMemo(() => {
        const range = HISTORY_RANGES.find((r) => r.key === rangeKey) || HISTORY_RANGES[1];
        const to = Date.now();
        return { from: to - range.days * 86400000, to };
    }, [rangeKey]);

    const api = useApi(() => getMonitorHistory(monitorId, window_), [monitorId, window_]);
    const history = api.data;

    const data = React.useMemo(() => {
        const points = history && Array.isArray(history.points) ? history.points : [];
        return points
            .map((p) => ({
                t: new Date(p.bucket).getTime(),
                alerts: Number(p.alertCount) || 0,
                // Null, never 0 — see the component doc. `== null` also catches
                // the key being absent entirely.
                mttr: p.avgResolveSeconds == null ? null : Number(p.avgResolveSeconds),
            }))
            .filter((p) => !isNaN(p.t));
    }, [history]);

    const totals = React.useMemo(() => {
        let alerts = 0;
        let activeDays = 0;
        let worstMttr = null;
        data.forEach((p) => {
            alerts += p.alerts;
            if (p.alerts > 0) activeDays += 1;
            if (p.mttr != null && (worstMttr == null || p.mttr > worstMttr)) worstMttr = p.mttr;
        });
        return { alerts, activeDays, worstMttr };
    }, [data]);

    const hasMttr = data.some((p) => p.mttr != null);

    return (
        <div className="panel mb-3">
            <div className="panel-header" style={{ flexWrap: 'wrap', rowGap: 6 }}>
                Alerting history
                <div className="panel-tools" style={{ flexWrap: 'wrap', rowGap: 6 }}>
                    <div className="segpill">
                        {HISTORY_RANGES.map((r) => (
                            <button key={r.key} type="button"
                                className={rangeKey === r.key ? 'on' : ''}
                                onClick={() => setRangeKey(r.key)}>{r.label}</button>
                        ))}
                    </div>
                </div>
            </div>
            <div className="panel-body">
                {api.error ? (
                    <div style={{ marginBottom: 8 }}>
                        <span className="text-err">Could not load history.</span>{' '}
                        <span className="text-text-dim">{api.error}</span>{' '}
                        <button type="button" className="btn btn-sm" onClick={api.reload}>Retry</button>
                    </div>
                ) : null}
                {!history && !api.error ? <div className="sn-empty">Loading history…</div> : null}
                {history && totals.alerts === 0 ? (
                    <div className="sn-empty">
                        No alerts opened in the selected range.
                    </div>
                ) : null}
                {history && totals.alerts > 0 ? (
                    <>
                        <div className="sn-tile-label" style={{ marginBottom: 2 }}>Alerts opened per day</div>
                        <div className="sn-chart" style={{ width: '100%', height: 150 }}>
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={data} margin={HISTORY_MARGIN} barCategoryGap="8%">
                                    <CartesianGrid stroke="var(--line)" strokeOpacity={0.6} vertical={false} />
                                    {/* Tick labels live on the lower chart only —
                                        one shared axis for the pair. scale is pinned
                                        rather than left to the default so both charts
                                        place a day at the same x; recharts would
                                        otherwise band-scale the bars and point-scale
                                        the line, offsetting them by half a day. */}
                                    <XAxis dataKey="t" scale="band" tick={false} height={6}
                                        stroke="var(--line-strong)" />
                                    <YAxis allowDecimals={false} width={HISTORY_AXIS_WIDTH}
                                        stroke="var(--line-strong)"
                                        tick={{ fill: 'var(--text-dim)', fontSize: 10 }}
                                        tickFormatter={(v) => fmtNum(v)} />
                                    <Tooltip
                                        labelFormatter={dayLabel}
                                        formatter={(value) => [fmtNum(value), 'Alerts opened']}
                                        contentStyle={HISTORY_TOOLTIP_STYLE}
                                        labelStyle={{ color: 'var(--text-dim)', marginBottom: 4 }}
                                        itemStyle={{ color: 'var(--text)', padding: 0 }}
                                        cursor={{ fill: 'var(--line)', fillOpacity: 0.35 }}
                                        isAnimationActive={false} />
                                    {/* maxBarSize keeps a 7-day range from rendering
                                        as seven fat slabs; the percentage gap is what
                                        survives a 365-day range, where a fixed pixel
                                        gap would consume the whole band. */}
                                    <Bar dataKey="alerts" fill="var(--accent)" radius={[3, 3, 0, 0]}
                                        maxBarSize={28} isAnimationActive={false} />
                                </BarChart>
                            </ResponsiveContainer>
                        </div>

                        <div className="sn-tile-label" style={{ margin: '8px 0 2px' }}>
                            Mean time to resolve
                        </div>
                        {hasMttr ? (
                            <div className="sn-chart" style={{ width: '100%', height: 150 }}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <LineChart data={data} margin={HISTORY_MARGIN}>
                                        <CartesianGrid stroke="var(--line)" strokeOpacity={0.6} vertical={false} />
                                        <XAxis dataKey="t" scale="band" tickFormatter={dayTick}
                                            interval="preserveStartEnd" minTickGap={28}
                                            stroke="var(--line-strong)"
                                            tick={{ fill: 'var(--text-dim)', fontSize: 10 }} />
                                        <YAxis width={HISTORY_AXIS_WIDTH}
                                            stroke="var(--line-strong)"
                                            tick={{ fill: 'var(--text-dim)', fontSize: 10 }}
                                            tickFormatter={fmtSeconds} />
                                        <Tooltip
                                            labelFormatter={dayLabel}
                                            formatter={(value) => [fmtSeconds(value), 'Mean time to resolve']}
                                            contentStyle={HISTORY_TOOLTIP_STYLE}
                                            labelStyle={{ color: 'var(--text-dim)', marginBottom: 4 }}
                                            itemStyle={{ color: 'var(--text)', padding: 0 }}
                                            cursor={{ stroke: 'var(--line-strong)' }}
                                            isAnimationActive={false} />
                                        {/* connectNulls stays off: the gap is the
                                            statement. See the component doc. */}
                                        <Line dataKey="mttr" stroke="var(--accent)" strokeWidth={2}
                                            dot={{ r: 2 }} activeDot={{ r: 4 }} connectNulls={false}
                                            isAnimationActive={false} />
                                    </LineChart>
                                </ResponsiveContainer>
                            </div>
                        ) : (
                            <div className="sn-empty">
                                No alerts in this range have been resolved yet.
                            </div>
                        )}
                    </>
                ) : null}
                {history ? (
                    <div className="sn-hint" style={{ marginTop: 6 }}>
                        {fmtNum(totals.alerts)} alert{totals.alerts === 1 ? '' : 's'}
                        {' over '}{fmtNum(data.length)} day{data.length === 1 ? '' : 's'}
                        {totals.activeDays ? ` · ${fmtNum(totals.activeDays)} day${totals.activeDays === 1 ? '' : 's'} with alerts` : ''}
                        {totals.worstMttr != null ? ` · worst daily MTTR ${fmtSeconds(totals.worstMttr)}` : ''}
                        {api.loading ? ' · updating…' : ''}
                        {history.truncated ? (
                            <>
                                {' · '}
                                <span className="text-err"
                                    title="More alerts exist than this view counts — the earliest days undercount.">
                                    partial: too many alerts to count in full
                                </span>
                            </>
                        ) : null}
                    </div>
                ) : null}
            </div>
        </div>
    );
}

/* ---- editor sub-view ----------------------------------------------------- */

function MonitorEditor({ monitor, monitors, channels, groups, tags, manage, onClose, onChanged }) {
    const isNew = !monitor;
    const [name, setName] = React.useState((monitor && monitor.name) || '');
    const [description, setDescription] = React.useState((monitor && monitor.description) || '');
    const [runbookUrl, setRunbookUrl] = React.useState((monitor && monitor.runbookUrl) || '');
    const [monitorType, setMonitorType] = React.useState((monitor && monitor.monitorType) || 'INACTIVITY');
    const [scopeType, setScopeType] = React.useState((monitor && monitor.scopeType) || 'ALL');
    const [scopeId, setScopeId] = React.useState((monitor && monitor.scopeId) || '');
    const [severity, setSeverity] = React.useState((monitor && monitor.severity) || 'HIGH');
    const [minBreaches, setMinBreaches] = React.useState(
        monitor ? String(Math.max(1, monitor.minConsecutiveBreaches || 1)) : '1');
    const [suppressedBy, setSuppressedBy] = React.useState(
        monitor && monitor.suppressedByMonitorId != null ? String(monitor.suppressedByMonitorId) : '');
    const [enabled, setEnabled] = React.useState(monitor ? !!monitor.enabled : true);
    const [configs, setConfigs] = React.useState(() => seedConfigs(monitor));
    const [busy, setBusy] = React.useState(null);

    const cfg = configs[monitorType] || {};
    const setCfg = (patch) => setConfigs((c) => ({
        ...c, [monitorType]: { ...(c[monitorType] || {}), ...patch },
    }));
    const others = (monitors || []).filter((m) => !monitor || m.id !== monitor.id);

    /** Editor state -> Monitor wire object; throws Error on client-side issues. */
    const buildPayload = () => {
        const trimmed = name.trim();
        if (!trimmed) throw new Error('Name is required.');
        if (scopeType === 'CHANNEL' && !scopeId) throw new Error('Select a channel for the CHANNEL scope.');
        if (scopeType === 'GROUP' && !scopeId) throw new Error('Select a channel group for the GROUP scope.');
        if (scopeType === 'TAG' && !scopeId) throw new Error('Select a channel tag for the TAG scope.');
        const rawBreaches = String(minBreaches).trim();
        const breaches = rawBreaches === '' ? 1 : Number(rawBreaches);
        if (!Number.isInteger(breaches) || breaches < 1) {
            throw new Error('Minimum consecutive breaches must be a whole number of at least 1.');
        }
        /* MonitorService is the authority and rejects anything that is not an
           absolute http/https URL — this mirror exists to say so before the
           round trip, and because the reason the server refuses ("this link is
           opened in an operator's browser") is worth stating where the value is
           typed. */
        const runbook = runbookUrl.trim();
        if (runbook && !/^https?:\/\/[^\s/?#]+[^\s]*$/i.test(runbook)) {
            throw new Error('Runbook URL must be an absolute http:// or https:// link, '
                + 'e.g. https://wiki.example.org/runbooks/adt-inactivity.');
        }
        return {
            id: monitor ? monitor.id : undefined,
            name: trimmed,
            description: description.trim() || null,
            runbookUrl: runbook || null,
            monitorType,
            scopeType,
            scopeId: scopeType === 'ALL' ? null : scopeId,
            enabled,
            severity,
            configJson: JSON.stringify(buildConfig(monitorType, configs[monitorType] || {})),
            minConsecutiveBreaches: breaches,
            suppressedByMonitorId: suppressedBy === '' ? null : Number(suppressedBy),
        };
    };

    const save = async () => {
        let payload;
        try { payload = buildPayload(); }
        catch (e) { errorModal('Cannot Save Monitor', e.message || String(e)); return; }
        setBusy('save');
        try {
            if (isNew) await createMonitor(payload);
            else await updateMonitor(monitor.id, payload);
            toast(`Monitor "${payload.name}" ${isNew ? 'created' : 'saved'}.`, 'success');
            onChanged();
            onClose();
        } catch (e) {
            // 400 validation messages from MonitorService arrive here.
            errorModal(isNew ? 'Create Failed' : 'Save Failed', errText(e), payload.name);
        } finally {
            setBusy(null);
        }
    };

    const test = async () => {
        let payload;
        try { payload = buildPayload(); }
        catch (e) { errorModal('Cannot Test Monitor', e.message || String(e)); return; }
        setBusy('test');
        try {
            showTestResult(payload.name, await testMonitor(payload));
        } catch (e) {
            errorModal('Test Failed', errText(e), payload.name);
        } finally {
            setBusy(null);
        }
    };

    const del = async () => {
        const ok = await confirmDialog('Delete Monitor',
            `Delete "${monitor.name}"? Its trigger states are removed and it stops being evaluated.`,
            { danger: true, okLabel: 'Delete' });
        if (!ok) return;
        setBusy('delete');
        try {
            await deleteMonitor(monitor.id);
            toast(`Monitor "${monitor.name}" deleted.`, 'success');
            onChanged();
            onClose();
        } catch (e) {
            errorModal('Delete Failed', errText(e), monitor.name);
        } finally {
            setBusy(null);
        }
    };

    return (
        <>
        <div className="panel mb-3">
            <div className="panel-header">
                {isNew ? 'New monitor' : `Edit monitor — ${monitor.name}`}
                <div className="panel-tools">
                    <button className="btn btn-sm" onClick={onClose}>Back to list</button>
                </div>
            </div>
            <div className="panel-body">
                {!manage ? (
                    <div className="sn-hint" style={{ marginBottom: 10 }}>
                        Read-only: the Manage Monitoring permission is required to change monitors.
                    </div>
                ) : null}
                <fieldset disabled={!manage} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
                    <div className="form-grid">
                        <div className="field">
                            <label>Name</label>
                            <input value={name} onChange={(e) => setName(e.target.value)}
                                placeholder="e.g. ADT feed inactivity" />
                        </div>
                        <div className="field">
                            <label>Type</label>
                            <select value={monitorType} onChange={(e) => setMonitorType(e.target.value)}>
                                {MONITOR_TYPE_ORDER.map((t) => (
                                    <option key={t} value={t}>{MONITOR_TYPE_META[t].label}</option>
                                ))}
                            </select>
                            <div className="hint">{(MONITOR_TYPE_META[monitorType] || {}).description}</div>
                        </div>
                        <div className="field span-2">
                            <label>Description</label>
                            <input value={description} onChange={(e) => setDescription(e.target.value)}
                                placeholder="Optional operator notes" />
                        </div>
                        <div className="field span-2">
                            <label>Runbook URL</label>
                            <input type="url" value={runbookUrl}
                                onChange={(e) => setRunbookUrl(e.target.value)}
                                placeholder="https://wiki.example.org/runbooks/adt-inactivity" />
                            <div className="hint">
                                Optional. Linked on every problem and carried into every
                                notification; must be an absolute http:// or https:// link.
                            </div>
                        </div>
                        <div className="field span-2">
                            <label>Scope</label>
                            <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                                <div className="segpill">
                                    {SCOPE_TYPES.map((s) => (
                                        <button key={s.key} type="button"
                                            className={scopeType === s.key ? 'on' : ''}
                                            onClick={() => {
                                                if (scopeType !== s.key) { setScopeType(s.key); setScopeId(''); }
                                            }}>
                                            {s.label}
                                        </button>
                                    ))}
                                </div>
                                {scopeType === 'CHANNEL' ? (
                                    <ChannelPicker value={scopeId} channels={channels}
                                        emptyLabel="Select a channel…" onChange={setScopeId} />
                                ) : null}
                                {scopeType === 'GROUP' ? (
                                    <ChannelGroupPicker value={scopeId} groups={groups}
                                        emptyLabel="Select a group…" onChange={setScopeId} />
                                ) : null}
                                {scopeType === 'TAG' ? (
                                    <TagPicker value={scopeId} tags={tags}
                                        emptyLabel="Select a tag…" onChange={setScopeId} />
                                ) : null}
                            </div>
                            <div className="hint">Only started channels are evaluated.</div>
                        </div>
                        <div className="field">
                            <label>Severity</label>
                            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                                <select value={severity} onChange={(e) => setSeverity(e.target.value)}>
                                    {SEVERITY_ORDER.map((s) => (
                                        <option key={s} value={s}>{SEVERITY_META[s].label}</option>
                                    ))}
                                </select>
                                <SeverityChip severity={severity} />
                            </div>
                        </div>
                        <div className="field">
                            <label>Min consecutive breaches</label>
                            <input type="number" min="1" step="1" value={minBreaches}
                                onChange={(e) => setMinBreaches(e.target.value)} />
                            <div className="hint">
                                Ticks the condition must hold before a problem opens.
                            </div>
                        </div>
                        <div className="field">
                            <label>Suppressed by</label>
                            <select value={suppressedBy} onChange={(e) => setSuppressedBy(e.target.value)}>
                                <option value="">None</option>
                                {others.map((m) => (
                                    <option key={m.id} value={String(m.id)}>{m.name}</option>
                                ))}
                            </select>
                            <div className="hint">
                                While the selected monitor has an open problem on the same channel,
                                this monitor's new problems are suppressed.
                            </div>
                        </div>
                        <div className="field">
                            <label>Enabled</label>
                            <label className="check">
                                <input type="checkbox" checked={enabled}
                                    onChange={(e) => setEnabled(e.target.checked)} />
                                Evaluate this monitor
                            </label>
                        </div>
                    </div>

                    <div style={{ borderTop: '1px solid var(--line)', margin: '4px 0 12px' }} />
                    <div className="sn-tile-label" style={{ marginBottom: 8 }}>
                        {(MONITOR_TYPE_META[monitorType] || { label: monitorType }).label} settings
                    </div>
                    <ConfigFields type={monitorType} cfg={cfg} setCfg={setCfg} />
                </fieldset>
            </div>
            <div className="panel-body"
                style={{ borderTop: '1px solid var(--line)', display: 'flex', gap: 8, alignItems: 'center' }}>
                {manage ? (
                    <button className="btn btn-primary" onClick={save} disabled={!!busy}>
                        {busy === 'save' ? 'Saving…' : (isNew ? 'Create monitor' : 'Save changes')}
                    </button>
                ) : null}
                {manage ? (
                    <button className="btn" onClick={test} disabled={!!busy}
                        title="Dry-run this configuration against every started channel in scope">
                        {busy === 'test' ? 'Testing…' : 'Test'}
                    </button>
                ) : null}
                <button className="btn" onClick={onClose}
                    disabled={busy === 'save' || busy === 'delete'}>
                    {manage ? 'Cancel' : 'Close'}
                </button>
                <span style={{ flex: 1 }} />
                {manage && !isNew ? (
                    <button className="btn btn-danger" onClick={del} disabled={!!busy}>
                        {busy === 'delete' ? 'Deleting…' : 'Delete'}
                    </button>
                ) : null}
            </div>
        </div>
        {/* Only for a saved monitor: an unsaved draft has no id to query, and
            no history to have. */}
        {!isNew ? <MonitorHistoryPanel monitorId={monitor.id} /> : null}
        </>
    );
}

/* ---- page ---------------------------------------------------------------- */

export function MonitorsPage() {
    const monitors = useApi(listMonitors, []);
    const channels = useApi(getCoreChannels, []);
    const groups = useApi(getCoreChannelGroups, []);
    const tags = useApi(getCoreTags, []);
    const [view, setView] = React.useState(null); // null | { mode: 'new' } | { mode: 'edit', monitor }
    const manage = canManage();

    // Scope names silently degrade to raw ids when these fail — still surface
    // it, but as 'info': the host shows warn toasts as blocking modals, too
    // heavy for a non-fatal background lookup.
    React.useEffect(() => {
        if (channels.error) toast(`Channel list failed to load: ${channels.error}`, 'info');
    }, [channels.error]);
    React.useEffect(() => {
        if (groups.error) toast(`Channel group list failed to load: ${groups.error}`, 'info');
    }, [groups.error]);
    React.useEffect(() => {
        if (tags.error) toast(`Channel tag list failed to load: ${tags.error}`, 'info');
    }, [tags.error]);

    const monitorList = Array.isArray(monitors.data) ? monitors.data : [];
    const channelNames = React.useMemo(() => {
        const map = {};
        (Array.isArray(channels.data) ? channels.data : []).forEach((c) => { map[c.channelId] = c.name; });
        return map;
    }, [channels.data]);
    const groupNames = React.useMemo(() => {
        const map = {};
        (Array.isArray(groups.data) ? groups.data : []).forEach((g) => { map[g.id] = g.name; });
        return map;
    }, [groups.data]);
    const tagNames = React.useMemo(() => {
        const map = {};
        (Array.isArray(tags.data) ? tags.data : []).forEach((t) => { map[t.id] = t.name; });
        return map;
    }, [tags.data]);
    const rows = React.useMemo(() => monitorList.map((m) => ({
        ...m,
        _scopeLabel: scopeLabel(m, channelNames, groupNames, tagNames),
        // eslint-disable-next-line react-hooks/exhaustive-deps
    })), [monitors.data, channelNames, groupNames, tagNames]); // monitorList derives from monitors.data

    const toggleEnabled = async (m) => {
        if (!canManage()) return;
        try {
            await setMonitorEnabled(m.id, !m.enabled);
            toast(`Monitor "${m.name}" ${m.enabled ? 'disabled' : 'enabled'}.`, 'success');
        } catch (e) {
            errorModal('Enable/Disable Failed', errText(e), m.name);
        }
        monitors.reload(); // re-sync the checkbox either way
    };

    const handlersRef = React.useRef({});
    handlersRef.current = {
        open: (m) => setView({ mode: 'edit', monitor: m }),
        toggle: toggleEnabled,
        manage,
    };
    const defRef = React.useRef(null);
    if (!defRef.current) {
        defRef.current = { columns: buildColumns(handlersRef), options: buildOptions(handlersRef) };
    }
    const dt = useDataTable(defRef.current.columns, defRef.current.options, rows);

    const firstLoading = monitors.loading && monitors.data == null;
    const isEmpty = !firstLoading && !monitors.error && rows.length === 0;
    const tableHidden = firstLoading || isEmpty || (monitors.error && rows.length === 0);

    return (
        <>
            {/* The list stays mounted while the editor is open: useDataTable's
                host <div> must not unmount, or the imperative table is lost. */}
            <div style={{ display: view ? 'none' : undefined }}>
                <div className="panel">
                    <div className="panel-header">
                        Monitors
                        <div className="panel-tools">
                            {rows.length ? (
                                <span className="sn-hint">
                                    {rows.length} monitor{rows.length === 1 ? '' : 's'}
                                </span>
                            ) : null}
                            <button className="btn btn-sm" onClick={monitors.reload}
                                disabled={monitors.loading}>
                                {monitors.loading ? 'Refreshing…' : 'Refresh'}
                            </button>
                            {manage ? (
                                <button className="btn btn-sm btn-primary"
                                    onClick={() => setView({ mode: 'new' })}>
                                    New monitor
                                </button>
                            ) : null}
                        </div>
                    </div>
                    {monitors.error ? (
                        <div className="panel-body" style={{ borderBottom: '1px solid var(--line)' }}>
                            <span className="text-err">Could not load monitors.</span>{' '}
                            <span className="text-text-dim">{monitors.error}</span>{' '}
                            <button className="btn btn-sm" onClick={monitors.reload}>Retry</button>
                        </div>
                    ) : null}
                    {firstLoading ? (
                        <div className="panel-body sn-hint">Loading monitors…</div>
                    ) : null}
                    {isEmpty ? (
                        <div className="panel-body">
                            <div className="sn-empty">
                                No monitors yet.
                                {manage ? (
                                    <div style={{ marginTop: 8 }}>
                                        <button className="btn btn-primary"
                                            onClick={() => setView({ mode: 'new' })}>
                                            Create your first monitor
                                        </button>
                                    </div>
                                ) : null}
                            </div>
                        </div>
                    ) : null}
                    <div className="panel-body flush" style={{ display: tableHidden ? 'none' : undefined }}>
                        {dt.node}
                    </div>
                </div>
            </div>
            {view ? (
                <MonitorEditor
                    key={view.monitor ? `edit-${view.monitor.id}` : 'new'}
                    monitor={view.monitor || null}
                    monitors={monitorList}
                    channels={Array.isArray(channels.data) ? channels.data : null}
                    groups={Array.isArray(groups.data) ? groups.data : null}
                    tags={Array.isArray(tags.data) ? tags.data : null}
                    manage={manage}
                    onClose={() => setView(null)}
                    onChanged={monitors.reload}
                />
            ) : null}
        </>
    );
}
