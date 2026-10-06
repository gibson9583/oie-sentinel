// Dedicated browser harness for the public plugin contracts, not an engine simulation.
import React from 'react';
export const state = window.triage = { failList: false, failDetail: false, mode: 'partial', writes: 0, permission: true,
    holdWrite: false, holdList: false, pendingLists: [], queries: [], rows: [
        {id:1, status:'PROBLEM', severity:'HIGH', monitorId:1, message:'Observed queue backlog '.repeat(12), openedTime:'2026-10-06T10:00:00Z'},
        {id:2, status:'PROBLEM', severity:'WARNING', monitorId:1, message:'Second problem', openedTime:'2026-10-06T11:00:00Z'},
    ] };
const detail = id => ({event:state.rows.find(r => String(r.id) === String(id)), monitorName:'Queue monitor',
    dispatches:[{id:1,success:false,errorMessage:'Destination timeout '.repeat(12),dispatchTime:'2026-10-06T10:01:00Z'}]});
const h = (spec, ...args) => {
    const [tag, ...classes] = spec.split('.'); const el = document.createElement(tag); el.className = classes.join(' ');
    for (const arg of args) {
        if (arg instanceof Node) el.append(arg);
        else if (typeof arg === 'object') for (const [key,value] of Object.entries(arg)) {
            if (key === 'style') el.style.cssText = value; else el.setAttribute(key, value);
        }
        else if (arg != null) el.append(String(arg));
    }
    return el;
};
const modal = options => {
    const root = h('section', {'role':'dialog', 'aria-label':options.title});
    root.append(options.body);
    const close = () => { root.remove(); options.onClose?.(); };
    for (const b of options.buttons) { const button = h('button', b.label); button.onclick = () => { b.onClick(); close(); }; root.append(button); }
    document.body.append(root); return {close};
};
export const platform = {React, ui:{h,modal,toast:()=>{}}, checkTask:()=>state.permission,
    store:{getState:key=>key === 'user' ? {} : null}, router:{currentPath:()=>'/sentinel'},
    api:{get:async(path,params)=>{
        if(path.endsWith('/problems')) {
            state.queries.push(params);
            if(state.failList) throw new Error('Queue offline');
            if(state.holdList) return new Promise(resolve=>state.pendingLists.push({resolve,params}));
            const rows = params.q ? state.rows.filter(r=>r.message.includes(params.q)) : state.rows;
            return {items:rows,total:state.total ?? rows.length,page:0,pageSize:25};
        }
        if(/\/problems\/\d+$/.test(path)) {if(state.failDetail) throw new Error('Detail offline');return detail(path.split('/').at(-1));}
        if(path.endsWith('/monitors')) return [{id:1,name:'Queue monitor',runbookUrl:'https://example.org/runbook'}];
        return [];
    },post:async(path,body)=>{
        state.writes++; if(state.holdWrite) await new Promise(resolve=>state.releaseWrite=resolve);
        if(state.mode === 'malformed') return {};
        const resolve = path.includes('Resolve') || path.endsWith('_resolve');
        if(body.ids) {
            if(!body.ids.every(Number.isInteger)) throw new Error('numeric IDs required');
            const rows = body.ids.map((id,i)=>({id,status:i===0?'APPLIED':'UNAVAILABLE'}));
            state.rows[0] = {...state.rows[0],acknowledgedBy:7,...(resolve?{status:'RESOLVED'}:{})};
            return {[resolve?'resolved':'acknowledged']:1,receipts:rows};
        }
        const id=Number(path.split('/').at(-2)); state.rows=state.rows.map(r=>r.id===id?{...r,acknowledgedBy:7,...(resolve?{status:'RESOLVED'}:{})}:r);
        return state.rows.find(r=>r.id===id);
    }} };
export class DataTable {}
export const fmtDate = v=>String(v);
export const fmtNumber = v=>String(v);
export const errorModal = (title,message)=>{state.error = `${title}: ${message}`;};
