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
        stdin: { contents: await readFile(source, 'utf8') + "\nexport { platform, snapshot, refresh, SentinelCell, subscribeStatus, PROBLEM_STALE_MS, COVERAGE_STALE_MS };",
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


let now = 1_000_000;
const realNow = Date.now;
Date.now = () => now;
after(() => { Date.now = realNow; });
mod.platform.store.getState = () => ({ id: 1 });
const goodGet = async p => p.endsWith('/monitors') ? [{ enabled: true, scopeType: 'ALL' }]
    : p.endsWith('/problems') ? { items: [], total: 0 } : [];
const cell = (id = 'a') => nodes(mod.SentinelCell({ channelId: id }));
const clear = () => cell().some(n => n.props?.className?.includes('sn-clear'));
const unavailable = () => cell().some(n => n.props?.title?.includes('status unavailable'));
test('initial errors are unavailable, then successful retry restores health', async () => {
    mod.platform.api.get = async () => { throw new Error('offline'); };
    await mod.refresh();
    assert.equal(unavailable(), true);
    assert.equal(clear(), false);
    mod.platform.api.get = goodGet;
    for (let i = 0; i < 10; i++) await mod.refresh();
    assert.equal(clear(), true);
    assert.equal(mod.snapshot.problemsFailed, false);
    assert.equal(mod.snapshot.coverageFailed, false);
});
test('a failure after a clear snapshot cannot leave a healthy verdict', async () => {
    mod.platform.api.get = async () => { throw new Error('database offline'); };
    await mod.refresh();
    assert.equal(mod.snapshot.loaded, true);
    assert.equal(unavailable(), true);
    assert.equal(clear(), false);
    mod.platform.api.get = goodGet;
    await mod.refresh();
    assert.equal(clear(), true);
});
test('age limits invalidate problems and coverage separately', () => {
    now += mod.PROBLEM_STALE_MS + 1;
    assert.equal(clear(), false);
    mod.snapshot.problemsUpdatedAt = now;
    assert.equal(clear(), true);
    now += mod.COVERAGE_STALE_MS + 1;
    mod.snapshot.problemsUpdatedAt = now;
    assert.equal(clear(), false);
    mod.snapshot.byChannel.a = { count: 1, worst: 'HIGH' };
    assert.ok(cell().some(n => n.props?.title?.includes('1 open Sentinel problem')));
    delete mod.snapshot.byChannel.a;
});
test('one shared clock updates stale cells during blocked requests and is cleaned up', async () => {
    const realSet = globalThis.setInterval, realClear = globalThis.clearInterval;
    const timers = new Map(); let next = 0, notifications = 0, release;
    globalThis.setInterval = fn => { timers.set(++next, fn); return next; };
    globalThis.clearInterval = id => timers.delete(id);
    mod.snapshot.problemsUpdatedAt = mod.snapshot.coverageUpdatedAt = now;
    mod.platform.api.get = () => new Promise(resolve => { release = resolve; });
    let off1, off2;
    try {
        off1 = mod.subscribeStatus(() => notifications++);
        off2 = mod.subscribeStatus(() => notifications++);
        assert.equal(timers.size, 2); // one shared network poll, one local freshness clock
        now += mod.PROBLEM_STALE_MS + 1;
        const clock = [...timers.values()].find(fn => fn.name === 'notify');
        assert.ok(clock);
        clock();
        assert.equal(notifications, 2);
        assert.equal(unavailable(), true);
        off1(); off1 = null;
        assert.equal(timers.size, 2);
        off2(); off2 = null;
        assert.equal(timers.size, 0);
        release({ items: [], total: 0 });
        for (let i = 0; i < 12; i++) await Promise.resolve();
    } finally {
        off1?.(); off2?.();
        globalThis.setInterval = realSet; globalThis.clearInterval = realClear;
    }
});
