// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Settings page: a form over GET/PUT /settings (SentinelSettings). The tab is
// already hidden without canManageSettings(), but checkTask fails open — the Save
// button is gated again here and the servlet's MANAGE permission is the real
// enforcement. Save success -> toast; validation failures (client pre-check or
// server 400) -> errorModal.
//
// Also hosts the Export / Import panel over GET /export and POST /import. Those
// two endpoints are MANAGE-gated rather than SETTINGS-gated (the export carries
// the full action config surface), so their controls use canManage() while the
// settings form above keeps canManageSettings(). Finally, a read-only About panel.

import { platform } from '@oie/web-shell';
import { errorModal, confirmDialog } from '@oie/web-ui';
import {
    getSettings, updateSettings, exportConfiguration, importConfiguration, errText,
} from '../api.js';
import { useApi, canManage, canManageSettings, toast, fmtTime } from '../ui.jsx';

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

/* ---- Export / import ----------------------------------------------------- */

const ENTITY_LABELS = {
    MONITOR: 'Monitor',
    ACTION: 'Action',
    MAINTENANCE_WINDOW: 'Maintenance window',
};

// One row style per outcome, with the dry-run wording in the same table so the
// preview can never be mistaken for something that was written.
const OUTCOME_META = {
    CREATED: { label: 'Created', would: 'Would create', cls: 'tag accent' },
    UPDATED: { label: 'Updated', would: 'Would update', cls: 'tag' },
    SKIPPED: { label: 'Skipped', would: 'Would skip', cls: 'tag' },
};

/** sentinel-config-20260806-0915.json — sorts chronologically in a directory. */
function exportFilename(now) {
    const p = (n) => String(n).padStart(2, '0');
    return `sentinel-config-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}`
        + `-${p(now.getHours())}${p(now.getMinutes())}.json`;
}

function outcomeNode(outcome, dryRun) {
    const meta = OUTCOME_META[outcome];
    if (!meta) return <span className="sn-hint">{outcome || '—'}</span>;
    return <span className={meta.cls}>{dryRun ? meta.would : meta.label}</span>;
}

/** Renders an ImportResult. The dry-run and applied payloads have the same
    shape by server contract, so this is deliberately ONE component — only the
    wording keys off result.dryRun. */
