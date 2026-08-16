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
    CartesianGrid, Tooltip, Legend, ReferenceLine,
} from 'recharts';
import {
    errText, getChannelActivity, getCoreChannels, getCoreChannelGroups, getCoreTags,
    getCoreUsers, listMonitors,
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

export const MONITOR_TYPE_ORDER = ['INACTIVITY', 'LOW_VOLUME', 'ANOMALY', 'CONNECTION_STATUS',
    'ERROR_RATE', 'QUEUE_DEPTH', 'CHANNEL_STATE'];

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
    ERROR_RATE: {
        label: 'Error rate',
        description: 'Alerts when the share of errored messages over a window reaches a percentage.',
        // minMessages is the floor of statistical meaning, not a nicety: below
        // it the evaluator returns INSUFFICIENT_DATA so one error on a quiet
        // channel cannot read as 100%.
        defaultConfig: { thresholdPercent: 10, windowSeconds: 3600, minMessages: 20 },
    },
    QUEUE_DEPTH: {
        label: 'Queue depth',
        description: 'Alerts when queued messages stay at or above a depth for a period.',
        // Queue depth is an instantaneous gauge, so the evaluator reads the
        // latest sample; minDurationSeconds separates a real backlog from the
        // burst a destination clears on its next reconnect.
        defaultConfig: { threshold: 1000, minDurationSeconds: 300 },
    },
    CHANNEL_STATE: {
        label: 'Channel state',
        description: 'Alerts when a channel is stopped, paused or undeployed for too long.',
        // The resting states only: every other type is evaluated against
        // STARTED channels alone, so a channel being stopped currently makes
        // its alarms go quiet rather than raising one. The transitional states
        // are selectable in the editor but never seeded — a redeploy walks
        // through them and a default that paged on that teaches operators to
        // ignore this monitor.
        defaultConfig: { alertOnStates: ['STOPPED', 'PAUSED', 'UNDEPLOYED'], minDurationSeconds: 300 },
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
 *
 * `markers` draws labelled vertical rules for moments of interest (the alert
 * open and resolve on the problem detail pane):
 * [{ time: ISO string|Date|millis, label, color, position }]. Dashed, so they
 * never read as the solid hairline grid, and their color should be a TEXT
 * token — annotation is not data, and a rule in a series hue reads as that
 * series. They use ifOverflow="extendDomain" so a marker just outside the
 * drawn data — an inactivity alert has no samples at the open, which is the
 * point — still appears instead of being silently discarded.
 */
export function ActivityChart({ points, height = 260, markers }) {
    const data = React.useMemo(() => (points || []).map((p) => ({
        t: new Date(p.time).getTime(),
        received: Number(p.received) || 0,
        sent: Number(p.sent) || 0,
        error: Number(p.error) || 0,
    })).filter((p) => !isNaN(p.t)), [points]);

    const marks = React.useMemo(() => (markers || []).map((m) => ({
        ...m,
        t: m.time instanceof Date ? m.time.getTime() : new Date(m.time).getTime(),
    })).filter((m) => !isNaN(m.t)), [markers]);

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
                    {/* After the series so the rules sit on top of them. */}
                    {marks.map((m, i) => (
                        <ReferenceLine key={`${m.t}-${i}`} x={m.t} ifOverflow="extendDomain"
                            stroke={m.color || 'var(--text-dim)'} strokeDasharray="4 3" strokeWidth={1.5}
                            label={m.label ? {
                                value: m.label,
                                position: m.position || 'insideTopLeft',
                                fill: m.color || 'var(--text-dim)',
                                fontSize: 10,
                            } : undefined} />
                    ))}
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

/* ---- activity granularity ----------------------------------------------- */

/**
 * The three values the server accepts on ?granularity. AUTO is offered but is
 * NOT the only useful setting, which is why the control exists at all: AUTO
 * switches on RANGE WIDTH, not on what data survives — anything wider than 6
 * hours resolves to the hourly rollup even though raw samples cover the whole
 * sample-retention window. RAW is how an operator gets tick resolution over a
 * 3 day incident.
 */
export const GRANULARITIES = [
    { key: 'AUTO', label: 'Auto', title: 'Tick samples for ranges up to 6 hours, the hourly rollup beyond that.' },
    { key: 'RAW', label: 'Raw', title: 'Collector tick samples, for as far back as sample retention keeps them.' },
    { key: 'HOURLY', label: 'Hourly', title: 'The hourly rollup, which is kept far longer than raw samples.' },
];

/** RAW / HOURLY / AUTO segpill. value: a GRANULARITIES key; onChange(key). */
export function GranularityPicker({ value, onChange }) {
    return (
        <div className="segpill sn-gran">
            {GRANULARITIES.map((g) => (
                <button key={g.key} type="button" title={g.title}
                    className={value === g.key ? 'on' : ''}
                    onClick={() => onChange && onChange(g.key)}>{g.label}</button>
            ))}
        </div>
    );
}

// Java Duration.toString(): hours/minutes/seconds only (no day field), seconds
// possibly fractional — "PT1H4M48S", "PT5M2.4S", "PT0.108S".
const ISO_DURATION_RE = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/;

/** "PT1H4M48S" -> "1h5m" (two units, smaller one rounded). Null if unreadable. */
function fmtIsoDuration(text) {
    const m = ISO_DURATION_RE.exec(String(text || ''));
    if (!m || (m[1] === undefined && m[2] === undefined && m[3] === undefined)) return null;
    const total = Number(m[1] || 0) * 3600 + Number(m[2] || 0) * 60 + Number(m[3] || 0);
    if (!isFinite(total)) return null;
    if (total < 60) return total < 0.5 ? '<1s' : `${Math.round(total)}s`;
    if (total < 3600) {
        let mins = Math.floor(total / 60);
        let secs = Math.round(total % 60);
        if (secs === 60) { mins += 1; secs = 0; }
        if (mins === 60) return '1h';
        return secs ? `${mins}m${secs}s` : `${mins}m`;
    }
    let hours = Math.floor(total / 3600);
    let mins = Math.round((total % 3600) / 60);
    if (mins === 60) { hours += 1; mins = 0; }
    return mins ? `${hours}h${mins}m` : `${hours}h`;
}

const GRANULARITY_SOURCE_LABELS = { RAW: 'tick samples', HOURLY: 'hourly rollup' };

/**
 * Human text for the granularity the SERVER reported, which is the only one
 * worth showing: it promotes an over-wide RAW request to the hourly rollup and
 * folds either source into at most 2000 buckets, so the requested value is a
 * guess and the response is the fact.
 *
 * A one-for-one read is the bare source name ("RAW"); a folded series is
 * "<SOURCE>_<ISO-8601 bucket width>" ("HOURLY_PT1H4M48S"), which renders as
 * "hourly rollup, 1h5m buckets". Purely presentational, and deliberately so —
 * ANY value it cannot parse falls through to the server's own text rather than
 * being guessed at, and nothing in the UI branches on granularity for
 * behaviour (an equality test against 'RAW' would call folded five-minute
 * buckets tick resolution, which is exactly the lie the compound label exists
 * to prevent).
 */
export function describeGranularity(resolved) {
    const text = resolved == null ? '' : String(resolved).trim();
    if (!text) return 'unknown';
    const cut = text.indexOf('_');
    const source = cut < 0 ? text : text.slice(0, cut);
    const label = GRANULARITY_SOURCE_LABELS[source];
    if (cut < 0) return label || text;
    const bucket = fmtIsoDuration(text.slice(cut + 1));
    return label && bucket ? `${label}, ${bucket} buckets` : text;
}

/**
 * Panel around ActivityChart that owns the GET /channels/{id}/activity fetch,
 * the RAW/HOURLY/AUTO control and the resolved-granularity caption. Shared so
 * the problem detail pane and the dashboard cannot drift on how a series is
 * requested or labelled.
 *
 * Props:
 *   channelId    channel to chart; falsy renders `unavailable` and fetches nothing
 *   from, to     epoch millis. MEMOISE THESE — they are useApi deps, so a
 *                fresh Date.now() per render refetches on every render
 *   markers      passed through to ActivityChart
 *   tools        extra header controls (channel/range pickers), left of the
 *                granularity segpill
 *   hint         note rendered under the caption
 *   unavailable  message to show instead of the chart (no channel, no range)
 *
 * Load failures stay inline with a Retry, never a toast: 'warn' toasts are
 * acknowledge-to-dismiss modals on this host, and the dashboard re-renders
 * this panel on a 30s cadence.
 */
export function ChannelActivityPanel({
    channelId, from, to, markers, tools, hint, height,
    title = 'Channel activity',
    unavailable,
}) {
    const [granularity, setGranularity] = React.useState('AUTO');
    const ready = !!channelId && from != null && to != null && !unavailable;
    const api = useApi(
        () => (ready ? getChannelActivity(channelId, { from, to, granularity }) : Promise.resolve(null)),
        [ready, channelId, from, to, granularity],
    );
    const activity = api.data;
    const points = activity && Array.isArray(activity.points) ? activity.points : [];

    return (
        <div className="panel mb-3">
            {/* .panel-header is a nowrap flex row; the pickers plus a long
                channel name overflow a narrow viewport without this. */}
            <div className="panel-header" style={{ flexWrap: 'wrap', rowGap: 6 }}>
                <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {title}
                </span>
                <div className="panel-tools" style={{ flexWrap: 'wrap', rowGap: 6 }}>
                    {tools}
                    <GranularityPicker value={granularity} onChange={setGranularity} />
                </div>
            </div>
            <div className="panel-body">
                {!ready ? (
                    <>
                        <div className="sn-empty">{unavailable || 'No channel to chart.'}</div>
                        {/* Also shown here, not just under a drawn chart: a note
                            explaining what the picker contains is most useful
                            before the operator has picked anything. */}
                        {hint ? <div className="sn-hint" style={{ marginTop: 6 }}>{hint}</div> : null}
                    </>
                ) : (
                    <>
                        {api.error ? (
                            <div style={{ marginBottom: 8 }}>
                                <span className="text-err">Could not load activity.</span>{' '}
                                <span className="text-text-dim">{api.error}</span>{' '}
                                <button type="button" className="btn btn-sm" onClick={api.reload}>Retry</button>
                            </div>
                        ) : null}
                        {!activity && !api.error ? (
                            <div className="sn-empty">Loading activity…</div>
                        ) : null}
                        {activity ? <ActivityChart points={points} height={height} markers={markers} /> : null}
                        {activity ? (
                            <div className="sn-hint" style={{ marginTop: 6 }}>
                                {/* The response's value verbatim in the tooltip: the prose
                                    above it is a rendering, this is the record. */}
                                <span title={`Server-reported granularity: ${activity.granularity || '(none)'}`}>
                                    Resolution: {describeGranularity(activity.granularity)}
                                </span>
                                {' · '}{fmtNum(points.length)} point{points.length === 1 ? '' : 's'}
                                {api.loading ? ' · updating…' : ''}
                                {hint ? <> · {hint}</> : null}
                            </div>
                        ) : null}
                    </>
                )}
            </div>
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
 * Narrows a channel list to the channels at least one enabled monitor
 * actually covers — the set a trend chart is worth offering, since an
 * unmonitored channel is the one case where the chart is guaranteed to have
 * nothing interesting to say.
 *
 * <p>Coverage is resolved exactly rather than guessed: CHANNEL scope names
 * its channel, and GROUP/TAG scopes are expanded through the membership the
 * {@code /core/channelGroups} and {@code /core/tags} payloads already carry,
 * so this agrees with what the evaluator will actually do. A single enabled
 * ALL-scoped monitor covers everything, in which case no filtering happens
 * at all.</p>
 *
 * <p>Falls back to the unfiltered list whenever filtering would leave the
 * picker empty or the lookups have not loaded — a picker with nothing in it
 * is worse than one showing too much, and this is a convenience filter, not
 * an access control.</p>
 *
 * @param channels the full channel list (from GET /core/channels)
 * @returns { channels, filtered, total } — `filtered` is false when the list
 *          was passed through untouched, so callers can say so in the UI
 */
export function useMonitoredChannels(channels) {
    const monitors = useApi(listMonitors, []);
    const groups = useApi(getCoreChannelGroups, []);
    const tags = useApi(getCoreTags, []);

    return React.useMemo(() => {
        const list = Array.isArray(channels) ? channels : [];
        const all = { channels: list, filtered: false, total: list.length };
        const defined = Array.isArray(monitors.data) ? monitors.data.filter((m) => m && m.enabled) : null;
        // Still loading, failed, or nothing to narrow by: show everything.
        if (!defined || !defined.length || !list.length) return all;
        if (defined.some((m) => m.scopeType === 'ALL')) return all;

        const members = (rows, key) => {
            const map = {};
            (Array.isArray(rows) ? rows : []).forEach((r) => {
                if (r && r[key]) map[r[key]] = Array.isArray(r.channelIds) ? r.channelIds : [];
            });
            return map;
        };
        const groupMembers = members(groups.data, 'id');
        const tagMembers = members(tags.data, 'id');

        const covered = new Set();
        defined.forEach((m) => {
            if (m.scopeType === 'CHANNEL') {
                if (m.scopeId) covered.add(m.scopeId);
            } else if (m.scopeType === 'GROUP') {
                (groupMembers[m.scopeId] || []).forEach((id) => covered.add(id));
            } else if (m.scopeType === 'TAG') {
                (tagMembers[m.scopeId] || []).forEach((id) => covered.add(id));
            }
        });

        const narrowed = list.filter((c) => covered.has(c.channelId));
        return narrowed.length
            ? { channels: narrowed, filtered: narrowed.length < list.length, total: list.length }
            : all;
    }, [channels, monitors.data, groups.data, tags.data]);
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
