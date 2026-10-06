// OIE Sentinel. Published under the Mozilla Public License 2.0.
export function validateNoteText(value) {
    const text=value.trim();
    if(!text || new TextEncoder().encode(text).length>4000)throw new Error('Enter a note containing 1–4000 UTF-8 bytes.');
    for(const character of text){const cp=character.codePointAt(0);if(cp===0 || (cp>=0xD800 && cp<=0xDFFF))throw new Error('Note contains an unsupported text character.');}
    return text;
}
export function validateNoteReceipt(value,request,eventId) {
    const note=value?.note;
    if(typeof value?.replayed!=='boolean' || note?.id!==request.requestId || note.eventId!==eventId
        || note.text!==request.text.trim() || (!Number.isInteger(note.actorId) || note.actorId<0) || typeof note.createdTime!=='string'
        || !Number.isFinite(Date.parse(note.createdTime)))throw new Error('Note completion could not be verified.');
    return note;
}
export function validateTimeline(value,eventId) {
    const date=v=>typeof v==='string' && Number.isFinite(Date.parse(v));
    const text=v=>v==null || typeof v==='string';
    const event=v=>v && Number.isInteger(v.id) && v.id>0 && ['PROBLEM','RESOLVED'].includes(v.status) && date(v.openedTime) && text(v.message);
    const fail=()=>{throw new Error('Timeline response could not be verified.');};
    if(value?.event?.id!==eventId || !event(value.event) || typeof value.event.channelId!=='string'
        || !Array.isArray(value.dispatches) || !Array.isArray(value.notes?.notes)
        || !(value.notes.nextCursor===null || typeof value.notes.nextCursor==='string') || !Array.isArray(value.nearby)
        || !Number.isInteger(value.nearbyTotal) || value.nearbyTotal<value.nearby.length || !date(value.readTime))fail();
    for(const key of ['acknowledgedTime','resolvedTime'])if(value.event[key]!=null && !date(value.event[key]))fail();
    if(!text(value.event.ackComment))fail();
    for(const d of value.dispatches)if(!d || !date(d.dispatchTime) || typeof d.success!=='boolean' || !text(d.errorMessage)
        || !(d.actionId==null || Number.isInteger(d.actionId)) || !(d.id==null || Number.isInteger(d.id)))fail();
    for(const peer of value.nearby)if(!event(peer))fail();
    for(const note of value.notes.notes)if(!note || typeof note.text!=='string')fail();else validateNoteReceipt({note,replayed:true},{requestId:note.id,text:note.text},eventId);
    return value;
}
export function timelineRows(value,notes=value.notes.notes) {
    const event=value.event, rows=[];
    const add=(id,time,label,text)=>{if(time)rows.push({id,time,label,text});};
    add('open',event.openedTime,'Problem opened',event.message);
    add('ack',event.acknowledgedTime,`Acknowledgement recorded · user #${event.acknowledgedBy}`,event.ackComment);
    add('resolve',event.resolvedTime,'Resolution recorded',null);
    for(const d of value.dispatches)add(`dispatch:${d.id}`,d.dispatchTime,`Transport ${d.success?'success':'failure'} recorded · action ${d.actionId==null?'deleted':`#${d.actionId}`}`,d.errorMessage);
    for(const n of notes)add(`note:${n.id}`,n.createdTime,`Handoff note · user #${n.actorId}`,n.text);
    return rows.sort((a,b)=>Date.parse(a.time)-Date.parse(b.time)||a.id.localeCompare(b.id));
}
