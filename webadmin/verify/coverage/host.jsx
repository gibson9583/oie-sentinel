import React from 'react';
export const state=window.coverage={permission:true,fail:false,hold:false,pending:[],posts:0,response:{inventoryStatus:'AVAILABLE',evaluatedAt:new Date().toISOString(),freshnessSeconds:120,issues:['Monitor #9: scope no longer resolves'],rows:[
    {channelId:'a',channelName:'Payments',status:'CONFIGURED',scopeUnknown:false,monitors:[{monitorId:1,monitorName:'Queue monitor',monitorType:'QUEUE_DEPTH',enabled:true,eligible:true,status:'UNKNOWN',reason:'Current trigger evaluation missing',latestEvaluatedTime:null,recordedStates:['OK'],evaluations:[]}]},
    {channelId:'b',channelName:'Stopped channel',status:'NO_COVERAGE',scopeUnknown:false,monitors:[]}]}};
const nativeInterval=window.setInterval;
window.setInterval=(fn,ms)=>{if(ms===30000){state.tick=fn;return 98765;}return nativeInterval(fn,ms);};
export const platform={React,checkTask:()=>state.permission,ui:{h:()=>({style:{}})},store:{getState:key=>key==='user'?{}:null,setState:(key,intent)=>state.intent=intent},router:{currentPath:()=>'/sentinel'},
    api:{get:async()=>{if(state.fail)throw new Error('Coverage offline');if(state.hold)return new Promise(resolve=>state.pending.push(resolve));return structuredClone(state.response);},post:async()=>{state.posts++;}}};
export class DataTable {}
export const fmtDate=value=>new Date(value).toISOString();
export const fmtNumber=value=>String(value);
export const errorModal=()=>{};
