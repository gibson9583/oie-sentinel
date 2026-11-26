// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Monitors page: DataTable list (name, type, scope, severity, enabled toggle,
// updated) with a page-local MonitorEditor sub-view (monitor-editor.jsx: kind
// and type, common fields, per-type configJson fieldsets matching the server's
// exact config shapes, a dry-run Test panel and confirmDialog-gated delete).
// All mutations are canManage()-gated; the servlet's MANAGE permission is the
// real enforcement.

import { platform } from '@oie/web-shell';
import { errorModal } from '@oie/web-ui';
import {
    listMonitors, setMonitorEnabled, getCoreChannels, getCoreChannelGroups, getCoreTags, errText,
} from '../api.js';
import {
    canManage, toast, useApi, useDataTable, severityChipNode, SEVERITY_META, MONITOR_TYPE_META,
    fmtTime, fmtAgo, monitorKind, LOG_LABEL,
} from '../ui.jsx';
import { readIntent, clearIntent } from '../host.jsx';
import { MonitorEditor } from './monitor-editor.jsx';

const React = platform.React;
const { h } = platform.ui;

/* ---- list helpers -------------------------------------------------------- */

function scopeLabel(monitor, channelNames, groupNames, tagNames) {
    // A Log monitor is stored with scope ALL but watches no channel.
    if (monitorKind(monitor.monitorType) === 'LOG') {
        return LOG_LABEL;
    }
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

/* ---- page ---------------------------------------------------------------- */

export function MonitorsPage() {
    const monitors = useApi(listMonitors, []);
    const channels = useApi(getCoreChannels, []);
    const groups = useApi(getCoreChannelGroups, []);
    const tags = useApi(getCoreTags, []);
    const [view, setView] = React.useState(null); // null | { mode: 'new' } | { mode: 'edit', monitor }
    const manage = canManage();

    /* The "New Sentinel Monitor" palette command lands here (see host.jsx).
       Consume it on mount — the shell has already switched to this tab — and
       clear it, so returning to Monitors later does not reopen an editor
       nobody asked for. */
    React.useEffect(() => {
        const intent = readIntent();
        if (!intent || intent.kind !== 'newMonitor') {
            return;
        }
        clearIntent();
        if (!canManage()) {
            // The palette gates this on doManageSentinel, but checkTask fails
            // open when no authorization plugin is installed. Say why rather
            // than opening an editor whose Save the servlet would refuse.
            toast('Creating a monitor requires the Manage Monitoring permission.', 'info');
            return;
        }
        setView({ mode: 'new' });
    }, []);

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
