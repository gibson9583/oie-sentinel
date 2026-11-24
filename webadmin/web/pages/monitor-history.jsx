// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// A saved monitor's alerting history under its editor. Moved out of
// monitors.jsx unchanged.

import { platform } from '@oie/web-shell';
import {
    ResponsiveContainer, BarChart, Bar, LineChart, Line, XAxis, YAxis,
    CartesianGrid, Tooltip,
} from 'recharts';
import { getMonitorHistory } from '../api.js';
import { useApi, fmtNum } from '../ui.jsx';

const React = platform.React;

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
export function MonitorHistoryPanel({ monitorId }) {
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
