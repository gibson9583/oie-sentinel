// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Dashboard page: KPI tiles from GET /dashboard/summary, recent problems,
// top problem channels with 1h sparklines (GET /activity/summary), and a
// collector/evaluator health footnote. Auto-refreshes every 30s while the
// Sentinel view is visible (gated on the session user and the current route,
// so the poll never outlives the view or resets the session timeout).
//
// plugin.jsx renders pages with no props (no onNavigate contract exists), so
// clicking a recent problem opens a read-only detail pane INLINE instead of
// switching to the Problems tab; acknowledging/resolving stays on that tab.

import { platform } from '@oie/web-shell';
import { errorModal } from '@oie/web-ui';
import {
    getDashboardSummary, getActivitySummary, getProblem, getCoreChannels,
    parseMaybeJson, errText,
} from '../api.js';
import {
    StatTile, SeverityChip, Sparkline, useApi, useUsernames, toast,
    fmtAgo, fmtTime, fmtNum, SEVERITY_ORDER, SEVERITY_META, MONITOR_TYPE_META,
} from '../ui.jsx';

const React = platform.React;

const REFRESH_MS = 30000;

// GET /settings is MANAGE-gated, so a VIEW-only dashboard cannot learn the
// configured collector/evaluator intervals to compute a "3x interval" lag
// threshold. Fixed thresholds derived from the server defaults instead:
// 3 x default interval + one interval of scheduling slack (collector default
// 30s -> 120s, evaluator default 60s -> 240s). Conservative for admins who
// lengthen the intervals: the pip may warn while the scheduler is healthy,
// which errs on the side of surfacing a possibly-stalled collector.
const COLLECTOR_STALE_SECONDS = 120;
const EVALUATOR_STALE_SECONDS = 240;

// Sparkline window matches the "Trend (1h)" column label.
const ACTIVITY_WINDOW_SECONDS = 3600;
const ACTIVITY_BUCKETS = 20;

/** Background polls must stop mattering the moment the user logs out or
    navigates away (PLUGINS.md: ungated polls reset the session timeout). */
function shouldPoll() {
    try {
        return !!platform.store.getState('user')
            && String(platform.router.currentPath() || '').startsWith('/sentinel');
    } catch (e) {
        return false;
    }
}

const LABEL_STYLE = {
    flex: 'none', width: 120, color: 'var(--text-dim)', fontSize: '10.5px',
    textTransform: 'uppercase', letterSpacing: '.04em', paddingTop: 2,
};

function MetaRow({ label, children }) {
    return (
        <div style={{ display: 'flex', gap: 10, padding: '2px 0' }}>
            <span style={LABEL_STYLE}>{label}</span>
            <span style={{ minWidth: 0, overflowWrap: 'anywhere' }}>{children}</span>
        </div>
    );
}

/** Collector/evaluator liveness pip: warn when the last run (or first run
    ever) is older than the fixed staleness threshold above. */
function HealthItem({ label, time, staleSeconds }) {
    const t = time ? new Date(time).getTime() : NaN;
    const stale = isNaN(t) || (Date.now() - t) > staleSeconds * 1000;
    return (
        <span className="status-cell" title={time ? fmtTime(time) : 'No run recorded yet'}>
            <span className={`pip ${stale ? 'warn' : 'ok'}`} />
            <span style={stale ? { color: 'var(--warn)' } : undefined}>
                {label} {time ? `ran ${fmtAgo(time)}` : 'has not run yet'}
            </span>
        </span>
    );
}

function HealthFootnote({ summary }) {
    return (
        <div className="sn-hint"
            style={{ display: 'flex', gap: 18, flexWrap: 'wrap', alignItems: 'center', marginTop: 12 }}>
            <HealthItem label="Collector" time={summary.lastCollectorRun}
                staleSeconds={COLLECTOR_STALE_SECONDS} />
            <HealthItem label="Evaluator" time={summary.lastEvaluatorRun}
                staleSeconds={EVALUATOR_STALE_SECONDS} />
            {/* Connector events are push-driven — silence is normal, so no pip. */}
            <span title={summary.lastConnectorEvent ? fmtTime(summary.lastConnectorEvent) : undefined}>
                Last connector event: {summary.lastConnectorEvent
                    ? fmtAgo(summary.lastConnectorEvent) : 'none observed'}
            </span>
        </div>
    );
}

