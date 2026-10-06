// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Problems queue and independent detail with explicit selection and receipts.

import { platform } from '@oie/web-shell';
import { errorModal } from '@oie/web-ui';
import {
    getProblems, getProblem, acknowledgeProblem, resolveProblem,
    bulkAcknowledgeProblems, bulkResolveProblems, listMonitors, getCoreChannels, errText,
} from '../api.js';
import {
    canAcknowledge, toast, useApi, useUsernames, FilterBar,
    ChannelActivityPanel, DEFAULT_PROBLEM_FILTERS, SeverityChip,
    MONITOR_TYPE_META, fmtTime, fmtAgo,
} from '../ui.jsx';
import { readIntent, clearIntent } from '../host.jsx';
import { parseBulkReceipt, RECEIPT_LABELS } from '../triage.js';

const React = platform.React;
const { h, modal } = platform.ui;

/**
 * One dialog that both confirms the action and collects the optional
 * comment (ack/resolve want a confirm step, but confirm-then-prompt was two
 * popups and the inline input had no confirm at all). Resolves to the
 * trimmed comment ('' allowed) on confirm, null on cancel.
 */
function confirmWithComment({ title, message, okLabel, danger = false }) {
    return new Promise((resolve) => {
        const input = h('input', { type: 'text', placeholder: 'Optional', maxLength: 1024, 'aria-label': 'Comment' });
        const m = modal({
            title,
            body: h('div',
                h('div', { style: 'margin-bottom:10px;' }, String(message)),
                h('div.field', h('label', 'Comment'), input)),
            onClose: () => resolve(null),
            buttons: [
                { label: 'Cancel', onClick: () => resolve(null) },
                { label: okLabel, primary: !danger, danger, onClick: () => resolve(input.value.trim()) },
            ],
        });
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { resolve(input.value.trim()); m.close(); }
        });
    });
}

const PAGE_SIZE = 25;
const POLL_MS = 30000;

/* Activity window on the detail pane: this much either side of the open, so
   the operator sees the run-up as well as the aftermath. Three hours puts the
   6h span exactly on AUTO's raw-sample threshold — the granularity control is
   there for when a wider view is wanted. */
const DETAIL_WINDOW_MS = 3 * 3600 * 1000;

// Client column key -> server sort column (AlertEventFilter.ALLOWED_SORT_COLUMNS).
const DEFAULT_SORT = { column: 'opened_time', dir: 'DESC' };

// Stable identity while /core/channels loads — ChannelPicker's useApi keys off
// the prop identity, so a fresh [] per render would refetch every render.
const NO_CHANNELS = [];

/* An ungated interval poll would outlive logout and keep resetting the
   engine's session-inactivity timeout — gate every background tick. */
function pollGate() {
    try {
        return !!platform.store.getState('user')
            && platform.router.currentPath().startsWith('/sentinel');
    } catch (e) {
        return false;
    }
}

function problemParams(filters, page, sort) {
    return {
        status: filters.status || undefined,
        severity: filters.severity && filters.severity.length ? filters.severity.join(',') : undefined,
        channelId: filters.channelId || undefined,
        monitorId: filters.monitorId || undefined,
        acknowledged: filters.acknowledged === '' ? undefined : filters.acknowledged === 'true',
        q: filters.q || undefined,
        sort: sort.column,
        sortDir: sort.dir,
        page,
        pageSize: PAGE_SIZE,
    };
}

/* PagedResult is a 4-key envelope and normally survives the api unwrap; be
   defensive about a bare-array shape anyway (single-key unwrap gotcha). */
function normalizePaged(res) {
    if (Array.isArray(res)) return { items: res, total: res.length };
    if (!res || !Array.isArray(res.items) || !Number.isInteger(res.total) || res.total < 0) {
        throw new Error('Unexpected problems response; previous data retained.');
    }
    const items = res.items;
    const total = res.total;
    return { items, total };
}

