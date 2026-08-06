// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Actions page: list of alert-delivery rules + the ActionEditor (common
// fields, ConditionBuilder, per-type EMAIL/CHANNEL/SNS/WEBHOOK config) and
// the "Send test" flow. Wire shape per Action.java: conditionJson/configJson
// are raw JSON strings the editor parses/serializes; the server redacts every
// sensitive config value — the SNS credential fields and any webhook header
// whose name looks like a credential — to the 8-bullet marker, and treats an
// unchanged marker on save as "keep the stored secret".

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

/* Server-side redaction marker for stored secrets (8 bullets). */
const REDACTED = '••••••••';

const ACTION_TYPES = ['EMAIL', 'CHANNEL', 'SNS', 'WEBHOOK'];

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
    WEBHOOK: {
        label: 'Webhook',
        description: 'Posts the alert to an HTTPS endpoint (Slack, Teams, PagerDuty, Alertmanager…).',
        // headers is an ARRAY of {name, value} rows in the editor draft and a
        // JSON object on the wire — see normalizeConfig/pruneConfig. Rows keep
        // the operator's order and let a name be edited a keystroke at a time,
        // which an object keyed by header name cannot do.
        defaultConfig: {
            url: '', method: 'POST', headers: [], bodyTemplate: '',
            timeoutSeconds: null, allowInsecure: false, allowPrivateNetwork: false,
        },
    },
};

const WEBHOOK_METHODS = ['POST', 'PUT', 'PATCH'];

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

/* Wire shape -> editor draft shape. Only WEBHOOK differs: its headers arrive
   as a JSON object and the editor needs ordered, individually-editable rows.
   Object key order is insertion order in every engine the web client runs on,
   so the round trip through pruneConfig preserves what the operator typed. */
function normalizeConfig(actionType, c) {
    if (actionType !== 'WEBHOOK') return c;
    const headers = c.headers && typeof c.headers === 'object' && !Array.isArray(c.headers)
        ? Object.keys(c.headers).map((name) => ({ name, value: String(c.headers[name] ?? '') }))
        : [];
    return { ...c, headers };
}

/* Mirrors WebhookAlertSender.SECRET_HEADER_NAME. Cosmetic only — it decides
   whether the value renders as a password field with the "kept unless you
   retype it" hint. The server is authoritative about what actually gets
   encrypted, so a disagreement here shows the wrong widget, never the wrong
   storage. */
const SECRET_HEADER_RE =
    /^(authorization|proxy-authorization|cookie)$|token|secret|password|passwd|signature|credential|api[-_]?key/i;

const isSecretHeader = (name) => SECRET_HEADER_RE.test((name || '').trim());

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

/* The same set plus the two WebhookAlertSender.render() adds: ${valueJson}
   (the evaluator's measurement evidence, substituted as raw JSON so it embeds
   unquoted) and ${alertEventId} (the correlation key a receiving system
   dedupes repeat sends on). Header values accept the same tokens. */
const WEBHOOK_BODY_TOKENS = [...EMAIL_SUBJECT_TOKENS, 'valueJson', 'alertEventId'];

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

/* Header name/value rows. Kept out of WebhookConfigFields so the row logic
   reads on its own; `rows` is the draft array, emit() replaces it wholesale
   (React state, no in-place mutation). Values for credential-looking names
   render as password fields carrying the same "unchanged marker = keep the
   stored secret" contract as the SNS secret access key. */
function HeaderRows({ rows, onChange }) {
    const list = Array.isArray(rows) ? rows : [];
    const setRow = (i, patch) => onChange(list.map((r, j) => (j === i ? { ...r, ...patch } : r)));
    return (
        <div className="sn-conds">
            {list.map((row, i) => {
                const secret = isSecretHeader(row.name);
                return (
                    <div key={i} className="sn-cond-row">
                        <input value={row.name || ''} placeholder="Header name"
                            autoComplete="off" style={{ flex: '0 0 220px' }}
                            onChange={(e) => setRow(i, { name: e.target.value })} />
                        <input value={row.value || ''} placeholder={secret ? 'Bearer …' : 'Value'}
                            type={secret ? 'password' : 'text'}
                            autoComplete={secret ? 'new-password' : 'off'}
                            style={{ flex: '1 1 240px' }}
                            onChange={(e) => setRow(i, { value: e.target.value })} />
                        <button type="button" className="btn btn-sm" title="Remove header"
                            onClick={() => onChange(list.filter((_, j) => j !== i))}>✕</button>
                    </div>
                );
            })}
            <button type="button" className="btn btn-sm"
                onClick={() => onChange([...list, { name: '', value: '' }])}>
                + Add header
            </button>
        </div>
    );
}

