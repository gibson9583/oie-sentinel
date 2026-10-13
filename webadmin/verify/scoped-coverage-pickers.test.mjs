import { after, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';

const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixture = {
    allowedChannelId: 'allowed',
    monitors: [],
    coverage: { coversAll: false, channelIds: ['allowed'] },
    problems: { items: [], total: 0 },
};
const source = path.join(dir, 'web/host.jsx');
const work = await mkdtemp(path.join(tmpdir(), 'sentinel-scoped-coverage-'));
const outfile = path.join(work, 'bundle.mjs');
after(() => rm(work, { recursive: true, force: true }));
try {
    await build({
        stdin: { contents: await readFile(source, 'utf8') + '\nexport { platform, snapshot, refresh, refreshCoverage, SentinelCell };\nexport { coverageFrom, ChannelPicker, ChannelGroupPicker, MonitorPicker, TagPicker } from "./ui.jsx";',
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
} catch (error) { await rm(work, { recursive: true, force: true }); throw error; }
const mod = await import(pathToFileURL(outfile).href);
mod.platform.React.createElement = (type, props, ...children) => ({ type, props: props || {}, children });
mod.platform.store.getState = () => ({ id: 42 });
const nodes = node => node == null || typeof node === 'boolean' ? []
    : Array.isArray(node) ? node.flatMap(nodes)
        : [node, ...(typeof node === 'object' ? nodes(node.children) : [])];
const cell = id => nodes(mod.SentinelCell({ channelId: id || fixture.allowedChannelId }));

test('restricted coverage response renders the monitored badge despite an empty definitions list', async () => {
    assert.equal(fixture.monitors.length, 0);
    const calls = [];
    mod.platform.api.get = async endpoint => {
        calls.push(endpoint);
        if (endpoint.endsWith('/dashboard/coverage')) return fixture.coverage;
        if (endpoint.endsWith('/problems')) return fixture.problems;
        throw new Error('Unexpected request ' + endpoint);
    };
    await mod.refresh();
    assert.ok(cell().some(node => node.props?.className?.includes('sn-clear')));
    assert.ok(calls.some(endpoint => endpoint.endsWith('/dashboard/coverage')));
    assert.equal(calls.some(endpoint => endpoint.endsWith('/monitors')), false);
});

test('channel outside authorized coverage does not get a monitored badge', () => {
    assert.equal(cell('outside-coverage').some(node => node.props?.className?.includes('sn-clear')), false);
});

test('coverage request failure marks a previously clear badge unavailable; retry restores it', async () => {
    mod.platform.api.get = async () => { throw new Error('coverage unavailable'); };
    await mod.refreshCoverage();
    assert.ok(cell().some(node => node.props?.title?.includes('status unavailable')));
    assert.equal(cell().some(node => node.props?.className?.includes('sn-clear')), false);
    mod.platform.api.get = async () => fixture.coverage;
    await mod.refreshCoverage();
    assert.ok(cell().some(node => node.props?.className?.includes('sn-clear')));
});

for (const [name, row, visible, hidden] of [
    ['ChannelPicker', { channelId: 'visible', name: 'Visible' }, 'visible', 'hidden'],
    ['ChannelGroupPicker', { id: 'visible', name: 'Visible' }, 'visible', 'hidden'],
    ['MonitorPicker', { id: 11, name: 'Visible' }, '11', '22'],
    ['TagPicker', { id: 'visible', name: 'Visible' }, 'visible', 'hidden'],
]) {
    test(name + ' retains hidden saved selections alongside visible options without duplicating IDs', () => {
        const original = mod.platform.React.useState;
        mod.platform.React.useState = init => [init && typeof init === 'object' && 'loading' in init
            ? { data: [row], error: null, loading: false } : init, () => {}];
        try {
            let value;
            const tree = mod[name]({ multiple: true, value: [visible, hidden], onChange: next => { value = next; } });
            const options = nodes(tree).filter(node => node.type === 'option');
            assert.deepEqual(options.map(option => String(option.props.value)), [visible, hidden]);
            const select = nodes(tree).find(node => node.type === 'select');
            select.props.onChange({ target: { selectedOptions: [{ value: visible }, { value: hidden }] } });
            assert.deepEqual(value, [visible, hidden]);
            select.props.onChange({ target: { selectedOptions: [{ value: visible }] } });
            assert.deepEqual(value, [visible]);
        } finally { mod.platform.React.useState = original; }
    });
    test(name + ' represents a hidden single selection without replacing its saved value', () => {
        const original = mod.platform.React.useState;
        mod.platform.React.useState = init => [init && typeof init === 'object' && 'loading' in init
            ? { data: [row], error: null, loading: false } : init, () => {}];
        try {
            const tree = mod[name]({ value: hidden });
            assert.ok(nodes(tree).some(node => node.type === 'option' && node.props.value === hidden));
            assert.equal(nodes(tree).find(node => node.type === 'select').props.value, hidden);
        } finally { mod.platform.React.useState = original; }
    });
}
