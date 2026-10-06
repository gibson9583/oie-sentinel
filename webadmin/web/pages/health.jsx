// OIE Sentinel — cluster evidence, never process-local standby health.
import { platform } from '@oie/web-shell';
import { getClusterHealth, errText } from '../api.js';
import { fmtTime } from '../ui.jsx';
const React=platform.React;
const JOBS=['collector','evaluator','rollup','prune'];
const optionalString=value=>value==null||typeof value==='string';
const optionalNumber=value=>value==null||Number.isSafeInteger(value)&&value>=0;
const nonnegative=value=>Number.isFinite(value)&&value>=0;
const valid=data=>data&&['leader','nodes','jobs','storage','retention','estimates'].every(key=>data[key]&&['OBSERVED','UNKNOWN'].includes(data[key].state)&&optionalString(data[key].detail))&&
    (data.jobs.state==='UNKNOWN'||Array.isArray(data.jobs.data)&&data.jobs.data.every(job=>job&&JOBS.includes(job.job_name)&&['SUCCESS','ERROR','RUNNING'].includes(job.outcome)&&typeof job.node_id==='string'&&Number.isSafeInteger(job.lease_epoch)&&['started_time','finished_time','last_success_time','last_success_node'].every(key=>optionalString(job[key]))&&optionalNumber(job.last_success_epoch)))&&
    (data.nodes.state==='UNKNOWN'||Array.isArray(data.nodes.data)&&data.nodes.data.every(node=>typeof node==='string'))&&
    ['leader','storage','retention','estimates'].every(key=>data[key].state==='UNKNOWN'||data[key].data&&typeof data[key].data==='object'&&!Array.isArray(data[key].data))&&
    (data.leader.state==='UNKNOWN'||['LIVE','EXPIRED','ABSENT'].includes(data.leader.data.state)&&optionalString(data.leader.data.nodeId)&&optionalNumber(data.leader.data.epoch)&&optionalString(data.leader.data.observedAt)&&optionalString(data.leader.data.expiresAt))&&
    (data.storage.state==='UNKNOWN'||['samples','trends','events','pending'].every(key=>nonnegative(data.storage.data[key]))&&optionalString(data.storage.data.oldestPendingEdge))&&
    (data.retention.state==='UNKNOWN'||['collectorIntervalSeconds','evaluatorIntervalSeconds','sampleRetentionDays','trendRetentionDays','resolvedAlertRetentionDays'].every(key=>nonnegative(data.retention.data[key])))&&
    (data.estimates.state==='UNKNOWN'||['visibleDeployedChannels','rawSampleRows','hourlyTrendRows'].every(key=>nonnegative(data.estimates.data[key]))&&typeof data.estimates.data.assumption==='string');