/** Key/value rows for the parsed detailsJson of the alert's last value. */
function LastValue({ detailsJson }) {
    if (detailsJson == null || detailsJson === '') return null;
    const parsed = parseMaybeJson(detailsJson);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return (
            <div style={{ marginTop: 8 }}>
                {Object.entries(parsed).map(([k, v]) => (
                    <MetaRow key={k} label={k}>
                        <span className="mono">{v == null ? '—' : String(v)}</span>
                    </MetaRow>
                ))}
            </div>
        );
    }
    return <pre className="mono" style={{ marginTop: 8, whiteSpace: 'pre-wrap' }}>{String(parsed)}</pre>;
}

/** Read-only inline problem detail (the Problems tab owns ack/resolve). */
function ProblemInline({ problem, channelNameOf, onBack }) {
    const userNameOf = useUsernames();
    const detail = useApi(() => getProblem(problem.id), [problem.id]);
    const d = detail.data;
    const ev = (d && d.event) || problem;
    const dispatches = (d && d.dispatches) || [];
    const typeMeta = d && MONITOR_TYPE_META[d.monitorType];
    return (
        <div className="panel">
            <div className="panel-header">
                Problem detail
                <div className="panel-tools">
                    <button type="button" className="btn btn-sm" onClick={onBack}>Back</button>
                </div>
            </div>
            <div className="panel-body">
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
                    <SeverityChip severity={ev.severity} />
                    {ev.status === 'PROBLEM'
                        ? <span className="tag red">Problem</span>
                        : <span className="tag">Resolved</span>}
                    {ev.suppressed ? <span className="tag">Suppressed</span> : null}
                    {ev.acknowledgedBy != null ? <span className="tag accent">Acknowledged</span> : null}
                    <span className="font-semibold" style={{ minWidth: 0 }}>{ev.message}</span>
                </div>
                {detail.loading ? <div className="sn-hint">Loading detail…</div> : null}
                {detail.error ? (
                    <div style={{ color: 'var(--err)', marginBottom: 8 }}>
                        Could not load detail: {detail.error}{' '}
                        <button type="button" className="btn btn-sm" onClick={detail.reload}>Retry</button>
                    </div>
                ) : null}
                <MetaRow label="Monitor">
                    {d ? d.monitorName : `#${ev.monitorId}`}
                    {typeMeta ? <span className="sn-hint"> ({typeMeta.label})</span> : null}
                </MetaRow>
                <MetaRow label="Channel">{(d && d.channelName) || channelNameOf(ev.channelId)}</MetaRow>
                {d && d.connectorName ? <MetaRow label="Connector">{d.connectorName}</MetaRow> : null}
                <MetaRow label="Opened">{fmtTime(ev.openedTime)} ({fmtAgo(ev.openedTime)})</MetaRow>
                {ev.resolvedTime ? <MetaRow label="Resolved">{fmtTime(ev.resolvedTime)}</MetaRow> : null}
                {ev.acknowledgedBy != null ? (
                    <MetaRow label="Acknowledged">
                        by {userNameOf(ev.acknowledgedBy)} {fmtTime(ev.acknowledgedTime)}
                        {ev.ackComment ? ` — ${ev.ackComment}` : ''}
                    </MetaRow>
                ) : null}
                <LastValue detailsJson={ev.detailsJson} />
                <div style={{ marginTop: 12 }}>
                    <div style={LABEL_STYLE}>Dispatched actions</div>
                    {detail.loading ? null : dispatches.length === 0 ? (
                        <div className="sn-hint" style={{ marginTop: 4 }}>No actions dispatched for this problem.</div>
                    ) : (
                        <table className="dt" style={{ marginTop: 4 }}>
                            <thead>
                                <tr><th>Time</th><th>Action</th><th>Result</th><th>Error</th></tr>
                            </thead>
                            <tbody>
                                {dispatches.map((log) => (
                                    <tr key={log.id}>
                                        <td title={fmtTime(log.dispatchTime)}>{fmtAgo(log.dispatchTime)}</td>
                                        <td>#{log.actionId}</td>
                                        <td>
                                            <span className="status-cell">
                                                <span className={`pip ${log.success ? 'ok' : 'err'}`} />
                                                {log.success ? 'Sent' : 'Failed'}
                                            </span>
                                        </td>
                                        <td>{log.errorMessage || ''}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </div>
                <div className="sn-hint" style={{ marginTop: 10 }}>
                    Acknowledge or resolve this problem from the Problems tab.
                </div>
            </div>
        </div>
    );
}

export function DashboardPage() {
    const [summary, setSummary] = React.useState(null);
    const [activity, setActivity] = React.useState({});   // channelId -> ChannelActivitySummary
    const [actError, setActError] = React.useState(null);
    const [error, setError] = React.useState(null);
    const [loading, setLoading] = React.useState(true);
    const [busy, setBusy] = React.useState(false);
    const [lastUpdated, setLastUpdated] = React.useState(null);
    const [selected, setSelected] = React.useState(null); // AlertEvent -> inline detail

    // One toast per failure streak, not one per 30s tick — and 'info', never
    // 'warn': the host routes warn/error toasts to a blocking modal, which a
    // non-fatal background refresh must not open.
    const failStreakRef = React.useRef(false);
    const inFlightRef = React.useRef(false);

    // Channel names for recent problems (AlertEvent carries only channelId).
    // Fetched once — channel renames are rare and the fallback is the id.
    const channelsApi = useApi(() => getCoreChannels(), []);
    const channelNames = React.useMemo(() => {
        const map = {};
        ((summary && summary.topChannels) || []).forEach((c) => { map[c.channelId] = c.channelName; });
        (Array.isArray(channelsApi.data) ? channelsApi.data : []).forEach((c) => {
            if (c && c.channelId) map[c.channelId] = c.name;
        });
        return map;
    }, [channelsApi.data, summary]);
    const channelNameOf = (id) => channelNames[id] || id || '—';

    // mode: 'initial' (inline error) | 'background' (toast once per streak)
    //     | 'manual' (errorModal — user pressed Refresh).
    const load = React.useCallback(async (mode) => {
        if (inFlightRef.current) return;
        inFlightRef.current = true;
        setBusy(true);
        try {
            const s = await getDashboardSummary();
            setSummary(s || null);
            setError(null);
            setLastUpdated(Date.now());
            failStreakRef.current = false;
            const tops = (s && s.topChannels) || [];
            if (tops.length) {
                try {
                    const act = await getActivitySummary({
                        channelIds: tops.map((c) => c.channelId),
                        windowSeconds: ACTIVITY_WINDOW_SECONDS,
                        buckets: ACTIVITY_BUCKETS,
                    });
                    const list = Array.isArray(act) ? act : (act ? [act] : []);
                    setActivity(Object.fromEntries(list.map((a) => [a.channelId, a])));
                    setActError(null);
                } catch (e) {
                    // Non-fatal: sparklines degrade; the hint below surfaces it.
                    setActError(errText(e));
                }
            } else {
                setActivity({});
                setActError(null);
            }
        } catch (e) {
            if (mode === 'background') {
                if (!failStreakRef.current) toast(`Sentinel dashboard refresh failed: ${errText(e)}`, 'info');
                failStreakRef.current = true;
            } else if (mode === 'manual') {
                errorModal('Dashboard Refresh Failed', e);
            } else {
                setError(errText(e));
            }
        } finally {
            inFlightRef.current = false;
            setBusy(false);
            setLoading(false);
        }
    }, []);

    React.useEffect(() => {
        load('initial');
        const id = setInterval(() => { if (shouldPoll()) load('background'); }, REFRESH_MS);
        return () => clearInterval(id);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (loading && !summary) {
        return <div className="sn-hint">Loading dashboard…</div>;
    }
    if (error && !summary) {
        return (
            <div className="panel">
                <div className="panel-body">
                    <span style={{ color: 'var(--err)' }}>Could not load the dashboard.</span>{' '}
                    <span className="text-text-dim">{error}</span>{' '}
                    <button type="button" className="btn btn-sm" onClick={() => load('initial')}>Retry</button>
                </div>
            </div>
        );
    }

    const sevCounts = {};
    SEVERITY_ORDER.forEach((s) => { sevCounts[s] = 0; });
    ((summary && summary.openBySeverity) || []).forEach((c) => {
        if (c && c.severity != null) sevCounts[c.severity] = c.count;
    });
    const recent = (summary && summary.recentProblems) || [];
    const tops = (summary && summary.topChannels) || [];

    return (
        <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                <span className="sn-hint" style={{ marginLeft: 'auto' }}>
                    {lastUpdated ? `Updated ${fmtAgo(lastUpdated)} · ` : ''}auto-refreshes every 30s
                </span>
                <button type="button" className="btn btn-sm" disabled={busy}
                    onClick={() => load('manual')}>
                    {busy ? 'Refreshing…' : 'Refresh'}
                </button>
            </div>

            <div className="sn-tiles">
                <StatTile label="Open problems" value={fmtNum(summary.openTotal)}
                    tone={summary.openTotal > 0 ? 'var(--err)' : undefined} />
                {SEVERITY_ORDER.map((s) => (
                    <StatTile key={s} label={SEVERITY_META[s].label} value={fmtNum(sevCounts[s])}
                        tone={sevCounts[s] > 0 ? SEVERITY_META[s].color : undefined} />
                ))}
                <StatTile label="Unacknowledged" value={fmtNum(summary.unacknowledged)}
                    tone={summary.unacknowledged > 0 ? 'var(--warn)' : undefined} />
                <StatTile label="Monitors enabled"
                    value={`${fmtNum(summary.monitorsEnabled)} / ${fmtNum(summary.monitorsTotal)}`} />
                <StatTile label="Channels watched" value={fmtNum(summary.channelsWatched)} />
            </div>

            {selected ? (
                <ProblemInline problem={selected} channelNameOf={channelNameOf}
                    onBack={() => setSelected(null)} />
            ) : (
                <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-start' }}>
                    <div className="panel" style={{ flex: '2 1 460px', minWidth: 0 }}>
                        <div className="panel-header">Recent problems</div>
                        <div className="panel-body flush">
                            {recent.length === 0 ? (
                                <div className="sn-empty">No problems recorded yet.</div>
                            ) : (
                                <table className="dt">
                                    <thead>
                                        <tr>
                                            <th>Severity</th><th>Channel</th><th>Message</th>
                                            <th>Opened</th><th></th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {recent.map((p) => (
                                            <tr key={p.id}
                                                className={p.status === 'PROBLEM' ? 'sn-problem' : undefined}
                                                style={{ cursor: 'pointer' }}
                                                title="Show details"
                                                onClick={() => setSelected(p)}>
                                                <td><SeverityChip severity={p.severity} /></td>
                                                <td>{channelNameOf(p.channelId)}</td>
                                                <td>{p.message}</td>
                                                <td title={fmtTime(p.openedTime)}>{fmtAgo(p.openedTime)}</td>
                                                <td>
                                                    <span style={{ display: 'inline-flex', gap: 4 }}>
                                                        {p.status === 'RESOLVED'
                                                            ? <span className="tag">Resolved</span> : null}
                                                        {p.acknowledgedBy != null
                                                            ? <span className="tag accent">Ack</span> : null}
                                                        {p.suppressed
                                                            ? <span className="tag">Suppressed</span> : null}
                                                    </span>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            )}
                        </div>
                    </div>

                    <div className="panel" style={{ flex: '1 1 320px', minWidth: 0 }}>
                        <div className="panel-header">Top problem channels</div>
                        <div className="panel-body flush">
                            {actError ? (
                                <div className="sn-hint" style={{ padding: '8px 13px' }}>
                                    Activity unavailable: {actError}
                                </div>
                            ) : null}
                            {tops.length === 0 ? (
                                <div className="sn-empty">No channels with open problems.</div>
                            ) : (
                                <table className="dt">
                                    <thead>
                                        <tr>
                                            <th>Channel</th><th className="num">Open</th>
                                            <th>Max severity</th><th>Received (1h)</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {tops.map((c) => (
                                            <tr key={c.channelId}>
                                                <td>{c.channelName || c.channelId}</td>
                                                <td className="num">{fmtNum(c.openCount)}</td>
                                                <td><SeverityChip severity={c.maxSeverity} /></td>
                                                <td>
                                                    <Sparkline width={110} height={24}
                                                        data={activity[c.channelId]
                                                            && activity[c.channelId].sparkline} />
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            )}
                        </div>
                    </div>
                </div>
            )}

            <HealthFootnote summary={summary} />
        </div>
    );
}
