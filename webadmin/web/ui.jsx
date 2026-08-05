// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Shared UI toolkit for the Sentinel webadmin pages: severity/type metadata,
// chips and tiles, recharts-based charts, pickers, the Action condition
// builder, and the useApi / useDataTable hooks. Custom classes are .sn-
// prefixed and defined in SENTINEL_CSS (plugin.jsx), which the root view
// injects globally; these components always render inside .sn-view.

import { platform } from '@oie/web-shell';
import { DataTable, fmtDate, fmtNumber } from '@oie/web-ui';
import {
    ResponsiveContainer, LineChart, Line, XAxis, YAxis,
    CartesianGrid, Tooltip, Legend,
} from 'recharts';
import {
    errText, getCoreChannels, getCoreChannelGroups, getCoreTags, getCoreUsers, listMonitors,
} from './api.js';

const React = platform.React;

/* ---- permissions (task names from SentinelServletInterface) ------------- */

export const canManage = () => platform.checkTask('', 'doManageSentinel');
export const canAcknowledge = () => platform.checkTask('', 'doAcknowledgeSentinelProblem');
// Split tiers (see SentinelServletInterface): on-call can open maintenance
// windows without monitor-authoring rights; settings are administration.
// checkTask fails open when no RBAC plugin is installed.
export const canManageMaintenance = () => platform.checkTask('', 'doManageSentinelMaintenance');
export const canManageSettings = () => platform.checkTask('', 'doManageSentinelSettings');

/* ---- toast (best-effort; use errorModal from @oie/web-ui for user actions) */

export function toast(message, kind) {
    try { platform.ui.toast(message, kind); } catch (e) { /* toast is best-effort */ }
}

/* ---- severity ramp (single source of truth; ordinal, host tokens) ------- */

export const SEVERITY_ORDER = ['INFORMATION', 'WARNING', 'AVERAGE', 'HIGH', 'DISASTER'];

export const SEVERITY_META = {
    INFORMATION: { label: 'Information', color: 'var(--text-dim)', rank: 0 },
    WARNING: { label: 'Warning', color: 'var(--warn)', rank: 1 },
    AVERAGE: { label: 'Average', color: 'var(--amber)', rank: 2 },
    HIGH: { label: 'High', color: 'var(--err)', rank: 3 },
    DISASTER: { label: 'Disaster', color: 'color-mix(in srgb, var(--err) 80%, black)', rank: 4 },
};

export function SeverityChip({ severity }) {
    const meta = SEVERITY_META[severity] || { label: severity || '—', color: 'var(--text-faint)' };
    return (
        <span className="tag sn-sev" style={{ borderColor: `color-mix(in srgb, ${meta.color} 55%, transparent)` }}>
            <span className="sn-sev-dot" style={{ background: meta.color }} />
            {meta.label}
        </span>
    );
}

/* ---- monitor types ------------------------------------------------------ */

export const MONITOR_TYPE_ORDER = ['INACTIVITY', 'LOW_VOLUME', 'ANOMALY', 'CONNECTION_STATUS'];

// defaultConfig mirrors the configJson shapes in webadmin-contract.md; the
// monitors page seeds new-monitor editors from it.
export const MONITOR_TYPE_META = {
    INACTIVITY: {
        label: 'Inactivity',
        description: 'Alerts when a channel receives no messages for a period.',
        defaultConfig: { noDataForSeconds: 3600 },
    },
    LOW_VOLUME: {
        label: 'Low volume',
        description: 'Alerts when message volume drops below a fixed floor or a learned baseline.',
        defaultConfig: { windowSeconds: 3600, compareTo: 'FIXED', minCount: 1, baselinePercent: 50, baselineLookbackDays: 14 },
    },
    ANOMALY: {
        label: 'Anomaly',
        description: 'Alerts when hourly volume deviates from the learned baseline (z-score).',
        defaultConfig: { metric: 'received', zScoreThreshold: 3, direction: 'BOTH', baselineWindowDays: 21, useWeekendBucket: true },
    },
    CONNECTION_STATUS: {
        label: 'Connection status',
        description: 'Alerts when a connector sits in a selected state for too long.',
        // DISCONNECTED is recorded by the connector listener (only INFO/FAILURE
        // are dropped as transient), so "connector down for 5 minutes" — the
        // headline use of this monitor type — is the natural seed.
        defaultConfig: { alertOnStates: ['DISCONNECTED'], minDurationSeconds: 300 },
    },
};

