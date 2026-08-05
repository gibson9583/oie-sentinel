// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Actions page: list of alert-delivery rules + the ActionEditor (common
// fields, ConditionBuilder, per-type EMAIL/CHANNEL/SNS config) and the
// "Send test" flow. Wire shape per Action.java: conditionJson/configJson are
// raw JSON strings the editor parses/serializes; the server redacts
// configJson.secretAccessKey to the 8-bullet marker and treats an unchanged
// marker on save as "keep the stored secret".

import { platform } from '@oie/web-shell';
import { errorModal, confirmDialog } from '@oie/web-ui';
import {
    listActions, createAction, updateAction, deleteAction, testAction, errText,
} from '../api.js';
import {
    canManage, toast, useApi, useDataTable, ConditionBuilder, ChannelPicker,
    CONDITION_FIELDS,
} from '../ui.jsx';

const React = platform.React;
const { h } = platform.ui;

/* Server-side redaction marker for configJson.secretAccessKey (8 bullets). */
const REDACTED = '••••••••';

const ACTION_TYPES = ['EMAIL', 'CHANNEL', 'SNS'];

const ACTION_TYPE_META = {
    EMAIL: {
        label: 'Email',
        description: 'Sends mail through the engine SMTP settings.',
        defaultConfig: { to: '', cc: '', subjectTemplate: '', includeDetails: true },
    },
    CHANNEL: {
        label: 'Channel',
        description: 'Routes the alert payload into an OIE channel (fan out to Slack, SMS, etc. yourself).',
        defaultConfig: { channelId: '' },
    },
    SNS: {
        label: 'SNS',
        description: 'Publishes the alert payload to an AWS SNS topic.',
        defaultConfig: {
            authType: 'DEFAULT', accessKeyId: '', secretAccessKey: '',
            region: '', topicArn: '', assumeRoleArn: '', assumeRoleExternalId: '',
        },
    },
};

const OPERATION_MODES = ['ON_PROBLEM', 'ON_RESOLVE', 'BOTH'];
const OPERATION_MODE_LABELS = {
    ON_PROBLEM: 'On problem',
    ON_RESOLVE: 'On resolve',
    BOTH: 'Problem + resolve',
};

/* ---- conditionJson / configJson parsing --------------------------------- */

function parseConditions(action) {
    if (!action || !action.conditionJson) return [];
    try {
        const v = JSON.parse(action.conditionJson);
        return Array.isArray(v) ? v : [];
    } catch (e) { return []; }
}

function parseConfig(action) {
    if (!action || !action.configJson) return {};
    try {
        const v = JSON.parse(action.configJson);
        return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
    } catch (e) { return {}; }
}

/** "Severity >= WARNING AND Channel = abc…" — one line, truncated. */
function conditionsSummary(action) {
    const rows = parseConditions(action);
    if (!rows.length) return 'Always';
    const parts = rows.map((r) => {
        const label = (CONDITION_FIELDS[r.field] || {}).label || r.field || '?';
        const value = Array.isArray(r.value)
            ? r.value.join(', ')
            : (r.value == null ? '' : String(r.value));
        return `${label} ${r.operator || '='} ${value}`;
    });
    let s = parts.join(' AND ');
    if (s.length > 90) s = `${s.slice(0, 87)}…`;
    return s;
}

/* ---- list table (columns/options captured once — useDataTable caveat) --- */

const ACTION_COLUMNS = [
    { key: 'name', label: 'Name', sortable: true, render: (r) => r.name || '' },
    {
        key: 'type', label: 'Type', sortable: true, width: '110px',
        render: (r) => (ACTION_TYPE_META[r.actionType] || {}).label || r.actionType || '',
        sortValue: (r) => r.actionType || '',
    },
    {
        key: 'mode', label: 'Fires', sortable: true, width: '140px',
        render: (r) => OPERATION_MODE_LABELS[r.operationMode] || r.operationMode || '',
        sortValue: (r) => r.operationMode || '',
    },
    {
        key: 'enabled', label: 'Enabled', sortable: true, width: '100px',
        // DataTable render() must return DOM, not JSX — build with h().
        render: (r) => (r.enabled ? h('span.tag.accent', 'Enabled') : h('span.tag', 'Disabled')),
        sortValue: (r) => (r.enabled ? 1 : 0),
    },
    { key: 'conditions', label: 'Conditions', render: (r) => conditionsSummary(r) },
];

