import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const {chromium,expect}=createRequire(process.env.SENTINEL_PLAYWRIGHT_PACKAGE || import.meta.url)('@playwright/test');
const dir=path.dirname(fileURLToPath(import.meta.url)),out=path.join(dir,'evidence');await mkdir(out,{recursive:true});
const browser=await chromium.launch(),results=[];
try{for(const width of [1440,390]){
 const page=await browser.newPage({viewport:{width,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:8770');
 const field=page.locator('.field').filter({has:page.locator('label',{hasText:'Minimum consecutive healthy evaluations'})}).locator('input');
 await expect(field).toHaveValue('3');await field.fill('0');await page.getByRole('button',{name:'Save changes',exact:true}).click();
 expect(await page.evaluate(()=>control.modal[1])).toContain('at least 1');await expect(field).toHaveValue('0');
 await field.fill('2');await page.getByRole('button',{name:'Test',exact:true}).click();
 const payload=await page.evaluate(()=>control.calls[0].payload);expect(JSON.parse(payload.configJson).minConsecutiveRecoveries).toBe(2);expect(JSON.parse(payload.configJson).thresholdPercent).toBe(5);
 await page.evaluate(()=>control.pending.shift().reject(new Error('offline')));await expect(field).toHaveValue('2');
 await expect(page.getByText('Latest saved trigger progress', {exact:false})).toBeVisible();
 await expect(page.getByText('recovery healthy 1/3',{exact:false})).toBeVisible();
 expect(errors).toEqual([]);expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)).toBe(false);
 await page.screenshot({path:path.join(out,`recovery-${width}.png`),fullPage:true});results.push({width,checks:6,errors,overflow:false});await page.close();
}}finally{await browser.close();await writeFile(path.join(out,'results.json'),JSON.stringify(results,null,2)+'\n');}
console.log(JSON.stringify(results));
