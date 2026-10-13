// OIE Sentinel — adversarial regression against the production source graph.
import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(dir, "web/pages/maintenance.jsx");
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
    const state = [], refs = [];
    let index = 0;
    React.useState = initial => {
        const i = index++;
        state[i] = typeof initial === 'function' ? initial() : initial;
        return [state[i], value => { state[i] = typeof value === 'function' ? value(state[i]) : value; }];
    };
    React.useRef = initial => { const ref = { current: initial }; refs.push(ref); return ref; };
    mod.MaintenancePage();
    Object.assign(React, originalHooks);
    return { state, toggle: refs.find(r => typeof r.current?.toggle === 'function').current.toggle };
}
const stale = Object.freeze({ id: 7, enabled: true, name: 'Old name', scopeType: 'ALL', mode: 'SUPPRESS' });
for (const requested of [false, true]) {
    test(`sets explicit ${requested} without sending a stale schedule definition, including retries`, async () => {
        const posts = [], puts = [];
        mod.platform.checkTask = () => true;
        mod.platform.api.post = async (...args) => { posts.push(args); return { id: 7, enabled: requested }; };
        mod.platform.api.put = async (...args) => { puts.push(args); };
        const p = page();
        await p.toggle(stale, requested);
        await p.toggle(stale, requested);
        assert.deepEqual(posts, Array(2).fill([
            '/extensions/sentinel/maintenanceWindows/7/_setEnabled', null, { params: { enabled: requested } },
        ]));
        assert.deepEqual(puts, []);
        assert.equal(p.state[1], 2, 'each completed request refreshes the row');
        assert.equal(stale.enabled, true, 'rendered policy is never changed locally');
    });
}
test('revoked permission is checked again when an already-rendered toggle runs', async () => {
    let permitted = true, writes = 0;
    mod.platform.checkTask = () => permitted;
    mod.platform.api.post = async () => { writes++; };
    const p = page();
    permitted = false;
    await p.toggle(stale, false);
    assert.equal(writes, 0);
    assert.equal(p.state[1], 0);
});
test('a rejected request refreshes the checkbox and can be retried without a whole-row PUT', async () => {
    let fail = true, writes = 0, puts = 0;
    mod.platform.checkTask = () => true;
    mod.platform.api.post = async () => { writes++; if (fail) throw new Error('permission or network failure'); return {}; };
    mod.platform.api.put = async () => { puts++; };
    const p = page();
    await p.toggle(stale, false);
    assert.equal(p.state[1], 1);
    fail = false;
    await p.toggle(stale, false);
    assert.equal(p.state[1], 2);
    assert.equal(writes, 2);
    assert.equal(puts, 0);
});
