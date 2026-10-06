import React from 'react';
export const state=window.maintenance={permission:true,writes:[],mode:'malformed',hold:false};
export const platform={React,checkTask:()=>state.permission,store:{getState:()=>null,setState:(key,value)=>state.intent=value},
    ui:{h:()=>({style:{}})},api:{post:async(path,body)=>{
        state.writes.push({path,body});if(state.hold) await new Promise(resolve=>state.release=resolve);
        if(state.mode==='malformed')return {};
        const payload=body || state.writes.find(r=>r.body)?.body;
        return {requestId:payload.requestId,removed:false,replayed:state.writes.length>1,window:{id:9,name:'Channel maintenance: '+payload.reason,
            scopeType:'CHANNEL',scopeId:payload.channelId,mode:'SUPPRESS',repeatType:'NONE',activeUntil:new Date(payload.untilMillis).toISOString(),
            activeNow:!path.endsWith('_cancel'),enabled:!path.endsWith('_cancel')}};
    }}};
export class DataTable {}
export const fmtDate=value=>new Date(value).toISOString();
export const fmtNumber=value=>String(value);
export const errorModal=()=>{};
