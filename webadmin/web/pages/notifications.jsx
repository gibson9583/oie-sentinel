// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Notifications page: the one-off alerts of Log monitors (kind NOTIFICATION)
// over GET /notifications, newest first, filtered by monitor and date, with a
// read-only detail from GET /problems/{id}. A notification has nothing to
// acknowledge or resolve, so neither control exists here. Log text is always
// a React text child, never HTML.

import { platform } from '@oie/web-shell';
import { getNotifications, getProblem, listMonitors, getCoreChannels } from '../api.js';
import {
    useApi, SeverityChip, MONITOR_TYPE_META, fmtTime, fmtAgo, monitorKind, isLogAlert, LOG_LABEL,
} from '../ui.jsx';
import { MatchesPanel, matchDetailsOf } from './matches-panel.jsx';

const React = platform.React;

const PAGE_SIZE = 25;
const NO_FILTERS = { monitorId: '', from: '', to: '' };
const HELD_BACK = 'Held back from actions by a schedule, a Suppressed by monitor, or the monitor being disabled.';

/**
 * Epoch millis of a yyyy-mm-dd day's first or last millisecond in the browser's
 * zone, which the filter says. The rows follow the host's display zone, which
 * the host does not give plugins yet.
 */
function dayEdge(day, end) {
    if (!day) return undefined;
    const t = new Date(`${day}T${end ? '23:59:59.999' : '00:00:00'}`).getTime();
    return isNaN(t) ? undefined : t;
}

/** Query params for a filter and page; the To day is included in full. */
function notificationParams(filters, page) {
    return {
        monitorId: filters.monitorId || undefined,
        from: dayEdge(filters.from, false),
        to: dayEdge(filters.to, true),
        page,
        pageSize: PAGE_SIZE,
    };
}

/** "Log" for a Log notification, else the channel's name. */
function labelOf(item, channelNames) {
    return isLogAlert(item) ? LOG_LABEL : (channelNames[item.channelId] || item.channelId || '—');
}

/** Enter or Space on a focused row opens it, as a click does. */
function openOnKey(e, open) {
    if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        open();
    }
}

