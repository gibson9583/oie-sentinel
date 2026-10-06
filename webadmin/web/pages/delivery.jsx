// OIE Sentinel — read-only attempt inbox, no retry or send controls.
import { platform } from '@oie/web-shell';
import { getDeliveries, getPendingDeliveries, errText } from '../api.js';
import { fmtTime } from '../ui.jsx';
import { INTENT_KEY } from '../host.jsx';
const React = platform.React;
const EMPTY = { channelId:'',actionId:'',transport:'',eventId:'',phase:'',success:'',from:'',to:'' };
function params(filters,page,pending) {
    const result={channelId:filters.channelId||undefined,page,pageSize:25};
    for(const key of ['from','to']) if(filters[key]) { const time=new Date(filters[key]).getTime();if(!Number.isFinite(time))throw new Error('Invalid date range');result[key]=time; }
    if(!pending) {
        for(const key of ['actionId','eventId']) if(filters[key]) { const value=Number(filters[key]);if(!Number.isSafeInteger(value)||value<=0)throw new Error(`${key} must be a positive integer`);result[key]=value; }
        result.transport=filters.transport||undefined;result.phase=filters.phase||undefined;
        result.success=filters.success===''?undefined:filters.success==='true';
    }
    return result;
}
const openProblem=id=>platform.store.setState(INTENT_KEY,{kind:'problem',problemId:id,at:Date.now()});
export function DeliveryInbox() {
    const[pending,setPending]=React.useState(false);
    const[filters,setFilters]=React.useState(EMPTY);
    const[page,setPage]=React.useState(0);
    const[data,setData]=React.useState(null);
    const[error,setError]=React.useState(null);
    const[busy,setBusy]=React.useState(false);
    const[observed,setObserved]=React.useState(null);
    const sequence=React.useRef(0);const id=React.useId();
    const load=React.useCallback(async()=>{
        const request=++sequence.current;setBusy(true);
        try {
            const next=await(pending?getPendingDeliveries(params(filters,page,true)):getDeliveries(params(filters,page,false)));
            if(!next||!Array.isArray(next.items)||!Number.isSafeInteger(next.total)||next.total<0||next.items.some(item=>!item||typeof item.channelId!=='string'||(pending?!Number.isSafeInteger(item.id)||item.id<=0:!item.attempt||!Number.isSafeInteger(item.attempt.id)||!Number.isSafeInteger(item.attempt.alertEventId)||typeof item.attempt.success!=='boolean')))throw new Error('Invalid delivery page');
            if(request===sequence.current){setData(next);setError(null);setObserved(new Date().toISOString());}
        }catch(e){if(request===sequence.current)setError(errText(e));}
        finally{if(request===sequence.current)setBusy(false);}
    },[pending,filters,page]);
    React.useEffect(()=>{setData(null);setObserved(null);setError(null);load();const timer=setInterval(load,30000);return()=>{sequence.current++;clearInterval(timer);};},[load]);
    const change=(key,value)=>{sequence.current++;setFilters(previous=>({...previous,[key]:value}));setPage(0);};
    const field=(key,label,type='text')=><div className="field"><label htmlFor={`${id}-${key}`}>{label}</label><input id={`${id}-${key}`} type={type} value={filters[key]} onChange={e=>change(key,e.target.value)} /></div>;
    const choice=(key,label,values)=><div className="field"><label htmlFor={`${id}-${key}`}>{label}</label><select id={`${id}-${key}`} value={filters[key]} onChange={e=>change(key,e.target.value)}><option value="">All</option>{values.map(([value,text])=><option key={value} value={value}>{text}</option>)}</select></div>;
    return <section aria-label="Notification delivery inbox">
        <h2>Notification delivery</h2>
        <p>Real transport attempts are separate from pending lifecycle work. Transport success does not establish human receipt. Failures, especially timeouts, may have an uncertain external outcome; a send can succeed before its log commits.</p>
        <div className="flex items-center gap-2 mb-3" style={{flexWrap:'wrap'}}>
            <button className="btn" aria-pressed={!pending} disabled={!pending} onClick={()=>{sequence.current++;setPending(false);setPage(0);}}>Transport attempts</button>
            <button className="btn" aria-pressed={pending} disabled={pending} onClick={()=>{sequence.current++;setPending(true);setPage(0);}}>Pending lifecycle work</button>
            <button className="btn" disabled={busy} onClick={load}>{busy?'Refreshing…':'Refresh delivery'}</button>
        </div>
        <div className="form-row" style={{display:'flex',flexWrap:'wrap',gap:12}}>
            {field('channelId','Channel IDs (comma-separated)')}
            {!pending?<>{field('actionId','Action ID','number')}{field('eventId','Event ID','number')}
                {choice('transport','Recorded transport',[['EMAIL','Email'],['CHANNEL','Channel'],['SNS','SNS'],['WEBHOOK','Webhook'],['UNKNOWN','Not recorded']])}
                {choice('phase','Recorded event phase',[['PROBLEM','Problem'],['RESOLVED','Recovery'],['UNKNOWN','Not recorded']])}
                {choice('success','Transport result',[['true','Succeeded'],['false','Failed / uncertain']])}</>:null}
            {field('from','From (local time)','datetime-local')}{field('to','To (local time)','datetime-local')}
        </div>
        {pending?<p>These event edges still await durable policy/fan-out accounting. This does not establish a send is queued or due. Time filters use event opening time; transport/action filters apply only to attempts.</p>:<p>Older rows may lack historical name, transport or phase. Current action names are labelled as current; missing historical values are never inferred from today's action or event status. Synthetic saved-action tests are not event dispatch logs.</p>}
        <div aria-live="polite">
            {error?<p role="alert" className="text-err">Delivery refresh failed: {error}. {observed?`Showing page observed ${fmtTime(observed)}.`:'No current page is available.'}</p>:null}
            {observed?<p>Last refreshed {fmtTime(observed)}. {data.total} matching {pending?'pending events':'attempts'}.</p>:null}
        </div>
        {!data&&!error?<p>Loading delivery observations…</p>:null}
        {data?<>
            <div style={{overflowX:'auto'}}><table className="dt"><thead>{pending?<tr><th>Event</th><th>Channel</th><th>Current lifecycle</th><th>Awaiting accounting</th></tr>:<tr><th>Attempt time</th><th>Event / channel</th><th>Action context</th><th>Recorded transport / phase</th><th>Transport result</th></tr>}</thead>
                <tbody>{data.items.map(item=>pending?<tr key={item.id}>
                    <td><button className="btn btn-sm" onClick={()=>openProblem(item.id)}>Open problem #{item.id}</button></td><td style={{overflowWrap:'anywhere'}}>{item.channelId}</td><td>{item.status}</td><td>{[item.problemPending?'Opened edge':null,item.resolutionPending?'Recovery edge':null].filter(Boolean).join(', ')}</td>
                </tr>:<tr key={item.attempt.id}>
                    <td>{fmtTime(item.attempt.dispatchTime)}</td><td><button className="btn btn-sm" onClick={()=>openProblem(item.attempt.alertEventId)}>Open problem #{item.attempt.alertEventId}</button><div style={{overflowWrap:'anywhere'}}>{item.channelId}</div><div className="sn-hint">Current lifecycle: {item.eventStatus}</div></td>
                    <td>{item.actionName||'Historical action name not recorded'}{item.actionDeleted?<div>Action deleted</div>:null}<div className="sn-hint">{item.actionContextSource==='CURRENT_ACTION'?'Current name; historical name not recorded':item.actionContextSource==='CAPTURED'?'Captured at attempt':'No historical label available'}</div><div>ID {item.attempt.actionIdAtAttempt??item.attempt.actionId??'not recorded'}</div></td>
                    <td>{item.attempt.actionTypeAtAttempt||'Transport not recorded'} / {item.attempt.eventPhaseAtAttempt||'Phase not recorded'}</td>
                    <td>{item.attempt.success?'Transport succeeded':'Transport failed / uncertain'}{item.attempt.errorMessage?<div style={{overflowWrap:'anywhere'}}>{item.attempt.errorMessage}</div>:null}</td>
                </tr>)}</tbody></table></div>
            {!data.items.length?<p>No matching {pending?'pending events':'transport attempts'}.</p>:null}
            <div className="flex items-center gap-2 mt-3"><button className="btn" disabled={page===0||busy} onClick={()=>setPage(value=>value-1)}>Previous page</button><span>Page {page+1}</span><button className="btn" disabled={(page+1)*25>=data.total||busy} onClick={()=>setPage(value=>value+1)}>Next page</button></div>
        </>:null}
    </section>;
}
