// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Problems page: server-side paginated/filtered/sorted list over GET /problems
// plus a master-detail ProblemDetail pane (GET /problems/{id}) with single and
// bulk acknowledge, manual resolve, and the channel's throughput either side of
// the open (the alert's evidence). The DataTable stays mounted (hidden)
// while the detail pane is open, so the 30s background refresh preserves
// filters, sort, page and multi-selection. Plain click opens the detail pane;
// ctrl/cmd/shift-click builds a multi-selection for the bulk-acknowledge
// toolbar button.

import { platform } from '@oie/web-shell';
import { errorModal } from '@oie/web-ui';
import {
    getProblems, getProblem, acknowledgeProblem, resolveProblem,
    bulkAcknowledgeProblems, bulkResolveProblems, listMonitors, getCoreChannels, errText,
} from '../api.js';
import {
    canAcknowledge, toast, useApi, useDataTable, useUsernames, FilterBar,
    ChannelActivityPanel, DEFAULT_PROBLEM_FILTERS, SeverityChip, SEVERITY_META,
    MONITOR_TYPE_META, fmtTime, fmtAgo,
} from '../ui.jsx';
import { readIntent, clearIntent } from '../host.jsx';

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
        const input = h('input', { type: 'text', placeholder: 'Optional' });
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
const SORT_COLUMNS = { severity: 'severity', channel: 'channel_id', opened: 'opened_time' };
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
    const items = res && Array.isArray(res.items) ? res.items : [];
    const total = res && typeof res.total === 'number' ? res.total : items.length;
    return { items, total };
}

/* ---- DataTable cell builders (DOM nodes via platform.ui.h, never JSX) ---- */

function sevChipNode(severity) {
    const meta = SEVERITY_META[severity] || { label: severity || '—', color: 'var(--text-faint)' };
    const dot = h('span.sn-sev-dot');
    dot.style.background = meta.color;
    const chip = h('span.tag.sn-sev', dot, String(meta.label));
    chip.style.borderColor = `color-mix(in srgb, ${meta.color} 55%, transparent)`;
    return chip;
}

function statusNode(status) {
    const resolved = status === 'RESOLVED';
    return h('span.status-cell', h(`span.pip.${resolved ? 'ok' : 'err'}`), status || '—');
}

function messageNode(message) {
    const text = message == null ? '' : String(message);
    const s = h('span', text);
    s.style.cssText = 'display:inline-block;max-width:420px;overflow:hidden;'
        + 'text-overflow:ellipsis;white-space:nowrap;vertical-align:bottom;';
    s.title = text;
    return s;
}

function openedNode(r) {
    const s = h('span', fmtAgo(r.openedTime));
    s.title = fmtTime(r.openedTime);
    return s;
}

function ackNode(r, namesRef) {
    if (r.acknowledgedBy == null) {
        const s = h('span', '—');
        s.style.color = 'var(--text-faint)';
        return s;
    }
    const who = namesRef.current.userNameOf
        ? namesRef.current.userNameOf(r.acknowledgedBy) : `user #${r.acknowledgedBy}`;
    const s = h('span', `${who} · ${fmtAgo(r.acknowledgedTime)}`);
    s.title = `${fmtTime(r.acknowledgedTime)}${r.ackComment ? ` — ${r.ackComment}` : ''}`;
    return s;
}

/* Columns are captured once at DataTable construction; name lookups go through
   the mutable ref so they resolve once /core/channels and /monitors load.
   sortValue mirrors the server ORDER BY (VARCHAR name for severity, channel id
   text) so the optimistic client resort of the current page agrees with the
   authoritative server response. */
function buildColumns(namesRef) {
    return [
        {
            key: 'severity', label: 'Severity', width: '110px',
            render: (r) => sevChipNode(r.severity),
            sortValue: (r) => r.severity || '',
        },
        {
            key: 'status', label: 'Status', width: '100px', sortable: false,
            render: (r) => statusNode(r.status),
        },
        {
            key: 'channel', label: 'Channel',
            render: (r) => namesRef.current.channels[r.channelId] || r.channelId || '—',
            sortValue: (r) => r.channelId || '',
        },
        {
            key: 'monitor', label: 'Monitor', sortable: false,
            render: (r) => namesRef.current.monitors[r.monitorId] || `#${r.monitorId}`,
        },
        {
            key: 'message', label: 'Message', sortable: false,
            render: (r) => messageNode(r.message),
        },
        {
            key: 'opened', label: 'Opened', width: '110px',
            render: (r) => openedNode(r),
            sortValue: (r) => new Date(r.openedTime || 0).getTime(),
        },
        {
            key: 'ack', label: 'Acknowledged', sortable: false,
            render: (r) => ackNode(r, namesRef),
        },
        {
            key: 'flags', label: 'Suppressed', width: '90px', sortable: false,
            render: (r) => (r.suppressed ? h('span.tag.amber', 'Suppressed') : ''),
        },
    ];
}