function initialFilters() {
    const intent = readIntent();
    if (!intent) {
        return DEFAULT_PROBLEM_FILTERS;
    }
    if (intent.kind === 'problems') {
        clearIntent();
        return { ...DEFAULT_PROBLEM_FILTERS, channelId: intent.channelId || '' };
    }
    if (intent.kind === 'unacknowledged') {
        clearIntent();
        return { ...DEFAULT_PROBLEM_FILTERS, acknowledged: 'false' };
    }
    // Some other page's intent (a monitor hand-off); leave it for that page.
    return DEFAULT_PROBLEM_FILTERS;
}

function Freshness({ label, time, error, loading, onRetry }) {
    return <div className="sn-freshness" role="status" aria-live="polite">
        <span>{label}: {time ? `last refreshed ${fmtTime(time)}` : 'no successful refresh yet'}.
            {error ? <span className="text-err"> Refresh failed: {error}. Displayed data may be stale.</span> : null}</span>
        <button className="btn btn-sm" onClick={onRetry} disabled={loading}>
            {loading ? 'Refreshing…' : error ? 'Retry' : 'Refresh'}
        </button>
    </div>;
}

function MutationReceipt({ receipt, pending, onReconcile }) {
    return <>{receipt ? <div className="panel mb-3"><div className="panel-body" role="status">
                <strong>{receipt.operation === 'ack' ? 'Acknowledgement' : 'Resolution'} receipt</strong>
                <p>{receipt.requested} requested; {receipt.applied == null ? 'applied count unknown' : `${receipt.applied} applied by this request`}.
                    {receipt.uncertain ? ' Completion uncertain. Check current states before retrying.' : ' Unapplied IDs remain selected.'}</p>
                {receipt.error ? <p className="text-err">{receipt.error}</p> : null}
                <ul>{receipt.items.map(item => <li key={item.id}>#{item.id}: {RECEIPT_LABELS[item.status]}
                    {item.observed ? ` (${item.observed}, ${item.acknowledged ? 'acknowledged' : 'unacknowledged'})` : ''}
                    {item.error ? ` — ${item.error}` : ''}</li>)}</ul>
                {receipt.uncertain ? <button className="btn btn-sm" disabled={pending} onClick={onReconcile}>Check current problem states</button> : null}
            </div></div> : null}</>;
}

