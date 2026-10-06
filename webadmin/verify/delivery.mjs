// Controlled host/API fixture; no live engine or external notification transport.
import { build } from 'esbuild';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const base=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const playwright=await import(process.env.SENTINEL_PLAYWRIGHT_PACKAGE || 'playwright');
const dir=await mkdtemp(path.join(tmpdir(),'sentinel-delivery-'));let browser,server;
try {
 const fixture=`import React from '${base}/node_modules/react/index.js';import{createRoot}from'${base}/node_modules/react-dom/client.js';import{DeliveryInbox}from'${base}/web/pages/delivery.jsx';import{ActionEditor}from'${base}/web/pages/actions.jsx';window.requests=[];window.intents=[];window.mode='inbox';window.renderAction=()=>createRoot(document.getElementById('action')).render(React.createElement(ActionEditor,{action:{id:1,name:'Saved mail',actionType:'EMAIL',enabled:true,operationMode:'ON_PROBLEM',configJson:'{"to":"saved@example.org"}'},actions:[],manage:true,onClose:()=>{},onSaved:()=>{}}));createRoot(document.getElementById('root')).render(React.createElement(DeliveryInbox));`;
 await build({stdin:{contents:fixture,loader:'jsx',resolveDir:base},outfile:path.join(dir,'fixture.js'),bundle:true,format:'iife',plugins:[{name:'fixture',setup(b){
 b.onResolve({filter:/^@oie\//},args=>({path:args.path,namespace:'fixture'}));
 b.onResolve({filter:/^\.\.\/(api.js|ui.jsx|host.jsx)$/},args=>/pages\/(delivery|actions).jsx$/.test(args.importer)?({path:args.path,namespace:'fixture'}):undefined);
 b.onLoad({filter:/.*/,namespace:'fixture'},args=>({loader:'js',resolveDir:base,contents:
 args.path==='@oie/web-shell'?`import React from '${base}/node_modules/react/index.js';export const platform={React,ui:{h:React.createElement},store:{setState:(key,intent)=>window.intents.push(intent)}};`:
 args.path==='@oie/web-ui'?`export const errorModal=()=>{};export const confirmDialog=async()=>true;`:
 args.path==='../host.jsx'?`export const INTENT_KEY='intent';`:
 args.path==='../ui.jsx'?`export const fmtTime=v=>v;export const canManage=()=>true;export const toast=()=>{};export const useApi=()=>{};export const useDataTable=()=>{};export const ConditionBuilder=()=>null;export const ChannelPicker=()=>null;export const CONDITION_FIELDS={};`:
 `export const errText=e=>e.message;const request=(kind,params)=>new Promise((resolve,reject)=>window.requests.push({kind,params,resolve,reject}));export const getDeliveries=params=>request('attempts',params);export const getPendingDeliveries=params=>request('pending',params);export const testAction=id=>request('test',id);export const listActions=()=>{};export const createAction=()=>{};export const updateAction=()=>{};export const deleteAction=()=>{};`}));
 }}]});
 await writeFile(path.join(dir,'index.html'),'<!doctype html><meta name="viewport" content="width=device-width"><style>body{font:14px Arial;line-height:1.5;margin:16px;color:#253443}button,input,select{font:inherit;max-width:100%;box-sizing:border-box}button{padding:8px}table{border-collapse:collapse;width:100%}td,th{padding:8px;text-align:left}p{overflow-wrap:anywhere}.form-row,.form-grid{display:flex;flex-wrap:wrap;gap:12px}.field{max-width:100%;display:flex;flex-direction:column}button:focus-visible{outline:2px solid #125ca5}</style><div id="root"></div><div id="action"></div><script src="/fixture.js"></script>');
 server=createServer(async(req,res)=>{res.setHeader('Content-Type',req.url.endsWith('.js')?'text/javascript':'text/html');res.end(await readFile(path.join(dir,req.url==='/fixture.js'?'fixture.js':'index.html')));});await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 browser=await playwright.chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(`http://127.0.0.1:${server.address().port}`);
 const reply=async(index,value)=>page.evaluate(({index,value})=>window.requests[index].resolve(value),{index,value});
 const attempt={attempt:{id:1,alertEventId:10,actionId:null,actionIdAtAttempt:7,dispatchTime:'2026-10-06T12:00:00Z',success:false,errorMessage:'timeout'},channelId:'channel-a',eventStatus:'RESOLVED',actionName:'Deleted mail',actionDeleted:true,actionContextSource:'CAPTURED'};
 await page.waitForFunction(()=>window.requests.length===1);await reply(0,{items:[attempt],total:26});await page.getByText('Action deleted',{exact:true}).waitFor();assert.match(await page.locator('body').innerText(),/Transport not recorded \/ Phase not recorded/);
 await page.getByRole('button',{name:'Open problem #10',exact:true}).click();assert.equal(await page.evaluate(()=>window.intents[0].problemId),10);
 await page.getByRole('button',{name:'Next page',exact:true}).click();await page.waitForFunction(()=>window.requests.length===2);assert.equal(await page.evaluate(()=>window.requests[1].params.page),1);await reply(1,{items:[attempt],total:26});
 await page.getByRole('button',{name:'Refresh delivery',exact:true}).click();await page.waitForFunction(()=>window.requests.length===3);await page.evaluate(()=>window.requests[2].reject(new Error('permission changed')));await page.getByRole('alert').waitFor();assert.match(await page.getByRole('alert').innerText(),/Showing page observed/);
 await page.getByLabel('Action ID',{exact:true}).fill('7');await page.waitForFunction(()=>window.requests.length===4);await page.getByLabel('Recorded transport').selectOption('UNKNOWN');await page.waitForFunction(()=>window.requests.length===5);
 await reply(4,{items:[],total:0});await reply(3,{items:[attempt],total:1});await page.waitForTimeout(20);assert.equal(await page.getByText('No matching transport attempts.',{exact:true}).count(),1);
 await page.getByRole('button',{name:'Pending lifecycle work',exact:true}).click();await page.waitForFunction(()=>window.requests.length===6);assert.equal(await page.evaluate(()=>window.requests[5].kind),'pending');assert.equal(await page.evaluate(()=>Object.hasOwn(window.requests[5].params,'actionId')),false);
 await reply(5,{items:[{id:10,channelId:'channel-a',status:'PROBLEM',problemPending:true,resolutionPending:false}],total:1});await page.getByText('Opened edge',{exact:true}).waitFor();assert.match(await page.locator('body').innerText(),/does not establish a send is queued or due/);
 await page.getByRole('button',{name:'Refresh delivery',exact:true}).click();await page.waitForFunction(()=>window.requests.length===7);await reply(6,{items:[null],total:1});await page.getByRole('alert').waitFor();assert.match(await page.getByRole('alert').innerText(),/Invalid delivery page/);
 await page.evaluate(()=>window.renderAction());await page.getByText(/Test uses saved EMAIL/).waitFor();assert.match(await page.locator('#action').innerText(),/saved@example.org/);
 await page.locator('#action input').first().fill('Unsaved name');await page.getByText(/Unsaved changes are excluded/).waitFor();await page.getByRole('button',{name:'Send test',exact:true}).click();await page.waitForFunction(()=>window.requests.length===8);assert.equal(await page.evaluate(()=>window.requests[7].params),1);
 assert.equal(await page.getByRole('button',{name:'Sending…',exact:true}).isDisabled(),true);
 await reply(7,{success:true,message:'accepted',transport:'EMAIL',actionName:'Changed on server',destination:'To: current@example.org'});await page.getByText(/Used saved EMAIL action/).waitFor();assert.match(await page.locator('#action').innerText(),/current@example.org/);
 await page.getByRole('button',{name:'Send test',exact:true}).click();await page.waitForFunction(()=>window.requests.length===9);await page.evaluate(()=>window.requests[8].reject(new Error('lost connection')));await page.locator('#action [role=alert]').waitFor();assert.match(await page.locator('#action [role=alert]').innerText(),/external outcome unknown/);
 await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.getByRole('button',{name:'Send test',exact:true}).focus();assert.equal(await page.getByRole('button',{name:'Send test',exact:true}).evaluate(node=>node===document.activeElement),true);assert.deepEqual(errors,[]);
 console.log('PASS: read-only inbox, deleted/captured and unknown context, event navigation, paging, permission failure with observation time, stale filter responses, pending distinct, malformed rows, saved destination, unsaved warning, server-authoritative transport receipt, duplicate pending guard, uncertain loss, keyboard, 390px, zero page errors. No live engine.');
}finally{if(browser)await browser.close();if(server)await new Promise(resolve=>server.close(resolve));await rm(dir,{recursive:true,force:true});}