/** Mounted only when there are rows; single click (select) opens the editor. */
function ActionsTable({ rows, onOpen }) {
    const onOpenRef = React.useRef(onOpen);
    onOpenRef.current = onOpen;
    const options = React.useRef({
        selectable: 'single',
        rowKey: (r) => String(r.id),
        emptyText: 'No actions yet.',
        onSelect: (selected) => { if (selected && selected[0]) onOpenRef.current(selected[0]); },
        onActivate: (row) => onOpenRef.current(row),
    }).current;
    const { node } = useDataTable(ACTION_COLUMNS, options, rows);
    return node;
}

/* ---- small form helpers -------------------------------------------------- */

/** Number input where empty = null (the wire's "unset" for Integer fields). */
function NullableNumberInput({ value, onChange, min, placeholder }) {
    return (
        <input type="number" min={min} placeholder={placeholder}
            value={value == null ? '' : value}
            onChange={(e) => {
                const raw = e.target.value;
                if (raw === '') { onChange(null); return; }
                const n = Math.round(Number(raw));
                onChange(isNaN(n) ? null : n);
            }} />
    );
}

/** Uppercase dim section divider inside the editor (no new global CSS). */
function SectionLabel({ children }) {
    return (
        <div style={{
            fontSize: '10.5px', textTransform: 'uppercase', letterSpacing: '.04em',
            color: 'var(--text-dim)', margin: '14px 0 8px',
            borderTop: '1px solid var(--line)', paddingTop: 10,
        }}>
            {children}
        </div>
    );
}

/* ---- template token chips ------------------------------------------------ */

/* ${var} tokens the EMAIL subject template substitutes — exactly the set
   EmailAlertSender.render() replaces. Anything else is left verbatim in the
   sent subject, so the chips offer only these. */
const EMAIL_SUBJECT_TOKENS = ['monitorName', 'channelName', 'severity', 'status', 'message'];

/* Chip drags carry a sentinel-specific MIME type (not text/plain) so tokens
   drop ONLY into inputs that opt in via TOKEN_TARGET_PROPS — other inputs
   ignore the unknown type, and the capture-phase blockers below cancel any
   stray drop while a chip is in flight. Same mechanics as the web client's
   connector-panel variable lists (file.tsx). */
const TOKEN_MIME = 'application/x-sentinel-token';

const isTokenDrag = (ev) => ev.dataTransfer && Array.from(ev.dataTransfer.types).includes(TOKEN_MIME);
const isTokenTarget = (t) => t instanceof HTMLElement && t.dataset.snTokenTarget === '1';
function tokenDocDragOver(ev) {
    if (isTokenDrag(ev) && !isTokenTarget(ev.target)) { ev.preventDefault(); ev.dataTransfer.dropEffect = 'none'; }
}
function tokenDocDrop(ev) {
    if (isTokenDrag(ev) && !isTokenTarget(ev.target)) { ev.preventDefault(); ev.stopPropagation(); }
}

/* Inserts the token at the input's cursor (replacing any selection) and fires
   a native input event so the controlled onChange updates state. The write
   must go through the prototype's value setter: React instruments the
   instance's `value` property to dedupe events, so a plain `input.value =`
   would update the tracker and the event would never reach onChange.
   `:disabled` (not `.disabled`) so the editor's read-only fieldset counts. */
function insertToken(input, token) {
    if (!input || input.matches(':disabled')) return;
    const s = input.selectionStart ?? input.value.length;
    const e = input.selectionEnd ?? input.value.length;
    const next = input.value.slice(0, s) + token + input.value.slice(e);
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input), 'value').set.call(input, next);
    const pos = s + token.length;
    input.focus();
    try { input.setSelectionRange(pos, pos); } catch (err) { /* detached input */ }
    input.dispatchEvent(new Event('input', { bubbles: true }));
}

/* Spread onto a template input to make it a permitted drop target; the drop
   inserts at the browser's drag caret (selectionStart tracks it). */
const TOKEN_TARGET_PROPS = {
    'data-sn-token-target': '1',
    onDragOver: (e) => {
        if (isTokenDrag(e) && !e.currentTarget.matches(':disabled')) {
            e.preventDefault();
            e.dataTransfer.dropEffect = 'copy';
        }
    },
    onDrop: (e) => {
        const token = e.dataTransfer.getData(TOKEN_MIME);
        if (!token) return;
        e.preventDefault();
        insertToken(e.currentTarget, token);
    },
};

