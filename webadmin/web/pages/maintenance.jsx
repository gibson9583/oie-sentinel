// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Schedules page: DataTable list of alerting schedules (name, mode tag,
// scope resolved to names, human schedule summary, enabled toggle, server-
// stamped activeNow indicator, per-row "Activate now" for one-time windows)
// with a page-local WindowEditor sub-view (name, mode + scope segpills with
// pickers, repeat type with weekly/monthly day inputs, HH:mm times plus the
// time zone they are read on, datetime-local from/until — required for
// one-time, optional bounds for recurring — and enabled). All mutations are
// canManageMaintenance()-gated; the servlet's MANAGE permission is the real
// enforcement.

import { platform } from '@oie/web-shell';
import { errorModal, confirmDialog, promptDialog } from '@oie/web-ui';
import {
    listMaintenanceWindows, createMaintenanceWindow, updateMaintenanceWindow,
    deleteMaintenanceWindow, activateMaintenanceWindowNow,
    getCoreChannels, getCoreChannelGroups, getCoreTags, errText,
} from '../api.js';
import {
    canManageMaintenance, toast, useApi, useDataTable, ChannelPicker, ChannelGroupPicker,
    TagPicker, fmtTime,
} from '../ui.jsx';

const React = platform.React;
const { h } = platform.ui;

const SCOPE_TYPES = [
    { key: 'ALL', label: 'All channels' },
    { key: 'GROUP', label: 'Channel group' },
    { key: 'TAG', label: 'Channel tag' },
    { key: 'CHANNEL', label: 'Single channel' },
];

const MODES = [
    {
        key: 'SUPPRESS', label: 'Suppress alerts',
        hint: 'Classic maintenance: alerts born while the window is active are suppressed.',
    },
    {
        key: 'ACTIVE', label: 'Alerting schedule',
        hint: 'Alerts on covered channels notify only while the window is active; outside its times they are suppressed.',
    },
];

const REPEAT_TYPES = [
    { key: 'NONE', label: 'One-time' },
    { key: 'WEEKLY', label: 'Weekly' },
    { key: 'MONTHLY', label: 'Monthly' },
];

// java.time.DayOfWeek names, Monday-first (the wire format for daysOfWeek).
const DOW_ORDER = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DOW_SHORT = {
    MONDAY: 'Mon', TUESDAY: 'Tue', WEDNESDAY: 'Wed', THURSDAY: 'Thu',
    FRIDAY: 'Fri', SATURDAY: 'Sat', SUNDAY: 'Sun',
};

// Offered when the runtime has no Intl.supportedValuesOf('timeZone') (added in
// Chrome 99 / Firefox 93 / Safari 15.4 — the OIE Administrator shell can be
// older than that). Deliberately short: the browser's own zone and the window's
// stored zone are merged in on top, so the common cases still work, and an
// operator who needs something else can be given a newer browser rather than
// this file being turned into a hand-maintained copy of the IANA database.
const FALLBACK_TIMEZONES = [
    'UTC',
    'America/Anchorage', 'America/Chicago', 'America/Denver', 'America/Los_Angeles',
    'America/New_York', 'America/Phoenix', 'America/Sao_Paulo', 'Pacific/Honolulu',
    'Europe/Dublin', 'Europe/London', 'Europe/Madrid', 'Europe/Paris', 'Europe/Berlin',
    'Africa/Johannesburg', 'Asia/Jerusalem', 'Asia/Dubai', 'Asia/Kolkata',
    'Asia/Shanghai', 'Asia/Tokyo', 'Australia/Sydney', 'Pacific/Auckland',
];

/* ---- time helpers -------------------------------------------------------- */

/** The browser's IANA zone id (e.g. "America/New_York"), or '' if the runtime
    won't say. Used as the default for NEW windows only — changing an existing
    window's zone is a schedule change and must be deliberate. */
function browserTimezone() {
    try {
        return Intl.DateTimeFormat().resolvedOptions().timeZone || '';
    } catch (e) {
        return '';
    }
}

/** Selectable zone ids: the runtime's full IANA list where available, else the
    curated fallback — always merged with the browser's zone and `current`, so a
    stored zone this runtime doesn't enumerate stays selected instead of
    silently resetting to the server default on the next save. */
