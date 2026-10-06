// OIE Sentinel. Published under the Mozilla Public License 2.0.
import {platform} from '@oie/web-shell';
import {createChannelMaintenance, cancelChannelMaintenance, errText} from './api.js';
import {canManageMaintenance, fmtTime} from './ui.jsx';
import {INTENT_KEY} from './host.jsx';
const React = platform.React;

/** All-channel notification maintenance, deliberately separate from incident acknowledgement. */
export function ChannelMaintenanceShortcut({channelId, channelName}) {
    const [expanded,setExpanded] = React.useState(false);
    const [duration,setDuration] = React.useState('15');
    const [minutes,setMinutes] = React.useState('120');
    const [until,setUntil] = React.useState('');
    const [reason,setReason] = React.useState('');
    const [review,setReview] = React.useState(null);
    const [attempted,setAttempted] = React.useState(false);
    const [result,setResult] = React.useState(null);
    const [error,setError] = React.useState(null);
    const [busy,setBusy] = React.useState(false);
    const lock = React.useRef(false);
    const openSchedules = () => platform.store.setState(INTENT_KEY, {kind:'schedules',at:Date.now()});
    const prepare = () => {
        try {
            const count = Number(duration === 'custom' ? minutes : duration);
            const untilMillis = duration === 'until' ? new Date(until).getTime() : Date.now()+count*60000;
            if ((duration !== 'until' && (!Number.isInteger(count) || count < 1 || count > 43200))
                || !Number.isFinite(untilMillis) || untilMillis <= Date.now() || untilMillis > Date.now()+30*86400000) {
                throw new Error('Choose a future expiry within 30 days; custom minutes must be a positive whole number.');
            }
            if (!reason.trim() || reason.trim().length > 200) throw new Error('Enter a reason (1–200 characters).');
            setReview({requestId:crypto.randomUUID(),channelId,untilMillis,reason:reason.trim()});setError(null);
        } catch(e) {setError(errText(e));}
    };
    const apply = async cancel => {
        if (lock.current || !canManageMaintenance() || !review) return;
        lock.current=true;setBusy(true);setError(null);
        if (!cancel) setAttempted(true);
        try {
            const response = cancel ? await cancelChannelMaintenance(review.requestId) : await createChannelMaintenance(review);
            const window = response?.window;
            if (response?.requestId !== review.requestId || typeof response.removed !== 'boolean'
                || (!response.removed && (!window || window.id == null || window.scopeType !== 'CHANNEL'
                    || window.scopeId !== channelId || window.mode !== 'SUPPRESS' || window.repeatType !== 'NONE'))) {
                throw new Error('Unexpected response. Completion is uncertain; retry this same request or inspect Schedules.');
            }
            setResult(response);
        } catch(e) {setError(`Completion could not be confirmed: ${errText(e)}. Retry uses the same request identity.`);}
        finally {lock.current=false;setBusy(false);}
    };
    if (!channelId || !canManageMaintenance()) return null;
    return <section className="panel mb-3 sn-channel-maintenance" aria-label="Expiring channel maintenance">
        <div className="panel-body">
            {!expanded ? <button className="btn btn-sm" onClick={()=>setExpanded(true)}>Mute this channel temporarily</button> : <>
                <h3 style={{marginTop:0}}>Expiring channel maintenance</h3>
                <p>Mute all notifications for <strong>{channelName || channelId}</strong> ({channelId}): open alerts,
                    repeats, escalation and recovery notifications. Monitoring and problem lifecycle remain active.
                    This affects every monitor on this channel.</p>
                {!review ? <>
                    <label className="field">Duration <select aria-label="Channel maintenance duration" value={duration} onChange={e=>setDuration(e.target.value)}>
                        <option value="15">15 minutes</option><option value="60">1 hour</option>
                        <option value="custom">Custom minutes</option><option value="until">Until a date and time</option>
                    </select></label>
                    {duration === 'custom' ? <label className="field">Minutes <input aria-label="Maintenance minutes" type="number" min="1" max="43200"
                        value={minutes} onChange={e=>setMinutes(e.target.value)} /></label> : null}
                    {duration === 'until' ? <label className="field">Expiry (your browser’s local timezone)
                        <input aria-label="Maintenance expiry" type="datetime-local" value={until} onChange={e=>setUntil(e.target.value)} /></label> : null}
                    <label className="field">Reason (required) <input aria-label="Channel maintenance reason" maxLength={200}
                        value={reason} onChange={e=>setReason(e.target.value)} /></label>
                    <button className="btn btn-sm" onClick={prepare}>Review channel maintenance</button>
                    <button className="btn btn-sm" onClick={()=>setExpanded(false)}>Close</button>
                </> : !result ? <>
                    <p><strong>Scope:</strong> all notifications on {channelName || channelId}. <strong>Expires:</strong> {fmtTime(review.untilMillis)}.</p>
                    <p><strong>Reason:</strong> {review.reason}</p>
                    <p>At expiry, existing notification policy applies again. Open unacknowledged problems may resume notifications.</p>
                    <button className="btn btn-sm btn-primary" disabled={busy} onClick={()=>apply(false)}>
                        {busy ? 'Applying…' : attempted ? 'Check / retry same request' : 'Mute channel until expiry'}</button>
                    {!attempted ? <button className="btn btn-sm" onClick={()=>{setReview(null);setError(null);}}>Edit</button> : null}
                </> : <div role="status">
                    {result.removed ? <p>The created schedule was removed. Replaying this request will not create another window.</p>
                        : <p><strong>Schedule #{result.window.id}:</strong> {result.window.name}. Expires {fmtTime(result.window.activeUntil)}.
                            {!result.window.enabled ? ' Cancelled (disabled).' : result.window.activeNow ? ' Active now.' : ' Not active now.'}
                            {result.replayed ? ' Existing request reconciled; expiry was not extended.' : ''}</p>}
                    {!result.removed && result.window.enabled ? <button className="btn btn-sm" disabled={busy} onClick={()=>apply(true)}>
                        {busy ? 'Cancelling…' : 'Cancel this maintenance window'}</button> : null}
                </div>}
                {error ? <p className="text-err" role="alert">{error}</p> : null}
                <button className="btn btn-sm" onClick={openSchedules}>Find and manage in Schedules</button>
            </>}
        </div>
    </section>;
}