/** Draggable / click-to-insert ${token} chips replacing a "Placeholders: …"
    hint line. `inputRef` is the template field the chips serve: click inserts
    at its cursor, drag-and-drop wherever the token is dropped. `note` renders
    as trailing hint text (e.g. what an empty template means). */
function TokenChips({ tokens, inputRef, note }) {
    return (
        <div className="hint" style={{ display: 'flex', flexWrap: 'wrap', gap: 4, alignItems: 'center' }}>
            {tokens.map((t) => {
                const token = '${' + t + '}';
                return (
                    <button key={t} type="button" draggable className="tag mono"
                        style={{ cursor: 'grab' }}
                        title="Drag into the template, or click to insert at the cursor"
                        onClick={() => insertToken(inputRef.current, token)}
                        onDragStart={(e) => {
                            // Custom MIME only (NOT text/plain), so the template
                            // field's drop handler is the sole acceptor; the
                            // document blockers cancel drops anywhere else.
                            e.dataTransfer.clearData();
                            e.dataTransfer.setData(TOKEN_MIME, token);
                            e.dataTransfer.effectAllowed = 'copy';
                            document.addEventListener('dragover', tokenDocDragOver, true);
                            document.addEventListener('drop', tokenDocDrop, true);
                        }}
                        onDragEnd={() => {
                            document.removeEventListener('dragover', tokenDocDragOver, true);
                            document.removeEventListener('drop', tokenDocDrop, true);
                        }}>
                        {token}
                    </button>
                );
            })}
            {note ? <span>{note}</span> : null}
        </div>
    );
}

/* ---- per-type delivery config fieldsets --------------------------------- */

function EmailConfigFields({ config, setConfig }) {
    // Chips target the subject input directly (EMAIL's only template field),
    // so no last-focused tracking is needed.
    const subjectRef = React.useRef(null);
    return (
        <div className="form-grid">
            <div className="field">
                <label>To</label>
                <input value={config.to || ''} placeholder="ops@example.org, oncall@example.org"
                    onChange={(e) => setConfig({ to: e.target.value })} />
                <div className="hint">Comma-separated recipients. Required.</div>
            </div>
            <div className="field">
                <label>Cc</label>
                <input value={config.cc || ''}
                    onChange={(e) => setConfig({ cc: e.target.value })} />
                <div className="hint">Optional, comma-separated.</div>
            </div>
            <div className="field span-2">
                <label>Subject template</label>
                <input ref={subjectRef} value={config.subjectTemplate || ''}
                    placeholder={'[Sentinel][${severity}] ${monitorName} — ${channelName}: ${status}'}
                    onChange={(e) => setConfig({ subjectTemplate: e.target.value })}
                    {...TOKEN_TARGET_PROPS} />
                <TokenChips tokens={EMAIL_SUBJECT_TOKENS} inputRef={subjectRef}
                    note="Empty = default subject." />
            </div>
            <label className="check">
                <input type="checkbox" checked={!!config.includeDetails}
                    onChange={(e) => setConfig({ includeDetails: e.target.checked })} />
                Include value details in the body
            </label>
        </div>
    );
}

function ChannelConfigFields({ config, setConfig }) {
    return (
        <div className="field">
            <label>Target channel</label>
            <ChannelPicker value={config.channelId || ''} emptyLabel="Select a channel…"
                onChange={(id) => setConfig({ channelId: id })} />
            <div className="hint">
                The alert payload is routed to this channel as a raw JSON message
                (sourceMap carries sentinelSeverity, sentinelChannelName, etc.).
            </div>
        </div>
    );
}