function WebhookConfigFields({ config, setConfig }) {
    const bodyRef = React.useRef(null);
    const insecure = !!config.allowInsecure;
    const privateNet = !!config.allowPrivateNetwork;
    return (
        <div className="form-grid">
            <div className="field span-2">
                <label>URL</label>
                <input value={config.url || ''} placeholder="https://hooks.example.org/services/T000/B000/xxxx"
                    autoComplete="off"
                    onChange={(e) => setConfig({ url: e.target.value })} />
                <div className="hint">
                    Required. The path is never echoed back in a failure message — for many
                    providers it is the credential.
                </div>
            </div>
            <div className="field">
                <label>Method</label>
                <select value={config.method || 'POST'}
                    onChange={(e) => setConfig({ method: e.target.value })}>
                    {WEBHOOK_METHODS.map((m) => <option key={m} value={m}>{m}</option>)}
                </select>
            </div>
            <div className="field">
                <label>Timeout (seconds)</label>
                <NullableNumberInput value={config.timeoutSeconds} min={1}
                    placeholder="10" onChange={(v) => setConfig({ timeoutSeconds: v })} />
                <div className="hint">Empty = 10. Maximum 30; the whole exchange is bounded by it.</div>
            </div>

            <div className="field span-2">
                <label>Headers</label>
                <HeaderRows rows={config.headers} onChange={(rows) => setConfig({ headers: rows })} />
                <div className="hint">
                    Values accept the same {'${…}'} tokens as the body. Authorization, Cookie and
                    any name containing token/secret/password/signature/credential/api-key are
                    stored encrypted and shown as {REDACTED} once saved — leave the marker
                    unchanged to keep the stored value, or type a new one to replace it.
                    Content-Length, Host, Connection, Expect and Upgrade are set by the HTTP
                    client and cannot be overridden.
                </div>
            </div>

            <div className="field span-2">
                <label>Body template</label>
                <textarea ref={bodyRef} rows={5} className="mono"
                    value={config.bodyTemplate || ''}
                    placeholder={'{"text": "[${severity}] ${monitorName} — ${channelName}: ${message}"}'}
                    onChange={(e) => setConfig({ bodyTemplate: e.target.value })}
                    {...TOKEN_TARGET_PROPS} />
                <TokenChips tokens={WEBHOOK_BODY_TOKENS} inputRef={bodyRef}
                    note={'Empty = the full alert payload as JSON. Values are JSON-escaped for a '
                        + 'JSON body; ${valueJson} is inserted raw so it embeds unquoted.'} />
            </div>

            <div className="field span-2">
                <label>Network</label>
                <label className="check">
                    <input type="checkbox" checked={insecure}
                        onChange={(e) => setConfig({ allowInsecure: e.target.checked })} />
                    Allow plaintext http
                </label>
                <label className="check">
                    <input type="checkbox" checked={privateNet}
                        onChange={(e) => setConfig({ allowPrivateNetwork: e.target.checked })} />
                    Allow private network targets (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7)
                </label>
                <div className="hint">
                    {insecure
                        ? 'Plaintext sends the alert body and any Authorization header unencrypted. '
                        : 'https is required unless you opt in. '}
                    {privateNet
                        ? 'Private targets are allowed for this action: it can reach anything on the '
                            + 'engine’s LAN, including unauthenticated internal admin APIs. '
                        : 'Private and internal targets are blocked. '}
                    Link-local (169.254.0.0/16 — the cloud instance metadata service), loopback,
                    wildcard and multicast addresses are always blocked and no setting permits them.
                    The check runs against every address the host resolves to, at send time, and
                    redirects are never followed.
                </div>
            </div>
        </div>
    );
}

