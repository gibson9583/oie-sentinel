import {build} from 'esbuild';
import {chromium} from '@playwright/test';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {createServer} from 'node:http';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
const dir=path.dirname(fileURLToPath(import.meta.url));
const out=path.resolve(dir,'../../target/triage-browser'); await mkdir(out,{recursive:true});
await build({entryPoints:[path.join(dir,'triage/entry.jsx')],outfile:path.join(out,'bundle.js'),bundle:true,format:'esm',
    alias:{'@oie/web-shell':path.join(dir,'triage/host.jsx'),'@oie/web-ui':path.join(dir,'triage/host.jsx')}});
const plugin=await readFile(path.join(dir,'../web/plugin.jsx'),'utf8');
const css=plugin.match(/const SENTINEL_CSS = `([\s\S]*?)`;/)[1];
await writeFile(path.join(out,'index.html'),`<style>:root{--accent:#1764b0;--text:#222;--text-dim:#555;--text-faint:#555;--line:#ccc;--bg1:#fff;--bg2:#f8f8f8;--err:#aa2233;--warn:#775500}body{font:14px Arial;margin:16px}.dt{width:100%;border-collapse:collapse}.dt td,.dt th{padding:8px;border-bottom:1px solid var(--line);text-align:left}.panel{border:1px solid var(--line);margin-bottom:12px}.panel-body,.panel-header{padding:12px}.flex{display:flex;gap:8px}.btn{padding:6px}.sn-filterbar label{display:block}${css}</style><div id="root" class="sn-view"></div><script type="module" src="/bundle.js"></script>`);
const server=createServer(async(req,res)=>{try{const file=req.url==='/bundle.js'?'bundle.js':'index.html';res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':'text/html');res.end(await readFile(path.join(out,file)));}catch{res.statusCode=500;res.end();}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({headless:true, ...(process.env.TRIAGE_BROWSER ? {executablePath:process.env.TRIAGE_BROWSER} : {})});
const page=await browser.newPage({viewport:{width:1440,height:1000}});
const errors=[];page.on('pageerror',e=>errors.push(e.message));
const waitText=async text=>{await page.getByText(text,{exact:false}).first().waitFor();};
try {
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.getByRole('checkbox',{name:'Select problem #1',exact:true}).waitFor();
    assert.deepEqual(await page.evaluate(()=>({page:window.triage.queries[0].page,pageSize:window.triage.queries[0].pageSize,sort:window.triage.queries[0].sort,sortDir:window.triage.queries[0].sortDir})),
        {page:0,pageSize:25,sort:'opened_time',sortDir:'DESC'});
    // Keyboard selection, independent queue/detail freshness, and focus return.
    await page.getByRole('checkbox',{name:'Select problem #1',exact:true}).focus();await page.keyboard.press('Space');
    assert.equal(await page.getByRole('checkbox',{name:'Select problem #1',exact:true}).isChecked(),true);
    await page.getByRole('button',{name:/Open problem #1/}).focus();await page.keyboard.press('Enter');
    await waitText('Captured evidence');
    await page.evaluate(()=>window.triage.failDetail=true);
    await page.locator('.sn-problem-detail .sn-freshness button').click();await waitText('Detail offline');
    assert.equal(await page.locator('.sn-problem-detail').getByText('Runbook',{exact:true}).isVisible(),true);
    assert.equal(await page.locator('.sn-problem-queue').getByText('Refresh failed:',{exact:false}).count(),0);
    await page.evaluate(()=>window.triage.failList=true);
    await page.locator('.sn-problem-queue .sn-freshness button').click();await waitText('Queue offline');
    await page.screenshot({path:path.join(out,'desktop.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.locator('.sn-problem-queue').isVisible(),false);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.screenshot({path:path.join(out,'mobile.png'),fullPage:true});
    await page.getByRole('button',{name:'Close detail',exact:true}).click();
    await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-label')?.startsWith('Open problem #1'));
    await page.setViewportSize({width:1440,height:1000});
    await page.evaluate(()=>{window.triage.failList=false;window.triage.failDetail=false;});
    await page.locator('.sn-problem-queue .sn-freshness button').click();
    await page.getByRole('checkbox',{name:'Select problem #2',exact:true}).check();
    // Pending lock begins before confirmation and survives an outstanding write.
    await page.getByRole('button',{name:'Acknowledge selected',exact:true}).click();
    assert.equal(await page.getByRole('button',{name:'Resolve selected',exact:true}).isDisabled(),true);
    await page.evaluate(()=>window.triage.holdWrite=true);
    await page.getByRole('dialog').getByRole('button',{name:'Acknowledge',exact:true}).click();
    await page.waitForFunction(()=>window.triage.releaseWrite);
    assert.equal(await page.getByRole('checkbox',{name:'Select problem #1',exact:true}).isDisabled(),true);
    await page.evaluate(()=>window.triage.releaseWrite());await waitText('2 requested; 1 applied');
    assert.equal(await page.getByRole('checkbox',{name:'Select problem #2',exact:true}).isChecked(),true);
    assert.equal(await page.getByRole('checkbox',{name:'Select problem #1',exact:true}).isChecked(),false);
    // Malformed receipts lock retries until direct per-ID state reconciliation.
    await page.evaluate(()=>{window.triage.holdWrite=false;window.triage.mode='malformed';});
    await page.getByRole('button',{name:'Resolve selected',exact:true}).click();
    await page.getByRole('dialog').getByRole('button',{name:'Resolve',exact:true}).click();
    await waitText('applied count unknown');
    assert.equal(await page.getByRole('button',{name:'Resolve selected',exact:true}).isDisabled(),true);
    await page.getByRole('button',{name:'Check current problem states'}).click();
    await waitText('Current state checked');
    assert.equal(await page.getByRole('button',{name:'Resolve selected',exact:true}).isDisabled(),false);
    // Permission changed while modal was open: no API mutation.
    const writes=await page.evaluate(()=>window.triage.writes);
    await page.getByRole('button',{name:'Resolve selected',exact:true}).click();
    await page.evaluate(()=>window.triage.permission=false);
    await page.getByRole('dialog').getByRole('button',{name:'Resolve',exact:true}).click();
    assert.equal(await page.evaluate(()=>window.triage.writes),writes);
    await page.evaluate(()=>window.triage.permission=true);
    // A single malformed mutation exposes its uncertain receipt on mobile.
    await page.getByRole('button',{name:/Open problem #2/}).click();
    await page.setViewportSize({width:390,height:844});
    await page.locator('.sn-problem-detail').getByRole('button',{name:'Resolve',exact:true}).click();
    await page.getByRole('dialog').getByRole('button',{name:'Resolve',exact:true}).click();
    await page.locator('.sn-problem-detail').getByText('applied count unknown',{exact:false}).waitFor();
    assert.equal(await page.locator('.sn-problem-detail').getByRole('button',{name:'Resolve',exact:true}).isDisabled(),true);
    await page.locator('.sn-problem-detail').getByRole('button',{name:'Check current problem states'}).click();
    await page.locator('.sn-problem-detail').getByText('Current state checked',{exact:false}).waitFor();
    await page.getByRole('button',{name:'Close detail',exact:true}).click();
    await page.setViewportSize({width:1440,height:1000});
    await page.evaluate(()=>window.triage.holdList=true);
    await page.locator('.sn-problem-queue .sn-freshness button').click();
    await page.waitForFunction(()=>window.triage.pendingLists.length===1);
    // A filter change invalidates even a response arriving during debounce.
    const search=page.getByPlaceholder('Search message…');
    await search.fill('Second');
    await page.evaluate(()=>{window.triage.holdList=false;window.triage.pendingLists[0].resolve({items:[{id:99,message:'STALE RESPONSE'}],total:1});});
    await page.waitForFunction(()=>window.triage.queries.at(-1).q==='Second');
    await page.getByRole('button',{name:/Open problem #2/}).waitFor();
    assert.equal(await page.getByText('STALE RESPONSE').count(),0);
    await page.evaluate(()=>window.triage.total=60);
    await page.locator('.sn-problem-queue .sn-freshness button').click();
    await page.getByRole('button',{name:'Next',exact:true}).click();
    await page.waitForFunction(()=>window.triage.queries.at(-1).page===1);
    await page.getByRole('button',{name:'Severity',exact:true}).click();
    await page.waitForFunction(()=>window.triage.queries.at(-1).sort==='severity' && window.triage.queries.at(-1).page===0);
    await page.getByRole('button',{name:'Severity (DESC)',exact:true}).click();
    await page.waitForFunction(()=>window.triage.queries.at(-1).sortDir==='ASC');
    assert.deepEqual(errors,[]);
    console.log('PASS: keyboard, desktop/mobile, focus return, independent stale indicators, pending locks, partial receipts, malformed reconciliation, action-time permission, stale filter ordering');
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