/* ---- formatting --------------------------------------------------------- */

/** Absolute timestamp honouring the topbar Server/Local/UTC toggle. */
export function fmtTime(value) {
    if (value == null || value === '') return '';
    // Wire Instants are ISO-8601 strings, but the host fmtDate accepts only
    // epoch millis (it does Number(value)) — pass millis, or the raw ISO text
    // leaks into the UI and the TZ toggle is silently bypassed.
    const t = value instanceof Date ? value.getTime() : new Date(value).getTime();
    if (isNaN(t)) return String(value);
    try { return fmtDate(t); } catch (e) { return String(value); }
}

/** Relative "3m ago"; falls back to fmtTime beyond a week. Null-safe. */
export function fmtAgo(value) {
    if (value == null || value === '') return '';
    const t = value instanceof Date ? value.getTime() : new Date(value).getTime();
    if (isNaN(t)) return String(value);
    const s = Math.round((Date.now() - t) / 1000);
    if (s < 0) return fmtTime(value);
    if (s < 45) return 'just now';
    if (s < 3600) return `${Math.max(1, Math.round(s / 60))}m ago`;
    if (s < 86400) return `${Math.round(s / 3600)}h ago`;
    if (s < 7 * 86400) return `${Math.round(s / 86400)}d ago`;
    return fmtTime(value);
}

export function fmtNum(n) {
    try { return fmtNumber(n); } catch (e) { return n == null ? '' : String(n); }
}

/* ---- hooks -------------------------------------------------------------- */

/**
 * Async fetch hook: { data, error, loading, reload }. `error` is already
 * errText()-humanized. The fetch re-runs when deps change or reload() is
 * called; stale resolutions after unmount/re-run are dropped.
 */
