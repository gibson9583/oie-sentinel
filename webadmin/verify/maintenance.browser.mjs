import {build} from 'esbuild';
import {chromium} from '@playwright/test';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {createServer} from 'node:http';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const dir=path.dirname(fileURLToPath(import.meta.url)),out=path.resolve(dir,'../../target/maintenance-browser');await mkdir(out,{recursive:true});
await build({entryPoints:[path.join(dir,'maintenance/entry.jsx')],outfile:path.join(out,'bundle.js'),bundle:true,format:'esm',
    alias:{'@oie/web-shell':path.join(dir,'maintenance/host.jsx'),'@oie/web-ui':path.join(dir,'maintenance/host.jsx')}});
await writeFile(path.join(out,'index.html'),'<style>body{font:14px Arial;margin:16px}.field{display:block;margin:12px 0}.field input,.field select{display:block}button{padding:6px;margin:4px}.text-err{color:#a12323}.panel{max-width:700px}p{overflow-wrap:anywhere}</style><div id="root"></div><script type="module" src="/bundle.js"></script>');
const server=createServer(async(req,res)=>{res.setHeader('Content-Type',req.url==='/bundle.js'?'text/javascript':'text/html');res.end(await readFile(path.join(out,req.url==='/bundle.js'?'bundle.js':'index.html')));});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try {
    browser=await chromium.launch({headless:true,...(process.env.MAINTENANCE_BROWSER?{executablePath:process.env.MAINTENANCE_BROWSER}:{})});
    const page=await browser.newPage({viewport:{width:1280,height:800}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
    const visit=()=>page.goto(`http://127.0.0.1:${server.address().port}`);
    await visit();await page.getByRole('button',{name:'Mute this channel temporarily'}).click();
    await page.getByRole('button',{name:'Review channel maintenance'}).click();await page.getByRole('alert').waitFor();
    assert.equal(await page.evaluate(()=>window.maintenance.writes.length),0);
    await page.getByLabel('Channel maintenance duration').selectOption('custom');
    await page.getByLabel('Maintenance minutes').fill('45');await page.getByLabel('Channel maintenance reason').fill('Repair destination');
    const start=Date.now();await page.getByRole('button',{name:'Review channel maintenance'}).click();
    await page.getByText('Scope:',{exact:false}).waitFor();
    assert.equal(await page.getByText('This affects every monitor on this channel.',{exact:false}).isVisible(),true);
    await page.screenshot({path:path.join(out,'review-desktop.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.screenshot({path:path.join(out,'review-mobile.png'),fullPage:true});
    await page.evaluate(()=>window.maintenance.hold=true);
    await page.getByRole('button',{name:'Mute channel until expiry'}).click();await page.waitForFunction(()=>window.maintenance.release);
    assert.equal(await page.getByRole('button',{name:'Applying…'}).isDisabled(),true);
    await page.evaluate(()=>window.maintenance.release());await page.getByRole('alert').waitFor();
    const first=await page.evaluate(()=>window.maintenance.writes[0].body);
    assert.ok(first.untilMillis>=start+45*60000 && first.untilMillis<=Date.now()+45*60000);
    await page.evaluate(()=>{window.maintenance.mode='success';window.maintenance.hold=false;});
    await page.getByRole('button',{name:'Check / retry same request'}).click();await page.getByText('Existing request reconciled',{exact:false}).waitFor();
    assert.deepEqual(await page.evaluate(()=>window.maintenance.writes[1].body),first);
    await page.getByRole('button',{name:'Cancel this maintenance window'}).click();await page.getByText('Cancelled (disabled)',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Find and manage in Schedules'}).click();assert.equal(await page.evaluate(()=>window.maintenance.intent.kind),'schedules');
    assert.deepEqual(errors,[]);
    // Action-time permission revocation after review cannot write.
    await visit();await page.getByRole('button',{name:'Mute this channel temporarily'}).click();
    await page.getByLabel('Channel maintenance reason').fill('Planned repair');await page.getByRole('button',{name:'Review channel maintenance'}).click();
    await page.evaluate(()=>window.maintenance.permission=false);
    await page.getByRole('button',{name:'Mute channel until expiry'}).click();assert.equal(await page.evaluate(()=>window.maintenance.writes.length),0);
    console.log('PASS: required reason, custom duration, scope/expiry preview, narrow screen, pending guard, malformed response, frozen idempotent retry, cancellation, schedules intent, action-time permission');
} finally {if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