function timezoneOptions(current) {
    let zones = null;
    try {
        if (typeof Intl.supportedValuesOf === 'function') zones = Intl.supportedValuesOf('timeZone');
    } catch (e) {
        zones = null;
    }
    const set = new Set(Array.isArray(zones) && zones.length ? zones : FALLBACK_TIMEZONES);
    const browser = browserTimezone();
    if (browser) set.add(browser);
    if (current) set.add(current);
    return Array.from(set).sort();
}

/** ISO instant -> "YYYY-MM-DDTHH:mm" in the BROWSER's local zone (the value
    space of <input type="datetime-local">). Empty string when absent/invalid. */
function isoToLocalInput(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** datetime-local value -> ISO instant. The input value carries no zone, so
    new Date(value) interprets it as browser-local time — exactly what the
    operator sees in the picker. Blank: throws for errorModal, unless
    `optional`, in which case it becomes null (an unbounded recurrence bound). */
function localInputToIso(value, label, optional) {
    const s = (value || '').trim();
    if (!s) {
        if (optional) return null;
        throw new Error(`${label} is required.`);
    }
    const d = new Date(s);
    if (isNaN(d.getTime())) throw new Error(`${label} is not a valid date and time.`);
    return d.toISOString();
}

/** Native <input type="time"> value -> validated "HH:mm" wire string; throws
    on blank/invalid for errorModal. */
function timeInputToHHmm(value, label) {
    const s = (value || '').trim();
    if (!s) throw new Error(`${label} is required.`);
    if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(s)) throw new Error(`${label} must be a valid HH:mm time.`);
    return s;
}

/** "1, 15" -> normalized "1,15" wire CSV; throws on empty/out-of-range days. */
function parseDaysOfMonth(value) {
    const parts = String(value || '').split(',').map((s) => s.trim()).filter(Boolean);
    if (!parts.length) throw new Error('Enter at least one day of the month (1-31).');
    const days = parts.map((p) => {
        const n = Number(p);
        if (!Number.isInteger(n) || n < 1 || n > 31) {
            throw new Error(`"${p}" is not a valid day of the month (1-31).`);
        }
        return n;
    });
    return Array.from(new Set(days)).sort((a, b) => a - b).join(',');
}

/* ---- list helpers -------------------------------------------------------- */

function scopeLabel(w, channelNames, groupNames, tagNames) {
    if (w.scopeType === 'CHANNEL') return `Channel: ${channelNames[w.scopeId] || w.scopeId || '?'}`;
    if (w.scopeType === 'GROUP') return `Group: ${groupNames[w.scopeId] || w.scopeId || '?'}`;
    if (w.scopeType === 'TAG') return `Tag: ${tagNames[w.scopeId] || w.scopeId || '?'}`;
    return 'All channels';
}

/** Human summary of the window's schedule for the list. Recurring rows name
    their zone: the daily times mean nothing without it, and a blank timezone
    (every window created before schema v3) means the server's own zone. */
function scheduleSummary(w) {
    const repeat = w.repeatType || 'NONE';
    const zone = w.timezone ? ` ${w.timezone}` : ' server time';
    if (repeat === 'WEEKLY') {
        const days = String(w.daysOfWeek || '').split(',').map((s) => s.trim()).filter(Boolean);
        const ordered = DOW_ORDER.filter((d) => days.includes(d));
        const label = (ordered.length ? ordered : days).map((d) => DOW_SHORT[d] || d).join(', ');
        return `Weekly ${label || '?'} ${w.startTime || '?'}–${w.endTime || '?'}${zone}`;
    }
    if (repeat === 'MONTHLY') {
        const days = String(w.daysOfMonth || '').split(',').map((s) => s.trim()).filter(Boolean).join(', ');
        return `Monthly ${days || '?'} ${w.startTime || '?'}–${w.endTime || '?'}${zone}`;
    }
    return `One-time: ${fmtTime(w.activeFrom) || '?'} – ${fmtTime(w.activeUntil) || '?'}`;
}

/** The "active" verdict is the server-stamped activeNow flag (it evaluates the
    schedule against the SERVER's clock — no client-side time math). The only
    client math left is the cosmetic Expired label for lapsed one-time windows. */