export function ProblemsPage() {
    const [filters, setFilters] = React.useState(initialFilters);
    const [page, setPage] = React.useState(0);
    const [sort, setSort] = React.useState(DEFAULT_SORT);
    const [data, setData] = React.useState(null);
    const [error, setError] = React.useState(null);
    const [loading, setLoading] = React.useState(true);
    const [lastSuccess, setLastSuccess] = React.useState(null);
    const [selected, setSelected] = React.useState(new Set());
    const [detailId, setDetailId] = React.useState(null);
    const [pending, setPending] = React.useState(false);
    const [receipt, setReceipt] = React.useState(null);
    const busyRef = React.useRef(false);
    const seqRef = React.useRef(0);
    const stateRef = React.useRef(null);
    stateRef.current = React.useMemo(() => ({ filters, page, sort }), [filters, page, sort]);
    const openControls = React.useRef(new Map());
    const queueRef = React.useRef(null);
    const channelsApi = useApi(getCoreChannels, []);
    const monitorsApi = useApi(listMonitors, []);
    const userNameOf = useUsernames();
    const channels = React.useMemo(() => Object.fromEntries((channelsApi.data || [])
        .map(c => [c.channelId, c.name || c.channelId])), [channelsApi.data]);
    const monitors = React.useMemo(() => Object.fromEntries((monitorsApi.data || [])
        .map(m => [m.id, m.name || `#${m.id}`])), [monitorsApi.data]);

    const load = React.useCallback(async () => {
        const seq = ++seqRef.current;
        const state = stateRef.current;
        setLoading(true);
        try {
            const res = normalizePaged(await getProblems(problemParams(state.filters, state.page, state.sort)));
            if (seq !== seqRef.current || state !== stateRef.current) return;
            setData(res); setError(null); setLastSuccess(Date.now());
        } catch (e) {
            if (seq === seqRef.current && state === stateRef.current) setError(errText(e));
        } finally { if (seq === seqRef.current) setLoading(false); }
    }, []);
    // Invalidate at effect cleanup, including the debounce interval and unmount.
    const prevQRef = React.useRef(filters.q);
    React.useEffect(() => {
        const delay = filters.q !== prevQRef.current ? 300 : 0;
        prevQRef.current = filters.q;
        const t = setTimeout(load, delay);
        return () => { clearTimeout(t); ++seqRef.current; };
    }, [filters, page, sort, load]);
    React.useEffect(() => {
        const t = setInterval(() => { if (pollGate()) load(); }, POLL_MS);
        return () => clearInterval(t);
    }, [load]);
    React.useEffect(() => {
        if (data && page > 0 && data.items.length === 0 && data.total > 0) setPage(p => Math.max(0, p - 1));
    }, [data, page]);

    const toggle = id => setSelected(prev => {
        const next = new Set(prev); const key = String(id);
        if (next.has(key)) next.delete(key); else next.add(key);
        return next;
    });
    const closeDetail = () => {
        const control = openControls.current.get(String(detailId));
        setDetailId(null);
        requestAnimationFrame(() => (control?.isConnected ? control : queueRef.current)?.focus());
    };
    const mutate = async operation => {
        if (busyRef.current || receipt?.uncertain || !canAcknowledge() || !selected.size) return;
        busyRef.current = true; setPending(true);
        const ids = [...selected];
        try {
            const comment = await confirmWithComment({
                title: operation === 'ack' ? 'Acknowledge Problems' : 'Resolve Problems',
                message: `${operation === 'ack' ? 'Acknowledge' : 'Manually resolve'} ${ids.length} selected problems?`
                    + (operation === 'resolve' ? ' Monitors may open new problems on later breaches.' : ''),
                okLabel: operation === 'ack' ? 'Acknowledge' : 'Resolve', danger: operation === 'resolve',
            });
            if (comment == null) return;
            if (!canAcknowledge()) { errorModal('Permission changed', 'Refresh your session before retrying.'); return; }
            let result;
            try {
                const response = await (operation === 'ack' ? bulkAcknowledgeProblems : bulkResolveProblems)(ids.map(Number), comment);
                result = parseBulkReceipt(response, ids, operation);
            } catch (e) {
                result = parseBulkReceipt(null, ids, operation); result.error = errText(e);
            }
            setReceipt({ ...result, operation });
            // Retain every unapplied/uncertain ID even if refresh removes its row.
            setSelected(prev => new Set([...prev].filter(id => !result.items.some(r => r.id === id && r.status === 'APPLIED'))));
            await load();
        } finally { busyRef.current = false; setPending(false); }
    };
    const reconcile = async () => {
        if (busyRef.current || !receipt) return;
        busyRef.current = true; setPending(true);
        try {
            const items = await Promise.all(receipt.items.map(async item => {
                if (item.status !== 'UNKNOWN') return item;
                try {
                    const d = await getProblem(item.id);
                    if (!d?.event || String(d.event.id) !== item.id) return item;
                    return { ...item, status: 'OBSERVED', observed: d.event.status,
                        acknowledged: d.event.acknowledgedBy != null };
                } catch (e) { return { ...item, error: errText(e) }; }
            }));
            setReceipt(prev => ({ ...prev, items, uncertain: items.some(i => i.status === 'UNKNOWN') }));
            await load();
        } finally { busyRef.current = false; setPending(false); }
    };
    const changeFilters = next => { setFilters(next); setPage(0); setData(null); setLastSuccess(null); setError(null); };
    const sortBy = column => {
        setSort(prev => ({ column, dir: prev.column === column && prev.dir === 'DESC' ? 'ASC' : 'DESC' }));
        setPage(0); setData(null); setLastSuccess(null); setError(null);
    };
    const rows = data?.items || [];
    const sortable = (label, column) => <th aria-sort={sort.column === column ? (sort.dir === 'ASC' ? 'ascending' : 'descending') : 'none'}>
        <button className="btn btn-sm" onClick={() => sortBy(column)}>{label}{sort.column === column ? ` (${sort.dir})` : ''}</button>
    </th>;

    return <div className={`sn-problems${detailId != null ? ' sn-has-detail' : ''}`}>
        <div className="sn-problem-queue" ref={queueRef} tabIndex={-1} aria-label="Problem queue">
            <FilterBar value={filters} onChange={changeFilters} monitors={monitorsApi.data}
                channels={channelsApi.data || NO_CHANNELS} />
            <Freshness label="Queue" time={lastSuccess} error={error} loading={loading} onRetry={load} />
            {channelsApi.error || monitorsApi.error ? <div className="sn-hint" role="status">
                Name lookup failed; IDs remain visible. {channelsApi.error || monitorsApi.error}
            </div> : null}
            <div className="sn-triage-toolbar">
                <span>{selected.size} selected{[...selected].some(id => !rows.some(r => String(r.id) === id)) ? ' (including IDs outside this page)' : ''}</span>
                <button className="btn btn-sm" disabled={pending || !selected.size} onClick={() => setSelected(new Set())}>Clear selection</button>
                {canAcknowledge() && selected.size > 0 ? <>
                    <button className="btn btn-sm btn-primary" disabled={pending || receipt?.uncertain} onClick={() => mutate('ack')}>Acknowledge selected</button>
                    <button className="btn btn-sm btn-danger" disabled={pending || receipt?.uncertain} onClick={() => mutate('resolve')}>Resolve selected</button>
                </> : null}
                {pending ? <span role="status">Operation pending…</span> : null}
            </div>
            <MutationReceipt receipt={receipt} pending={pending} onReconcile={reconcile} />
            {!data && !error ? <div className="sn-empty">Loading problems…</div> : null}
            {data ? <>
                <div className="sn-table-scroll"><table className="dt" aria-label="Problems">
                    <thead><tr><th>Select</th>{sortable('Severity', 'severity')}<th>Status</th>
                        {sortable('Channel', 'channel_id')}<th>Monitor / problem</th>{sortable('Opened', 'opened_time')}<th>Acknowledged</th></tr></thead>
                    <tbody>{rows.map(r => <tr key={r.id} onClick={e => {
                        if (!pending && (e.ctrlKey || e.metaKey || e.shiftKey) && !e.target.closest('button, input')) toggle(r.id);
                    }}>
                        <td><input type="checkbox" aria-label={`Select problem #${r.id}`} checked={selected.has(String(r.id))}
                            disabled={pending} onChange={() => toggle(r.id)} /></td>
                        <td><SeverityChip severity={r.severity} /></td>
                        <td>{r.status}{r.suppressed ? <span className="tag amber">Suppressed</span> : null}</td>
                        <td>{channels[r.channelId] || r.channelId || '—'}</td>
                        <td><span className="text-text-dim">{monitors[r.monitorId] || `#${r.monitorId}`}</span>
                            <button className="sn-problem-open" aria-label={`Open problem #${r.id}: ${r.message || 'No message'}`}
                                ref={el => { if (el) openControls.current.set(String(r.id), el); else openControls.current.delete(String(r.id)); }}
                                disabled={pending} onClick={() => setDetailId(r.id)}>{r.message || `Problem #${r.id}`}</button></td>
                        <td title={fmtTime(r.openedTime)}>{fmtAgo(r.openedTime)}</td>
                        <td>{r.acknowledgedBy == null ? '—' : `${userNameOf(r.acknowledgedBy)} · ${fmtAgo(r.acknowledgedTime)}`}</td>
                    </tr>)}</tbody>
                </table></div>
                {!rows.length ? <div className="sn-empty">No problems match the current filters.</div> : null}
                <div className="sn-triage-toolbar">
                    <button className="btn btn-sm" disabled={loading || page === 0} onClick={() => { setPage(page - 1); setData(null); setLastSuccess(null); setError(null); }}>Previous</button>
                    <span>{data.total === 0 ? '0 of 0' : `${page * PAGE_SIZE + 1}–${page * PAGE_SIZE + rows.length} of ${data.total}`}</span>
                    <button className="btn btn-sm" disabled={loading || (page + 1) * PAGE_SIZE >= data.total}
                        onClick={() => { setPage(page + 1); setData(null); setLastSuccess(null); setError(null); }}>Next</button>
                </div>
            </> : null}
        </div>
        {detailId != null ? <ProblemDetailPane key={detailId} id={detailId} monitors={monitorsApi.data}
            receipt={receipt} onReconcile={reconcile} mutationBlocked={pending || receipt?.uncertain} mutationLock={busyRef} onPending={setPending}
            onUncertain={(operation, id, error) => setReceipt({ ...parseBulkReceipt(null, [id], operation), operation, error })}
            onBack={closeDetail} onChanged={load} /> : null}
    </div>;
}