function SnsConfigFields({ config, setConfig }) {
    const authType = config.authType || 'DEFAULT';
    return (
        <div className="form-grid">
            <div className="field">
                <label>Authentication</label>
                <select value={authType} onChange={(e) => setConfig({ authType: e.target.value })}>
                    <option value="DEFAULT">Default provider chain</option>
                    <option value="STATIC">Static access key</option>
                    <option value="ROLE">Assume role</option>
                </select>
            </div>
            <div className="field">
                <label>Region</label>
                <input value={config.region || ''} placeholder="us-east-1"
                    onChange={(e) => setConfig({ region: e.target.value })} />
            </div>
            <div className="field span-2">
                <label>Topic ARN</label>
                <input value={config.topicArn || ''} placeholder="arn:aws:sns:us-east-1:123456789012:sentinel-alerts"
                    onChange={(e) => setConfig({ topicArn: e.target.value })} />
            </div>
            {authType === 'STATIC' ? (
                <>
                    <div className="field">
                        <label>Access key ID</label>
                        <input value={config.accessKeyId || ''} autoComplete="off"
                            onChange={(e) => setConfig({ accessKeyId: e.target.value })} />
                    </div>
                    <div className="field">
                        <label>Secret access key</label>
                        <input type="password" value={config.secretAccessKey || ''} autoComplete="new-password"
                            onChange={(e) => setConfig({ secretAccessKey: e.target.value })} />
                        <div className="hint">
                            Stored encrypted; shown as {REDACTED} once saved. Leave the marker
                            unchanged to keep the stored secret, or type a new one to replace it.
                        </div>
                    </div>
                </>
            ) : null}
            {authType === 'ROLE' ? (
                <>
                    <div className="field">
                        <label>Assume role ARN</label>
                        <input value={config.assumeRoleArn || ''} placeholder="arn:aws:iam::123456789012:role/sentinel-sns"
                            onChange={(e) => setConfig({ assumeRoleArn: e.target.value })} />
                    </div>
                    <div className="field">
                        <label>External ID</label>
                        <input value={config.assumeRoleExternalId || ''}
                            onChange={(e) => setConfig({ assumeRoleExternalId: e.target.value })} />
                        <div className="hint">Optional sts:ExternalId condition value.</div>
                    </div>
                </>
            ) : null}
        </div>
    );
}

/* Serialize the edited config to the exact per-type configJson shape; empty
   optionals are omitted (server treats missing as null). The secret is sent
   verbatim — an untouched redaction marker means "keep the stored secret". */
function pruneConfig(actionType, c) {
    const t = (s) => (s == null ? '' : String(s)).trim();
    if (actionType === 'EMAIL') {
        const out = { to: t(c.to), includeDetails: !!c.includeDetails };
        if (t(c.cc)) out.cc = t(c.cc);
        if (t(c.subjectTemplate)) out.subjectTemplate = t(c.subjectTemplate);
        return out;
    }
    if (actionType === 'CHANNEL') {
        return { channelId: t(c.channelId) };
    }
    const out = { authType: c.authType || 'DEFAULT', region: t(c.region), topicArn: t(c.topicArn) };
    if (out.authType === 'STATIC') {
        out.accessKeyId = t(c.accessKeyId);
        out.secretAccessKey = c.secretAccessKey == null ? '' : String(c.secretAccessKey);
    }
    if (out.authType === 'ROLE') {
        out.assumeRoleArn = t(c.assumeRoleArn);
        if (t(c.assumeRoleExternalId)) out.assumeRoleExternalId = t(c.assumeRoleExternalId);
    }
    return out;
}

/* ---- editor -------------------------------------------------------------- */

