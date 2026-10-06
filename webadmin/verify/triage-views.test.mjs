import {test} from 'node:test';import assert from 'node:assert/strict';
import {viewStorageKey,validateView,readViews,writeViews,VIEW_DEFAULTS} from '../web/triage-views.js';
const memory=()=>{const data=new Map();return {getItem:key=>data.get(key)??null,setItem:(key,value)=>data.set(key,value),data};};
test('engine and user namespaces are distinct and anonymous identities disabled',()=>{
    assert.notEqual(viewStorageKey({serverId:'engine-a',userId:7}),viewStorageKey({serverId:'engine-b',userId:7}));
    assert.notEqual(viewStorageKey({serverId:'engine-a',userId:7}),viewStorageKey({serverId:'engine-a',userId:8}));assert.equal(viewStorageKey(null),null);
});
test('persist only explicit filters, sort and page size, never incident payloads or credentials',()=>{
    const cleaned=validateView({...VIEW_DEFAULTS,secret:'password',event:{message:'payload'},filters:{...VIEW_DEFAULTS.filters,password:'secret'}});
    assert.deepEqual(Object.keys(cleaned),['filters','sort','pageSize']);assert.equal(JSON.stringify(cleaned).includes('secret'),false);assert.equal(JSON.stringify(cleaned).includes('payload'),false);
});
test('invalid status, severity, sort, monitor, oversized query and page size rejected',()=>{
    for(const changed of [{pageSize:1000},{sort:{column:'secret',dir:'ASC'}},{sort:{column:'severity',dir:'INVALID'}},
        {filters:{...VIEW_DEFAULTS.filters,status:'HEALTHY'}},{filters:{...VIEW_DEFAULTS.filters,severity:['FAKE']}},
        {filters:{...VIEW_DEFAULTS.filters,monitorId:'-1'}},{filters:{...VIEW_DEFAULTS.filters,q:'x'.repeat(301)}}])assert.throws(()=>validateView({...VIEW_DEFAULTS,...changed}));
});
test('writes read fresh storage and preserve other saved views and last preferences',()=>{
    const storage=memory(),key='views';writeViews(storage,key,p=>({...p,views:[{name:'First',...VIEW_DEFAULTS}]}));
    writeViews(storage,key,p=>({...p,last:{...VIEW_DEFAULTS,pageSize:100},views:[...p.views,{name:'Second',...VIEW_DEFAULTS}]}));
    assert.deepEqual(readViews(storage,key).views.map(v=>v.name),['First','Second']);assert.equal(readViews(storage,key).last.pageSize,100);
});
test('corrupt or unsupported version does not apply preferences',()=>{
    const storage=memory();for(const raw of ['{','null',JSON.stringify({version:2,views:[]}),JSON.stringify({version:1,views:[{name:'A',...VIEW_DEFAULTS},{name:'A',...VIEW_DEFAULTS}]})]){storage.setItem('views',raw);assert.throws(()=>readViews(storage,'views'));}
});
test('storage failure propagates and cannot pretend preferences were saved',()=>{
    assert.throws(()=>writeViews({getItem:()=>null,setItem:()=>{throw new Error('Storage blocked');}},'views',p=>({...p,last:VIEW_DEFAULTS})),/Storage blocked/);
});