/* ---- detail pane --------------------------------------------------------- */

function DetailRow({ label, mono, children }) {
    return (
        <tr>
            <td className="text-text-dim" style={{ width: 170, whiteSpace: 'nowrap' }}>{label}</td>
            <td className={mono ? 'mono' : undefined}>{children}</td>
        </tr>
    );
}

function parseDetails(json) {
    if (!json) return null;
    try {
        const v = JSON.parse(json);
        return v && typeof v === 'object' && !Array.isArray(v) ? v : null;
    } catch (e) {
        return null;
    }
}

function detailValue(v) {
    if (v == null) return '—';
    if (typeof v === 'object') return JSON.stringify(v);
    return String(v);
}

/**
 * The raising monitor's runbook, rendered as the one link on this page that
 * points off the console.
 *
 * `rel="noopener noreferrer"` is not boilerplate here. `target="_blank"` alone
 * hands the opened page a live `window.opener` handle back to the admin
 * console, which is enough to navigate this tab to a credential-phishing copy
 * of the login screen from a page whose URL an operator typed months ago into a
 * monitor; `noreferrer` additionally keeps the console's URL out of the
 * destination's logs. The URL itself is validated at save time by
 * MonitorService as an absolute http/https URL; the scheme check below repeats
 * that client-side so a value that never went through the service layer (a row
 * predating the validation, or written straight to the database) still cannot
 * put a `javascript:`/`data:` href into an admin page.
 */
