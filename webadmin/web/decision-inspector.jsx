// OIE Sentinel — read-only current policy, separate from captured event evidence.
import { platform } from '@oie/web-shell';
import { inspectDecision, errText } from './api.js';
import { fmtTime } from './ui.jsx';
import { INTENT_KEY } from './host.jsx';
const React = platform.React;

const REASONS = {
    MONITOR_MISSING: 'Monitor no longer exists',
    MONITOR_DISABLED: 'Monitor is disabled',
    SUPPRESS_SCHEDULE: 'Inside a suppression schedule',
    OUTSIDE_ALERTING_SCHEDULE: 'Outside an alerting schedule',
    DEPENDENCY_OPEN: 'Parent problem is still open on this channel',
};

export function DecisionInspector({ event, onOpenProblem }) {
    const [data, setData] = React.useState(null);
    const [error, setError] = React.useState(null);
    const [busy, setBusy] = React.useState(false);
    const sequence = React.useRef(0);
    const load = React.useCallback(async () => {
        const request = ++sequence.current;
        setBusy(true);
        try {
            const next = await inspectDecision(event.id);
            if (!next || !next.policy || !['ALLOW', 'SUPPRESS', 'UNKNOWN'].includes(next.policy.decision)
                    || !next.event || next.event.id !== event.id) throw new Error('Invalid decision response');
            if (request === sequence.current) { setData(next); setError(null); }
        } catch (e) {
            if (request === sequence.current) setError(errText(e));
        } finally {
            if (request === sequence.current) setBusy(false);
        }
    }, [event.id]);
    React.useEffect(() => {
        setData(null); setError(null);
        load();
        return () => { sequence.current++; };
    }, [load, event.status, event.acknowledgedBy, event.suppressed, event.problemPending, event.resolutionPending]);
    const current = data && data.event;
    const trigger = data && data.currentTrigger;
    return <section className="panel mb-3" aria-label="Alert decision inspector">
        <div className="panel-header flex items-center gap-2">
            <span>Current notification decision</span>
            <button className="btn btn-sm" disabled={busy} onClick={load}>{busy ? 'Refreshing…' : 'Refresh decision'}</button>
        </div>
        <div className="panel-body" aria-live="polite">
            {error ? <p className="text-err" role="alert">Decision refresh failed: {error}. {data ? `Showing observation from ${fmtTime(data.observedAt)}.` : 'Current decision is unknown.'}</p> : null}
            {!data && !error ? <p>Reading current policy…</p> : null}
            {data ? <>
                <p><strong>{data.policy.decision}</strong> — observed {fmtTime(data.observedAt)}. This read sends nothing.</p>
                {data.policy.lookupError ? <p className="text-err">{data.policy.lookupError}</p> : null}
                <ul>{(data.policy.reasons || []).map((reason, i) => <li key={i}>
                    {REASONS[reason.code] || reason.code}
                    {reason.name ? `: ${reason.name}` : ''}
                    {reason.scheduleId != null ? <> <button className="btn btn-sm" onClick={() => platform.store.setState(INTENT_KEY, { kind: 'schedules', at: Date.now() })}>View schedules (#{reason.scheduleId})</button></> : null}
                    {reason.timezone ? ` • ${reason.timezone}` : ''}
                    {reason.nextBoundary ? ` • next absolute boundary ${fmtTime(reason.nextBoundary)}` : ''}
                    {reason.parentEventId != null ? <> <button className="btn btn-sm" onClick={() => onOpenProblem(reason.parentEventId)}>Open parent problem #{reason.parentEventId}</button></> : null}
                </li>)}</ul>
                <p>{current.acknowledgedBy != null ? 'Acknowledged: further open-problem repeats stop; recovery notification follows its own policy.' : 'Not acknowledged.'}</p>
                <p>Pending opened edge: {current.problemPending ? 'yes' : 'no'}; pending recovery edge: {current.resolutionPending ? 'yes' : 'no'}. Pending work is not a delivery attempt.</p>
                <details><summary>Current evaluation and routing</summary>
                    {data.evaluationError ? <p className="text-err">{data.evaluationError}</p> : <p>
                        Latest evaluation: {trigger ? fmtTime(trigger.lastEvaluatedTime) : 'not available'}.
                        {trigger ? ` State ${trigger.state}; consecutive breaches ${trigger.consecutiveBreachCount}, current required count ${data.currentMinConsecutiveBreaches ?? 'unknown'}.` : ''}
                        {' '}This is current trigger progress, not progress captured when this event opened. Opening breach progress was not persisted.
                        {trigger && trigger.openAlertEventId != null && trigger.openAlertEventId !== current.id ? ` The trigger now refers to a different problem (#${trigger.openAlertEventId}).` : ''}
                    </p>}
                    {data.routingError ? <p className="text-err">{data.routingError}</p> : <>
                        <p>Enabled actions matching the current {current.status} phase and current configuration:</p>
                        <ul>{(data.matchingActions || []).map(action => <li key={action.id}>{action.name} (#{action.id}, {action.transport}, {action.phase})</li>)}</ul>
                        {!data.matchingActions?.length ? <p>No enabled actions matched.</p> : null}
                    </>}
                    <p>Matching uses the dispatcher's fail-closed matcher; invalid conditions or unavailable scope membership can fail to match. Matching does not promise a send: repeat timing, escalation, leadership, flap and storm controls also apply.</p>
                </details>
                <p>Captured values and thresholds are in Last Value below; the Details panel records event opening time. Action Dispatches records earlier transport attempts independently of this policy. Historical flap and storm decisions were not recorded and cannot be reconstructed here.</p>
            </> : null}
        </div>
    </section>;
}
