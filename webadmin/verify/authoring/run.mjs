import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
// Install @playwright/test locally, or supply an existing package root. The
// fixture uses real React and controlled host/API boundaries, no live engine.
const require=createRequire(process.env.SENTINEL_PLAYWRIGHT_PACKAGE || import.meta.url);
const {chromium}=require('@playwright/test');
const dir=path.dirname(fileURLToPath(import.meta.url));
const output=path.join(dir,'evidence');await mkdir(output,{recursive:true});
const browser=await chromium.launch();const records=[];
try {
    for (const width of [1440,390]) {
        const page=await browser.newPage({viewport:{width,height:1000}}),errors=[];
        page.on('pageerror',e=>errors.push(e.message));
        await page.goto(process.env.SENTINEL_AUTHORING_URL || 'http://127.0.0.1:8769');
        await page.waitForFunction(()=>typeof window.runAuthoringChecks==='function');
        const result=await page.evaluate(()=>window.runAuthoringChecks());
        if(result.overflow || errors.length)throw new Error(JSON.stringify({result,errors}));
        records.push({width,...result,errors});
        await page.evaluate(()=>window.mount('copy'));await page.waitForTimeout(100);
        await page.screenshot({path:path.join(output,`copy-${width}.png`),fullPage:true});
        await page.close();
    }
} finally {
    await browser.close();
    await writeFile(path.join(output,'results.json'),JSON.stringify(records,null,2)+'\n');
}
console.log(JSON.stringify(records));