function RunbookLink({ url }) {
    if (!/^https?:\/\//i.test(url)) {
        return null;
    }
    return (
        <a href={url} target="_blank" rel="noopener noreferrer" title={url}
            style={{ overflowWrap: 'anywhere' }}>
            {url}
        </a>
    );
}

function ProblemDetailPane({ id, monitors, receipt, onReconcile, mutationBlocked, mutationLock, onPending, onUncertain, onBack, onChanged }) {
    const userNameOf = useUsernames();
    const [detail, setDetail] = React.useState(null);
    const [error, setError] = React.useState(null);
    const seqRef = React.useRef(0);
    const [lastSuccess, setLastSuccess] = React.useState(null);
    const [loading, setLoading] = React.useState(false);
    const headingRef = React.useRef(null);
    const load = React.useCallback(async () => {
        const seq = ++seqRef.current;
        setLoading(true);
        try {
            const d = await getProblem(id);
            if (!d?.event || String(d.event.id) !== String(id)) throw new Error('Unexpected problem response.');
            if (seq !== seqRef.current) return;
            setDetail(d); setError(null); setLastSuccess(Date.now());
        } catch (e) {
            if (seq === seqRef.current) setError(errText(e));
        } finally { if (seq === seqRef.current) setLoading(false); }
    }, [id]);
    React.useEffect(() => {
        load(); headingRef.current?.focus();
        return () => { ++seqRef.current; };
    }, [load]);
    React.useEffect(() => {
        const t = setInterval(() => { if (pollGate()) load(); }, POLL_MS);
        return () => clearInterval(t);
    }, [load]);

    const ev = (detail && detail.event) || {};
    const open = ev.status === 'PROBLEM';
    const changed = () => { load(false); if (onChanged) onChanged(); };

    const mutate = async operation => {
        if (mutationLock.current || mutationBlocked || !canAcknowledge()) return;
        mutationLock.current = true; onPending(true);
        const eventId = ev.id;
        try {
            const comment = await confirmWithComment({
                title: operation === 'ack' ? 'Acknowledge Problem' : 'Resolve Problem',
                message: `${operation === 'ack' ? 'Acknowledge' : 'Manually resolve'} "${ev.message || `problem #${eventId}`}"?`
                    + (operation === 'resolve' ? ' The monitor may open a new problem on a later breach.' : ''),
                okLabel: operation === 'ack' ? 'Acknowledge' : 'Resolve', danger: operation === 'resolve',
            });
            if (comment == null) return;
            if (!canAcknowledge()) { errorModal('Permission changed', 'Refresh your session before retrying.'); return; }
            try {
                const result = await (operation === 'ack' ? acknowledgeProblem : resolveProblem)(eventId, comment);
                if (!result || String(result.id) !== String(eventId)
                    || (operation === 'ack' ? result.acknowledgedBy == null : result.status !== 'RESOLVED')) {
                    throw new Error('Unexpected mutation response; completion is uncertain.');
                }
                toast(operation === 'ack' ? 'Problem acknowledged.' : 'Problem resolved.', 'success');
            } catch (e) {
                onUncertain(operation, eventId, errText(e));
            }
            changed();
        } finally { mutationLock.current = false; onPending(false); }
    };

    /* Throughput either side of the open: the evidence for the alert, which
       detailsJson otherwise reduces to a single number. from/to are fetch deps
       inside ChannelActivityPanel, so they must be memoised — a fresh window
       per render would refetch forever. */
    const openedMs = ev.openedTime ? new Date(ev.openedTime).getTime() : NaN;
    const resolvedMs = ev.resolvedTime ? new Date(ev.resolvedTime).getTime() : NaN;
    const activityWindow = React.useMemo(
        () => (isNaN(openedMs)
            ? null
            : { from: openedMs - DETAIL_WINDOW_MS, to: openedMs + DETAIL_WINDOW_MS }),
        [openedMs],
    );
    // A long-running problem resolves past the right edge; say so rather than
    // stretching the axis out to a marker with no data around it.
    const resolveInWindow = !!activityWindow && !isNaN(resolvedMs)
        && resolvedMs >= activityWindow.from && resolvedMs <= activityWindow.to;
    /* Text tokens, not the series hues: --err and --ok are the Errors and Sent
       lines, and an annotation borrowing a series color reads as that series.
       Both labels sit to the right of their own rule but in different vertical
       bands, so a problem that resolves seconds after it opened still gets two
       readable labels instead of one overprinted smear. */
    const activityMarkers = React.useMemo(() => {
        const list = [];
        if (!isNaN(openedMs)) {
            list.push({ time: openedMs, label: 'Opened', color: 'var(--text)', position: 'insideTopLeft' });
        }
        if (resolveInWindow) {
            list.push({ time: resolvedMs, label: 'Resolved', color: 'var(--text-dim)', position: 'insideBottomLeft' });
        }
        return list;
    }, [openedMs, resolvedMs, resolveInWindow]);
    const activityHint = `3h either side of the open${
        !isNaN(resolvedMs) && !resolveInWindow ? `, resolved ${fmtTime(ev.resolvedTime)} (outside it)` : ''}`;
    const activityUnavailable = !ev.channelId
        ? 'Not scoped to a single channel — no throughput to chart.'
        : (!activityWindow
            ? 'No open time recorded — nothing to chart.'
            : null);

    const parsed = detail ? parseDetails(ev.detailsJson) : null;
    const dispatches = (detail && detail.dispatches) || [];
    const typeMeta = detail && MONITOR_TYPE_META[detail.monitorType];
    /* The runbook lives on the monitor, not on ProblemDetail, so it comes from
       the monitor list the page already loaded for its Monitor column — no
       extra round trip, and it degrades to no row (never an error) while that
       list is loading, if it failed, or if the monitor has since been deleted. */
    const runbookUrl = React.useMemo(() => {
        const owner = (monitors || []).find((m) => m.id === ev.monitorId);
        const url = owner && owner.runbookUrl ? String(owner.runbookUrl).trim() : '';
        return url || null;
    }, [monitors, ev.monitorId]);

    return (
        <div className="sn-problem-detail">
            <div className="flex items-center gap-2 mb-3">
                <button className="btn btn-sm" disabled={mutationBlocked && mutationLock.current} onClick={onBack}>Close detail</button>
                {detail ? <SeverityChip severity={ev.severity} /> : null}
                <h2 ref={headingRef} tabIndex={-1} title={ev.message || ''}
                    style={{ margin: 0, fontSize: 15, fontWeight: 600, minWidth: 0,
                        overflowWrap: 'anywhere', whiteSpace: 'normal' }}>
                    {detail ? (ev.message || `Problem #${ev.id}`) : `Problem #${id}`}
                </h2>
                <span style={{ flex: 1 }} />
                {detail && canAcknowledge() && open && ev.acknowledgedBy == null ? (
                    <button className="btn btn-sm btn-primary" disabled={mutationBlocked} onClick={() => mutate('ack')}>Acknowledge</button>
                ) : null}
                {detail && canAcknowledge() && open ? (
                    <button className="btn btn-sm btn-danger" disabled={mutationBlocked} onClick={() => mutate('resolve')}>Resolve</button>
                ) : null}
            </div>

            <MutationReceipt receipt={receipt} pending={mutationLock.current} onReconcile={onReconcile} />
            <Freshness label="Detail" time={lastSuccess} error={error} loading={loading} onRetry={load} />
            {!detail && !error ? <div className="sn-empty">Loading problem…</div> : null}

            {detail ? (
                <>
                    {ev.suppressed ? (
                        <div className="panel mb-3"><div className="panel-body">
                            <span className="tag amber">Suppressed</span>{' '}
                            <span className="text-text-dim">
                                A maintenance, alerting-schedule, or dependency decision was recorded;
                                Sentinel rechecks current policy before every later delivery attempt.
                                Earlier attempts, if any, remain in Dispatch History.
                            </span>
                        </div></div>
                    ) : null}

                    {runbookUrl ? <div className="panel mb-3"><div className="panel-header">Runbook</div>
                        <div className="panel-body"><RunbookLink url={runbookUrl} /></div></div> : null}
                    {dispatches.some(d => !d.success) ? <div className="panel mb-3"><div className="panel-header">Delivery failures</div>
                        <div className="panel-body">{dispatches.filter(d => !d.success).map((d, i) =>
                            <p key={d.id ?? i}>{fmtTime(d.dispatchTime)} — {d.errorMessage || 'Failed without an error message'}</p>)}</div></div> : null}
                    <ChannelActivityPanel
                        title={`Channel activity${detail.channelName ? ` — ${detail.channelName}` : ''}`}
                        channelId={ev.channelId}
                        from={activityWindow ? activityWindow.from : null}
                        to={activityWindow ? activityWindow.to : null}
                        markers={activityMarkers}
                        hint={activityHint}
                        unavailable={activityUnavailable} />

                    <div className="panel mb-3">
                        <div className="panel-header">Captured evidence</div>
                        <div className="panel-body">
                            {parsed ? (
                                <table className="dt">
                                    <tbody>
                                        {Object.entries(parsed).map(([k, v]) => (
                                            <tr key={k}>
                                                <td className="text-text-dim" style={{ width: 170, whiteSpace: 'nowrap' }}>{k}</td>
                                                <td className="mono">{detailValue(v)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            ) : ev.detailsJson ? (
                                <div className="sn-hint">Captured JSON could not be interpreted; see technical details.</div>
                            ) : (
                                <div className="sn-hint">No captured value.</div>
                            )}
                        </div>
                    </div>

                    <div className="panel mb-3">
                        <div className="panel-header">Details</div>
                        <div className="panel-body">
                            <table className="dt">
                                <tbody>
                                    <DetailRow label="Status">
                                        <span className="status-cell">
                                            <span className={`pip ${open ? 'err' : 'ok'}`} />
                                            {ev.status || '—'}
                                            {ev.suppressed ? <span className="tag amber">Suppressed</span> : null}
                                        </span>
                                    </DetailRow>
                                    <DetailRow label="Severity"><SeverityChip severity={ev.severity} /></DetailRow>
                                    <DetailRow label="Message">{ev.message || <span className="text-text-dim">(none)</span>}</DetailRow>
                                    <DetailRow label="Monitor">
                                        {detail.monitorName || `#${ev.monitorId}`}
                                        {typeMeta ? <span className="text-text-dim"> — {typeMeta.label}</span> : null}

                                    </DetailRow>
                                    <DetailRow label="Channel">
                                        {detail.channelName || ev.channelId || '—'}

                                    </DetailRow>
                                    {ev.metadataId != null ? (
                                        <DetailRow label="Connector">
                                            {detail.connectorName || '—'}

                                        </DetailRow>
                                    ) : null}
                                    <DetailRow label="Opened">
                                        {fmtTime(ev.openedTime)}
                                        <span className="text-text-dim"> ({fmtAgo(ev.openedTime)})</span>
                                    </DetailRow>
                                    {ev.resolvedTime ? (
                                        <DetailRow label="Resolved">
                                            {fmtTime(ev.resolvedTime)}
                                            <span className="text-text-dim"> ({fmtAgo(ev.resolvedTime)})</span>
                                        </DetailRow>
                                    ) : null}
                                    <DetailRow label="Acknowledged">
                                        {ev.acknowledgedBy != null ? (
                                            <>
                                                {`${userNameOf(ev.acknowledgedBy)} at ${fmtTime(ev.acknowledgedTime)}`}
                                                {ev.ackComment ? <span className="text-text-dim"> — {ev.ackComment}</span> : null}
                                            </>
                                        ) : (
                                            <span className="text-text-dim">Not acknowledged</span>
                                        )}
                                    </DetailRow>

                                </tbody>
                            </table>
                        </div>
                    </div>

                    <details className="panel mb-3">
                        <summary className="panel-header">Technical IDs and raw evidence</summary>
                        <div className="panel-body"><p>Event #{ev.id}; monitor #{ev.monitorId}; channel {ev.channelId || 'none'};
                            connector metadata {ev.metadataId ?? 'none'}.</p>
                            <pre className="mono">{ev.detailsJson || 'No raw evidence.'}</pre></div>
                    </details>
                    <div className="panel">
                        <div className="panel-header">Action Dispatches</div>
                        <div className="panel-body">
                            {dispatches.length === 0 ? (
                                <div className="sn-empty">No actions were dispatched for this event.</div>
                            ) : (
                                <div className="sn-table-scroll"><table className="dt">
                                    <thead>
                                        <tr><th>Time</th><th>Action</th><th>Result</th><th>Error</th></tr>
                                    </thead>
                                    <tbody>
                                        {dispatches.map((d, i) => (
                                            <tr key={d.id != null ? d.id : i}>
                                                <td title={fmtAgo(d.dispatchTime)}>{fmtTime(d.dispatchTime)}</td>
                                                <td className="mono">{d.actionId != null ? `#${d.actionId}` : '—'}</td>
                                                <td>
                                                    <span className="status-cell">
                                                        <span className={`pip ${d.success ? 'ok' : 'err'}`} />
                                                        {d.success ? 'Sent' : 'Failed'}
                                                    </span>
                                                </td>
                                                <td>{d.errorMessage || ''}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table></div>
                            )}
                        </div>
                    </div>
                </>
            ) : null}
        </div>
    );
}