function windowStatus(w) {
    if (!w.enabled) return 'DISABLED';
    if (w.activeNow) return 'ACTIVE';
    if ((w.repeatType || 'NONE') === 'NONE') {
        const until = w.activeUntil ? new Date(w.activeUntil).getTime() : NaN;
        if (!isNaN(until) && until < Date.now()) return 'EXPIRED';
    }
    return 'SCHEDULED';
}

const STATUS_RANK = { ACTIVE: 0, SCHEDULED: 1, EXPIRED: 2, DISABLED: 3 };

function statusNode(status) {
    if (status === 'ACTIVE') return h('span.tag.accent', 'Active now');
    const label = status === 'DISABLED' ? 'Disabled'
        : status === 'EXPIRED' ? 'Expired' : 'Scheduled';
    return h('span.sn-hint', label);
}

function modeNode(mode) {
    return mode === 'ACTIVE'
        ? h('span.tag.accent', 'Alerting schedule')
        : h('span.tag', 'Suppress');
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
                title: 'Open editor',
                onclick: (e) => { e.stopPropagation(); handlersRef.current.open(r); },
            }, r.name),
            sortValue: (r) => (r.name || '').toLowerCase(),
        },
        {
            key: 'mode', label: 'Mode', width: '140px',
            render: (r) => modeNode(r.mode),
            sortValue: (r) => (r.mode === 'ACTIVE' ? 1 : 0),
        },
        {
            key: 'scope', label: 'Scope',
            render: (r) => r._scopeLabel,
            sortValue: (r) => r._scopeLabel || '',
        },
        {
            key: 'schedule', label: 'Schedule',
            render: (r) => r._schedule,
            sortValue: (r) => (r._schedule || '').toLowerCase(),
        },
        {
            key: 'enabled', label: 'Enabled', width: '80px',
            render: (r) => h('input', {
                type: 'checkbox',
                checked: !!r.enabled,
                disabled: !handlersRef.current.manage,
                title: r.enabled ? 'Disable this window' : 'Enable this window',
                onclick: (e) => e.stopPropagation(),
                ondblclick: (e) => e.stopPropagation(),
                onchange: () => handlersRef.current.toggle(r),
            }),
            sortValue: (r) => (r.enabled ? 0 : 1),
        },
        {
            key: 'status', label: 'Status', width: '110px',
            render: (r) => statusNode(r._status),
            sortValue: (r) => (STATUS_RANK[r._status] != null ? STATUS_RANK[r._status] : 9),
        },
        {
            key: 'actions', label: '', width: '120px',
            render: (r) => {
                if (!handlersRef.current.manage) return '';
                const recurring = (r.repeatType || 'NONE') !== 'NONE';
                return h('button.btn.btn-sm', {
                    type: 'button',
                    disabled: recurring,
                    title: recurring
                        ? 'Activate now only applies to one-time windows'
                        : 'Start this window now for a chosen number of minutes',
                    onclick: (e) => {
                        e.stopPropagation();
                        if (!recurring) handlersRef.current.activate(r);
                    },
                }, 'Activate now');
            },
            sortable: false,
        },
    ];
}

function buildOptions(handlersRef) {
    return {
        selectable: false,
        rowKey: (r) => String(r.id),
        onActivate: (r) => handlersRef.current.open(r),
        emptyText: 'No schedules yet.',
    };
}

/* ---- editor sub-view ----------------------------------------------------- */