function ActionEditor({ action, manage, onClose, onSaved }) {
    const existing = action && action.id != null;
    const [name, setName] = React.useState(action ? action.name || '' : '');
    const [description, setDescription] = React.useState(action ? action.description || '' : '');
    const [enabled, setEnabled] = React.useState(action ? !!action.enabled : true);
    const [actionType, setActionType] = React.useState(
        action && action.actionType ? action.actionType : 'EMAIL');
    const [operationMode, setOperationMode] = React.useState(
        action && action.operationMode ? action.operationMode : 'ON_PROBLEM');
    const [repeatIntervalSeconds, setRepeatIntervalSeconds] = React.useState(
        action && action.repeatIntervalSeconds != null ? action.repeatIntervalSeconds : null);
    const [maxRepeats, setMaxRepeats] = React.useState(
        action && action.maxRepeats != null ? action.maxRepeats : null);
    const [conditions, setConditions] = React.useState(() => parseConditions(action));
    // Per-type config drafts so switching Type and back keeps entered values.
    const [configs, setConfigs] = React.useState(() => {
        const base = {};
        for (const t of ACTION_TYPES) base[t] = { ...ACTION_TYPE_META[t].defaultConfig };
        if (action && base[action.actionType]) {
            base[action.actionType] = { ...base[action.actionType], ...parseConfig(action) };
        }
        return base;
    });
    const [busy, setBusy] = React.useState(false);   // false | 'save' | 'test' | 'delete'

    const config = configs[actionType] || {};
    const setConfig = (patch) => setConfigs((all) => ({
        ...all, [actionType]: { ...all[actionType], ...patch },
    }));

    const buildAction = () => ({
        id: existing ? action.id : null,
        name: name.trim(),
        description: description.trim() ? description.trim() : null,
        enabled,
        actionType,
        operationMode,
        repeatIntervalSeconds,
        maxRepeats,
        conditionJson: conditions.length ? JSON.stringify(conditions) : null,
        configJson: JSON.stringify(pruneConfig(actionType, config)),
    });

    const save = async () => {
        setBusy('save');
        try {
            const body = buildAction();
            if (existing) await updateAction(action.id, body);
            else await createAction(body);
            toast('Action saved.', 'success');
            onSaved();
        } catch (e) {
            errorModal('Save Failed', errText(e));
        } finally {
            setBusy(false);
        }
    };

    const remove = async () => {
        const ok = await confirmDialog('Delete Action',
            `Delete action "${action.name}"? It will never fire again; past dispatch history is kept.`,
            { danger: true, okLabel: 'Delete' });
        if (!ok) return;
        setBusy('delete');
        try {
            await deleteAction(action.id);
            toast('Action deleted.', 'success');
            onSaved();
        } catch (e) {
            errorModal('Delete Failed', errText(e));
        } finally {
            setBusy(false);
        }
    };

    const sendTest = async () => {
        setBusy('test');
        try {
            const result = await testAction(action.id);
            if (result && result.success) toast(result.message || 'Test alert sent.', 'success');
            else errorModal('Test Failed', (result && result.message) || 'The test send did not succeed.');
        } catch (e) {
            errorModal('Test Failed', errText(e));
        } finally {
            setBusy(false);
        }
    };

    const typeMeta = ACTION_TYPE_META[actionType] || { label: actionType, description: '' };

    return (
        <div className="panel">
            <div className="panel-header">
                {existing ? `Edit action — ${action.name}` : 'New action'}
                <div className="panel-tools">
                    <button type="button" className="btn btn-sm" onClick={onClose}
                        disabled={busy === 'save'}>Back</button>
                </div>
            </div>
            <div className="panel-body">
                {/* One disabled attribute read-onlys the whole form for viewers. */}
                <fieldset disabled={!manage || busy === 'save'}
                    style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
                    <div className="form-grid">
                        <div className="field">
                            <label>Name</label>
                            <input value={name} onChange={(e) => setName(e.target.value)} />
                        </div>
                        <div className="field">
                            <label>Type</label>
                            <select value={actionType} onChange={(e) => setActionType(e.target.value)}>
                                {ACTION_TYPES.map((t) => (
                                    <option key={t} value={t}>{ACTION_TYPE_META[t].label}</option>
                                ))}
                            </select>
                            <div className="hint">{typeMeta.description}</div>
                        </div>
                        <div className="field span-2">
                            <label>Description</label>
                            <textarea rows={2} value={description}
                                onChange={(e) => setDescription(e.target.value)} />
                        </div>
                        <div className="field">
                            <label>Fires</label>
                            <select value={operationMode} onChange={(e) => setOperationMode(e.target.value)}>
                                {OPERATION_MODES.map((m) => (
                                    <option key={m} value={m}>{OPERATION_MODE_LABELS[m]}</option>
                                ))}
                            </select>
                        </div>
                        <div className="field">
                            <label>Status</label>
                            <label className="check">
                                <input type="checkbox" checked={enabled}
                                    onChange={(e) => setEnabled(e.target.checked)} />
                                Enabled
                            </label>
                        </div>
                        <div className="field">
                            <label>Repeat interval (seconds)</label>
                            <NullableNumberInput value={repeatIntervalSeconds} min={60}
                                placeholder="fire once" onChange={setRepeatIntervalSeconds} />
                            <div className="hint">Empty = fire once per problem. Minimum 60 when set.</div>
                        </div>
                        <div className="field">
                            <label>Max repeats</label>
                            <NullableNumberInput value={maxRepeats} min={0}
                                placeholder="unlimited" onChange={setMaxRepeats} />
                            <div className="hint">Empty = repeat until the problem resolves.</div>
                        </div>
                    </div>

                    <SectionLabel>Conditions</SectionLabel>
                    <ConditionBuilder value={conditions} onChange={setConditions} />
                    <div className="sn-hint">
                        All rows must match (AND). With no rows the action fires for every event
                        its mode covers. To tie this action to specific monitors, add a Monitor
                        condition and multi-select them.
                    </div>

                    <SectionLabel>Delivery — {typeMeta.label}</SectionLabel>
                    {actionType === 'EMAIL' ? <EmailConfigFields config={config} setConfig={setConfig} /> : null}
                    {actionType === 'CHANNEL' ? <ChannelConfigFields config={config} setConfig={setConfig} /> : null}
                    {actionType === 'SNS' ? <SnsConfigFields config={config} setConfig={setConfig} /> : null}
                </fieldset>

                <div className="form-row" style={{ marginTop: 14, gap: 8, alignItems: 'center' }}>
                    {manage ? (
                        <button type="button" className="btn btn-primary" onClick={save} disabled={!!busy}>
                            {busy === 'save' ? 'Saving…' : 'Save'}
                        </button>
                    ) : null}
                    <button type="button" className="btn" onClick={onClose} disabled={busy === 'save'}>
                        {manage ? 'Cancel' : 'Close'}
                    </button>
                    {manage && existing ? (
                        <button type="button" className="btn" onClick={sendTest} disabled={!!busy}
                            title="Sends a synthetic test alert using this action's last saved configuration.">
                            {busy === 'test' ? 'Sending…' : 'Send test'}
                        </button>
                    ) : null}
                    {manage && !existing ? (
                        <span className="sn-hint">Save the action first to send a test.</span>
                    ) : null}
                    {manage && existing ? (
                        <button type="button" className="btn btn-danger" onClick={remove}
                            disabled={!!busy} style={{ marginLeft: 'auto' }}>
                            {busy === 'delete' ? 'Deleting…' : 'Delete'}
                        </button>
                    ) : null}
                </div>
            </div>
        </div>
    );
}

