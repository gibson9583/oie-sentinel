import React from 'react';
window.incident={reads:[],writes:[],failRead:false,malformed:false,holdWrite:false,holdRead:false,pending:[],readPending:[],allowed:true,notes:[],older:[],replay:false,serverTime:'2026-10-06T11:00:00Z'};
const event={id:42,status:'RESOLVED',channelId:'channel-a',openedTime:'2026-10-06T10:00:00Z',acknowledgedTime:'2026-10-06T10:05:00Z',acknowledgedBy:7,ackComment:'Taking ownership',resolvedTime:'2026-10-06T10:10:00Z',message:'Queue backlog '.repeat(20)};
const snapshot=(params={})=>({event,dispatches:[{id:1,dispatchTime:'2026-10-06T10:01:00Z',actionId:2,success:false,errorMessage:'Destination timeout'}],notes:{notes:params.cursor?[...window.incident.older]:[...window.incident.notes],nextCursor:!params.cursor&&window.incident.older.length?window.incident.notes.at(-1)?.id:null},nearby:[event,{id:43,openedTime:'2026-10-06T10:30:00Z',status:'PROBLEM',message:'Another incident on this channel'}],nearbyTotal:2,readTime:window.incident.serverTime});
export const platform={React,checkTask:()=>window.incident.allowed,api:{
 get:async(path,params)=>{window.incident.reads.push({path,params});if(window.incident.failRead)throw new Error('Injected inventory read failure');if(window.incident.holdRead)return new Promise(resolve=>window.incident.readPending.push(resolve));return snapshot(params);},
 post:async(path,body)=>{window.incident.writes.push({path,body});if(!window.incident.allowed)throw new Error('Permission denied');if(window.incident.holdWrite)return new Promise(resolve=>window.incident.pending.push(resolve));
 let note=window.incident.notes.find(n=>n.id===body.requestId),replayed=!!note;if(!note){note={id:body.requestId,eventId:42,actorId:7,createdTime:'2026-10-06T11:01:00Z',text:body.text};window.incident.notes.unshift(note);}return window.incident.malformed?{}:{note,replayed};
 }},ui:{toast(){}}};
export function errorModal(){}

export class DataTable {}
export const fmtDate=value=>new Date(value).toISOString();export const fmtNumber=value=>String(value);
