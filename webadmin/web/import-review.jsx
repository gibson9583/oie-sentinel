// OIE Sentinel — preview is a read; apply is additive and returns per-entity receipts.
import { platform } from '@oie/web-shell';
import { confirmDialog } from '@oie/web-ui';
import { previewImport, applyReviewedImport, errText } from './api.js';
import { canManage, fmtTime } from './ui.jsx';
const React = platform.React;
const DEFAULT_MAPPINGS = '{"CHANNEL": {}, "GROUP": {}, "TAG": {}}';

function validatePreview(value) {
    if (!value || typeof value.targetFingerprint !== 'string' || typeof value.planHash !== 'string'
            || !Array.isArray(value.plan?.entries)) throw new Error('Invalid preview response');
    validateReceipt(value.plan);
    if (value.plan.incomplete || value.plan.uncertain) throw new Error('Preview has incomplete validation; retry before applying');
    return value;
}
function validateReceipt(value) {
    if (!value || !Array.isArray(value.entries)
            || ['created', 'updated', 'skipped', 'uncertain'].some(key => !Number.isInteger(value[key]) || value[key] < 0)
            || value.entries.some(entry => !['CREATED', 'UPDATED', 'SKIPPED', 'UNCERTAIN'].includes(entry.outcome))) {
        throw new Error('Invalid apply receipt; completion is uncertain');
    }
    if (value.created + value.updated + value.skipped + value.uncertain !== value.entries.length) {
        throw new Error('Apply receipt counts do not match; completion is uncertain');
    }
    return value;
}

export function ImportReview({ document, name, renderReceipt }) {
    const [mappingText, setMappingText] = React.useState(DEFAULT_MAPPINGS);
    const [preview, setPreview] = React.useState(null);
    const [busy, setBusy] = React.useState(null);
    const [error, setError] = React.useState(null);
    const [notice, setNotice] = React.useState(null);
    const [receipts, setReceipts] = React.useState([]);
    const sequence = React.useRef(0);
    const lock = React.useRef(false);
    const mappingId = React.useId();
    React.useEffect(() => { sequence.current++; setPreview(null); setError(null); setNotice(null); }, [document]);
    React.useEffect(() => () => { sequence.current++; }, []);
    const readMappings = () => JSON.parse(mappingText);
    const plan = async () => {
        if (lock.current || !document) return;
        lock.current = true; const request = ++sequence.current; setBusy('preview'); setError(null); setNotice(null); setPreview(null);
        try {
            const next = validatePreview(await previewImport(document, readMappings()));
            if (request === sequence.current) setPreview(next);
        } catch (e) { if (request === sequence.current) setError(`Preview failed: ${errText(e)}`); }
        finally { lock.current = false; setBusy(null); }
    };
    const apply = async () => {
        if (lock.current || !preview || !document) return;
        lock.current = true; const request = ++sequence.current; setBusy('confirm'); setError(null); setNotice(null);
        try {
            const ok = await confirmDialog('Apply reviewed import',
                `Apply reviewed changes from ${name}? Creates and updates happen individually. Nothing is deleted; this is not a transaction. Invalid, unresolved and missing-secret entries stay untouched.`,
                { danger: true, okLabel: 'Apply reviewed changes' });
            if (!ok || request !== sequence.current) return;
            setBusy('apply');
            const response = await applyReviewedImport(document, readMappings(), preview);
            if (request !== sequence.current) return;
            if (response?.stale === true) {
                setPreview(validatePreview(response.preview));
                setNotice('Target configuration or import input changed. No changes were applied. Review the refreshed preview before applying.');
            } else if (response?.stale === false) {
                const receipt = validateReceipt(response.receipt);
                setReceipts(previous => [...previous, { name, time: new Date().toISOString(), result: receipt }]);
                setPreview(null);
                setNotice(receipt.incomplete || receipt.uncertain ? 'Import needs reconciliation. Receipts below preserve known completion; preview again before rerunning.' : 'Import finished. Review each entry in the receipt below.');
            } else throw new Error('Invalid apply response; completion is uncertain');
        } catch (e) {
            if (request === sequence.current) {
                setPreview(null);
                setError(`Apply failed: ${errText(e)}. Some changes may have completed. Previous receipts are preserved; preview again to reconcile before rerunning.`);
            }
        } finally { lock.current = false; setBusy(null); }
    };
    return <div style={{ marginTop: 12 }}>
        <details><summary>Optional environment reference mapping</summary>
            <p>Map source IDs to existing target IDs explicitly. CHANNEL mappings cover channel scopes, channel delivery targets and conditions; GROUP and TAG mappings cover scopes and conditions. No references are guessed or silently dropped.</p>
            <label htmlFor={mappingId}>Mapping JSON (source ID → target ID)</label>
            <textarea id={mappingId} rows={4} value={mappingText} disabled={!!busy} style={{ width: '100%', boxSizing: 'border-box' }}
                onChange={e => { setMappingText(e.target.value); sequence.current++; setPreview(null); setError(null); setNotice(null); }} />
        </details>
        <div className="flex items-center gap-2 mt-3" style={{ flexWrap: 'wrap' }}>
            <button className="btn" disabled={!canManage() || !document || !!busy} onClick={plan}>{busy === 'preview' ? 'Previewing…' : 'Preview import'}</button>
            <button className="btn btn-primary" disabled={!canManage() || !preview || !!busy || !(preview.plan.created + preview.plan.updated)} onClick={apply}>{busy === 'apply' ? 'Applying…' : 'Apply reviewed changes'}</button>
        </div>
        <div aria-live="polite">
            {error ? <p className="text-err" role="alert">{error}</p> : null}
            {notice ? <p>{notice}</p> : null}
            {preview ? <>
                <p><strong>Preview only — nothing written or sent.</strong> Would create {preview.plan.created}, update {preview.plan.updated}, leave untouched {preview.plan.skipped}. Apply revalidates the target. Known numeric configuration fields are shown; other configuration values are omitted to protect secrets.</p>
                {(preview.remappings || []).length ? <details open><summary>Explicit reference mappings</summary><ul>{preview.remappings.map((mapping, i) => <li key={i} style={{ overflowWrap: 'anywhere' }}>{mapping.path}: {mapping.source} → {mapping.target} ({mapping.type})</li>)}</ul></details> : null}
                {preview.plan.entries.map((entry, i) => <details key={i} style={{ marginTop: 8 }}>
                    <summary>{entry.entityType}: {entry.name || '(unnamed)'} — {entry.outcome === 'CREATED' ? 'Would create' : entry.outcome === 'UPDATED' ? 'Would update' : entry.reason === 'Already matches this server' ? 'Unchanged' : 'Invalid or unresolved'}</summary>
                    {entry.reason ? <p>{entry.reason}</p> : null}
                    {entry.secretsNote ? <p>{entry.secretsNote}</p> : null}
                    {Object.entries(entry.fieldDiffs || {}).map(([field, change]) => <div key={field}>
                        <strong>{field}</strong>
                        <div style={{ overflowWrap: 'anywhere' }}>Before: {JSON.stringify(change.before)}<br />After: {JSON.stringify(change.after)}</div>
                    </div>)}
                </details>)}
            </> : null}
        </div>
        {receipts.map((receipt, i) => <section key={i} aria-label={`Import receipt ${i + 1}`} style={{ marginTop: 16 }}>
            <p><strong>Receipt for {receipt.name}</strong> — {fmtTime(receipt.time)}</p>
            {renderReceipt(receipt.result)}
        </section>)}
    </div>;
}
