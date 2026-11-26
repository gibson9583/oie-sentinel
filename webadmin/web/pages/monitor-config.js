// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Monitor config state: the state lists the channel types match, and the
// conversion between a monitor's configJson and the editor's per-type state.
// Moved out of monitors.jsx; the Log type's rules live in
// log-monitor-fields.jsx.

import { MONITOR_TYPE_ORDER, MONITOR_TYPE_META } from '../ui.jsx';
import { buildLogConfig, normalizeLogConfig } from './log-monitor-fields.jsx';

// The ConnectionStatusEventType names Sentinel's connector listener records —
// everything except the transient INFO/FAILURE message types, which never
// enter the live state map, so they are the only names a CONNECTION_STATUS
// monitor cannot match.
export const CONNECTION_STATES = [
    'IDLE', 'READING', 'WRITING', 'POLLING', 'RECEIVING', 'SENDING',
    'WAITING_FOR_RESPONSE', 'CONNECTED', 'CONNECTING', 'DISCONNECTED',
];

// CONNECTION_STATUS `rollup`: how many problems (and therefore how many
// notifications) one channel's connectors may open at once. The server's enum
// also reserves SCOPE, which it rejects until scope-level rollup ships, so it
// is deliberately not offered here.
export const ROLLUP_MODES = [
    { value: 'CONNECTOR', label: 'Alert per connector' },
    { value: 'CHANNEL', label: 'Alert once per channel' },
];

// The donkey DeployedState names a CHANNEL_STATE monitor can match, split by
// whether a channel rests there or merely passes through. The split is the
// whole usability of this editor: the resting states are what an operator
// wants ("stopped and nobody noticed"), the transitional ones are what every
// ordinary redeploy walks through, so offering them as one flat list invites
// a monitor that pages on every deployment.
export const CHANNEL_RESTING_STATES = ['STOPPED', 'PAUSED', 'UNDEPLOYED'];
export const CHANNEL_TRANSITIONAL_STATES = [
    'DEPLOYING', 'UNDEPLOYING', 'STARTING', 'STOPPING', 'PAUSING', 'SYNCING',
];
// STARTED is matchable but deliberately absent from both lists: a monitor that
// alerts because a channel is running is a monitor nobody wants, and offering
// the checkbox is an invitation to page the whole estate by accident.
export const CHANNEL_STATES = [...CHANNEL_RESTING_STATES, ...CHANNEL_TRANSITIONAL_STATES];

/** "WAITING_FOR_RESPONSE" -> "Waiting for response". */
export function stateLabel(s) {
    return String(s).toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
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
        if (typeof out.rollup === 'string') out.rollup = out.rollup.trim().toUpperCase();
    }
    if (type === 'CHANNEL_STATE') {
        // Same rule as CONNECTION_STATUS, against the deployed-state list:
        // an existing monitor's unrecognized name survives a round trip (it
        // may be a state a newer engine reports), a seeded default is filtered.
        const names = (Array.isArray(out.alertOnStates) ? out.alertOnStates : [])
            .map((s) => String(s).trim().toUpperCase()).filter(Boolean);
        out.alertOnStates = keepUnknownStates
            ? names
            : names.filter((s) => CHANNEL_STATES.includes(s));
    }
    if (type === 'LOG_EVENTS') {
        return normalizeLogConfig(out);
    }
    return out;
}

/** One editable config per type: defaults for every type, overlaid with the
    monitor's parsed configJson for its own type. */
export function seedConfigs(monitor) {
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
export function buildConfig(type, cfg) {
    const out = {};
    const num = (key, label, opts = {}) => {
        const raw = cfg[key];
        const s = raw == null ? '' : String(raw).trim();
        if (s === '') return;
        const n = Number(s);
        if (!isFinite(n)) throw new Error(`${label} must be a number.`);
        if (opts.integer && !Number.isInteger(n)) throw new Error(`${label} must be a whole number.`);
        if (opts.min != null && n < opts.min) throw new Error(`${label} must be at least ${opts.min}.`);
        if (opts.max != null && n > opts.max) throw new Error(`${label} must be at most ${opts.max}.`);
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
            // Always written, like compareTo/direction: the server rejects an
            // out-of-enum rollup rather than defaulting it, so sending the
            // editor's own value keeps a monitor saved before rollup existed
            // from quietly acquiring a mode nobody chose.
            out.rollup = String(cfg.rollup || 'CONNECTOR').toUpperCase();
            break;
        }
        case 'ERROR_RATE':
            num('windowSeconds', 'Window (seconds)', { min: 1, integer: true });
            // The server rejects anything outside 0–100 rather than clamping,
            // so bound it here too and fail with the friendlier message.
            num('thresholdPercent', 'Error rate threshold (%)', { min: 0, max: 100 });
            num('minMessages', 'Minimum messages', { min: 0, integer: true });
            break;
        case 'QUEUE_DEPTH':
            num('threshold', 'Queue depth threshold', { min: 0, integer: true });
            num('minDurationSeconds', 'Minimum duration (seconds)', { min: 0, integer: true });
            break;
        case 'CHANNEL_STATE': {
            const states = Array.from(new Set(
                (Array.isArray(cfg.alertOnStates) ? cfg.alertOnStates : [])
                    .map((s) => String(s).trim().toUpperCase()).filter(Boolean)));
            // The server rejects an empty array outright (the evaluator would
            // fall back to its default set, which is not what an operator who
            // cleared every box asked for), so fail here with better words.
            if (!states.length) {
                throw new Error('Select at least one channel state to alert on.');
            }
            out.alertOnStates = states;
            num('minDurationSeconds', 'Minimum duration (seconds)', { min: 0, integer: true });
            break;
        }
        case 'LOG_EVENTS':
            return buildLogConfig(cfg);
        default:
            break;
    }
    return out;
}
