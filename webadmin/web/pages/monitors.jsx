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
    listMonitors, createMonitor, updateMonitor, deleteMonitor,
    setMonitorEnabled, testMonitor, getCoreChannels, getCoreChannelGroups,
    getCoreTags, errText,
} from '../api.js';
import {
    canManage, toast, useApi, useDataTable, SeverityChip, SEVERITY_ORDER,
    SEVERITY_META, MONITOR_TYPE_ORDER, MONITOR_TYPE_META, ChannelPicker,
    ChannelGroupPicker, TagPicker, fmtTime, fmtAgo,
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
            break;
        }
        default:
            break;
    }
    return out;
}

/* ---- small form pieces --------------------------------------------------- */

function NumField({ label, value, onChange, hint, min, step }) {
    return (
        <div className="field">
            <label>{label}</label>
            <input type="number" value={value == null ? '' : value}
                min={min} step={step == null ? 1 : step}
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
                                    title="Not a recorded connection state — this entry never matches live connector state.">
                                    <input type="checkbox" checked
                                        onChange={(e) => toggle(s, e.target.checked)} />
                                    {stateLabel(s)} (transient)
                                </label>
                            ))}
                        </div>
                        <div className="hint">
                            Breach while any connector of the channel sits in a selected state
                            for at least the minimum duration. DISCONNECTED catches a downed
                            connection; CONNECTING catches one stuck reconnecting.
                        </div>
                    </div>
                    <NumField label="Minimum duration (seconds)" value={cfg.minDurationSeconds} min={0}
                        onChange={(v) => setCfg({ minDurationSeconds: v })}
                        hint="The state must persist at least this long before it counts as a breach." />
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
                text: 'No started channels matched this monitor\'s scope — nothing was evaluated.',
            }],
    });
}

/* ---- editor sub-view ----------------------------------------------------- */

function MonitorEditor({ monitor, monitors, channels, groups, tags, manage, onClose, onChanged }) {
    const isNew = !monitor;
    const [name, setName] = React.useState(monitor ? monitor.name : '');
    const [description, setDescription] = React.useState((monitor && monitor.description) || '');
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
        return {
            id: monitor ? monitor.id : undefined,
            name: trimmed,
            description: description.trim() || null,
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
        <div className="panel">
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
                                Evaluator ticks that must breach in a row before a problem opens (hysteresis).
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