export function useApi(fn, deps) {
    const [state, setState] = React.useState({ data: null, error: null, loading: true });
    const [tick, setTick] = React.useState(0);
    React.useEffect(() => {
        let cancelled = false;
        setState((s) => ({ ...s, loading: true, error: null }));
        Promise.resolve().then(fn).then(
            (data) => { if (!cancelled) setState({ data, error: null, loading: false }); },
            (e) => { if (!cancelled) setState((s) => ({ ...s, error: errText(e), loading: false })); },
        );
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, deps ? [...deps, tick] : [tick]);
    const reload = React.useCallback(() => setTick((t) => t + 1), []);
    return { data: state.data, error: state.error, loading: state.loading, reload };
}

/**
 * Resolves engine user ids to usernames for "acknowledged by" style displays.
 * Returns a stable `userNameOf(id)` that falls back to `user #id` until
 * /core/users loads (or if it fails — display degrades, never errors) and to
 * an em dash for null.
 */
export function useUsernames() {
    const api = useApi(getCoreUsers, []);
    const map = React.useMemo(() => {
        const m = {};
        (api.data || []).forEach((u) => { if (u.userId != null) m[u.userId] = u.username; });
        return m;
    }, [api.data]);
    return React.useCallback(
        (id) => (id == null ? '—' : (map[id] || `user #${id}`)),
        [map],
    );
}

/**
 * Bridge the @oie/web-ui DataTable (imperative DOM class) into React.
 * IMPORTANT: `columns` and `options` are captured ONCE at mount (host
 * DataTableHost caveat) — hoist them to module consts or useMemo/useRef, or
 * the table keeps the first render's callbacks forever. Column render()
 * callbacks must return DOM nodes (platform.ui.h) or strings, never JSX.
 *
 * Returns { node, table }: render `node`; `table()` yields the DataTable
 * instance (or null before mount) for selectedRows()/clearSelection()/etc.
 */
export function useDataTable(columns, options, rows) {
    const hostRef = React.useRef(null);
    const tableRef = React.useRef(null);
    React.useEffect(() => {
        const host = hostRef.current;
        const table = new DataTable(columns, options);
        tableRef.current = table;
        host.appendChild(table.el);
        return () => { host.replaceChildren(); tableRef.current = null; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    React.useEffect(() => {
        if (tableRef.current && rows) tableRef.current.setRows(rows);
    }, [rows]);
    const table = React.useCallback(() => tableRef.current, []);
    const node = <div ref={hostRef} className="sn-dt-host" />;
    return { node, table };
}

/* ---- stat tile ---------------------------------------------------------- */

/** Dashboard KPI tile. `tone` is a CSS color (e.g. SEVERITY_META[s].color). */
export function StatTile({ label, value, tone, hint, onClick }) {
    return (
        <div className={`panel sn-tile${onClick ? ' sn-tile-click' : ''}`}
            onClick={onClick}
            role={onClick ? 'button' : undefined}>
            <div className="sn-tile-value" style={tone ? { color: tone } : undefined}>{value}</div>
            <div className="sn-tile-label">{label}</div>
            {hint ? <div className="sn-tile-hint">{hint}</div> : null}
        </div>
    );
}

/* ---- charts (recharts; series colors are host tokens) ------------------- */

const CHART_TOOLTIP_STYLE = {
    background: 'var(--bg2)',
    border: '1px solid var(--line)',
    borderRadius: 'var(--radius)',
    color: 'var(--text)',
    fontSize: 11,
    padding: '6px 9px',
};

/** Tiny inline trend line (no axes/legend/tooltip) for
    ChannelActivitySummary.sparkline (an array of numbers). */
export function Sparkline({ data, width = 120, height = 28, color = 'var(--accent)' }) {
    const points = (data || []).map((v, i) => ({ i, v: Number(v) || 0 }));
    if (!points.length) return <span className="sn-spark sn-hint">no data</span>;
    return (
        <span className="sn-spark" style={{ width, height }}>
            <LineChart width={width} height={height} data={points}
                margin={{ top: 3, right: 2, bottom: 2, left: 2 }}>
                <Line type="monotone" dataKey="v" stroke={color} strokeWidth={1.5}
                    dot={false} isAnimationActive={false} />
            </LineChart>
        </span>
    );
}

const hh = (n) => String(n).padStart(2, '0');
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * received/sent/error time series over ActivityPoint[]
 * ({ time: ISO string, received, sent, error, ... }). Colors are contractual:
 * received=var(--accent), sent=var(--ok), error=var(--err). Axis ticks are
 * HH:mm for spans <= 6h, else "MMM d HH:00"; the tooltip label uses fmtTime
 * (honours the TZ toggle — tick labels are browser-local, documented).
 */
export function ActivityChart({ points, height = 260 }) {
    const data = React.useMemo(() => (points || []).map((p) => ({
        t: new Date(p.time).getTime(),
        received: Number(p.received) || 0,
        sent: Number(p.sent) || 0,
        error: Number(p.error) || 0,
    })).filter((p) => !isNaN(p.t)), [points]);

    if (!data.length) return <div className="sn-empty">No activity in this range.</div>;

    const spanMs = data[data.length - 1].t - data[0].t;
    const short = spanMs <= 6 * 3600 * 1000;
    const tickFmt = (t) => {
        const d = new Date(t);
        return short
            ? `${hh(d.getHours())}:${hh(d.getMinutes())}`
            : `${MONTHS[d.getMonth()]} ${d.getDate()} ${hh(d.getHours())}:00`;
    };

    return (
        <div className="sn-chart" style={{ width: '100%', height }}>
            <ResponsiveContainer width="100%" height="100%">
                <LineChart data={data} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
                    <CartesianGrid stroke="var(--line)" strokeOpacity={0.6} vertical={false} />
                    <XAxis dataKey="t" type="number" scale="time"
                        domain={['dataMin', 'dataMax']}
                        tickFormatter={tickFmt} minTickGap={40}
                        stroke="var(--line-strong)"
                        tick={{ fill: 'var(--text-dim)', fontSize: 10 }} />
                    <YAxis allowDecimals={false} width={44}
                        stroke="var(--line-strong)"
                        tick={{ fill: 'var(--text-dim)', fontSize: 10 }}
                        tickFormatter={(v) => fmtNum(v)} />
                    <Tooltip
                        labelFormatter={(t) => fmtTime(new Date(t).toISOString())}
                        formatter={(value, name) => [fmtNum(value), name]}
                        contentStyle={CHART_TOOLTIP_STYLE}
                        labelStyle={{ color: 'var(--text-dim)', marginBottom: 4 }}
                        itemStyle={{ color: 'var(--text)', padding: 0 }}
                        cursor={{ stroke: 'var(--line-strong)' }}
                        isAnimationActive={false} />
                    <Legend iconType="plainline" iconSize={12}
                        wrapperStyle={{ fontSize: 11, color: 'var(--text-dim)' }} />
                    <Line name="Received" dataKey="received" stroke="var(--accent)"
                        strokeWidth={2} dot={false} activeDot={{ r: 3 }} isAnimationActive={false} />
                    <Line name="Sent" dataKey="sent" stroke="var(--ok)"
                        strokeWidth={2} dot={false} activeDot={{ r: 3 }} isAnimationActive={false} />
                    {/* Dashed: the ok/err hue pair fails deutan CVD separation in the
                        light theme (validated) and the token colors are contractual —
                        the dash is the secondary encoding that keeps Sent and Errors
                        distinguishable without color. */}
                    <Line name="Errors" dataKey="error" stroke="var(--err)" strokeDasharray="5 3"
                        strokeWidth={2} dot={false} activeDot={{ r: 3 }} isAnimationActive={false} />
                </LineChart>
            </ResponsiveContainer>
        </div>
    );
}

/* ---- time range picker -------------------------------------------------- */

export const TIME_RANGES = [
    { key: '1h', label: '1h', seconds: 3600 },
    { key: '6h', label: '6h', seconds: 6 * 3600 },
    { key: '24h', label: '24h', seconds: 24 * 3600 },
    { key: '7d', label: '7d', seconds: 7 * 86400 },
    { key: '30d', label: '30d', seconds: 30 * 86400 },
];

/** { from, to } in epoch millis for a TIME_RANGES key (defaults to 6h). */
export function rangeWindow(key) {
    const r = TIME_RANGES.find((x) => x.key === key) || TIME_RANGES[1];
    const to = Date.now();
    return { from: to - r.seconds * 1000, to };
}

/** Preset range segpill. value: a TIME_RANGES key; onChange(key). */
export function TimeRangePicker({ value, onChange }) {
    return (
        <div className="segpill sn-range">
            {TIME_RANGES.map((r) => (
                <button key={r.key} type="button"
                    className={value === r.key ? 'on' : ''}
                    onClick={() => onChange && onChange(r.key)}>{r.label}</button>
            ))}
        </div>
    );
}

/* ---- channel / group pickers ------------------------------------------- */

function multiValue(e) {
    return Array.from(e.target.selectedOptions, (o) => o.value);
}

function PickerShell({ loading, error, reload, children }) {
    if (error) {
        return (
            <span className="sn-picker-err">
                <span className="text-err" title={error}>load failed</span>
                <button type="button" className="btn btn-sm" onClick={reload}>Retry</button>
            </span>
        );
    }
    if (loading) return <select disabled><option>Loading…</option></select>;
    return children;
}

/**
 * Channel select fed from GET /core/channels (or a `channels` prop to share
 * one fetch across pickers). Single: value = channelId ('' = the emptyLabel
 * row). multiple: value = channelId[].
 */
export function ChannelPicker({ value, onChange, channels, multiple, emptyLabel = 'All channels', size }) {
    const self = useApi(() => (channels ? Promise.resolve(channels) : getCoreChannels()), [channels]);
    const list = self.data || [];
    return (
        <PickerShell loading={self.loading} error={self.error} reload={self.reload}>
            <select multiple={multiple || undefined} size={multiple ? (size || 5) : undefined}
                value={multiple ? (value || []) : (value == null ? '' : value)}
                onChange={(e) => onChange && onChange(multiple ? multiValue(e) : e.target.value)}>
                {!multiple ? <option value="">{emptyLabel}</option> : null}
                {list.map((c) => (
                    <option key={c.channelId} value={c.channelId}>
                        {c.name || c.channelId}{c.started === false ? ' (stopped)' : ''}
                    </option>
                ))}
            </select>
        </PickerShell>
    );
}

/** Channel-group select fed from GET /core/channelGroups; same value contract
    as ChannelPicker (group ids). */
export function ChannelGroupPicker({ value, onChange, groups, multiple, emptyLabel = 'All groups', size }) {
    const self = useApi(() => (groups ? Promise.resolve(groups) : getCoreChannelGroups()), [groups]);
    const list = self.data || [];
    return (
        <PickerShell loading={self.loading} error={self.error} reload={self.reload}>
            <select multiple={multiple || undefined} size={multiple ? (size || 5) : undefined}
                value={multiple ? (value || []) : (value == null ? '' : value)}
                onChange={(e) => onChange && onChange(multiple ? multiValue(e) : e.target.value)}>
                {!multiple ? <option value="">{emptyLabel}</option> : null}
                {list.map((g) => (
                    <option key={g.id} value={g.id}>{g.name || g.id}</option>
                ))}
            </select>
        </PickerShell>
    );
}

/** Monitor select fed from GET /monitors; same value contract as
    ChannelPicker but values are monitor ids as STRINGS (condition rows and
    filter params are string-typed on the wire). */
export function MonitorPicker({ value, onChange, monitors, multiple, emptyLabel = 'All monitors', size }) {
    const self = useApi(() => (monitors ? Promise.resolve(monitors) : listMonitors()), [monitors]);
    const list = Array.isArray(self.data) ? self.data : [];
    return (
        <PickerShell loading={self.loading} error={self.error} reload={self.reload}>
            <select multiple={multiple || undefined} size={multiple ? (size || 5) : undefined}
                value={multiple ? (value || []) : (value == null ? '' : value)}
                onChange={(e) => onChange && onChange(multiple ? multiValue(e) : e.target.value)}>
                {!multiple ? <option value="">{emptyLabel}</option> : null}
                {list.map((m) => (
                    <option key={m.id} value={String(m.id)}>{m.name || `#${m.id}`}</option>
                ))}
            </select>
        </PickerShell>
    );
}

/** Channel-tag select fed from GET /core/tags; same value contract as
    ChannelPicker (tag ids). <option> can't carry arbitrary markup, so the
    tag's color tints the option text instead of rendering a swatch. */
export function TagPicker({ value, onChange, tags, multiple, emptyLabel = 'All tags', size }) {
    const self = useApi(() => (tags ? Promise.resolve(tags) : getCoreTags()), [tags]);
    const list = self.data || [];
    return (
        <PickerShell loading={self.loading} error={self.error} reload={self.reload}>
            <select multiple={multiple || undefined} size={multiple ? (size || 5) : undefined}
                value={multiple ? (value || []) : (value == null ? '' : value)}
                onChange={(e) => onChange && onChange(multiple ? multiValue(e) : e.target.value)}>
                {!multiple ? <option value="">{emptyLabel}</option> : null}
                {list.map((t) => (
                    <option key={t.id} value={t.id} style={t.colorHex ? { color: t.colorHex } : undefined}>
                        {t.name || t.id}
                    </option>
                ))}
            </select>
        </PickerShell>
    );
}

/* ---- problems filter bar ------------------------------------------------ */

export const DEFAULT_PROBLEM_FILTERS = {
    status: 'PROBLEM', severity: [], channelId: '', monitorId: '', acknowledged: '', q: '',
};

/**
 * Problems filter row. value: DEFAULT_PROBLEM_FILTERS shape (severity is an
 * array of Severity names; acknowledged '', 'true' or 'false'); onChange(next)
 * receives the whole next filter object. `monitors` (Monitor[]) feeds the
 * monitor select and may be null while loading; `channels` optionally shares a
 * ChannelPicker fetch.
 */
export function FilterBar({ value, onChange, monitors, channels }) {
    const v = value || DEFAULT_PROBLEM_FILTERS;
    const set = (k, val) => onChange && onChange({ ...v, [k]: val });
    const toggleSeverity = (s) => set('severity',
        v.severity.includes(s) ? v.severity.filter((x) => x !== s) : [...v.severity, s]);
    return (
        <div className="sn-filterbar">
            <select value={v.status} onChange={(e) => set('status', e.target.value)}>
                <option value="">Any status</option>
                <option value="PROBLEM">Problem</option>
                <option value="RESOLVED">Resolved</option>
            </select>
            <span className="sn-sev-toggles">
                {SEVERITY_ORDER.map((s) => (
                    <button key={s} type="button"
                        className={`tag sn-sev sn-sev-toggle${v.severity.includes(s) ? ' on' : ''}`}
                        style={{ borderColor: `color-mix(in srgb, ${SEVERITY_META[s].color} 55%, transparent)` }}
                        title={`Filter severity: ${SEVERITY_META[s].label}`}
                        onClick={() => toggleSeverity(s)}>
                        <span className="sn-sev-dot" style={{ background: SEVERITY_META[s].color }} />
                        {SEVERITY_META[s].label}
                    </button>
                ))}
            </span>
            <ChannelPicker value={v.channelId} channels={channels}
                onChange={(id) => set('channelId', id)} />
            {monitors ? (
                <select value={v.monitorId} onChange={(e) => set('monitorId', e.target.value)}>
                    <option value="">All monitors</option>
                    {monitors.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
                </select>
            ) : null}
            <select value={v.acknowledged} onChange={(e) => set('acknowledged', e.target.value)}>
                <option value="">Ack: any</option>
                <option value="false">Unacknowledged</option>
                <option value="true">Acknowledged</option>
            </select>
            <input placeholder="Search message…" value={v.q}
                onChange={(e) => set('q', e.target.value)} />
            <button type="button" className="btn btn-sm"
                onClick={() => onChange && onChange({ ...DEFAULT_PROBLEM_FILTERS, status: '' })}>
                Clear
            </button>
        </div>
    );
}

/* ---- Action condition builder ------------------------------------------- */

export const EVENT_TYPES = ['PROBLEM', 'RESOLVED'];

// Field/operator matrix per ActionConditionMatcher (server contract): rows are
// ANDed; IN takes an array value; unknown field/op fails closed server-side.
// MONITOR lists IN first so "assign this action to specific monitors" opens as
// a multi-select by default.
export const CONDITION_FIELDS = {
    SEVERITY: { label: 'Severity', operators: ['=', '!=', '>=', 'IN'] },
    MONITOR_TYPE: { label: 'Monitor type', operators: ['=', '!=', 'IN'] },
    MONITOR: { label: 'Monitor', operators: ['IN', '=', '!='] },
    CHANNEL: { label: 'Channel', operators: ['=', '!=', 'IN'] },
    CHANNEL_GROUP: { label: 'Channel group', operators: ['=', '!=', 'IN'] },
    CHANNEL_TAG: { label: 'Channel tag', operators: ['=', '!=', 'IN'] },
    EVENT_TYPE: { label: 'Event type', operators: ['='] },
};

function conditionDefault(field, operator) {
    const multi = operator === 'IN';
    if (field === 'SEVERITY') return multi ? [] : SEVERITY_ORDER[1];
    if (field === 'MONITOR_TYPE') return multi ? [] : MONITOR_TYPE_ORDER[0];
    if (field === 'EVENT_TYPE') return 'PROBLEM';
    return multi ? [] : '';
}

function EnumSelect({ options, labels, value, onChange, multiple }) {
    return (
        <select multiple={multiple || undefined} size={multiple ? Math.min(5, options.length) : undefined}
            value={multiple ? (value || []) : (value == null ? '' : value)}
            onChange={(e) => onChange(multiple ? multiValue(e) : e.target.value)}>
            {options.map((o) => <option key={o} value={o}>{(labels && labels[o]) || o}</option>)}
        </select>
    );
}

function ConditionValueEditor({ row, onChange }) {
    const multi = row.operator === 'IN';
    switch (row.field) {
        case 'SEVERITY':
            return <EnumSelect options={SEVERITY_ORDER} multiple={multi}
                labels={Object.fromEntries(SEVERITY_ORDER.map((s) => [s, SEVERITY_META[s].label]))}
                value={row.value} onChange={onChange} />;
        case 'MONITOR_TYPE':
            return <EnumSelect options={MONITOR_TYPE_ORDER} multiple={multi}
                labels={Object.fromEntries(MONITOR_TYPE_ORDER.map((t) => [t, MONITOR_TYPE_META[t].label]))}
                value={row.value} onChange={onChange} />;
        case 'CHANNEL':
            return <ChannelPicker value={row.value} onChange={onChange} multiple={multi}
                emptyLabel="Select a channel…" />;
        case 'CHANNEL_GROUP':
            return <ChannelGroupPicker value={row.value} onChange={onChange} multiple={multi}
                emptyLabel="Select a group…" />;
        case 'MONITOR':
            return <MonitorPicker value={row.value} onChange={onChange} multiple={multi}
                emptyLabel="Select a monitor…" />;
        case 'CHANNEL_TAG':
            return <TagPicker value={row.value} onChange={onChange} multiple={multi}
                emptyLabel="Select a tag…" />;
        case 'EVENT_TYPE':
            return <EnumSelect options={EVENT_TYPES} value={row.value} onChange={onChange} />;
        default:
            return <input value={row.value == null ? '' : row.value} onChange={(e) => onChange(e.target.value)} />;
    }
}

/**
 * Action condition rows editor. value: [{ field, operator, value }] (the
 * parsed conditionJson array; value is a string, or an array when operator is
 * IN); onChange(nextArray). Rows are ANDed; an empty list matches everything.
 */
export function ConditionBuilder({ value, onChange }) {
    const rows = value || [];
    const emit = (next) => onChange && onChange(next);
    const setRow = (i, patch) => emit(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
    const changeField = (i, field) => {
        const operator = CONDITION_FIELDS[field].operators[0];
        setRow(i, { field, operator, value: conditionDefault(field, operator) });
    };
    const changeOperator = (i, operator) => {
        const row = rows[i];
        const wasMulti = row.operator === 'IN';
        const isMulti = operator === 'IN';
        let v = row.value;
        if (isMulti && !wasMulti) v = v === '' || v == null ? [] : [v];
        else if (!isMulti && wasMulti) v = Array.isArray(v) && v.length ? v[0] : conditionDefault(row.field, operator);
        setRow(i, { operator, value: v });
    };
    return (
        <div className="sn-conds">
            {rows.length === 0 ? (
                <div className="sn-hint">No conditions — this action fires for every matching event.</div>
            ) : null}
            {rows.map((row, i) => (
                <div key={i} className="sn-cond-row">
                    <select value={row.field} onChange={(e) => changeField(i, e.target.value)}>
                        {Object.keys(CONDITION_FIELDS).map((f) => (
                            <option key={f} value={f}>{CONDITION_FIELDS[f].label}</option>
                        ))}
                    </select>
                    <select value={row.operator} onChange={(e) => changeOperator(i, e.target.value)}>
                        {(CONDITION_FIELDS[row.field] || { operators: ['='] }).operators.map((op) => (
                            <option key={op} value={op}>{op}</option>
                        ))}
                    </select>
                    <ConditionValueEditor row={row} onChange={(v) => setRow(i, { value: v })} />
                    <button type="button" className="btn btn-sm" title="Remove condition"
                        onClick={() => emit(rows.filter((_, j) => j !== i))}>✕</button>
                </div>
            ))}
            <button type="button" className="btn btn-sm"
                onClick={() => emit([...rows, { field: 'SEVERITY', operator: '>=', value: 'WARNING' }])}>
                + Add condition
            </button>
        </div>
    );
}