/** The list: time, monitor, label, message and whether it was held back. Rows open by click or keyboard. */
function NotificationTable({ items, monitorNames, channelNames, onOpen }) {
    return (
        <table className="dt">
            <thead>
                <tr><th>Time</th><th>Monitor</th><th>Label</th><th>Message</th><th>Held back</th></tr>
            </thead>
            <tbody>
                {items.map((n) => (
                    <tr key={n.id} className="sn-row-link" tabIndex={0} title="Show details" role="button"
                        aria-label={`Show details of the notification at ${fmtTime(n.openedTime)} from `
                            + `${monitorNames[n.monitorId] || `monitor #${n.monitorId}`}`}
                        onClick={() => onOpen(n)} onKeyDown={(e) => openOnKey(e, () => onOpen(n))}>
                        <td title={fmtAgo(n.openedTime)}>{fmtTime(n.openedTime)}</td>
                        <td>{monitorNames[n.monitorId] || `#${n.monitorId}`}</td>
                        <td>{labelOf(n, channelNames)}</td>
                        <td style={{ overflowWrap: 'anywhere' }}>{n.message || ''}</td>
                        <td>{n.suppressed ? <span className="tag amber" title={HELD_BACK}>Held back</span> : ''}</td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
}

function Row({ label, children }) {
    return (
        <tr>
            <td className="text-text-dim" style={{ width: 170, whiteSpace: 'nowrap' }}>{label}</td>
            <td>{children}</td>
        </tr>
    );
}

/**
 * One notification: details, matches and the actions it was sent to. With a
 * matchesNote, the matches are not shown and the note says why.
 */
function NotificationBody({ event, detail, label, matchesNote }) {
    const ev = event || {};
    const d = detail || {};
    const typeMeta = MONITOR_TYPE_META[d.monitorType];
    const matches = matchesNote ? null : matchDetailsOf(ev, d.monitorType);
    const dispatches = Array.isArray(d.dispatches) ? d.dispatches : [];
    return (
        <>
            <div className="panel mb-3">
                <div className="panel-header">Details</div>
                <div className="panel-body">
                    <table className="dt">
                        <tbody>
                            <Row label="Time">{fmtTime(ev.openedTime)}</Row>
                            <Row label="Severity"><SeverityChip severity={ev.severity} /></Row>
                            <Row label="Monitor">
                                {d.monitorName || `#${ev.monitorId}`}
                                {typeMeta ? <span className="text-text-dim">{` (${typeMeta.label})`}</span> : null}
                            </Row>
                            <Row label="Label">{d.channelName || label}</Row>
                            <Row label="Message">{ev.message || ''}</Row>
                            {ev.suppressed ? <Row label="Held back">{HELD_BACK}</Row> : null}
                            <Row label="Event ID"><span className="mono">{ev.id}</span></Row>
                        </tbody>
                    </table>
                </div>
            </div>
            <div className="panel mb-3">
                <div className="panel-header">Matches</div>
                <div className="panel-body">
                    {matches ? <MatchesPanel details={matches} notification />
                        : <div className="sn-hint">{matchesNote || 'No match details were recorded.'}</div>}
                </div>
            </div>
            <div className="panel">
                <div className="panel-header">Action Dispatches</div>
                <div className="panel-body">
                    {dispatches.length === 0 ? (
                        <div className="sn-empty">No actions were dispatched for this notification.</div>
                    ) : (
                        <table className="dt">
                            <thead><tr><th>Time</th><th>Action</th><th>Result</th><th>Error</th></tr></thead>
                            <tbody>
                                {dispatches.map((x, i) => (
                                    <tr key={x.id != null ? x.id : i}>
                                        <td>{fmtTime(x.dispatchTime)}</td>
                                        <td className="mono">{x.actionId != null ? `#${x.actionId}` : '—'}</td>
                                        <td>{x.success ? 'Sent' : 'Failed'}</td>
                                        <td>{x.errorMessage || ''}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </div>
            </div>
        </>
    );
}

function NotificationDetail({ item, label, onBack }) {
    const detail = useApi(() => getProblem(item.id), [item.id]);
    const loaded = detail.data && detail.data.event;
    const ev = loaded || item;
    // Matched lines come only from this detail read, which applies the access
    // and include-matches rules now; the list row may predate a change to either.
    const matchesNote = loaded ? null
        : detail.error ? 'Matched lines are not shown because the notification could not be loaded.'
            : 'Loading matched lines…';
    return (
        <div className="sn-notification-detail">
            <div className="flex items-center gap-2 mb-3">
                <button className="btn btn-sm" onClick={onBack}>← Back</button>
                <SeverityChip severity={ev.severity} />
                <h2 style={{ margin: 0, fontSize: 15, fontWeight: 600, minWidth: 0, overflowWrap: 'anywhere' }}>
                    {ev.message || `Notification #${ev.id}`}
                </h2>
            </div>
            {detail.error ? (
                <div className="sn-hint" style={{ marginBottom: 8 }}>
                    <span className="text-err">Could not load the notification.</span> {detail.error}{' '}
                    <button className="btn btn-sm" onClick={detail.reload}>Retry</button>
                </div>
            ) : null}
            <NotificationBody event={ev} detail={detail.data} label={label} matchesNote={matchesNote} />
        </div>
    );
}

export function NotificationsPage() {
    const [filters, setFilters] = React.useState(NO_FILTERS);
    const [page, setPage] = React.useState(0);
    const [selected, setSelected] = React.useState(null);
    const list = useApi(() => getNotifications(notificationParams(filters, page)), [filters, page]);
    const monitorsApi = useApi(listMonitors, []);
    const channelsApi = useApi(getCoreChannels, []);

    const monitors = (Array.isArray(monitorsApi.data) ? monitorsApi.data : [])
        .filter((m) => monitorKind(m.monitorType) !== 'CHANNEL');
    const monitorNames = {};
    (Array.isArray(monitorsApi.data) ? monitorsApi.data : []).forEach((m) => { monitorNames[m.id] = m.name; });
    const channelNames = {};
    (Array.isArray(channelsApi.data) ? channelsApi.data : []).forEach((c) => { channelNames[c.channelId] = c.name; });

    const items = list.data && Array.isArray(list.data.items) ? list.data.items : [];
    const total = list.data && typeof list.data.total === 'number' ? list.data.total : items.length;
    const set = (key, value) => { setFilters((f) => ({ ...f, [key]: value })); setPage(0); };

    return (
        <div className="sn-notifications">
            {selected ? (
                <NotificationDetail item={selected} label={labelOf(selected, channelNames)}
                    onBack={() => setSelected(null)} />
            ) : null}
            <div style={{ display: selected ? 'none' : undefined }}>
                <div className="sn-filterbar">
                    <select value={filters.monitorId} onChange={(e) => set('monitorId', e.target.value)}>
                        <option value="">All monitors</option>
                        {monitors.map((m) => <option key={m.id} value={String(m.id)}>{m.name}</option>)}
                    </select>
                    <span className="sn-hint">From</span>
                    <input type="date" aria-label="From" value={filters.from} onChange={(e) => set('from', e.target.value)} />
                    <span className="sn-hint">To</span>
                    <input type="date" aria-label="To" value={filters.to} onChange={(e) => set('to', e.target.value)} />
                    <span className="sn-hint">Dates in your browser's time zone</span>
                    <button type="button" className="btn btn-sm" onClick={() => { setFilters(NO_FILTERS); setPage(0); }}>
                        Clear
                    </button>
                    <span style={{ flex: 1 }} />
                    <button type="button" className="btn btn-sm" onClick={list.reload} disabled={list.loading}>
                        {list.loading ? 'Refreshing…' : 'Refresh'}
                    </button>
                </div>
                {list.error ? (
                    <div className="panel mb-3"><div className="panel-body">
                        <span className="text-err">Could not load notifications.</span>{' '}
                        <span className="text-text-dim">{list.error}</span>{' '}
                        <button className="btn btn-sm" onClick={list.reload}>Retry</button>
                    </div></div>
                ) : null}
                {!list.data && !list.error ? <div className="sn-empty">Loading notifications…</div> : null}
                {list.data && items.length === 0 ? (
                    <div className="sn-empty">No notifications match the current filters.</div>
                ) : null}
                {items.length ? (
                    <div className="panel"><div className="panel-body flush">
                        <NotificationTable items={items} monitorNames={monitorNames} channelNames={channelNames}
                            onOpen={setSelected} />
                    </div></div>
                ) : null}
                {list.data ? (
                    <div className="flex items-center gap-2" style={{ marginTop: 8 }}>
                        <button className="btn btn-sm" disabled={list.loading || page === 0}
                            onClick={() => setPage(page - 1)}>‹ Prev</button>
                        <span className="sn-hint">
                            {total === 0 ? '0 of 0' : `${page * PAGE_SIZE + 1}-${page * PAGE_SIZE + items.length} of ${total}`}
                        </span>
                        <button className="btn btn-sm" disabled={list.loading || (page + 1) * PAGE_SIZE >= total}
                            onClick={() => setPage(page + 1)}>Next ›</button>
                    </div>
                ) : null}
            </div>
        </div>
    );
}
