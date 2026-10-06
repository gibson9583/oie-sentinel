// Render the shipped component with real React and controlled read-only API replies.
// Run: SENTINEL_PLAYWRIGHT_PACKAGE=/absolute/path/to/playwright/index.mjs node webadmin/verify/decision-inspector.mjs
import { build } from 'esbuild';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createServer } from 'node:http';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const webadmin = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const playwright = await import(process.env.SENTINEL_PLAYWRIGHT_PACKAGE || 'playwright');
const dir = await mkdtemp(path.join(tmpdir(), 'sentinel-inspector-'));
let browser, server;
try {
    const fixture = `import React from '${webadmin}/node_modules/react/index.js';
import { createRoot } from '${webadmin}/node_modules/react-dom/client.js';
import { DecisionInspector } from '${webadmin}/web/decision-inspector.jsx';
window.requests=[]; window.intents=[]; window.opened=[];
window.event={id:7,status:'PROBLEM',problemPending:true};
function App(){const [event,setEvent]=React.useState(window.event);window.setEvent=setEvent;return React.createElement(DecisionInspector,{event,onOpenProblem:id=>window.opened.push(id)});}
createRoot(document.getElementById('root')).render(React.createElement(App));`;
    await build({stdin:{contents:fixture,resolveDir:webadmin,loader:'jsx'}, bundle:true, format:'iife', outfile:path.join(dir,'fixture.js'),
        plugins:[{name:'controlled-host',setup(builder){
            builder.onResolve({filter:/^@oie\/web-shell$/},()=>({path:'shell',namespace:'fixture'}));
            builder.onResolve({filter:/^\.\/api.js$/,namespace:'file'},args=>args.importer.endsWith('decision-inspector.jsx')?({path:'api',namespace:'fixture'}):undefined);
            builder.onResolve({filter:/^\.\/ui.jsx$/,namespace:'file'},args=>args.importer.endsWith('decision-inspector.jsx')?({path:'ui',namespace:'fixture'}):undefined);
            builder.onResolve({filter:/^\.\/host.jsx$/,namespace:'file'},args=>args.importer.endsWith('decision-inspector.jsx')?({path:'host',namespace:'fixture'}):undefined);
            builder.onLoad({filter:/.*/,namespace:'fixture'},args=>({loader:'js',resolveDir:webadmin,contents:args.path==='shell'?`import React from '${webadmin}/node_modules/react/index.js'; export const platform={React,store:{setState:(key,value)=>window.intents.push(value)}};`:
                args.path==='api'?`export const errText=e=>e.message; export const inspectDecision=id=>new Promise((resolve,reject)=>window.requests.push({id,resolve,reject}));`:
                args.path==='ui'?`export const fmtTime=value=>value||'not available';`:`export const INTENT_KEY='sentinel:intent';`}));
        }}]});
    await writeFile(path.join(dir,'index.html'), '<!doctype html><meta name="viewport" content="width=device-width"><style>body{font:14px Arial;line-height:1.5;margin:16px;color:#253443}button{font:inherit;padding:8px}section{border:1px solid #c8d0d8;padding:16px}p,li{overflow-wrap:anywhere}.panel-header{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.text-err{color:#a51f1f}button:focus-visible,summary:focus-visible{outline:2px solid #125ca5;outline-offset:2px}</style><div id="root"></div><script src="/fixture.js"></script>');
    server = createServer(async(req,res)=>{try{res.setHeader('Content-Type',req.url.endsWith('.js')?'text/javascript':'text/html');res.end(await readFile(path.join(dir,req.url==='/fixture.js'?'fixture.js':'index.html')));}catch{res.statusCode=500;res.end();}});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    browser=await playwright.chromium.launch({headless:true});
    const page=await browser.newPage({viewport:{width:1440,height:1000}});
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.waitForFunction(()=>window.requests.length===1);
    const reply={observedAt:'2026-10-06T12:00:00Z',event:{id:7,status:'PROBLEM',problemPending:true},policy:{decision:'SUPPRESS',reasons:[{code:'DEPENDENCY_OPEN',parentEventId:88}]},matchingActions:[{id:3,name:'Pager',transport:'EMAIL',phase:'ON_PROBLEM'}],currentTrigger:{lastEvaluatedTime:'2026-10-06T11:59:00Z',state:'PROBLEM',consecutiveBreachCount:3},currentMinConsecutiveBreaches:3};
    await page.evaluate(reply=>window.requests[0].resolve(reply),reply);
    await page.getByText('Parent problem is still open on this channel').waitFor();
    await page.getByRole('button',{name:'Open parent problem #88'}).click();
    assert.deepEqual(await page.evaluate(()=>window.opened),[88]);
    await page.getByRole('button',{name:'Refresh decision'}).click();
    await page.evaluate(()=>window.requests[1].reject(new Error('Permission revoked')));
    await page.getByRole('alert').waitFor();
    assert.match(await page.getByRole('alert').textContent(),/Showing observation from/);
    // Change entity while a refresh is in flight: old completion must not replace the new observation.
    await page.getByRole('button',{name:'Refresh decision'}).click();
    await page.evaluate(()=>window.setEvent({id:9,status:'RESOLVED'}));
    await page.waitForFunction(()=>window.requests.length===4);
    await page.evaluate(reply=>{window.requests[3].resolve({...reply,event:{id:9,status:'RESOLVED'},policy:{decision:'ALLOW',reasons:[]}});},reply);
    await page.getByText('ALLOW',{exact:true}).waitFor();
    await page.evaluate(reply=>window.requests[2].resolve(reply),reply);
    assert.equal(await page.getByText('SUPPRESS',{exact:true}).count(),0);
    await page.getByText('Current evaluation and routing',{exact:true}).click();
    assert.match(await page.locator('body').innerText(),/Pending opened edge: no/);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.getByRole('button',{name:'Refresh decision'}).focus();
    assert.equal(await page.getByRole('button',{name:'Refresh decision'}).evaluate(node=>node===document.activeElement),true);
    await page.keyboard.press('Enter');
    await page.waitForFunction(()=>window.requests.length===5);
    await page.evaluate(()=>window.requests[4].resolve({unexpected:true}));
    await page.getByRole('alert').waitFor();
    assert.match(await page.getByRole('alert').textContent(),/Invalid decision response/);
    assert.deepEqual(errors,[]);
    console.log('PASS: read-only API fixture, parent navigation, refresh failure/permission change, stale response rejection, malformed response, keyboard refresh, 390px overflow, zero page errors. Fixture uses host-shaped CSS, not live host or engine.');
} finally {
    if(browser) await browser.close();
    if(server) await new Promise(resolve=>server.close(resolve));
    await rm(dir,{recursive:true,force:true});
}
