// OIE Sentinel — adversarial regression against the production source graph.
import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(dir, "web/ui.jsx");
const work = await mkdtemp(path.join(tmpdir(), 'sentinel-regression-'));
const outfile = path.join(work, 'bundle.mjs');
after(() => rm(work, { recursive: true, force: true }));
try {
    await build({
        stdin: { contents: await readFile(source, 'utf8') + "\nexport { platform };",
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


const original = { ...React };
class Hooks {
    slots = []; cursor = 0; effects = [];
    render(channelId, from = 0) {
        this.cursor = 0;
        React.useState = initial => {
            const i = this.cursor++;
            if (!(i in this.slots)) this.slots[i] = typeof initial === 'function' ? initial() : initial;
            return [this.slots[i], value => { this.slots[i] = typeof value === 'function' ? value(this.slots[i]) : value; }];
        };
        React.useCallback = fn => fn;
        React.useEffect = (fn, deps) => {
            const i = this.cursor++, old = this.slots[i];
            if (!old || deps.some((v, j) => !Object.is(v, old.deps[j]))) {
                this.effects.push(() => { old?.cleanup?.(); this.slots[i] = { deps, cleanup: fn() }; });
            }
        };
        return nodes(mod.ChannelActivityPanel({ channelId, from, to: 1000, title: `Activity ${channelId}` }));
    }
    async flush() {
        for (const fn of this.effects.splice(0)) fn();
        for (let i = 0; i < 12; i++) await Promise.resolve();
    }
    stop() {
        for (const slot of this.slots) slot?.cleanup?.();
        Object.assign(React, original);
    }
}
const response = channelId => ({ channelId, granularity: 'RAW', points: [{ time: 100, received: channelId === 'A' ? 17 : 3 }] });
const chart = rendered => rendered.find(n => n.type === mod.ActivityChart);
test('channel switch hides old data immediately and after failure, then shows successful retry', async () => {
    const h = new Hooks();
    try {
        let failB = true;
        mod.platform.api.get = async p => {
            const id = p.includes('/A/') ? 'A' : 'B';
            if (id === 'B' && failB) throw new Error('B unavailable');
            return response(id);
        };
        h.render('A'); await h.flush();
        assert.equal(chart(h.render('A')).props.points[0].received, 17);
        assert.equal(chart(h.render('B')), undefined);
        await h.flush();
        const failed = h.render('B');
        assert.equal(chart(failed), undefined);
        assert.ok(failed.includes('B unavailable'));
        failB = false;
        const retry = failed.find(n => n.type === 'button' && n.children.includes('Retry'));
        retry.props.onClick();
        h.render('B'); await h.flush();
        assert.equal(chart(h.render('B')).props.points[0].received, 3);
    } finally { h.stop(); }
});
test('same-channel refresh keeps its own series while fetching', async () => {
    const h = new Hooks();
    try {
        mod.platform.api.get = async () => response('A');
        h.render('A'); await h.flush();
        mod.platform.api.get = () => new Promise(() => {});
        assert.equal(chart(h.render('A', 1)).props.points[0].received, 17);
        await h.flush();
        assert.equal(chart(h.render('A', 1)).props.points[0].received, 17);
    } finally { h.stop(); }
});
test('an out-of-order previous-channel response cannot replace the current series', async () => {
    const h = new Hooks();
    let releaseA;
    try {
        mod.platform.api.get = p => p.includes('/A/')
            ? new Promise(resolve => { releaseA = resolve; }) : Promise.resolve(response('B'));
        h.render('A'); await h.flush();
        h.render('B'); await h.flush();
        releaseA(response('A')); await h.flush();
        assert.equal(chart(h.render('B')).props.points[0].received, 3);
    } finally { h.stop(); }
});