function WindowEditor({ window: win, channels, groups, tags, manage, onClose, onChanged }) {
    const isNew = !win;
    const [name, setName] = React.useState(win ? win.name : '');
    const [mode, setMode] = React.useState((win && win.mode) || 'SUPPRESS');
    const [scopeType, setScopeType] = React.useState((win && win.scopeType) || 'ALL');
    const [scopeId, setScopeId] = React.useState((win && win.scopeId) || '');
    const [repeatType, setRepeatType] = React.useState((win && win.repeatType) || 'NONE');
    const [daysOfWeek, setDaysOfWeek] = React.useState(() => (win && win.daysOfWeek
        ? String(win.daysOfWeek).split(',').map((s) => s.trim()).filter(Boolean)
        : []));
    const [daysOfMonth, setDaysOfMonth] = React.useState((win && win.daysOfMonth) || '');
    const [startTime, setStartTime] = React.useState((win && win.startTime) || '');
    const [endTime, setEndTime] = React.useState((win && win.endTime) || '');
    // New windows default to the browser's zone — an operator building a
    // schedule almost always means their own clock, and the DST-correctness
    // this exists for only happens if a zone is actually set. Existing windows
    // keep exactly what is stored, blank included: silently promoting a stored
    // null (= server zone) to the zone of whoever happened to open the editor
    // would move a live schedule on save.
    const [timezone, setTimezone] = React.useState(() => (win ? (win.timezone || '') : browserTimezone()));
    const zoneChoices = React.useMemo(() => timezoneOptions(win && win.timezone), [win]);
    const [from, setFrom] = React.useState(win ? isoToLocalInput(win.activeFrom) : '');
    const [until, setUntil] = React.useState(win ? isoToLocalInput(win.activeUntil) : '');
    const [enabled, setEnabled] = React.useState(win ? !!win.enabled : true);
    const [busy, setBusy] = React.useState(null);

    const recurring = repeatType !== 'NONE';
    const toggleDay = (d, on) => setDaysOfWeek((cur) => (on ? [...cur, d] : cur.filter((x) => x !== d)));

    /** Editor state -> MaintenanceWindow wire object; throws on client-side issues. */
    const buildPayload = () => {
        const trimmed = name.trim();
        if (!trimmed) throw new Error('Name is required.');
        if (scopeType === 'CHANNEL' && !scopeId) throw new Error('Select a channel for the CHANNEL scope.');
        if (scopeType === 'GROUP' && !scopeId) throw new Error('Select a channel group for the GROUP scope.');
        if (scopeType === 'TAG' && !scopeId) throw new Error('Select a channel tag for the TAG scope.');
        const payload = {
            id: win ? win.id : undefined,
            name: trimmed,
            mode,
            scopeType,
            scopeId: scopeType === 'ALL' ? null : scopeId,
            repeatType,
            daysOfWeek: null,
            daysOfMonth: null,
            startTime: null,
            endTime: null,
            // Only recurring windows have a clock to read; one-time windows are
            // absolute instants, and the server blanks this for them anyway.
            timezone: null,
            activeFrom: null,
            activeUntil: null,
            enabled,
        };
        if (repeatType === 'NONE') {
            payload.activeFrom = localInputToIso(from, 'Active from');
            payload.activeUntil = localInputToIso(until, 'Active until');
            if (new Date(payload.activeFrom).getTime() >= new Date(payload.activeUntil).getTime()) {
                throw new Error('Active from must be before active until.');
            }
        } else {
            // End at or before start is legal — the window wraps past midnight.
            payload.startTime = timeInputToHHmm(startTime, 'Start time');
            payload.endTime = timeInputToHHmm(endTime, 'End time');
            payload.timezone = timezone.trim() || null; // blank = the server's zone
            if (repeatType === 'WEEKLY') {
                if (!daysOfWeek.length) throw new Error('Select at least one day of the week.');
                payload.daysOfWeek = DOW_ORDER.filter((d) => daysOfWeek.includes(d)).join(',');
            } else {
                payload.daysOfMonth = parseDaysOfMonth(daysOfMonth);
            }
            payload.activeFrom = localInputToIso(from, 'Active from bound', true);
            payload.activeUntil = localInputToIso(until, 'Active until bound', true);
            if (payload.activeFrom && payload.activeUntil
                && new Date(payload.activeFrom).getTime() >= new Date(payload.activeUntil).getTime()) {
                throw new Error('Active from bound must be before the active until bound.');
            }
        }
        return payload;
    };

    const save = async () => {
        let payload;
        try { payload = buildPayload(); }
        catch (e) { errorModal('Cannot Save Window', e.message || String(e)); return; }
        setBusy('save');
        try {
            if (isNew) await createMaintenanceWindow(payload);
            else await updateMaintenanceWindow(win.id, payload);
            toast(`Schedule "${payload.name}" ${isNew ? 'created' : 'saved'}.`, 'success');
            onChanged();
            onClose();
        } catch (e) {
            // 400 validation messages from MaintenanceWindowService arrive here.
            errorModal(isNew ? 'Create Failed' : 'Save Failed', errText(e), payload.name);
        } finally {
            setBusy(null);
        }
    };

    const del = async () => {
        const ok = await confirmDialog('Delete Schedule',
            win.mode === 'ACTIVE'
                ? `Delete "${win.name}"? Its alerting schedule is removed and alerts on its channels notify normally again.`
                : `Delete "${win.name}"? Problems on its channels stop being suppressed immediately.`,
            { danger: true, okLabel: 'Delete' });
        if (!ok) return;
        setBusy('delete');
        try {
            await deleteMaintenanceWindow(win.id);
            toast(`Schedule "${win.name}" deleted.`, 'success');
            onChanged();
            onClose();
        } catch (e) {
            errorModal('Delete Failed', errText(e), win.name);
        } finally {
            setBusy(null);
        }
    };

    return (
        <div className="panel">
            <div className="panel-header">
                {isNew ? 'New schedule' : `Edit schedule — ${win.name}`}
                <div className="panel-tools">
                    <button className="btn btn-sm" onClick={onClose}>Back to list</button>
                </div>
            </div>
            <div className="panel-body">
                {!manage ? (
                    <div className="sn-hint" style={{ marginBottom: 10 }}>
                        Read-only: the Manage Schedules permission is required to change schedules.
                    </div>
                ) : null}
                <fieldset disabled={!manage} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
                    <div className="form-grid">
                        <div className="field span-2">
                            <label>Name</label>
                            <input value={name} onChange={(e) => setName(e.target.value)}
                                placeholder="e.g. Lab interface upgrade" />
                        </div>
                        <div className="field span-2">
                            <label>Mode</label>
                            <div className="segpill">
                                {MODES.map((m) => (
                                    <button key={m.key} type="button"
                                        className={mode === m.key ? 'on' : ''}
                                        onClick={() => setMode(m.key)}>
                                        {m.label}
                                    </button>
                                ))}
                            </div>
                            <div className="hint">
                                {(MODES.find((m) => m.key === mode) || MODES[0]).hint}
                            </div>
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
                            <div className="hint">Which channels this window covers.</div>
                        </div>
                        <div className="field span-2">
                            <label>Repeat</label>
                            <div className="segpill">
                                {REPEAT_TYPES.map((r) => (
                                    <button key={r.key} type="button"
                                        className={repeatType === r.key ? 'on' : ''}
                                        onClick={() => setRepeatType(r.key)}>
                                        {r.label}
                                    </button>
                                ))}
                            </div>
                        </div>
                        {repeatType === 'WEEKLY' ? (
                            <div className="field span-2">
                                <label>Days of week</label>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px' }}>
                                    {DOW_ORDER.map((d) => (
                                        <label key={d} className="check">
                                            <input type="checkbox" checked={daysOfWeek.includes(d)}
                                                onChange={(e) => toggleDay(d, e.target.checked)} />
                                            {DOW_SHORT[d]}
                                        </label>
                                    ))}
                                </div>
                            </div>
                        ) : null}
                        {repeatType === 'MONTHLY' ? (
                            <div className="field span-2">
                                <label>Days of month</label>
                                <input value={daysOfMonth}
                                    onChange={(e) => setDaysOfMonth(e.target.value)}
                                    placeholder="e.g. 1,15" />
                                <div className="hint">Comma-separated days, 1–31.</div>
                            </div>
                        ) : null}
                        {recurring ? (
                            <>
                                <div className="field span-2">
                                    <label>Time zone</label>
                                    <select value={timezone} onChange={(e) => setTimezone(e.target.value)}>
                                        <option value="">Server time zone</option>
                                        {zoneChoices.map((z) => (
                                            <option key={z} value={z}>{z}</option>
                                        ))}
                                    </select>
                                    <div className="hint">
                                        The clock the start/end times and the recurrence days are read on.
                                        Pick the zone your on-call rotation lives in — the schedule then keeps
                                        its length across daylight-saving changes there. Leave it on
                                        &quot;Server time zone&quot; to follow the OIE server&#39;s own clock.
                                    </div>
                                </div>
                                <div className="field">
                                    <label>Start time</label>
                                    <input type="time" value={startTime}
                                        onChange={(e) => setStartTime(e.target.value)} />
                                    <div className="hint">
                                        In {timezone || 'the server’s time zone'}.
                                    </div>
                                </div>
                                <div className="field">
                                    <label>End time</label>
                                    <input type="time" value={endTime}
                                        onChange={(e) => setEndTime(e.target.value)} />
                                    <div className="hint">
                                        End at or before start runs past midnight (e.g. 22:00–06:00).
                                    </div>
                                </div>
                                <div className="field">
                                    <label>Active from (optional bound)</label>
                                    <input type="datetime-local" value={from}
                                        onChange={(e) => setFrom(e.target.value)} />
                                    <div className="hint">
                                        The schedule never runs before this. Blank = unbounded.
                                        Entered in your browser&#39;s local time zone.
                                    </div>
                                </div>
                                <div className="field">
                                    <label>Active until (optional bound)</label>
                                    <input type="datetime-local" value={until}
                                        onChange={(e) => setUntil(e.target.value)} />
                                    <div className="hint">
                                        The schedule never runs after this. Blank = unbounded.
                                    </div>
                                </div>
                            </>
                        ) : (
                            <>
                                <div className="field">
                                    <label>Active from</label>
                                    <input type="datetime-local" value={from}
                                        onChange={(e) => setFrom(e.target.value)} />
                                    <div className="hint">Entered in your browser&#39;s local time zone.</div>
                                </div>
                                <div className="field">
                                    <label>Active until</label>
                                    <input type="datetime-local" value={until}
                                        onChange={(e) => setUntil(e.target.value)} />
                                    <div className="hint">Must be after the start time.</div>
                                </div>
                            </>
                        )}
                        <div className="field">
                            <label>Enabled</label>
                            <label className="check">
                                <input type="checkbox" checked={enabled}
                                    onChange={(e) => setEnabled(e.target.checked)} />
                                {mode === 'ACTIVE'
                                    ? 'Enforce this alerting schedule'
                                    : 'Suppress problems during this window'}
                            </label>
                        </div>
                    </div>
                </fieldset>
            </div>
            <div className="panel-body"
                style={{ borderTop: '1px solid var(--line)', display: 'flex', gap: 8, alignItems: 'center' }}>
                {manage ? (
                    <button className="btn btn-primary" onClick={save} disabled={!!busy}>
                        {busy === 'save' ? 'Saving…' : (isNew ? 'Create window' : 'Save changes')}
                    </button>
                ) : null}
                <button className="btn" onClick={onClose} disabled={!!busy}>
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

export function MaintenancePage() {
    const windows = useApi(listMaintenanceWindows, []);
    const channels = useApi(getCoreChannels, []);
    const groups = useApi(getCoreChannelGroups, []);
    const tags = useApi(getCoreTags, []);
    const [view, setView] = React.useState(null); // null | { mode: 'new' } | { mode: 'edit', window }
    const manage = canManageMaintenance();

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

    const windowList = Array.isArray(windows.data) ? windows.data : [];
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
    const rows = React.useMemo(() => windowList.map((w) => ({
        ...w,
        _scopeLabel: scopeLabel(w, channelNames, groupNames, tagNames),
        _schedule: scheduleSummary(w),
        _status: windowStatus(w),
        // eslint-disable-next-line react-hooks/exhaustive-deps
    })), [windows.data, channelNames, groupNames, tagNames]); // windowList derives from windows.data

    // No _setEnabled endpoint for windows — toggling round-trips the full entity.
    const toggleEnabled = async (w) => {
        if (!canManageMaintenance()) return;
        try {
            await updateMaintenanceWindow(w.id, {
                id: w.id,
                name: w.name,
                mode: w.mode,
                scopeType: w.scopeType,
                scopeId: w.scopeId == null ? null : w.scopeId,
                repeatType: w.repeatType,
                daysOfWeek: w.daysOfWeek == null ? null : w.daysOfWeek,
                daysOfMonth: w.daysOfMonth == null ? null : w.daysOfMonth,
                startTime: w.startTime == null ? null : w.startTime,
                endTime: w.endTime == null ? null : w.endTime,
                timezone: w.timezone == null ? null : w.timezone,
                activeFrom: w.activeFrom == null ? null : w.activeFrom,
                activeUntil: w.activeUntil == null ? null : w.activeUntil,
                enabled: !w.enabled,
            });
            toast(`Maintenance window "${w.name}" ${w.enabled ? 'disabled' : 'enabled'}.`, 'success');
        } catch (e) {
            errorModal('Enable/Disable Failed', errText(e), w.name);
        }
        windows.reload(); // re-sync the checkbox either way
    };

    const activateNow = async (w) => {
        if (!canManageMaintenance()) return;
        if ((w.repeatType || 'NONE') !== 'NONE') {
            // The list disables the button for recurring windows; this guard
            // mirrors the server's 400.
            errorModal('Cannot Activate Window', 'Activate now only applies to one-time windows.');
            return;
        }
        const answer = await promptDialog('Activate Maintenance Window',
            w.mode === 'ACTIVE'
                ? `Make alerting active for "${w.name}" starting now — for how many minutes?`
                : `Suppress problems for "${w.name}" starting now — for how many minutes?`,
            '60');
        if (answer == null) return;
        const minutes = Number(String(answer).trim() || '60');
        if (!Number.isInteger(minutes) || minutes < 1) {
            errorModal('Cannot Activate Window', 'Duration must be a whole number of minutes, at least 1.');
            return;
        }
        try {
            await activateMaintenanceWindowNow(w.id, minutes);
            toast(`Maintenance window "${w.name}" active for ${minutes} minute${minutes === 1 ? '' : 's'}.`, 'success');
            windows.reload();
        } catch (e) {
            errorModal('Activate Failed', errText(e), w.name);
        }
    };

    const handlersRef = React.useRef({});
    handlersRef.current = {
        open: (w) => setView({ mode: 'edit', window: w }),
        toggle: toggleEnabled,
        activate: activateNow,
        manage,
    };
    const defRef = React.useRef(null);
    if (!defRef.current) {
        defRef.current = { columns: buildColumns(handlersRef), options: buildOptions(handlersRef) };
    }
    const dt = useDataTable(defRef.current.columns, defRef.current.options, rows);

    const firstLoading = windows.loading && windows.data == null;
    const isEmpty = !firstLoading && !windows.error && rows.length === 0;
    const tableHidden = firstLoading || isEmpty || (windows.error && rows.length === 0);
    const activeCount = rows.filter((r) => r._status === 'ACTIVE').length;

    return (
        <>
            {/* The list stays mounted while the editor is open: useDataTable's
                host <div> must not unmount, or the imperative table is lost. */}
            <div style={{ display: view ? 'none' : undefined }}>
                <div className="panel">
                    <div className="panel-header">
                        Maintenance windows
                        <div className="panel-tools">
                            {rows.length ? (
                                <span className="sn-hint">
                                    {rows.length} window{rows.length === 1 ? '' : 's'}
                                    {activeCount ? `, ${activeCount} active now` : ''}
                                </span>
                            ) : null}
                            <button className="btn btn-sm" onClick={windows.reload}
                                disabled={windows.loading}>
                                {windows.loading ? 'Refreshing…' : 'Refresh'}
                            </button>
                            {manage ? (
                                <button className="btn btn-sm btn-primary"
                                    onClick={() => setView({ mode: 'new' })}>
                                    New window
                                </button>
                            ) : null}
                        </div>
                    </div>
                    {windows.error ? (
                        <div className="panel-body" style={{ borderBottom: '1px solid var(--line)' }}>
                            <span className="text-err">Could not load maintenance windows.</span>{' '}
                            <span className="text-text-dim">{windows.error}</span>{' '}
                            <button className="btn btn-sm" onClick={windows.reload}>Retry</button>
                        </div>
                    ) : null}
                    {firstLoading ? (
                        <div className="panel-body sn-hint">Loading maintenance windows…</div>
                    ) : null}
                    {isEmpty ? (
                        <div className="panel-body">
                            <div className="sn-empty">
                                No maintenance windows yet.
                                {manage ? (
                                    <div style={{ marginTop: 8 }}>
                                        <button className="btn btn-primary"
                                            onClick={() => setView({ mode: 'new' })}>
                                            Create your first window
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
                <WindowEditor
                    key={view.window ? `edit-${view.window.id}` : 'new'}
                    window={view.window || null}
                    channels={Array.isArray(channels.data) ? channels.data : null}
                    groups={Array.isArray(groups.data) ? groups.data : null}
                    tags={Array.isArray(tags.data) ? tags.data : null}
                    manage={manage}
                    onClose={() => setView(null)}
                    onChanged={windows.reload}
                />
            ) : null}
        </>
    );
}
