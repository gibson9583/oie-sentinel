// OIE Sentinel. Published under the Mozilla Public License 2.0.
import {platform} from '@oie/web-shell';
import {getIncidentTimeline,appendIncidentNote,errText} from './api.js';
import {canAcknowledge,fmtTime} from './ui.jsx';
import {validateNoteText,validateTimeline,validateNoteReceipt,timelineRows} from './incident-timeline.js';
const React=platform.React;
export function IncidentTimeline({id}) {
    const [data,setData]=React.useState(null),[notes,setNotes]=React.useState([]),[nextCursor,setNextCursor]=React.useState(null),[error,setError]=React.useState(null),[loading,setLoading]=React.useState(false);
    const [text,setText]=React.useState(''),[attempt,setAttempt]=React.useState(null),[pending,setPending]=React.useState(false),[writeError,setWriteError]=React.useState(null),[receipt,setReceipt]=React.useState(null);
    const expanded=React.useRef(false),seq=React.useRef(0),lock=React.useRef(false),currentId=React.useRef(id);currentId.current=id;
    const load=React.useCallback(async(cursor=null)=>{
        const sequence=++seq.current;setLoading(true);
        try{const result=validateTimeline(await getIncidentTimeline(id,cursor),id);if(sequence!==seq.current || id!==currentId.current)return;
            setData(result);setNotes(previous=>cursor?[...new Map([...previous,...result.notes.notes].map(n=>[n.id,n])).values()]:result.notes.notes);
            setNextCursor(result.notes.nextCursor);expanded.current=!!cursor;setError(null);
        }catch(e){if(sequence===seq.current && id===currentId.current)setError(errText(e));}
        finally{if(sequence===seq.current && id===currentId.current)setLoading(false);}
    },[id]);
    React.useEffect(()=>{load();const timer=setInterval(()=>{if(!document.hidden && !lock.current && !expanded.current)load();},30000);return()=>{++seq.current;clearInterval(timer);};},[load]);
    const append=async()=>{
        if(lock.current || !canAcknowledge())return;
        let trimmed;try{trimmed=validateNoteText(text);}catch(e){setWriteError(e.message);return;}
        lock.current=true;setPending(true);setWriteError(null);
        let request=attempt;
        try{
            if(!request){request={requestId:crypto.randomUUID(),text:trimmed};setAttempt(request);}
            const note=validateNoteReceipt(await appendIncidentNote(id,request),request,id);
            if(id!==currentId.current)return;setReceipt(note);setAttempt(null);setText('');load();
        }catch(e){if(id===currentId.current)setWriteError(`${errText(e)} Completion is uncertain. Retry the same note to reconcile without creating a duplicate.`);}
        finally{lock.current=false;if(id===currentId.current)setPending(false);}
    };
    return <section className="panel mb-3" aria-label="Incident timeline and notes">
        <div className="panel-header">Incident timeline and handoff notes</div><div className="panel-body">
            <p className="sn-hint">Recorded facts for this incident. Time proximity does not establish a cause; transport success does not confirm human receipt.</p>
            <div role="status"><span>{data?`Last read ${fmtTime(data.readTime)}`:'Timeline not loaded'}</span>{error?<p className="text-err">Refresh failed: {error}. Previously read facts may be stale.</p>:null}</div>
            <button className="btn btn-sm" disabled={loading} onClick={()=>load()}>{loading?'Reading timeline…':'Refresh timeline'}</button>
            {data?<>
                <p className="sn-hint">{notes.length} notes loaded. Latest 50 notes initially; loading older notes pauses automatic refresh until you refresh the timeline.</p>
                <ol className="sn-timeline-list">{timelineRows(data,notes).map(row=><li key={row.id}><time>{fmtTime(row.time)}</time><div><strong>{row.label}</strong></div>{row.text?<div className="sn-note-text">{row.text}</div>:null}</li>)}</ol>
                {nextCursor?<button className="btn btn-sm" disabled={loading} onClick={()=>load(nextCursor)}>Load older notes</button>:null}
                <details><summary>Nearby incidents on this channel (opened within 24 hours either side)</summary>
                    <p className="sn-hint">{data.nearby.length} of {data.nearbyTotal} recorded incidents shown, newest first. This is a time grouping, not a causal chain.</p>
                    <ul>{data.nearby.map(peer=><li key={peer.id}><time>{fmtTime(peer.openedTime)}</time> · #{peer.id} · {peer.status}<div className="sn-note-text">{peer.message}</div></li>)}</ul>
                </details>
            </>:null}
            <p className="sn-hint">Notes are append-only. Correct an earlier note by adding another. Avoid credentials and sensitive message contents.</p>
            <label htmlFor={`sn-note-${id}`}>Handoff note</label>
            <textarea id={`sn-note-${id}`} className="sn-note-editor" value={text} disabled={pending || !!attempt || !canAcknowledge()} onChange={e=>setText(e.target.value)} />
            <button className="btn btn-sm" disabled={pending || !canAcknowledge()} onClick={append}>{pending?'Appending note…':attempt?'Retry same note':'Append note'}</button>
            {!canAcknowledge()?<p className="sn-hint">Acknowledge Problems permission is required to append notes.</p>:null}
            {writeError?<p role="alert" className="text-err">{writeError}</p>:null}
            {receipt?<p role="status">Last confirmed note: recorded at {fmtTime(receipt.createdTime)} by user #{receipt.actorId}. Receipt: <span className="mono">{receipt.id}</span></p>:null}
        </div>
    </section>;
}
