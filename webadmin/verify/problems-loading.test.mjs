// OIE Sentinel — adversarial regression against the production source graph.
import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(dir, "web/pages/problems.jsx");
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


const originalHooks = { ...React };
function page() {
    const state = [], callbacks = [];
    let index = 0;
    React.useState = initial => {
        const i = index++;
        state[i] = typeof initial === 'function' ? initial() : initial;
        return [state[i], value => { state[i] = typeof value === 'function' ? value(state[i]) : value; }];
    };
    React.useCallback = fn => { callbacks.push(fn); return fn; };
    mod.ProblemsPage();
    Object.assign(React, originalHooks);
    return { state, load: callbacks[0] };
}
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
for (const failed of [false, true]) {
    test(`a winning background ${failed ? 'failure' : 'success'} clears a superseded foreground spinner`, async () => {
        const p = page(), first = deferred();
        let calls = 0;
        mod.platform.api.get = () => ++calls === 1 ? first.promise
            : failed ? Promise.reject(new Error('offline')) : Promise.resolve({ items: [], total: 0 });
        const pending = p.load(false);
        assert.equal(p.state[5], true);
        await p.load(true);
        assert.equal(p.state[5], false);
        first.resolve({ items: [{ id: 'old' }], total: 1 });
        await pending;
        assert.equal(p.state[5], false);
        assert.equal(p.state[3]?.items?.length || 0, 0);
    });
}
test('a superseded background completion cannot clear a newer foreground spinner', async () => {
    const p = page(), background = deferred(), foreground = deferred();
    let calls = 0;
    mod.platform.api.get = () => ++calls === 1 ? background.promise : foreground.promise;
    const b = p.load(true), f = p.load(false);
    background.resolve({ items: [], total: 0 });
    await b;
    assert.equal(p.state[5], true);
    foreground.resolve({ items: [{ id: 2 }], total: 1 });
    await f;
    assert.equal(p.state[5], false);
    assert.equal(p.state[3].items[0].id, 2);
});
