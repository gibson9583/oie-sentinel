// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// The monitor editor sub-view: kind first (Channel or Log), then type, the
// common fields, the type's settings, and save, test and delete. Moved out of
// monitors.jsx; every mutation is canManage()-gated there and the servlet is
// the real enforcement.

import { platform } from '@oie/web-shell';
import { errorModal, confirmDialog, detailModal } from '@oie/web-ui';
import { createMonitor, updateMonitor, deleteMonitor, testMonitor, errText } from '../api.js';
import {
    toast, SeverityChip, SEVERITY_ORDER, SEVERITY_META, MONITOR_TYPE_ORDER, MONITOR_TYPE_META,
    MONITOR_KINDS, monitorKind, ChannelPicker, ChannelGroupPicker, TagPicker,
} from '../ui.jsx';
import { seedConfigs, buildConfig } from './monitor-config.js';
import { ConfigFields } from './monitor-config-fields.jsx';
import { MonitorHistoryPanel } from './monitor-history.jsx';
import { LOG_PREVIEW_HINT, normalizeLogConfig } from './log-monitor-fields.jsx';

const React = platform.React;

const SCOPE_TYPES = [
    { key: 'ALL', label: 'All channels' },
    { key: 'GROUP', label: 'Channel group' },
    { key: 'TAG', label: 'Channel tag' },
    { key: 'CHANNEL', label: 'Single channel' },
];

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

/* ---- kind, type and scope ----------------------------------------------- */

/** Kind first, then a type of that kind. Log has one type, so it is preselected. */
function KindAndType({ kind, monitorType, locked, onKind, onType }) {
    const meta = MONITOR_KINDS.find((k) => k.key === kind) || MONITOR_KINDS[0];
    return (
        <>
            <div className="field">
                <label>Kind</label>
                <select value={kind} disabled={locked} onChange={(e) => onKind(e.target.value)}>
                    {MONITOR_KINDS.map((k) => <option key={k.key} value={k.key}>{k.label}</option>)}
                </select>
                <div className="hint">
                    {locked ? 'A saved monitor keeps its kind. Create a new monitor for another kind.' : meta.description}
                    {kind === 'LOG' ? ' Only users without channel restrictions can create or change Log monitors.' : ''}
                </div>
            </div>
            <div className="field">
                <label>Type</label>
                <select value={monitorType} onChange={(e) => onType(e.target.value)}>
                    {MONITOR_TYPE_ORDER.filter((t) => monitorKind(t) === kind).map((t) => (
                        <option key={t} value={t}>{MONITOR_TYPE_META[t].label}</option>
                    ))}
                </select>
                <div className="hint">{(MONITOR_TYPE_META[monitorType] || {}).description}</div>
            </div>
        </>
    );
}

/** A Log monitor watches no channel; its scope is always ALL. */
function ScopeField({ kind, scopeType, scopeId, setScopeType, setScopeId, channels, groups, tags }) {
    if (kind === 'LOG') {
        return (
            <div className="field span-2">
                <label>Scope</label>
                <div>The engine log on every cluster node</div>
            </div>
        );
    }
    return (
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
    );
}

/* ---- editor sub-view ----------------------------------------------------- */

export function MonitorEditor({ monitor, monitors, channels, groups, tags, manage, onClose, onChanged }) {
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
    /* The server refuses a saved monitor that changes kind, and suppression
       across kinds, so a saved monitor's kind is locked and "Suppressed by"
       lists only monitors of the current kind. Changing kind clears it. */
    const kind = monitorKind(monitorType);
    const isLog = kind === 'LOG';
    const changeKind = (next) => {
        if (next === kind) return;
        setSuppressedBy('');
        setMonitorType(MONITOR_TYPE_ORDER.find((t) => monitorKind(t) === next));
    };
    const others = (monitors || []).filter((m) => (!monitor || m.id !== monitor.id)
        && monitorKind(m.monitorType) === kind);
    const scope = isLog ? 'ALL' : scopeType;
    const logCfg = isLog ? normalizeLogConfig(cfg) : null;
    const notifies = !!logCfg && logCfg.outcome === 'NOTIFICATION';
    /* The history counts problems; for a saved monitor that sends notifications, say so. */
    const savedNotifies = !isNew && monitorKind(monitor.monitorType) === 'LOG'
        && normalizeLogConfig(seedConfigs(monitor)[monitor.monitorType] || {}).outcome === 'NOTIFICATION';

    /** Editor state -> Monitor wire object; throws Error on client-side issues. */
    const buildPayload = () => {
        const trimmed = name.trim();
        if (!trimmed) throw new Error('Name is required.');
        if (scope === 'CHANNEL' && !scopeId) throw new Error('Select a channel for the CHANNEL scope.');
        if (scope === 'GROUP' && !scopeId) throw new Error('Select a channel group for the GROUP scope.');
        if (scope === 'TAG' && !scopeId) throw new Error('Select a channel tag for the TAG scope.');
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
            scopeType: scope,
            scopeId: scope === 'ALL' ? null : scopeId,
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
                        <KindAndType kind={kind} monitorType={monitorType} locked={!isNew}
                            onKind={changeKind} onType={setMonitorType} />
                        <div className="field span-2">
                            <label>Name</label>
                            <input value={name} onChange={(e) => setName(e.target.value)}
                                placeholder="e.g. ADT feed inactivity" />
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
                        <ScopeField kind={kind} scopeType={scopeType} scopeId={scopeId}
                            setScopeType={setScopeType} setScopeId={setScopeId}
                            channels={channels} groups={groups} tags={tags} />
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
                                {!notifies ? 'Ticks the condition must hold before a problem opens.'
                                    : logCfg.condition === 'EACH' ? 'Not used when each occurrence is a notification.'
                                        : 'Ticks the condition must hold before a notification is sent.'}
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
                                {isLog
                                    ? 'While the selected monitor has an open problem, '
                                    : 'While the selected monitor has an open problem on the same channel, '}
                                {notifies
                                    ? 'this monitor\'s notifications are held back and never sent to actions. '
                                        + 'They still appear in the Notifications tab.'
                                    : 'this monitor\'s notifications are suppressed. Its open problems are '
                                        + 'still tracked and become eligible when the dependency clears.'}
                                {isLog ? ' Only Log monitors are listed.' : ''}
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
                {manage && !isLog ? (
                    <button className="btn" onClick={test} disabled={!!busy}
                        title="Dry-run this configuration against every started channel in scope">
                        {busy === 'test' ? 'Testing…' : 'Test'}
                    </button>
                ) : null}
                <button className="btn" onClick={onClose}
                    disabled={busy === 'save' || busy === 'delete'}>
                    {manage ? 'Cancel' : 'Close'}
                </button>
                {manage && isLog ? <span className="sn-hint">{LOG_PREVIEW_HINT}</span> : null}
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
        {!isNew ? <MonitorHistoryPanel monitorId={monitor.id} notifications={savedNotifies} /> : null}
        </>
    );
}
