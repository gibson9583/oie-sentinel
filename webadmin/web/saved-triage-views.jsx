// OIE Sentinel. Published under the Mozilla Public License 2.0.
import {platform} from '@oie/web-shell';
import {readViews,writeViews,validateView,VIEW_PAGE_SIZES} from './triage-views.js';
const React=platform.React;
export function SavedTriageViews({storageKey,value,onLoad,onPageSizeChange}) {
    const [views,setViews]=React.useState([]),[name,setName]=React.useState(''),[selected,setSelected]=React.useState(''),[error,setError]=React.useState(null);
    const refresh=React.useCallback(()=>{try{setViews(readViews(localStorage,storageKey).views);setError(null);}catch(e){setError(e.message);}},[storageKey]);
    React.useEffect(()=>{refresh();const listener=e=>{if(e.key===storageKey)refresh();};window.addEventListener('storage',listener);return()=>window.removeEventListener('storage',listener);},[storageKey,refresh]);
    React.useEffect(()=>{
        if(!storageKey)return;
        try{writeViews(localStorage,storageKey,previous=>({...previous,last:validateView(value)}));setError(null);}catch(e){setError(`Preferences not saved: ${e.message}`);}
    },[storageKey,value]);
    const save=()=>{
        try {
            const label=name.trim();if(!label || label.length>80)throw new Error('Enter a view name (1–80 characters).');
            const next=writeViews(localStorage,storageKey,previous=>{
                const views=previous.views.filter(v=>v.name!==label);if(views.length>=20)throw new Error('Keep at most 20 saved views; remove one first.');
                return {...previous,views:[...views,{name:label,...validateView(value)}]};
            });setViews(next.views);setSelected(label);setError(null);
        }catch(e){setError(e.message);}
    };
    const remove=()=>{try{const next=writeViews(localStorage,storageKey,p=>({...p,views:p.views.filter(v=>v.name!==selected)}));setViews(next.views);setSelected('');setError(null);}catch(e){setError(e.message);}};
    const load=()=>{try{const current=readViews(localStorage,storageKey).views.find(v=>v.name===selected);if(!current)throw new Error('View changed in another tab; refresh the saved views.');onLoad(validateView(current));setError(null);}catch(e){refresh();setError(e.message);}};
    return <div className="panel mb-3"><div className="panel-body">
        <div className="sn-saved-views">
            <label>Saved view <select aria-label="Saved triage view" value={selected} onChange={e=>setSelected(e.target.value)} disabled={!storageKey}>
                <option value="">Choose a view</option>{views.map(v=><option key={v.name} value={v.name}>{v.name}</option>)}</select></label>
            <button className="btn btn-sm" disabled={!selected || !storageKey} onClick={load}>Load view</button>
            <button className="btn btn-sm" disabled={!selected || !storageKey} onClick={remove}>Delete selected view</button>
            <label>View name <input aria-label="Triage view name" maxLength={80} value={name} onChange={e=>setName(e.target.value)} disabled={!storageKey} /></label>
            <button className="btn btn-sm" disabled={!storageKey} onClick={save}>{views.some(v=>v.name===name.trim())?'Replace saved view':'Save current view'}</button>
            <label>Page size <select aria-label="Problems page size" value={value.pageSize} onChange={e=>onPageSizeChange(Number(e.target.value))}>
                {VIEW_PAGE_SIZES.map(n=><option key={n} value={n}>{n}</option>)}</select></label>
        </div>
        <p className="sn-hint">{storageKey?'Saved on this browser for your account and engine. Filters, sort and page size only; current server permissions apply on every load.':'Saved views unavailable; Problems remains usable without local preferences.'}</p>
        {error?<div role="status"><p className="text-err">{error}</p>
            {storageKey?<button className="btn btn-sm" onClick={()=>{try{localStorage.setItem(storageKey,JSON.stringify({version:1,last:null,views:[]}));setViews([]);setSelected('');setError(null);}catch(e){setError(e.message);}}}>Reset local saved views</button>:null}</div>:null}
    </div></div>;
}