/* ---- page --------------------------------------------------------------- */

/**
 * Turns a pending host-surface intent (see host.jsx) into the filter set the
 * list should open with, or returns the defaults when there is none.
 *
 * <p>Consumed in a state initializer rather than an effect: an effect would
 * render the default filter set first, fire its fetch, and only then narrow —
 * so clicking a channel's severity chip on the host Dashboard would flash
 * every open problem on the server before showing the one channel's. Reading
 * it before the first render means the very first request is already the right
 * one.</p>
 */
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

export function ProblemsPage() {
    const [filters, setFilters] = React.useState(initialFilters);
    const [page, setPage] = React.useState(0);
    const [sort, setSort] = React.useState(DEFAULT_SORT);
    const [data, setData] = React.useState(null);       // { items, total }
    const [error, setError] = React.useState(null);     // interactive-load failure
    const [loading, setLoading] = React.useState(true);
    const [selCount, setSelCount] = React.useState(0);
    const [detailId, setDetailId] = React.useState(null);

    const seqRef = React.useRef(0);
    const stateRef = React.useRef(null);
    stateRef.current = { filters, page, sort };
    const namesRef = React.useRef({ channels: {}, monitors: {} });
    const modRef = React.useRef(false);                 // last click had ctrl/cmd/shift
    const handlersRef = React.useRef({ onSelect: () => {} });

    /* One toast per failure streak, not one per 30s tick — and 'info', never
       'warn': the host routes warn/error toasts to an acknowledge-to-dismiss
       modal, which must not stack up from a non-fatal background poll. */
    const failStreakRef = React.useRef(false);

    const load = React.useCallback(async (background) => {
        const seq = ++seqRef.current;
        const s = stateRef.current;
        if (!background) setLoading(true);
        try {
            const res = await getProblems(problemParams(s.filters, s.page, s.sort));
            if (seq !== seqRef.current) return;
            setData(normalizePaged(res));
            setError(null);
            failStreakRef.current = false;
        } catch (e) {
            if (seq !== seqRef.current) return;
            if (background) {
                if (!failStreakRef.current) toast(`Problems refresh failed: ${errText(e)}`, 'info');
                failStreakRef.current = true;
            } else {
                setError(errText(e));
            }
        } finally {
            if (seq === seqRef.current && !background) setLoading(false);
        }
    }, []);

    // Fetch on filter/page/sort change; only free-text typing is debounced.
    const prevQRef = React.useRef(filters.q);
    React.useEffect(() => {
        const delay = filters.q !== prevQRef.current ? 300 : 0;
        prevQRef.current = filters.q;
        const t = setTimeout(() => load(false), delay);
        return () => clearTimeout(t);
    }, [filters, page, sort, load]);

    React.useEffect(() => {
        const t = setInterval(() => { if (pollGate()) load(true); }, POLL_MS);
        return () => clearInterval(t);
    }, [load]);

    // Name lookups for the channel/monitor columns and the FilterBar selects.
    const channelsApi = useApi(getCoreChannels, []);
    const monitorsApi = useApi(listMonitors, []);

    const columns = React.useMemo(() => buildColumns(namesRef), []);
    const options = React.useMemo(() => ({
        selectable: 'multi',
        rowKey: (r) => String(r.id),
        emptyText: 'No problems match the current filters.',
        onSelect: (rows) => handlersRef.current.onSelect(rows),
    }), []);
    const rows = data ? data.items : null;
    const { node, table } = useDataTable(columns, options, rows);

    handlersRef.current.onSelect = (rows_) => {
        setSelCount(rows_.length);
        if (rows_.length === 1 && !modRef.current) setDetailId(rows_[0].id);
    };

    // Repaint table cells once channel/monitor names arrive.
    React.useEffect(() => {
        const map = {};
        (channelsApi.data || []).forEach((c) => { map[c.channelId] = c.name || c.channelId; });
        namesRef.current.channels = map;
        const t = table();
        if (t) t.render();
    }, [channelsApi.data, table]);
    React.useEffect(() => {
        const map = {};
        (monitorsApi.data || []).forEach((m) => { map[m.id] = m.name || `#${m.id}`; });
        namesRef.current.monitors = map;
        const t = table();
        if (t) t.render();
    }, [monitorsApi.data, table]);
    // Same repaint dance for the Acknowledged column's usernames.
    const userNameOf = useUsernames();
    React.useEffect(() => {
        namesRef.current.userNameOf = userNameOf;
        const t = table();
        if (t) t.render();
    }, [userNameOf, table]);
    // 'info', not 'warn' (host warn toasts are modals): the page still works
    // with raw ids when these name lookups fail.
    React.useEffect(() => {
        if (channelsApi.error) toast(`Failed to load channels: ${channelsApi.error}`, 'info');
    }, [channelsApi.error]);
    React.useEffect(() => {
        if (monitorsApi.error) toast(`Failed to load monitors: ${monitorsApi.error}`, 'info');
    }, [monitorsApi.error]);

    // setRows prunes selection keys missing from the new page; keep the count honest.
    React.useEffect(() => {
        const t = table();
        if (t) setSelCount(t.selectedRows().length);
    }, [rows, table]);

    // A background refresh can strand the pager past the last page.
    React.useEffect(() => {
        if (data && page > 0 && data.items.length === 0 && data.total > 0) {
            setPage((p) => Math.max(0, p - 1));
        }
    }, [data, page]);

    const changeFilters = (next) => { setFilters(next); setPage(0); };

    /* DataTable has no sort callback, and a bubbled wrapper onClick can never
       observe one: the th's own click handler synchronously re-renders the
       table, detaching the clicked th before React's delegated bubble listener
       resolves it, so the synthetic event is dropped. The capture phase fires
       while the th is still attached — sample the table's sortKey/sortDir one
       tick later (after sortNow ran) and map it onto the server sort params. */
    const sortRef = React.useRef(sort);
    sortRef.current = sort;
    const syncSortFromTable = () => {
        const t = table();
        if (!t) return;
        const mapped = t.sortKey && SORT_COLUMNS[t.sortKey]
            ? { column: SORT_COLUMNS[t.sortKey], dir: t.sortDir > 0 ? 'ASC' : 'DESC' }
            : DEFAULT_SORT;
        if (mapped.column !== sortRef.current.column || mapped.dir !== sortRef.current.dir) {
            setSort(mapped);
            setPage(0);
        }
    };
    const onTableClickCapture = (e) => {
        modRef.current = e.ctrlKey || e.metaKey || e.shiftKey;
        setTimeout(syncSortFromTable, 0);
    };
    // th sorting is also keyboard-operable (Enter/Space on the focused th).
    const onTableKeyDownCapture = (e) => {
        if (e.key === 'Enter' || e.key === ' ') setTimeout(syncSortFromTable, 0);
    };

    const bulkAck = async () => {
        const t = table();
        if (!t) return;
        const ids = t.selectedRows().map((r) => r.id);
        if (!ids.length) return;
        const comment = await confirmWithComment({
            title: 'Acknowledge Problems',
            message: `Acknowledge ${ids.length} selected problem${ids.length === 1 ? '' : 's'}?`,
            okLabel: 'Acknowledge',
        });
        if (comment == null) return;
        try {
            const res = await bulkAcknowledgeProblems(ids, comment);
            const n = res && typeof res.acknowledged === 'number' ? res.acknowledged : ids.length;
            toast(`Acknowledged ${n} problem${n === 1 ? '' : 's'}.`, 'success');
            t.clearSelection();
            setSelCount(0);
            load(false);
        } catch (e) {
            errorModal('Bulk Acknowledge Failed', errText(e));
        }
    };

    /* Mirrors bulkAck, with the danger styling single-resolve already uses:
       resolving closes the problem, and the monitor only re-opens it if the
       condition is still true on a later tick. The server skips ids that are
       already resolved or that this user cannot see, so the reported count
       can legitimately be lower than the selection. */
    const bulkResolve = async () => {
        const t = table();
        if (!t) return;
        const ids = t.selectedRows().map((r) => r.id);
        if (!ids.length) return;
        const comment = await confirmWithComment({
            title: 'Resolve Problems',
            message: `Manually resolve ${ids.length} selected problem${ids.length === 1 ? '' : 's'}?`
                + ' Each monitor will re-open its problem if the condition recurs.',
            okLabel: 'Resolve',
            danger: true,
        });
        if (comment == null) return;
        try {
            const res = await bulkResolveProblems(ids, comment);
            const n = res && typeof res.resolved === 'number' ? res.resolved : ids.length;
            toast(`Resolved ${n} problem${n === 1 ? '' : 's'}.`, 'success');
            t.clearSelection();
            setSelCount(0);
            load(false);
        } catch (e) {
            errorModal('Bulk Resolve Failed', errText(e));
        }
    };

    const rangeText = !data ? '' : (data.total === 0
        ? '0 of 0'
        : `${page * PAGE_SIZE + 1}–${page * PAGE_SIZE + data.items.length} of ${data.total}`);

    return (
        <div className="sn-problems">
            {detailId != null ? (
                <ProblemDetailPane id={detailId}
                    monitors={monitorsApi.data}
                    onBack={() => setDetailId(null)}
                    onChanged={() => load(true)} />
            ) : null}
            <div style={{ display: detailId != null ? 'none' : undefined }}>
                <FilterBar value={filters} onChange={changeFilters}
                    monitors={monitorsApi.data} channels={channelsApi.data || NO_CHANNELS} />
                <div className="flex items-center gap-2" style={{ marginBottom: 8 }}>
                    <span className="sn-hint">
                        {selCount > 0
                            ? `${selCount} selected`
                            : 'Click a row for details · Ctrl-click to select for bulk actions'}
                    </span>
                    <span style={{ flex: 1 }} />
                    {canAcknowledge() && selCount > 0 ? (
                        <>
                            <button className="btn btn-sm btn-primary" onClick={bulkAck}>
                                Acknowledge {selCount} selected
                            </button>
                            <button className="btn btn-sm btn-danger" onClick={bulkResolve}>
                                Resolve {selCount} selected
                            </button>
                        </>
                    ) : null}
                    <button className="btn btn-sm" onClick={() => load(false)} disabled={loading}>
                        {loading ? 'Refreshing…' : 'Refresh'}
                    </button>
                </div>
                {error ? (
                    <div className="panel mb-3"><div className="panel-body">
                        <span className="text-err">Could not load problems.</span>{' '}
                        <span className="text-text-dim">{error}</span>{' '}
                        <button className="btn btn-sm" onClick={() => load(false)}>Retry</button>
                    </div></div>
                ) : null}
                {!data && !error ? <div className="sn-empty">Loading problems…</div> : null}
                <div style={{ display: data ? undefined : 'none' }}
                    onClickCapture={onTableClickCapture}
                    onKeyDownCapture={onTableKeyDownCapture}>
                    {node}
                </div>
                {data ? (
                    <div className="flex items-center gap-2" style={{ marginTop: 8 }}>
                        <button className="btn btn-sm"
                            disabled={loading || page === 0}
                            onClick={() => setPage(page - 1)}>‹ Prev</button>
                        <span className="sn-hint">{rangeText}</span>
                        <button className="btn btn-sm"
                            disabled={loading || (page + 1) * PAGE_SIZE >= data.total}
                            onClick={() => setPage(page + 1)}>Next ›</button>
                    </div>
                ) : null}
            </div>
        </div>
    );
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

function ProblemDetailPane({ id, monitors, onBack, onChanged }) {
    const userNameOf = useUsernames();
    const [detail, setDetail] = React.useState(null);
    const [error, setError] = React.useState(null);
    const seqRef = React.useRef(0);
    // One 'info' toast per failure streak (host warn toasts are modals).
    const failStreakRef = React.useRef(false);

    const load = React.useCallback(async (background) => {
        const seq = ++seqRef.current;
        try {
            const d = await getProblem(id);
            if (seq !== seqRef.current) return;
            setDetail(d);
            setError(null);
            failStreakRef.current = false;
        } catch (e) {
            if (seq !== seqRef.current) return;
            if (background) {
                if (!failStreakRef.current) toast(`Problem refresh failed: ${errText(e)}`, 'info');
                failStreakRef.current = true;
            } else {
                setError(errText(e));
            }
        }
    }, [id]);

    React.useEffect(() => { setDetail(null); setError(null); load(false); }, [load]);
    React.useEffect(() => {
        const t = setInterval(() => { if (pollGate()) load(true); }, POLL_MS);
        return () => clearInterval(t);
    }, [load]);

    const ev = (detail && detail.event) || {};
    const open = ev.status === 'PROBLEM';
    const changed = () => { load(false); if (onChanged) onChanged(); };

    /* Both actions confirm and collect their comment in ONE dialog
       (confirmWithComment) — no inline input, no second popup. */
    const doAck = async () => {
        const comment = await confirmWithComment({
            title: 'Acknowledge Problem',
            message: `Acknowledge "${ev.message || `problem #${ev.id}`}"?`,
            okLabel: 'Acknowledge',
        });
        if (comment == null) return;
        try {
            await acknowledgeProblem(ev.id, comment);
            toast('Problem acknowledged.', 'success');
            changed();
        } catch (e) {
            errorModal('Acknowledge Failed', errText(e));
        }
    };

    const doResolve = async () => {
        const comment = await confirmWithComment({
            title: 'Resolve Problem',
            message: `Manually resolve "${ev.message || `problem #${ev.id}`}"? The monitor will re-open it if the condition recurs.`,
            okLabel: 'Resolve',
            danger: true,
        });
        if (comment == null) return;
        try {
            await resolveProblem(ev.id, comment);
            toast('Problem resolved.', 'success');
            changed();
        } catch (e) {
            errorModal('Resolve Failed', errText(e));
        }
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
                <button className="btn btn-sm" onClick={onBack}>← Back</button>
                {detail ? <SeverityChip severity={ev.severity} /> : null}
                <h2 title={ev.message || ''}
                    style={{ margin: 0, fontSize: 15, fontWeight: 600, minWidth: 0,
                        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {detail ? (ev.message || `Problem #${ev.id}`) : `Problem #${id}`}
                </h2>
                <span style={{ flex: 1 }} />
                {detail && canAcknowledge() && open && ev.acknowledgedBy == null ? (
                    <button className="btn btn-sm btn-primary" onClick={doAck}>Acknowledge</button>
                ) : null}
                {detail && canAcknowledge() && open ? (
                    <button className="btn btn-sm btn-danger" onClick={doResolve}>Resolve</button>
                ) : null}
            </div>

            {error && !detail ? (
                <div className="panel mb-3"><div className="panel-body">
                    <span className="text-err">Could not load problem.</span>{' '}
                    <span className="text-text-dim">{error}</span>{' '}
                    <button className="btn btn-sm" onClick={() => load(false)}>Retry</button>
                </div></div>
            ) : null}
            {!detail && !error ? <div className="sn-empty">Loading problem…</div> : null}

            {detail ? (
                <>
                    {ev.suppressed ? (
                        <div className="panel mb-3"><div className="panel-body">
                            <span className="tag amber">Suppressed</span>{' '}
                            <span className="text-text-dim">
                                Opened during a maintenance window, outside its alerting schedule,
                                or while its dependency monitor was alerting — no actions were
                                dispatched.
                            </span>
                        </div></div>
                    ) : null}

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
                                        <span className="text-text-faint mono"> (#{ev.monitorId})</span>
                                    </DetailRow>
                                    {runbookUrl ? (
                                        <DetailRow label="Runbook">
                                            <RunbookLink url={runbookUrl} />
                                        </DetailRow>
                                    ) : null}
                                    <DetailRow label="Channel">
                                        {detail.channelName || ev.channelId || '—'}
                                        {ev.channelId ? <span className="text-text-faint mono"> ({ev.channelId})</span> : null}
                                    </DetailRow>
                                    {ev.metadataId != null ? (
                                        <DetailRow label="Connector">
                                            {detail.connectorName || '—'}
                                            <span className="text-text-faint mono"> (metadata {ev.metadataId})</span>
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
                                    <DetailRow label="Event ID" mono>{ev.id}</DetailRow>
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <ChannelActivityPanel
                        title={`Channel activity${detail.channelName ? ` — ${detail.channelName}` : ''}`}
                        channelId={ev.channelId}
                        from={activityWindow ? activityWindow.from : null}
                        to={activityWindow ? activityWindow.to : null}
                        markers={activityMarkers}
                        hint={activityHint}
                        unavailable={activityUnavailable} />

                    <div className="panel mb-3">
                        <div className="panel-header">Last Value</div>
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
                                <pre className="mono" style={{ margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                                    {ev.detailsJson}
                                </pre>
                            ) : (
                                <div className="sn-hint">No captured value.</div>
                            )}
                        </div>
                    </div>

                    <div className="panel">
                        <div className="panel-header">Action Dispatches</div>
                        <div className="panel-body">
                            {dispatches.length === 0 ? (
                                <div className="sn-empty">No actions were dispatched for this event.</div>
                            ) : (
                                <table className="dt">
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
                                </table>
                            )}
                        </div>
                    </div>
                </>
            ) : null}
        </div>
    );
}