/* Serialize the edited config to the exact per-type configJson shape; empty
   optionals are omitted (server treats missing as null). Secrets are sent
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
    if (actionType === 'WEBHOOK') {
        const out = {
            url: t(c.url),
            method: c.method || 'POST',
            // Always explicit, never omitted: these two are the security
            // opt-ins, and "absent" reading as false is a property of the
            // server's defaults that this editor should not lean on.
            allowInsecure: !!c.allowInsecure,
            allowPrivateNetwork: !!c.allowPrivateNetwork,
        };
        // Rows back to the wire object. Name trimmed (a stray space makes an
        // invalid header token); value NOT trimmed or coerced, because it may
        // be the redaction marker and must round-trip byte for byte.
        const headers = {};
        (Array.isArray(c.headers) ? c.headers : []).forEach((row) => {
            const name = t(row && row.name);
            const value = row && row.value != null ? String(row.value) : '';
            if (name && value) headers[name] = value;
        });
        if (Object.keys(headers).length) out.headers = headers;
        if (t(c.bodyTemplate)) out.bodyTemplate = t(c.bodyTemplate);
        if (c.timeoutSeconds != null) out.timeoutSeconds = c.timeoutSeconds;
        return out;
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

function ActionEditor({ action, actions, manage, onClose, onSaved }) {
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
    const [maxNotificationsPerWindow, setMaxNotificationsPerWindow] = React.useState(
        action && action.maxNotificationsPerWindow != null ? action.maxNotificationsPerWindow : null);
    const [rollupWindowSeconds, setRollupWindowSeconds] = React.useState(
        action && action.rollupWindowSeconds != null ? action.rollupWindowSeconds : null);
    const [escalateAfterSeconds, setEscalateAfterSeconds] = React.useState(
        action && action.escalateAfterSeconds != null ? action.escalateAfterSeconds : null);
    const [escalateToActionId, setEscalateToActionId] = React.useState(
        action && action.escalateToActionId != null ? action.escalateToActionId : null);
    const [conditions, setConditions] = React.useState(() => parseConditions(action));
    // Per-type config drafts so switching Type and back keeps entered values.
    const [configs, setConfigs] = React.useState(() => {
        const base = {};
        for (const t of ACTION_TYPES) base[t] = { ...ACTION_TYPE_META[t].defaultConfig };
        if (action && base[action.actionType]) {
            base[action.actionType] = normalizeConfig(action.actionType,
                { ...base[action.actionType], ...parseConfig(action) });
        }
        return base;
    });
    const [busy, setBusy] = React.useState(false);   // false | 'save' | 'test' | 'delete'

    const config = configs[actionType] || {};
    const setConfig = (patch) => setConfigs((all) => ({
        ...all, [actionType]: { ...all[actionType], ...patch },
    }));

    // Escalation targets: every OTHER action. Self is excluded because the
    // dispatcher refuses a self-escalation anyway (it would be an immediate
    // one-link cycle), so offering it would only let someone configure a
    // no-op. A stored target that is no longer in the list — the id carries
    // no foreign key, so the action it points at can simply have been
    // deleted — is re-added below as a flagged option rather than silently
    // dropped on the next save.
    const escalationTargets = (Array.isArray(actions) ? actions : [])
        .filter((a) => a && a.id != null && !(existing && a.id === action.id))
        .slice()
        .sort((a, b) => String(a.name || '').localeCompare(String(b.name || '')));
    const targetMissing = escalateToActionId != null
        && !escalationTargets.some((a) => a.id === escalateToActionId);

    // Storm control needs both halves: a ceiling with no window has nothing
    // to count against, and a window with no ceiling never trips. Same for
    // escalation — a delay with no target, or a target with no delay, does
    // nothing. Flagged rather than blocked: the server stores each column
    // independently and treats a half-configured pair as "off".
    const ceilingPartial = (maxNotificationsPerWindow == null) !== (rollupWindowSeconds == null);
    const escalationPartial = (escalateAfterSeconds == null) !== (escalateToActionId == null);

    const buildAction = () => ({
        id: existing ? action.id : null,
        name: name.trim(),
        description: description.trim() ? description.trim() : null,
        enabled,
        actionType,
        operationMode,
        repeatIntervalSeconds,
        maxRepeats,
        maxNotificationsPerWindow,
        rollupWindowSeconds,
        escalateAfterSeconds,
        escalateToActionId,
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
                        <div className="field">
                            <label>Notification ceiling</label>
                            <NullableNumberInput value={maxNotificationsPerWindow} min={1}
                                placeholder="no ceiling" onChange={setMaxNotificationsPerWindow} />
                            <div className="hint">
                                Individual notifications this action may send per rollup window,
                                across all problems. Empty = no ceiling.
                            </div>
                        </div>
                        <div className="field">
                            <label>Rollup window (seconds)</label>
                            <NullableNumberInput value={rollupWindowSeconds} min={10}
                                placeholder="no ceiling" onChange={setRollupWindowSeconds} />
                            <div className="hint">
                                {ceilingPartial ? (
                                    <span className="text-err">
                                        Set both the ceiling and the window — one without the other
                                        does nothing.{' '}
                                    </span>
                                ) : null}
                                The period the ceiling counts over and the rollup summarizes.
                            </div>
                        </div>
                        <div className="field">
                            <label>Escalate after (seconds)</label>
                            <NullableNumberInput value={escalateAfterSeconds} min={60}
                                placeholder="no escalation" onChange={setEscalateAfterSeconds} />
                            <div className="hint">
                                How long a problem may stay open before a different action is
                                notified. Empty = never escalate.
                            </div>
                        </div>
                        <div className="field">
                            <label>Escalate to</label>
                            <select value={escalateToActionId == null ? '' : String(escalateToActionId)}
                                onChange={(e) => setEscalateToActionId(
                                    e.target.value === '' ? null : Number(e.target.value))}>
                                <option value="">No escalation target</option>
                                {escalationTargets.map((a) => (
                                    <option key={a.id} value={String(a.id)}>
                                        {a.name || `#${a.id}`}{a.enabled ? '' : ' (disabled)'}
                                    </option>
                                ))}
                                {targetMissing ? (
                                    <option value={String(escalateToActionId)}>
                                        {`#${escalateToActionId} — action no longer exists`}
                                    </option>
                                ) : null}
                            </select>
                            <div className="hint">
                                {escalationPartial ? (
                                    <span className="text-err">
                                        Set both the delay and the target — one without the other
                                        does nothing.{' '}
                                    </span>
                                ) : null}
                                {targetMissing ? (
                                    <span className="text-err">
                                        The selected action has been deleted; escalation is skipped
                                        with a log warning until you pick another.{' '}
                                    </span>
                                ) : null}
                                A disabled target never fires. The target sends under its own
                                conditions, repeat interval and ceiling.
                            </div>
                        </div>
                        <div className="field span-2">
                            <div className="hint">
                                <b>Order these apply in.</b> For each notification: suppression
                                first (maintenance window, dependency, acknowledgement, or a
                                trigger detected as flapping) — a suppressed alert is not counted
                                and not escalated. Then the ceiling: under it the notification is
                                sent individually; over it the individual sends stop and one rollup
                                naming the affected channels goes out per window. Then escalation,
                                independently of what the ceiling decided — a problem still open
                                past the delay reaches the target even if this action has gone
                                quiet, which is usually exactly what you want. Repeat and escalation
                                are separate: repeat re-notifies <i>this</i> action, escalation
                                hands the problem to <i>another</i> one, and if the target also has
                                an escalation the delays add up along the chain. Acknowledging a
                                problem stops both.
                            </div>
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
                    {actionType === 'WEBHOOK' ? <WebhookConfigFields config={config} setConfig={setConfig} /> : null}
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

    const rows = Array.isArray(data) ? data : [];

    if (editor) {
        // The loaded list doubles as the escalation-target picker's options,
        // so it is computed before this branch rather than after it.
        return (
            <ActionEditor action={editor.action} actions={rows} manage={manage}
                onClose={() => setEditor(null)}
                onSaved={() => { setEditor(null); reload(); }} />
        );
    }

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
