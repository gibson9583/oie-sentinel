// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// The per-type settings fieldsets of the monitor editor, matching the
// server's config shapes. Moved out of monitors.jsx; the Log type's fields
// live in log-monitor-fields.jsx.

import { platform } from '@oie/web-shell';
import {
    CONNECTION_STATES, ROLLUP_MODES, CHANNEL_RESTING_STATES, CHANNEL_TRANSITIONAL_STATES,
    CHANNEL_STATES, stateLabel,
} from './monitor-config.js';
import { LogMonitorFields } from './log-monitor-fields.jsx';

const React = platform.React;

/* ---- small form pieces --------------------------------------------------- */

function NumField({ label, value, onChange, hint, min, max, step }) {
    return (
        <div className="field">
            <label>{label}</label>
            <input type="number" value={value == null ? '' : value}
                min={min} max={max} step={step == null ? 1 : step}
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

export function ConfigFields({ type, cfg, setCfg }) {
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
                                    title="Not a recorded connection state — never matches.">
                                    <input type="checkbox" checked
                                        onChange={(e) => toggle(s, e.target.checked)} />
                                    {stateLabel(s)} (transient)
                                </label>
                            ))}
                        </div>
                        <div className="hint">
                            Breach while any connector sits in a selected state for the minimum duration.
                        </div>
                    </div>
                    <NumField label="Minimum duration (seconds)" value={cfg.minDurationSeconds} min={0}
                        onChange={(v) => setCfg({ minDurationSeconds: v })}
                        hint="The state must persist at least this long before it counts as a breach." />
                    <SelectField label="Alerting" value={String(cfg.rollup || 'CONNECTOR').toUpperCase()}
                        options={ROLLUP_MODES} onChange={(v) => setCfg({ rollup: v })}
                        hint={'Per connector opens one problem per failing connector; once per '
                            + 'channel opens a single problem that clears only when every '
                            + 'connector recovers.'} />
                </div>
            );
        }
        case 'ERROR_RATE':
            return (
                <div className="form-grid">
                    <NumField label="Error rate threshold (%)" value={cfg.thresholdPercent}
                        min={0} max={100} step={0.5}
                        onChange={(v) => setCfg({ thresholdPercent: v })}
                        hint="Breach when errored messages reach this share of received messages in the window." />
                    <NumField label="Window (seconds)" value={cfg.windowSeconds} min={1}
                        onChange={(v) => setCfg({ windowSeconds: v })}
                        hint="Sliding window the error and received counts are summed over." />
                    <NumField label="Minimum messages" value={cfg.minMessages} min={0}
                        onChange={(v) => setCfg({ minMessages: v })}
                        hint="Windows with fewer received messages than this report insufficient data, never OK." />
                </div>
            );
        case 'QUEUE_DEPTH':
            return (
                <div className="form-grid">
                    <NumField label="Queue depth threshold" value={cfg.threshold} min={0}
                        onChange={(v) => setCfg({ threshold: v })}
                        hint="Breach when the channel's queued-message count is at or above this." />
                    <NumField label="Minimum duration (seconds)" value={cfg.minDurationSeconds} min={0}
                        onChange={(v) => setCfg({ minDurationSeconds: v })}
                        hint="The depth must hold at least this long before it counts as a breach." />
                    <div className="field span-2">
                        <div className="hint">
                            Depth is read from the latest sample; with no recent sample the monitor
                            reports insufficient data.
                        </div>
                    </div>
                </div>
            );
        case 'CHANNEL_STATE': {
            const selected = Array.isArray(cfg.alertOnStates) ? cfg.alertOnStates : [];
            const extras = selected.filter((s) => !CHANNEL_STATES.includes(s));
            const toggle = (s, on) => setCfg({
                alertOnStates: on ? [...selected, s] : selected.filter((x) => x !== s),
            });
            const box = (s, suffix, title) => (
                <label key={s} className="check" title={title}>
                    <input type="checkbox" checked={selected.includes(s)}
                        onChange={(e) => toggle(s, e.target.checked)} />
                    {stateLabel(s)}{suffix || ''}
                </label>
            );
            return (
                <div className="form-grid">
                    <div className="field span-2">
                        <label>Alert on channel states</label>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px' }}>
                            {CHANNEL_RESTING_STATES.map((s) => box(s))}
                        </div>
                        <div className="hint">
                            A channel does not leave these on its own — this is the
                            &ldquo;stopped and nobody noticed&rdquo; set.
                        </div>
                    </div>
                    <div className="field span-2">
                        <label>Also alert on transitional states</label>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px' }}>
                            {CHANNEL_TRANSITIONAL_STATES.map((s) => box(s))}
                            {extras.map((s) => box(s, ' (unknown)',
                                'Not a state this engine reports — it will never match.'))}
                        </div>
                        <div className="hint">
                            Every redeploy passes through these. Only select one with a minimum
                            duration long enough to clear a normal deployment — that is how you
                            catch a channel stuck mid-start.
                        </div>
                    </div>
                    <NumField label="Minimum duration (seconds)" value={cfg.minDurationSeconds} min={0}
                        onChange={(v) => setCfg({ minDurationSeconds: v })}
                        hint="The state must persist at least this long before it counts as a breach." />
                    <div className="field span-2">
                        <div className="hint">
                            The only monitor type evaluated against channels that are not started —
                            that is the point of it. Duration is counted from the first tick that saw
                            the current state, so a monitor created while a channel is already
                            stopped breaches one minimum-duration later rather than immediately.
                        </div>
                    </div>
                </div>
            );
        }
        case 'LOG_EVENTS':
            return <LogMonitorFields cfg={cfg} setCfg={setCfg} />;
        default:
            return null;
    }
}
