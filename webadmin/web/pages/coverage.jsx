// OIE Sentinel. Published under the Mozilla Public License 2.0.
import {platform} from '@oie/web-shell';
import {getCoverage, errText} from '../api.js';
import {canManage, fmtTime, MONITOR_TYPE_META} from '../ui.jsx';
import {INTENT_KEY} from '../host.jsx';
const React=platform.React;
const LABELS={NO_COVERAGE:'No configured coverage',CONFIGURED:'Configured coverage',UNKNOWN:'Unknown',
    INELIGIBLE:'Configured but ineligible',WARMING_UP:'Warming up / insufficient data',HEALTHY:'Recently evaluated healthy',OPEN_PROBLEM:'Open problem'};
export function CoveragePage() {
    const [data,setData]=React.useState(null),[error,setError]=React.useState(null),[loading,setLoading]=React.useState(false),[time,setTime]=React.useState(null);
    const sequence=React.useRef(0);
    const load=React.useCallback(async()=>{
        const seq=++sequence.current;setLoading(true);
        try {
            const response=await getCoverage();
            if(!response || !Array.isArray(response.rows) || !['AVAILABLE','PARTIAL'].includes(response.inventoryStatus)
                || response.rows.some(r=>!r?.channelId || !Array.isArray(r.monitors))) throw new Error('Coverage inventory unavailable or response incomplete.');
            if(seq!==sequence.current)return;
            setData(response);setError(null);setTime(Date.now());
        } catch(e) {if(seq===sequence.current)setError(errText(e));}
        finally {if(seq===sequence.current)setLoading(false);}
    },[]);
    React.useEffect(()=>{
        load();const timer=setInterval(()=>{
            if(platform.store.getState('user') && platform.router.currentPath().startsWith('/sentinel'))load();
        },30000);
        return()=>{clearInterval(timer);++sequence.current;};
    },[load]);
    const create=channelId=>{if(canManage())platform.store.setState(INTENT_KEY,{kind:'newMonitor',channelId,at:Date.now()});};
    return <div className="sn-coverage">
        <h2>Coverage and evaluation health</h2>
        <p>Configured scope, runtime eligibility and recorded evaluation are separate facts.
            A missing or stale evaluation cannot establish health.</p>
        <div className="sn-coverage-toolbar" role="status">
            <span>{time?`Last refreshed ${fmtTime(time)}`:'No successful refresh yet'}.
                {error?<span className="text-err"> Refresh failed: {error} Displayed evidence may be stale.</span>:null}</span>
            <button className="btn btn-sm" disabled={loading} onClick={load}>{loading?'Refreshing…':error?'Retry':'Refresh'}</button>
        </div>
        {data? <>
            <p className="sn-hint">{data.rows.length} visible channels. Evaluation freshness limit: {data.freshnessSeconds} seconds;
                evaluations must also follow the latest monitor update and current connector deployment.
                Channel state monitors include stopped/undeployed channels. Connection status uses live shared deployment inventory.</p>
            {data.inventoryStatus==='PARTIAL'?<p className="text-err" role="status">Channel inventory is incomplete. Some channel identities could not be resolved.</p>:null}
            {data.issues?.length?<div className="panel mb-3"><div className="panel-header">Scope configuration issues</div>
                <div className="panel-body"><ul>{data.issues.map((issue,i)=><li key={i}>{issue}</li>)}</ul></div></div>:null}
            {!data.rows.length?<div className="sn-empty">No visible channels in the current inventory.</div>:<div className="sn-coverage-scroll">
                <table className="dt" aria-label="Channel coverage"><thead><tr><th>Channel</th><th>Covering monitors and evidence</th><th>Next action</th></tr></thead>
                    <tbody>{data.rows.map(row=><tr key={row.channelId}><td><strong>{row.channelName || row.channelId}</strong>
                        <div className="sn-hint">{LABELS[row.status] || 'Unknown'}</div></td><td>
                        {row.scopeUnknown?<p className="text-err">Scope membership is unknown; this channel’s coverage may be incomplete.</p>:null}
                        {!row.monitors.length?<span>No resolvable configured monitor.</span>:<ul>{row.monitors.map(m=><li key={m.monitorId}>
                            <strong>{m.monitorName || `Monitor #${m.monitorId}`}</strong> — {MONITOR_TYPE_META[m.monitorType]?.label || m.monitorType || 'Unknown type'}
                            <div>{m.enabled?'Enabled':'Disabled'}; runtime {m.eligible?'eligible':'ineligible or unknown'}. <strong>{LABELS[m.status] || 'Unknown'}.</strong></div>
                            <div>{m.reason}</div>
                            <div>Latest recorded evaluation: {m.latestEvaluatedTime?fmtTime(m.latestEvaluatedTime):'unknown'}.
                                Recorded states: {m.recordedStates?.length?m.recordedStates.join(', '):'unknown'}.</div>
                            {m.evaluations?.length?<details><summary>Recorded trigger state and evaluation time</summary><ul>{m.evaluations.map((ev,i)=><li key={i}>
                                {ev.metadataId==null?'Channel trigger':`Connector ${ev.metadataId}`}: {ev.state || 'unknown'} at {ev.lastEvaluatedTime?fmtTime(ev.lastEvaluatedTime):'unknown time'}.
                                {!ev.current?' Historical or currently ineligible identity.':''}
                                {ev.consecutiveBreaches>0?` ${ev.consecutiveBreaches} consecutive breaches.`:''}
                                {ev.problemId?` Open problem #${ev.problemId}.`:''}
                            </li>)}</ul></details>:<div className="sn-hint">No recorded evaluation.</div>}
                        </li>)}</ul>}
                    </td><td>{canManage()?<button className="btn btn-sm" onClick={()=>create(row.channelId)}>Create monitor for this channel</button>:null}</td></tr>)}</tbody>
                </table></div>}
        </>:!error?<div className="sn-empty">Loading coverage…</div>:null}
    </div>;
}