const recorded=time=>time?fmtTime(time):'Not recorded';
function seconds(now,time){const age=(Date.parse(now)-Date.parse(time))/1000;return Number.isFinite(age)&&age>=0?Math.floor(age):null;}
export function jobEvidence(job,leader,retention){
    if(!job)return 'No shared observation';
    if(job.outcome==='ERROR')return 'Last tick reported errors';
    if(job.outcome==='RUNNING')return 'Start recorded; completion unknown';
    if(job.outcome!=='SUCCESS')return 'Completion outcome unknown';
    if(!leader||leader.state!=='LIVE')return 'Current leader unknown or absent';
    if(job.last_success_node!==leader.nodeId||job.last_success_epoch!==leader.epoch)return 'No success recorded for current leader epoch';
    const interval=job.job_name==='collector'?retention?.collectorIntervalSeconds:job.job_name==='evaluator'?retention?.evaluatorIntervalSeconds:job.job_name==='rollup'?3600:86400;
    const age=seconds(leader.observedAt,job.last_success_time);
    if(age===null||!Number.isFinite(interval)||interval<=0)return 'Freshness unknown';
    return age>interval*2+120?'Success observation stale':'Recent success observed';
}
export function ClusterHealth(){
    const[data,setData]=React.useState(null),[error,setError]=React.useState(null),[busy,setBusy]=React.useState(false);
    const sequence=React.useRef(0);
    const load=React.useCallback(async()=>{const request=++sequence.current;setBusy(true);try{
        const next=await getClusterHealth();if(!valid(next))throw new Error('Invalid health observations');
        if(request===sequence.current){setData(next);setError(null);}
    }catch(e){if(request===sequence.current)setError(errText(e));}finally{if(request===sequence.current)setBusy(false);}},[]);
    React.useEffect(()=>{load();const timer=setInterval(load,30000);return()=>{sequence.current++;clearInterval(timer);};},[load]);
    const observed=key=>data?.[key]?.state==='OBSERVED'?data[key].data:null;
    const leader=observed('leader'),retention=observed('retention'),storage=observed('storage'),jobs=observed('jobs'),estimates=observed('estimates');
    const unavailable=key=>data?.[key]?.state==='UNKNOWN'?<p role="status">{key}: Unknown. {data[key].detail}</p>:null;
    const oldestAge=storage&&leader?seconds(leader.observedAt,storage.oldestPendingEdge):null;
    return <section aria-label="Sentinel cluster health" style={{overflowWrap:'anywhere'}}>
        <h2>Sentinel health</h2>
        <p>Shared database evidence from the leader and job observations is available on any node. A standby's empty local collector/evaluator heartbeat does not establish cluster failure. These reads span several queries and are observations, not a consistent snapshot or a guarantee that a process is executing.</p>
        <button className="btn" disabled={busy} onClick={load}>{busy?'Refreshing…':'Refresh health'}</button>
        <div aria-live="polite">{error?<p role="alert">Health refresh failed: {error}. {data?'Previous observations remain below; their timestamps have not advanced.':'Current health is unknown.'}</p>:null}</div>
        {!data&&!error?<p>Loading shared observations…</p>:null}
        {data?<>
            <h3>Leadership and live presence</h3>{unavailable('leader')}
            {leader?<p>Lease: {leader.state}. Node: {leader.nodeId||'No leader recorded'}. Fencing epoch: {leader.epoch??'Unknown'}. Expires: {recorded(leader.expiresAt)}. Database observation: {recorded(leader.observedAt)}.</p>:null}
            {unavailable('nodes')}{observed('nodes')?<p>Nodes with live Sentinel presence leases: {observed('nodes').length?observed('nodes').join(', '):'None observed'}. Presence expiry uses the database clock; this does not guarantee a node responds now.</p>:null}
            <h3>Shared job observations</h3>{unavailable('jobs')}
            <p>Success means the tick completed without a detected job exception. Per-monitor unknown results and asynchronous notification outcomes remain separate. Missing, stale or earlier-epoch evidence cannot establish current success. Freshness uses two configured intervals plus a 120-second failover allowance; rollup/prune use their hourly/daily cadence.</p>
            {jobs?<div style={{overflowX:'auto'}}><table className="dt"><thead><tr><th>Job</th><th>Evidence</th><th>Latest start / finish</th><th>Last successful tick</th></tr></thead><tbody>{JOBS.map(name=>{const job=jobs.find(row=>row.job_name===name);return <tr key={name}><th scope="row">{name}</th><td>{jobEvidence(job,leader,retention)}</td><td>{recorded(job?.started_time)} / {recorded(job?.finished_time)}<div>Node {job?.node_id||'Unknown'}, epoch {job?.lease_epoch??'Unknown'}</div></td><td>{recorded(job?.last_success_time)}<div>Node {job?.last_success_node||'Unknown'}, epoch {job?.last_success_epoch??'Unknown'}</div></td></tr>;})}</tbody></table></div>:null}
            <h3>Pending lifecycle accounting and retained rows</h3>{unavailable('storage')}
            {storage?<p>Visible channels only: {storage.pending} pending event(s); oldest pending edge age {storage.pending===0?'none':oldestAge===null?'unknown':`${oldestAge} seconds`}. Pending flags do not establish a transport send is queued or due. Retained rows: {storage.samples} raw samples, {storage.trends} hourly trends, {storage.events} alert events.</p>:null}
            <h3>Retention and capacity estimates</h3>{unavailable('retention')}
            {retention?<p>Configured retention: raw samples and connector events {retention.sampleRetentionDays} days; hourly trends {retention.trendRetentionDays} days; resolved alerts {retention.resolvedAlertRetentionDays} days. Actual deletion depends on a successful prune run.</p>:null}
            {unavailable('estimates')}{estimates?<><p>Estimated row capacity for {estimates.visibleDeployedChannels} currently visible deployed channels: {estimates.rawSampleRows} raw sample rows and {estimates.hourlyTrendRows} hourly trend rows.</p><p>{estimates.assumption}</p></>:null}
        </>:null}
    </section>;
}
