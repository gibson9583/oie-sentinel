// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// The settings of a Log monitor (type LOG_EVENTS): the fields the monitor
// editor shows, and the conversion between editor state and configJson. The
// server's LogEventsConfig is the authority on every value; the checks here
// only catch a malformed number before the round trip.

import { platform } from '@oie/web-shell';

const React = platform.React;

/* The levels the server accepts, least severe first. */
const LEVELS = [
    { value: 'TRACE', label: 'Trace' }, { value: 'DEBUG', label: 'Debug' }, { value: 'INFO', label: 'Info' },
    { value: 'WARN', label: 'Warn' }, { value: 'ERROR', label: 'Error' }, { value: 'FATAL', label: 'Fatal' },
];
const CONDITIONS = [
    { value: 'EACH', label: 'Each occurrence' },
    { value: 'AT_LEAST', label: 'At least' },
    { value: 'AT_MOST', label: 'At most' },
];
const OUTCOMES = [
    { value: 'NOTIFICATION', label: 'Notification' },
    { value: 'PROBLEM', label: 'Problem' },
];
const CLEAR_MODES = [
    { value: 'CONDITION', label: 'When the condition goes away' },
    { value: 'MANUAL', label: 'When a person resolves it' },
    { value: 'TIMEOUT', label: 'After a set time' },
];

/* The server's Test answer for a Log monitor, shown in place of the button. */
export const LOG_PREVIEW_HINT = "Log monitors can't be previewed: they count lines as the engine logs them, "
    + 'from the first collector run after the monitor is saved.';

/** Which fields apply: threshold for at least and at most, the window unless
    each occurrence is a notification, clearing only for a problem. */
function logFieldsShown(cfg) {
    const each = cfg.condition === 'EACH';
    const problem = cfg.outcome === 'PROBLEM';
    return {
        threshold: !each,
        window: !(each && !problem),
        clears: problem,
        clearAfter: problem && cfg.clearMode === 'TIMEOUT',
    };
}

const upper = (v, fallback) => (v == null || String(v).trim() === '' ? fallback : String(v).trim().toUpperCase());

/** Editor state from a seeded or stored config (numbers already arrive as
    strings). Unknown stored names are kept, so the editor shows them. */
export function normalizeLogConfig(cfg) {
    return {
        ...cfg,
        minLevel: upper(cfg.minLevel, 'ERROR'),
        condition: upper(cfg.condition, 'AT_LEAST'),
        outcome: upper(cfg.outcome, 'PROBLEM'),
        clearMode: upper(cfg.clearMode, 'CONDITION'),
        loggerPrefix: cfg.loggerPrefix == null ? '' : String(cfg.loggerPrefix),
        pattern: cfg.pattern == null ? '' : String(cfg.pattern),
        includeMatches: cfg.includeMatches === true || String(cfg.includeMatches).trim().toLowerCase() === 'true',
    };
}

/* A whole number of at least min, or nothing when blank (the server's default). */
function wholeNumber(out, cfg, key, label, min) {
    const s = cfg[key] == null ? '' : String(cfg[key]).trim();
    if (s === '') return;
    const n = Number(s);
    if (!isFinite(n)) throw new Error(`${label} must be a number.`);
    if (!Number.isInteger(n)) throw new Error(`${label} must be a whole number.`);
    if (n < min) throw new Error(`${label} must be at least ${min}.`);
    out[key] = n;
}

/**
 * The wire configJson from editor state. Only the fields that apply are
 * written, so what the editor shows is what is stored. The logger prefix is
 * trimmed; the pattern is sent as typed, since a space in a pattern matters.
 *
 * @throws Error with a message for errorModal
 */
export function buildLogConfig(cfg) {
    const c = normalizeLogConfig(cfg);
    const shown = logFieldsShown(c);
    const out = { minLevel: c.minLevel };
    if (c.loggerPrefix.trim()) out.loggerPrefix = c.loggerPrefix.trim();
    if (c.pattern !== '') out.pattern = c.pattern;
    out.condition = c.condition;
    if (shown.threshold) wholeNumber(out, cfg, 'threshold', 'Number of lines', c.condition === 'AT_MOST' ? 0 : 1);
    if (shown.window) wholeNumber(out, cfg, 'windowSeconds', 'Window (seconds)', 1);
    out.outcome = c.outcome;
    if (shown.clears) out.clearMode = c.clearMode;
    if (shown.clearAfter) {
        wholeNumber(out, cfg, 'clearAfterSeconds', 'Clear after (seconds)', 1);
        if (out.clearAfterSeconds == null) throw new Error('Clear after (seconds) is required.');
    }
    out.includeMatches = c.includeMatches;
    return out;
}

