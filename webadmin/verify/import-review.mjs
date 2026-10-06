// Run: SENTINEL_PLAYWRIGHT_PACKAGE=/path/to/playwright/index.mjs node webadmin/verify/import-review.mjs
import { build } from 'esbuild';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const base=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const playwright=await import(process.env.SENTINEL_PLAYWRIGHT_PACKAGE || 'playwright');
const dir=await mkdtemp(path.join(tmpdir(),'sentinel-import-review-')); let browser,server;
try {
    const fixture=`import React from '${base}/node_modules/react/index.js';import{createRoot}from'${base}/node_modules/react-dom/client.js';import{ImportReview}from'${base}/web/import-review.jsx';
window.requests=[];window.confirmations=[];window.manage=true;
function App(){const[doc,setDoc]=React.useState({schemaVersion:1});window.changeDocument=setDoc;return React.createElement(ImportReview,{document:doc,name:'test.json',renderReceipt:r=>React.createElement('p',{},'Created '+r.created+', uncertain '+r.uncertain)});}
createRoot(document.getElementById('root')).render(React.createElement(App));`;
    await build({stdin:{contents:fixture,loader:'jsx',resolveDir:base},outfile:path.join(dir,'fixture.js'),bundle:true,format:'iife',plugins:[{name:'fixture',setup(b){
        b.onResolve({filter:/^@oie\//},args=>({path:args.path,namespace:'fixture'}));
        b.onResolve({filter:/^\.\/(api.js|ui.jsx)$/},args=>args.importer.endsWith('import-review.jsx')?({path:args.path,namespace:'fixture'}):undefined);
        b.onLoad({filter:/.*/,namespace:'fixture'},args=>({loader:'js',resolveDir:base,contents:args.path==='@oie/web-shell'?`import React from '${base}/node_modules/react/index.js';export const platform={React};`:
            args.path==='@oie/web-ui'?`export const confirmDialog=()=>new Promise(resolve=>window.confirmations.push(resolve));`:
            args.path==='./ui.jsx'?`export const canManage=()=>window.manage;export const fmtTime=v=>v;`:
            `export const errText=e=>e.message;export const previewImport=(document,mappings)=>new Promise((resolve,reject)=>window.requests.push({kind:'preview',document,mappings,resolve,reject}));export const applyReviewedImport=(document,mappings,preview)=>new Promise((resolve,reject)=>window.requests.push({kind:'apply',document,mappings,preview,resolve,reject}));`}));
    }}]});
    await writeFile(path.join(dir,'index.html'),'<!doctype html><meta name="viewport" content="width=device-width"><style>body{font:14px Arial;line-height:1.5;margin:16px;color:#253443}button{font:inherit;padding:8px}textarea{font:inherit}.flex{display:flex;gap:8px}.text-err{color:#a51f1f}button:focus-visible,summary:focus-visible{outline:2px solid #125ca5;outline-offset:2px}p,li{overflow-wrap:anywhere}</style><div id="root"></div><script src="/fixture.js"></script>');
    server=createServer(async(req,res)=>{try{res.setHeader('Content-Type',req.url.endsWith('.js')?'text/javascript':'text/html');res.end(await readFile(path.join(dir,req.url==='/fixture.js'?'fixture.js':'index.html')));}catch{res.statusCode=500;res.end();}});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));browser=await playwright.chromium.launch({headless:true});
    const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    const receipt={created:1,updated:0,skipped:0,uncertain:0,incomplete:false,entries:[{entityType:'MONITOR',name:'Rule',outcome:'CREATED',fieldDiffs:{scopeId:{before:null,after:'target'}}}]};
    const preview={targetFingerprint:'target-v1',planHash:'plan-v1',plan:receipt,remappings:[{path:'monitors[0].scopeId',type:'CHANNEL',source:'source',target:'target'}]};
    const reply=async(index,value)=>page.evaluate(({index,value})=>window.requests[index].resolve(value),{index,value});
    const previewButton=page.getByRole('button',{name:'Preview import',exact:true});const applyButton=page.getByRole('button',{name:'Apply reviewed changes',exact:true});
    await previewButton.click();await page.waitForFunction(()=>window.requests.length===1);assert.equal(await page.evaluate(()=>window.requests[0].kind),'preview');
    await reply(0,preview);await applyButton.waitFor();assert.equal(await applyButton.isEnabled(),true);
    await applyButton.click();await page.waitForFunction(()=>window.confirmations.length===1);assert.equal(await page.evaluate(()=>window.requests.length),1);
    await page.evaluate(()=>window.confirmations[0](true));await page.waitForFunction(()=>window.requests.length===2);
    await reply(1,{stale:true,preview:{...preview,targetFingerprint:'target-v2'}});
    await page.getByText(/Target configuration or import input changed/).waitFor();assert.equal(await page.getByRole('region').count(),0);
    await applyButton.click();await page.evaluate(()=>window.confirmations[1](true));await page.waitForFunction(()=>window.requests.length===3);
    const partial={...receipt,uncertain:1,entries:[...receipt.entries,{entityType:'ACTION',name:'Pager',outcome:'UNCERTAIN'}]};
    await reply(2,{stale:false,receipt:partial});await page.getByRole('region',{name:'Import receipt 1'}).waitFor();
    assert.equal(await applyButton.isDisabled(),true);assert.match(await page.locator('body').innerText(),/needs reconciliation/);
    await previewButton.click();await reply(3,preview);await applyButton.click();await page.evaluate(()=>window.confirmations[2](true));await page.waitForFunction(()=>window.requests.length===5);
    await page.evaluate(()=>window.requests[4].reject(new Error('connection lost')));await page.getByRole('alert').waitFor();
    assert.equal(await page.getByRole('region',{name:'Import receipt 1'}).count(),1);assert.equal(await applyButton.isDisabled(),true);
    assert.match(await page.getByRole('alert').innerText(),/Some changes may have completed/);
    // A document changed while confirmation is open must never use the old plan.
    await previewButton.click();await reply(5,preview);await applyButton.click();await page.waitForFunction(()=>window.confirmations.length===4);
    await page.evaluate(()=>{window.changeDocument({schemaVersion:1,monitors:[]});});await page.waitForTimeout(30);
    await page.evaluate(()=>window.confirmations[3](true));await page.waitForTimeout(30);assert.equal(await page.evaluate(()=>window.requests.length),6);
    await previewButton.click();await reply(6,{unexpected:true});await page.getByRole('alert').waitFor();assert.match(await page.getByRole('alert').innerText(),/Invalid preview response/);
    await page.getByText('Optional environment reference mapping',{exact:true}).click();await page.getByLabel('Mapping JSON (source ID → target ID)').fill('{"CHANNEL":{"source":"target"}}');
    await previewButton.click();await page.waitForFunction(()=>window.requests.length===8);assert.deepEqual(await page.evaluate(()=>window.requests[7].mappings),{CHANNEL:{source:'target'}});
    await reply(7,preview);await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await previewButton.focus();assert.equal(await previewButton.evaluate(node=>node===document.activeElement),true);
    assert.deepEqual(errors,[]);
    console.log('PASS: preview precedes writes, confirm pending lock, stale target revalidation, partial/uncertain receipts preserved, network failure reconciliation, stale-document confirmation, malformed preview, explicit mapping, keyboard focus, 390px overflow, zero page errors. Controlled host/API fixture, no live engine.');
}finally{if(browser)await browser.close();if(server)await new Promise(resolve=>server.close(resolve));await rm(dir,{recursive:true,force:true});}
