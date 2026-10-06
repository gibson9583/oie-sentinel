import {test} from 'node:test';import assert from 'node:assert/strict';import {validateNoteText,validateNoteReceipt,validateTimeline,timelineRows} from '../web/incident-timeline.js';
const request={requestId:'e3baa688-4c85-4c14-bc66-d426986792a4',text:' note '},note={id:request.requestId,eventId:42,actorId:7,text:'note',createdTime:'2026-10-06T11:00:00Z'};
test('receipt requires exact identity, incident, text, server actor/time and replay flag',()=>{
 assert.equal(validateNoteReceipt({note,replayed:false},request,42),note);
 for(const value of [{},{note},{note:{...note,eventId:43},replayed:true},{note:{...note,text:'changed'},replayed:true},{note:{...note,actorId:null},replayed:true},{note:{...note,createdTime:'invalid'},replayed:true}])assert.throws(()=>validateNoteReceipt(value,request,42));
});
test('partial timeline response is never accepted as empty successful evidence',()=>{
 const value={event:{id:42,status:'PROBLEM',channelId:'channel-a',openedTime:'2026-10-06T10:00:00Z'},dispatches:[],notes:{notes:[note],nextCursor:null},nearby:[],nearbyTotal:0,readTime:note.createdTime};assert.equal(validateTimeline(value,42),value);
 for(const changed of [{notes:null},{dispatches:null},{dispatches:[null]},{dispatches:[{dispatchTime:note.createdTime,success:"true"}]},{nearby:[null]},{nearbyTotal:-1},{readTime:null}])assert.throws(()=>validateTimeline({...value,...changed},42));
});
test('time order preserves explicit recorded labels without inventing dispatch causality',()=>{
 const rows=timelineRows({event:{openedTime:'2026-10-06T10:00:00Z',acknowledgedTime:'2026-10-06T10:30:00Z',acknowledgedBy:7,resolvedTime:'2026-10-06T10:45:00Z'},dispatches:[{id:1,dispatchTime:'2026-10-06T10:40:00Z',success:true,actionId:2}],notes:{notes:[note]}});
 assert.deepEqual(rows.map(r=>r.id),['open','ack','dispatch:1','resolve',`note:${note.id}`]);assert.equal(rows[2].label,'Transport success recorded · action #2');assert.equal(rows.some(r=>/caused|recovery notification/i.test(r.label)),false);
});

test('UTF-8 limits accept astral text and reject NUL or unpaired surrogate before submission',()=>{
 assert.equal(validateNoteText('😀'.repeat(1000)).length,2000);
 for(const text of [' ', '😀'.repeat(1001), 'invalid\u0000text', 'invalid\uD800text'])assert.throws(()=>validateNoteText(text));
});