/** A select that keeps an unknown stored value visible as "(unknown)". */
function Choice({ label, value, options, onChange, hint, wide }) {
    const known = options.some((o) => o.value === value);
    return (
        <div className={wide ? 'field span-2' : 'field'}>
            <label>{label}</label>
            <select value={value} onChange={(e) => onChange(e.target.value)}>
                {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                {known ? null : <option value={value}>{`${value} (unknown)`}</option>}
            </select>
            {hint ? <div className="hint">{hint}</div> : null}
        </div>
    );
}

function Num({ label, value, min, onChange, hint }) {
    return (
        <div className="field">
            <label>{label}</label>
            <input type="number" min={min} step={1} value={value == null ? '' : value}
                onChange={(e) => onChange(e.target.value)} />
            {hint ? <div className="hint">{hint}</div> : null}
        </div>
    );
}

const WHEN_HINTS = {
    EACH: 'Every matching line counts.',
    AT_LEAST: 'Too many: at least this many matching lines within the window.',
    AT_MOST: 'Too few: at most this many matching lines within the window, for example a missing heartbeat.',
};
const RESULT_HINTS = {
    NOTIFICATION: 'A one-off entry in the Notifications tab, sent to actions. Nothing to clear.',
    PROBLEM: 'Stays open in the Problems tab until it clears. Matches while it is open add to it.',
};

/** The Log fieldset of the monitor editor. */
export function LogMonitorFields({ cfg, setCfg }) {
    const c = normalizeLogConfig(cfg);
    const shown = logFieldsShown(c);
    const eachNotification = c.condition === 'EACH' && c.outcome === 'NOTIFICATION';
    return (
        <div className="form-grid">
            <Choice label="Minimum level" value={c.minLevel} options={LEVELS}
                onChange={(v) => setCfg({ minLevel: v })}
                hint="Lines at this level or a more severe one count." />
            <div className="field">
                <label>Logger name prefix</label>
                <input value={c.loggerPrefix} placeholder="e.g. transformer"
                    onChange={(e) => setCfg({ loggerPrefix: e.target.value })} />
                <div className="hint">
                    Optional. Counts only loggers whose name starts with this. Script loggers are
                    named transformer, filter, deploy, db-connector, js-connector and so on.
                </div>
            </div>
            <div className="field span-2">
                <label>Pattern</label>
                <input className="mono" value={c.pattern} placeholder="e.g. Connection refused|timed out"
                    onChange={(e) => setCfg({ pattern: e.target.value })} />
                <div className="hint">
                    Optional Java regular expression, found anywhere in the message or in the
                    exception&rsquo;s one-line summary. It sees the first 4,096 characters of a
                    message. Empty matches every line that passes the level and logger filters.
                </div>
            </div>

            <Choice label="When" value={c.condition} options={CONDITIONS}
                onChange={(v) => setCfg({ condition: v })}
                hint={eachNotification
                    ? 'Each matching line sends its own notification, up to 100 per node per collector '
                        + 'run. The rest are counted in one summary notification.'
                    : WHEN_HINTS[c.condition]} />
            {shown.threshold ? (
                <Num label="Number of lines" value={cfg.threshold} min={c.condition === 'AT_MOST' ? 0 : 1}
                    onChange={(v) => setCfg({ threshold: v })} />
            ) : null}
            {shown.window ? (
                <Num label="Window (seconds)" value={cfg.windowSeconds} min={1}
                    onChange={(v) => setCfg({ windowSeconds: v })}
                    hint={'Counted across all cluster nodes. At least the evaluator interval and at most '
                        + 'the sample retention.'} />
            ) : null}

            <Choice label="Result" value={c.outcome} options={OUTCOMES}
                onChange={(v) => setCfg({ outcome: v })} hint={RESULT_HINTS[c.outcome]} />
            {shown.clears ? (
                <Choice label="Clears" value={c.clearMode} options={CLEAR_MODES}
                    onChange={(v) => setCfg({ clearMode: v })} />
            ) : null}
            {shown.clearAfter ? (
                <Num label="Clear after (seconds)" value={cfg.clearAfterSeconds} min={1}
                    onChange={(v) => setCfg({ clearAfterSeconds: v })}
                    hint="Counted from the last time the condition was true." />
            ) : null}

            <div className="field span-2">
                <label>Matched items</label>
                <label className="check">
                    <input type="checkbox" checked={c.includeMatches}
                        onChange={(e) => setCfg({ includeMatches: e.target.checked })} />
                    Include matched items in alerts
                </label>
                <div className="hint">
                    Keeps up to 10 of the newest matching lines with the problem or notification and
                    adds them to what actions send. A line holds whatever the engine logged.
                </div>
            </div>
            <div className="field span-2">
                <div className="hint">
                    Counting starts at each node&rsquo;s next collector run after the monitor is saved
                    and enabled. Lines logged before then are not counted, and neither are lines the
                    engine&rsquo;s log configuration drops.
                </div>
            </div>
        </div>
    );
}
