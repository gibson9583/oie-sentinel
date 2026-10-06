// Sentinel — bounded raw activity threshold replay, MPL 2.0.
import {platform} from '@oie/web-shell';
import {replayActivity,errText} from './api.js';
import {ChannelPicker,canManage,fmtTime} from './ui.jsx';
const React=platform.React;
function localTime(date){const p=n=>String(n).padStart(2,'0');return `${date.getFullYear()}-${p(date.getMonth()+1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`;}
export function ActivityReplayPanel({buildDraft,type,compareTo,channelId,channels,manage,draftFingerprint}) {
    const [target,setTarget]=React.useState(channelId || '');
    const [from,setFrom]=React.useState(()=>localTime(new Date(Date.now()-3600000)));
    const [to,setTo]=React.useState(()=>localTime(new Date()));
    const [step,setStep]=React.useState('300'),[gap,setGap]=React.useState('120');
    const [result,setResult]=React.useState(null),[error,setError]=React.useState(''),[busy,setBusy]=React.useState(false);
    const alive=React.useRef(true),pending=React.useRef(false);
    const fingerprint=JSON.stringify({draftFingerprint,target,from,to,step,gap});
    const live=React.useRef(fingerprint);live.current=fingerprint;
    React.useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
    React.useEffect(()=>{setResult(null);setError('');},[fingerprint]);
    const supported=type==='ERROR_RATE' || type==='LOW_VOLUME' && String(compareTo || 'FIXED').toUpperCase()==='FIXED';
    const replay=async()=>{
        if(pending.current || !canManage() || !supported)return;
        let payload;
        try{
            const start=new Date(from).getTime(),end=new Date(to).getTime();
            if(!target)throw new Error('Select an explicit channel for replay.');
            if(!Number.isFinite(start)||!Number.isFinite(end)||start>=end||end-start>86400000)throw new Error('Choose a range of at most 24 hours with From before Until.');
            if(!Number.isInteger(Number(step))||Number(step)<60||Number(step)>3600||!Number.isInteger(Number(gap))||Number(gap)<1||Number(gap)>1200)throw new Error('Step must be 60–3600 seconds and gap tolerance 1–1200 seconds.');
            payload={monitor:buildDraft(),channelId:target,from:start,to:end,stepSeconds:Number(step),maxSampleGapSeconds:Number(gap)};
        }catch(e){setError(e.message);return;}
        const captured=fingerprint;pending.current=true;setBusy(true);setError('');setResult(null);
        try{
            const response=await replayActivity(payload);
            if(!alive.current)return;
            if(live.current!==captured){setError('Draft changed while replay was running. Run again to compare the current draft.');return;}
            const statuses=['BREACH','OK','INSUFFICIENT_DATA'];
            const valid=response && Array.isArray(response.runs) && Array.isArray(response.points)
                && Number.isInteger(response.evaluationCount) && response.evaluationCount>0 && response.evaluationCount<=1000
                && response.points.length===response.evaluationCount
                && Number.isInteger(response.breachCount) && response.breachCount>=0
                && Number.isInteger(response.unknownCount) && response.unknownCount>=0
                && response.breachCount+response.unknownCount<=response.evaluationCount
                && Number.isInteger(response.rawSampleCount) && response.rawSampleCount>=0 && response.rawSampleCount<=10000
                && response.channelId===target && response.comparisonOnly===true
                && response.historicalDeploymentKnown===false && response.historicalSchedulesKnown===false
                && response.points.every(p=>statuses.includes(p.status) && typeof p.valueJson==='string')
                && response.runs.every(r=>statuses.includes(r.status) && Number.isInteger(r.evaluations) && r.evaluations>0)
                && response.runs.reduce((sum,r)=>sum+r.evaluations,0)===response.evaluationCount;
            if(!valid)throw new Error('Unexpected replay response; no replay conclusion is available.');
            setResult(response);
        }catch(e){if(alive.current&&live.current===captured)setError(`Replay failed: ${errText(e)}. Your monitor draft is preserved.`);}
        finally{pending.current=false;if(alive.current)setBusy(false);}
    };
    return <div className="panel mb-3">
        <div className="panel-header">Historical activity threshold replay</div>
        <div className="panel-body">
            <p>Compare Error rate or fixed Low volume thresholds with retained raw samples on one channel. No monitor is saved and no notification is sent.</p>
            <p className="sn-hint">Metric comparisons assume runtime eligibility. Historical deployment, schedules, dependencies, opening/recovery hysteresis and delivery are unknown. Hourly rollups are never used as exact replay.</p>
            {!supported ? <p className="sn-hint">Select Error rate or fixed Low volume to replay a candidate.</p> : null}
            {!manage ? <p className="sn-hint">Manage Monitoring permission is required to replay candidates.</p> : null}
            {error ? <p className="text-err" role="alert">{error}</p> : null}
            <fieldset disabled={!manage || !supported || busy} style={{border:0,padding:0,margin:0,minWidth:0}}>
                <div className="form-grid">
                    <div className="field"><label>Replay channel</label><ChannelPicker channels={channels} value={target} onChange={setTarget} emptyLabel="Select a channel…" /></div>
                    <div className="field"><label htmlFor="sn-replay-from">From (browser local time)</label><input id="sn-replay-from" type="datetime-local" value={from} onChange={e=>setFrom(e.target.value)} /></div>
                    <div className="field"><label htmlFor="sn-replay-to">Until (browser local time)</label><input id="sn-replay-to" type="datetime-local" value={to} onChange={e=>setTo(e.target.value)} /></div>
                    <div className="field"><label htmlFor="sn-replay-step">Evaluation step (seconds)</label><input id="sn-replay-step" type="number" min={60} max={3600} value={step} onChange={e=>setStep(e.target.value)} /><div className="hint">Sampled instants, not the historical engine scheduler. At most 1,000 instants.</div></div>
                    <div className="field"><label htmlFor="sn-replay-gap">Maximum sample gap (seconds)</label><input id="sn-replay-gap" type="number" min={1} max={1200} value={gap} onChange={e=>setGap(e.target.value)} /><div className="hint">Set above collector interval. Missing coverage or larger gaps remain unknown. At most 10,000 raw samples including lookback.</div></div>
                </div>
            </fieldset>
            <button className="btn" disabled={!manage || !supported || busy} onClick={replay}>{busy?'Replaying…':'Replay candidate'}</button>
            {result ? <div role="status" style={{marginTop:16}}>
                <p>{result.breachCount} threshold breaches across {result.evaluationCount} sampled instants; {result.unknownCount} unknown. These are not historical incidents or delivery predictions.</p>
                <p className="sn-hint">Available raw context: {result.rawSampleCount} samples, {fmtTime(result.rawFrom)||'none'} to {fmtTime(result.rawTo)||'none'}. Requested window {result.windowSeconds}s; evaluation step {result.stepSeconds}s.</p>
                <p className="sn-hint">{result.limitation}</p>
                {result.runs.map((run,i)=><p key={i} style={{overflowWrap:'anywhere'}}>{run.status} · {fmtTime(run.from)} to {fmtTime(run.to)} · {run.evaluations} sampled instants</p>)}
                <details><summary>Captured comparisons ({result.points.length})</summary>{result.points.map((point,i)=><p key={i} style={{overflowWrap:'anywhere'}}>{fmtTime(point.at)} · {point.status} · {point.valueJson}</p>)}</details>
            </div> : null}
        </div>
    </div>;
}