function ImportResult({ result }) {
    const entries = Array.isArray(result.entries) ? result.entries : [];
    const dry = !!result.dryRun;
    return (
        <div style={{ marginTop: 12 }}>
            <div className="flex items-center gap-2" style={{ flexWrap: 'wrap' }}>
                <span className={dry ? 'tag' : 'tag accent'}>
                    {dry ? 'Dry run — nothing was written' : 'Applied'}
                </span>
                <span className="sn-hint">
                    {dry ? 'Would create' : 'Created'} {result.created},
                    {' '}{dry ? 'update' : 'updated'} {result.updated},
                    {' '}skipped {result.skipped}
                </span>
                {result.exportedAt
                    ? <span className="sn-hint">Document exported {fmtTime(result.exportedAt)}</span>
                    : null}
            </div>
            {result.secretsNotice
                ? <div className="sn-hint" style={{ marginTop: 6 }}>{result.secretsNotice}</div>
                : null}
            {entries.length === 0 ? (
                <div className="sn-empty">The document defined no monitors, actions, or windows.</div>
            ) : (
                <table className="dt" style={{ marginTop: 8 }}>
                    <thead>
                        <tr><th>Type</th><th>Name</th><th>Outcome</th><th>Detail</th></tr>
                    </thead>
                    <tbody>
                        {entries.map((e, i) => (
                            <tr key={`${e.entityType}:${e.name}:${i}`}>
                                <td>{ENTITY_LABELS[e.entityType] || e.entityType}</td>
                                <td>{e.name || <span className="sn-hint">(unnamed)</span>}</td>
                                <td>{outcomeNode(e.outcome, dry)}</td>
                                <td>
                                    {e.reason ? <div>{e.reason}</div> : null}
                                    {e.secretsNote ? <div className="sn-hint">{e.secretsNote}</div> : null}
                                    {!e.reason && !e.secretsNote ? <span className="sn-hint">—</span> : null}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    );
}

function ExportImportPanel() {
    const manage = canManage();
    const [busy, setBusy] = React.useState(null);   // null | 'export' | 'dryRun' | 'apply'
    const [doc, setDoc] = React.useState(null);     // { name, parsed }
    const [result, setResult] = React.useState(null);

    const download = async () => {
        setBusy('export');
        try {
            const exported = await exportConfiguration();
            // Re-serialized with two-space indent rather than saved as the wire
            // sent it: the point of the file is to live in a repository, and a
            // single-line document makes every review diff useless.
            const blob = new Blob([JSON.stringify(exported, null, 2)], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            // Anchor must be in the document for click() to download in every
            // browser, and the object URL must outlive the click — hence the
            // append/click/remove/defer-revoke dance rather than a bare click.
            const link = document.createElement('a');
            link.href = url;
            link.download = exportFilename(new Date());
            document.body.appendChild(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 0);
            toast('Configuration exported.', 'success');
        } catch (e) {
            errorModal('Export Failed', errText(e));
        } finally {
            setBusy(null);
        }
    };

    const pick = async (e) => {
        const file = e.target.files && e.target.files[0];
        if (!file) return;
        setResult(null);
        try {
            setDoc({ name: file.name, parsed: JSON.parse(await file.text()) });
        } catch (err) {
            setDoc(null);
            errorModal('Invalid File', `${file.name} is not valid JSON.`);
        }
    };

    const run = async (dryRun) => {
        if (!doc) return;
        if (!dryRun) {
            const ok = await confirmDialog('Apply Import',
                `Apply ${doc.name} to this server? Entities are matched by name — a name already `
                + 'here is overwritten, an unknown name is created, and nothing is deleted.',
                { okLabel: 'Apply' });
            if (!ok) return;
        }
        setBusy(dryRun ? 'dryRun' : 'apply');
        try {
            const applied = await importConfiguration(doc.parsed, dryRun);
            setResult(applied);
            if (!dryRun) {
                toast(`Import applied: ${applied.created} created, ${applied.updated} updated, `
                    + `${applied.skipped} skipped.`, 'success');
            }
        } catch (e) {
            setResult(null);
            errorModal(dryRun ? 'Dry Run Failed' : 'Import Failed', errText(e));
        } finally {
            setBusy(null);
        }
    };

    const manageTitle = manage ? undefined : 'Requires the Manage Monitoring permission';

    return (
        <div className="panel mb-3">
            <div className="panel-header">Export / Import</div>
            <div className="panel-body">
                <div className="sn-hint" style={{ marginBottom: 10, lineHeight: 1.55 }}>
                    <div>
                        The export carries every monitor, action, and maintenance window as one JSON
                        document — promotable dev → test → prod like a channel, and reviewable in
                        version control.
                    </div>
                    <div style={{ marginTop: 4 }}>
                        Import matches entities by <b>name</b>, not id (ids are per-server serials):
                        a known name is updated, an unknown name is created, an entity that already
                        matches is skipped, and nothing is ever deleted. Every entity is validated
                        exactly as the editors validate it. Secrets export as the redaction marker
                        and must be re-entered here — the result names the fields per action.
                    </div>
                    <div style={{ marginTop: 4 }}>
                        Dry run first: it reports the same result the apply would, without writing.
                    </div>
                </div>

                <div className="flex items-center gap-2" style={{ flexWrap: 'wrap' }}>
                    <button type="button" className="btn"
                        disabled={!manage || !!busy}
                        title={manageTitle}
                        onClick={download}>
                        {busy === 'export' ? 'Exporting…' : 'Export configuration'}
                    </button>
                    <input type="file" accept="application/json,.json"
                        disabled={!manage || !!busy}
                        onChange={pick} />
                </div>

                <div className="flex items-center gap-2 mt-3">
                    <button type="button" className="btn"
                        disabled={!manage || !doc || !!busy}
                        title={manage ? 'Reports what would change, without writing anything.' : manageTitle}
                        onClick={() => run(true)}>
                        {busy === 'dryRun' ? 'Checking…' : 'Dry run'}
                    </button>
                    <button type="button" className="btn btn-primary"
                        disabled={!manage || !doc || !!busy}
                        title={manageTitle}
                        onClick={() => run(false)}>
                        {busy === 'apply' ? 'Applying…' : 'Apply'}
                    </button>
                    {!doc ? <span className="sn-hint">Choose an export document to import.</span> : null}
                </div>

                {result ? <ImportResult result={result} /> : null}
            </div>
        </div>
    );
}

/* ---- page ---------------------------------------------------------------- */

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

    // The settings form has its own load/error states, but Export / Import and
    // About depend on neither — so the states are rendered as the Settings
    // PANEL rather than as the whole page, and the panels below always mount.
    const settingsPanel = settings.error && !form ? (
        <div className="panel mb-3">
            <div className="panel-header">Settings</div>
            <div className="panel-body">
                <span className="text-err">Could not load settings.</span>{' '}
                <span className="sn-hint">{settings.error}</span>{' '}
                <button type="button" className="btn btn-sm" onClick={settings.reload}>Retry</button>
            </div>
        </div>
    ) : !form ? (
        <div className="panel mb-3">
            <div className="panel-header">Settings</div>
            <div className="panel-body sn-hint">Loading…</div>
        </div>
    ) : (
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
    );

    return (
        <div style={{ maxWidth: 760 }}>
            {settingsPanel}

            <ExportImportPanel />

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
