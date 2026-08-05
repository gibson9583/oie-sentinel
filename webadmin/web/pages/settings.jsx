// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Settings page: a form over GET/PUT /settings (SentinelSettings). The tab is
// already hidden without canManageSettings(), but checkTask fails open — the Save
// button is gated again here and the servlet's MANAGE permission is the real
// enforcement. Save success -> toast; validation failures (client pre-check or
// server 400) -> errorModal. Also renders a read-only About panel.

import { platform } from '@oie/web-shell';
import { errorModal } from '@oie/web-ui';
import { getSettings, updateSettings, errText } from '../api.js';
import { useApi, canManageSettings, toast } from '../ui.jsx';

const React = platform.React;

// Field metadata; min/max mirror SettingsService's server-side ranges
// (collector 10..600, evaluator 30..3600, sample 1..90, trend 7..3650,
// resolvedAlert 7..3650) so the hints match what a 400 would enforce.
const SETTINGS_FIELDS = [
    {
        key: 'collectorIntervalSeconds',
        label: 'Collector interval (seconds)',
        min: 10, max: 600,
        hint: 'How often channel statistics are sampled into activity data. Valid range 10–600.',
    },
    {
        key: 'evaluatorIntervalSeconds',
        label: 'Evaluator interval (seconds)',
        min: 30, max: 3600,
        hint: 'How often monitors are evaluated against their channels. Valid range 30–3600.',
    },
    {
        key: 'sampleRetentionDays',
        label: 'Sample retention (days)',
        min: 1, max: 90,
        hint: 'How long raw activity samples are kept before pruning. Valid range 1–90.',
    },
    {
        key: 'trendRetentionDays',
        label: 'Trend retention (days)',
        min: 7, max: 3650,
        hint: 'How long hourly baseline trends are kept. Valid range 7–3650.',
    },
    {
        key: 'resolvedAlertRetentionDays',
        label: 'Resolved alert retention (days)',
        min: 7, max: 3650,
        hint: 'How long resolved problems are kept in history. Valid range 7–3650.',
    },
];

/** Settings object -> string form values (empty string for missing fields). */
function toForm(settings) {
    const form = {};
    for (const f of SETTINGS_FIELDS) {
        form[f.key] = settings && settings[f.key] != null ? String(settings[f.key]) : '';
    }
    return form;
}

export function SettingsPage() {
    const settings = useApi(getSettings, []);
    const [form, setForm] = React.useState(null);
    // Last server-confirmed settings (loaded or saved) — the Reset target.
    const [baseline, setBaseline] = React.useState(null);
    const [saving, setSaving] = React.useState(false);

    React.useEffect(() => {
        if (settings.data && typeof settings.data === 'object') {
            setBaseline(settings.data);
            setForm(toForm(settings.data));
        }
    }, [settings.data]);

    const manage = canManageSettings();
    const setField = (key, value) => setForm((f) => ({ ...f, [key]: value }));

    const save = async () => {
        // Pre-check with the same ranges the server enforces so the user gets
        // one clear message instead of a raw 400; the server remains the
        // authority and its rejections surface through the same errorModal.
        const payload = {};
        for (const f of SETTINGS_FIELDS) {
            const n = Number(form[f.key]);
            if (form[f.key] === '' || !Number.isInteger(n) || n < f.min || n > f.max) {
                errorModal('Invalid Settings',
                    `${f.label} must be a whole number between ${f.min} and ${f.max}.`);
                return;
            }
            payload[f.key] = n;
        }
        setSaving(true);
        try {
            const updated = await updateSettings(payload);
            const next = updated && updated.collectorIntervalSeconds != null ? updated : payload;
            setBaseline(next);
            setForm(toForm(next));
            toast('Settings saved.', 'success');
        } catch (e) {
            errorModal('Save Settings Failed', errText(e));
        } finally {
            setSaving(false);
        }
    };

    if (settings.error && !form) {
        return (
            <div className="panel">
                <div className="panel-header">Settings</div>
                <div className="panel-body">
                    <span className="text-err">Could not load settings.</span>{' '}
                    <span className="sn-hint">{settings.error}</span>{' '}
                    <button type="button" className="btn btn-sm" onClick={settings.reload}>Retry</button>
                </div>
            </div>
        );
    }
    if (!form) {
        return (
            <div className="panel">
                <div className="panel-header">Settings</div>
                <div className="panel-body sn-hint">Loading…</div>
            </div>
        );
    }

    return (
        <div style={{ maxWidth: 760 }}>
            <div className="panel mb-3">
                <div className="panel-header">Settings</div>
                <div className="panel-body">
                    <div className="form-grid">
                        {SETTINGS_FIELDS.map((f) => (
                            <div key={f.key} className="field">
                                <label>{f.label}</label>
                                <input type="number" min={f.min} max={f.max} step={1}
                                    value={form[f.key]}
                                    disabled={saving}
                                    onChange={(e) => setField(f.key, e.target.value)} />
                                <div className="hint">{f.hint}</div>
                            </div>
                        ))}
                    </div>
                    <div className="sn-hint">
                        Interval changes reschedule the collector and evaluator immediately on save;
                        retention changes apply at the next nightly prune.
                    </div>
                    <div className="flex items-center gap-2 mt-3">
                        <button type="button" className="btn btn-primary"
                            disabled={!manage || saving}
                            title={manage ? undefined : 'Requires the Manage Monitoring permission'}
                            onClick={save}>
                            {saving ? 'Saving…' : 'Save'}
                        </button>
                        <button type="button" className="btn"
                            disabled={saving}
                            onClick={() => setForm(toForm(baseline))}>
                            Reset
                        </button>
                    </div>
                </div>
            </div>

            <div className="panel">
                <div className="panel-header">About</div>
                <div className="panel-body">
                    <div className="flex items-center gap-2">
                        <span className="font-semibold">OIE Sentinel</span>
                        <span className="tag">v0.1.0</span>
                    </div>
                    <div style={{ color: 'var(--text-dim)', fontSize: 12, lineHeight: 1.55, marginTop: 8 }}>
                        <div>
                            A background collector samples every deployed channel&apos;s message
                            statistics on the collector interval and stores the per-tick deltas
                            (received, sent, errored, filtered, queued) as activity samples. An
                            hourly rollup condenses those samples into per-channel trends — the
                            learned baselines behind low-volume and anomaly monitors. A separate
                            listener tracks connector connection-state transitions as they happen.
                        </div>
                        <div style={{ marginTop: 6 }}>
                            The evaluator runs on the evaluator interval: each enabled monitor is
                            checked against its scoped channels, consecutive breaches open problems
                            at the monitor&apos;s severity, recoveries resolve them, and matching
                            actions dispatch notifications (email, channel, or SNS). Maintenance
                            windows and monitor dependencies suppress notifications without
                            discarding the underlying problems.
                        </div>
                        <div style={{ marginTop: 6 }}>
                            Nightly retention jobs prune raw samples, hourly trends, and resolved
                            problems older than the ages configured above.
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
