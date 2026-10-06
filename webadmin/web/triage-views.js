// OIE Sentinel. Published under the Mozilla Public License 2.0.
const SEVERITIES=['INFORMATION','WARNING','AVERAGE','HIGH','DISASTER'];
export const VIEW_PAGE_SIZES=[25,50,100];
export const VIEW_DEFAULTS={filters:{status:'PROBLEM',severity:[],channelId:'',monitorId:'',acknowledged:'',q:''},sort:{column:'opened_time',dir:'DESC'},pageSize:25};
export function viewStorageKey(context) {
    if(!context || typeof context.serverId!=='string' || !context.serverId || !Number.isInteger(context.userId) || context.userId<0)return null;
    return `sentinel:triage-views:v1:${encodeURIComponent(context.serverId)}:${context.userId}`;
}
export function validateView(value) {
    if(!value || typeof value!=='object')throw new Error('Invalid saved view.');
    const f=value.filters || {},sort=value.sort || {};
    const text=(v,max)=>{if(typeof v!=='string'||v.length>max)throw new Error('Invalid saved filter.');return v;};
    if(!['','PROBLEM','RESOLVED'].includes(f.status) || !['','true','false'].includes(f.acknowledged)
        || !Array.isArray(f.severity) || f.severity.length>5 || f.severity.some(s=>!SEVERITIES.includes(s))
        || !['severity','channel_id','opened_time'].includes(sort.column) || !['ASC','DESC'].includes(sort.dir)
        || !VIEW_PAGE_SIZES.includes(value.pageSize))throw new Error('Unsupported saved filter, sort or page size.');
    const monitorId=text(String(f.monitorId ?? ''),10);
    if(monitorId && (!/^[1-9][0-9]*$/.test(monitorId) || Number(monitorId)>2147483647))throw new Error('Invalid saved monitor filter.');
    return {filters:{status:f.status,severity:[...new Set(f.severity)],channelId:text(f.channelId,36),monitorId,
        acknowledged:f.acknowledged,q:text(f.q,300)},sort:{column:sort.column,dir:sort.dir},pageSize:value.pageSize};
}
export function readViews(storage,key) {
    if(!key)return {last:null,views:[]};
    const raw=storage.getItem(key);if(!raw)return {last:null,views:[]};
    const data=JSON.parse(raw);
    if(data?.version!==1 || !Array.isArray(data.views) || data.views.length>20)throw new Error('Saved views could not be read.');
    const views=data.views.map(view=>{
        if(typeof view?.name!=='string' || !view.name.trim() || view.name.length>80)throw new Error('Invalid saved view name.');
        return {name:view.name,...validateView(view)};
    });
    if(new Set(views.map(v=>v.name)).size!==views.length)throw new Error('Duplicate saved view names.');
    return {last:data.last?validateView(data.last):null,views};
}
export function writeViews(storage,key,change) {
    if(!key)throw new Error('Signed-in account context is unavailable.');
    // Read immediately before each synchronous write; separate tabs use last-writer-wins.
    const previous=readViews(storage,key),next=change(previous);
    const raw=JSON.stringify({version:1,last:next.last?validateView(next.last):null,
        views:next.views.map(v=>({name:v.name,...validateView(v)}))});
    const validated=readViews({getItem:()=>raw},key);
    storage.setItem(key,raw);
    return validated;
}