/* ---- page ---------------------------------------------------------------- */

export function ActionsPage() {
    const manage = canManage();
    const { data, error, loading, reload } = useApi(listActions, []);
    const [editor, setEditor] = React.useState(null);   // null | { action: Action|null }

    if (editor) {
        return (
            <ActionEditor action={editor.action} manage={manage}
                onClose={() => setEditor(null)}
                onSaved={() => { setEditor(null); reload(); }} />
        );
    }

    const rows = Array.isArray(data) ? data : [];
    const showTable = !error && rows.length > 0;

    return (
        <div className="panel">
            <div className="panel-header">
                Actions
                <div className="panel-tools">
                    <button type="button" className="btn btn-sm" onClick={reload} disabled={loading}>
                        {loading ? 'Loading…' : 'Refresh'}
                    </button>
                    {manage ? (
                        <button type="button" className="btn btn-sm btn-primary"
                            onClick={() => setEditor({ action: null })}>
                            New action
                        </button>
                    ) : null}
                </div>
            </div>
            <div className={showTable ? 'panel-body flush' : 'panel-body'}>
                {error ? (
                    <div>
                        <span className="text-err">Could not load actions.</span>{' '}
                        <span className="sn-hint">{error}</span>{' '}
                        <button type="button" className="btn btn-sm" onClick={reload}>Retry</button>
                    </div>
                ) : !data && loading ? (
                    <div className="sn-hint">Loading actions…</div>
                ) : rows.length === 0 ? (
                    <div className="sn-empty">
                        <div>No actions yet.</div>
                        {manage ? (
                            <button type="button" className="btn btn-primary" style={{ marginTop: 10 }}
                                onClick={() => setEditor({ action: null })}>
                                New action
                            </button>
                        ) : (
                            <div className="sn-hint" style={{ marginTop: 6 }}>
                                Alert delivery rules appear here once someone creates them.
                            </div>
                        )}
                    </div>
                ) : (
                    <ActionsTable rows={rows} onOpen={(r) => setEditor({ action: r })} />
                )}
            </div>
        </div>
    );
}
