import React from 'react';
export const state=window.saved={user:{id:7},serverId:'engine-a',queries:[],contextFail:false,hold:false,pending:[],permission:true};
const subscribers={};
const h=(spec,...args)=>{const [tag,...classes]=spec.split('.');const el=document.createElement(tag);el.className=classes.join(' ');for(const arg of args){if(arg instanceof Node)el.append(arg);else if(typeof arg==='object')for(const [key,value]of Object.entries(arg)){if(key==='style')el.style.cssText=value;else el.setAttribute(key,value);}else if(arg!=null)el.append(String(arg));}return el;};
export const platform={React,ui:{h,toast:()=>{},modal:()=>{}},checkTask:()=>state.permission,
    store:{getState:key=>key==='user'?state.user:key==='sentinel:intent'?state.intent:null,setState:(key,value)=>{if(key==='user')state.user=value;else if(key==='sentinel:intent')state.intent=value;(subscribers[key]||[]).forEach(fn=>fn(value));},subscribe:(key,fn)=>{(subscribers[key]||=[]).push(fn);return()=>subscribers[key]=subscribers[key].filter(f=>f!==fn);}},router:{currentPath:()=>'/sentinel'},
    api:{get:async(path,params)=>{
        if(path.endsWith('/triageViews/context')){if(state.contextFail)throw new Error('Context offline');return {serverId:state.serverId,userId:state.user.id};}
        if(path.endsWith('/problems')){state.queries.push(params);if(state.hold)return new Promise(resolve=>state.pending.push(resolve));return {items:[],total:0,page:params.page,pageSize:params.pageSize};}
        if(path.endsWith('/core/channels'))return [{channelId:'a',name:'Payments'}];return [];
    }}};
window.switchSavedUser=id=>platform.store.setState('user',{id});
// Minimal imperative table adapter; tests exercise preference-to-query contracts, not host sorting internals.
export class DataTable{constructor(){this.el=document.createElement('div');window.saved.table=this;this.rows=[];}setRows(rows){this.rows=rows;this.render();}selectedRows(){return [];}clearSelection(){}render(){this.el.textContent=this.rows.map(r=>r.message).join(' ');}}
export const fmtDate=value=>String(value);export const fmtNumber=value=>String(value);export const errorModal=()=>{};
