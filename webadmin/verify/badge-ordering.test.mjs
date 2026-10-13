// OIE Sentinel — adversarial regression against the production source graph.
import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(dir, "web/host.jsx");
const work = await mkdtemp(path.join(tmpdir(), 'sentinel-regression-'));
const outfile = path.join(work, 'bundle.mjs');
after(() => rm(work, { recursive: true, force: true }));
try {
    await build({
        stdin: { contents: await readFile(source, 'utf8') + "\nexport { platform, snapshot, refresh };",
            sourcefile: source, resolveDir: path.dirname(source), loader: 'jsx' },
        outfile, bundle: true, format: 'esm', target: 'es2022',
        jsxFactory: 'React.createElement', jsxFragment: 'React.Fragment',
        alias: {
            react: path.join(dir, 'web/react-shim.js'),
            'react-dom': path.join(dir, 'web/react-dom-shim.js'),
            '@oie/web-shell': path.join(dir, 'verify/stubs.js'),
            '@oie/web-ui': path.join(dir, 'verify/stubs.js'),
            '@oie/web-api': path.join(dir, 'verify/stubs.js'),
        },
    });
} catch (error) {
    await rm(work, { recursive: true, force: true });
    throw error;
}
const mod = await import(pathToFileURL(outfile).href);
const React = mod.platform.React;
React.createElement = (type, props, ...children) => ({ type, props: props || {}, children });
const nodes = (node) => node == null || typeof node === 'boolean' ? []
    : Array.isArray(node) ? node.flatMap(nodes)
        : [node, ...(typeof node === 'object' ? nodes(node.children) : [])];


mod.platform.store.getState = () => ({ id: 1 });
const coverage = p => p.endsWith('/dashboard/coverage') ? { coversAll: true, channelIds: [] } : [];
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
async function settle() { for (let i = 0; i < 12; i++) await Promise.resolve(); }
test('timer and remount refreshes join a pending poll instead of racing responses', async () => {
    const first = deferred();
    let problemCalls = 0;
    mod.platform.api.get = async p => p.endsWith('/problems')
        ? (++problemCalls === 1 ? first.promise : { items: [{ channelId: 'a', severity: 'DISASTER' }], total: 1 })
        : coverage(p);
    const initial = mod.refresh();
    await settle();
    assert.equal(mod.refresh(), initial);
    assert.equal(mod.refresh(), initial);
    assert.equal(problemCalls, 1);
    first.resolve({ items: [], total: 0 });
    await initial;
    await mod.refresh();
    assert.equal(problemCalls, 2);
    assert.equal(mod.snapshot.byChannel.a.worst, 'DISASTER');
});
test('the serialized refresh includes slow coverage reads', async () => {
    // Advance to the next periodic coverage refresh (poll count 10).
    mod.platform.api.get = async p => p.endsWith('/problems') ? { items: [], total: 0 } : coverage(p);
    for (let i = 2; i < 10; i++) await mod.refresh();
    const pending = deferred();
    let coverageCalls = 0, problemCalls = 0;
    mod.platform.api.get = async p => p.endsWith('/dashboard/coverage') ? (++coverageCalls, pending.promise)
        : p.endsWith('/problems') ? (++problemCalls, { items: [], total: 0 }) : [];
    const first = mod.refresh();
    assert.equal(mod.refresh(), first);
    assert.equal(coverageCalls, 1);
    assert.equal(problemCalls, 0);
    pending.resolve({ coversAll: true, channelIds: [] });
    await first;
    assert.equal(problemCalls, 1);
});
test('a failed request releases the poll for retry', async () => {
    mod.platform.api.get = async () => { throw new Error('offline'); };
    await mod.refresh();
    let calls = 0;
    mod.platform.api.get = async p => p.endsWith('/problems')
        ? (++calls, { items: [{ channelId: 'recovered', severity: 'HIGH' }], total: 1 }) : coverage(p);
    await mod.refresh();
    assert.equal(calls, 1);
    assert.equal(mod.snapshot.byChannel.recovered.count, 1);
});
