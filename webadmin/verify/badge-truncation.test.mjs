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
        stdin: { contents: await readFile(source, 'utf8') + "\nexport { platform, snapshot, refresh, SentinelCell };",
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


const cell = () => nodes(mod.SentinelCell({ channelId: 'excluded-channel' }));
const isClear = () => cell().some(n => n.props?.className?.includes('sn-clear'));
mod.platform.store.getState = () => ({ id: 1 });
mod.platform.api.get = async p => p.endsWith('/dashboard/coverage')
    ? { coversAll: true, channelIds: [] }
    : p.endsWith('/problems')
        ? { items: Array.from({ length: 500 }, (_, id) => ({ id, channelId: 'other', severity: 'HIGH' })), total: 501 }
        : [];
test('truncation never establishes health for an excluded channel', async () => {
    await mod.refresh();
    assert.equal(mod.snapshot.truncated, true);
    assert.equal(isClear(), false);
    assert.ok(cell().some(n => n.props?.title?.includes('status incomplete')));
    assert.ok(nodes(mod.SentinelCell({ channelId: 'other' })).some(n => n.props?.title?.includes('500 open Sentinel problems')));
});
test('complete pages distinguish clear, not watched and unresolved coverage', () => {
    mod.snapshot.truncated = false;
    assert.equal(isClear(), true);
    mod.snapshot.coverage = { known: true, coversAll: false, covered: new Set() };
    assert.ok(cell().some(n => n.props?.className?.includes('sn-none')));
    mod.snapshot.coverage.known = false;
    assert.equal(isClear(), false);
    assert.ok(cell().some(n => n.props?.title === 'Sentinel is still loading'));
});
