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
    function render() {
        let stateIndex = 0, refIndex = 0;
        React.useState = initial => {
            const i = stateIndex++;
            if (!(i in state)) state[i] = typeof initial === 'function' ? initial() : initial;
            return [state[i], value => { state[i] = typeof value === 'function' ? value(state[i]) : value; }];
        };
        React.useRef = initial => {
            const i = refIndex++;
            return refs[i] || (refs[i] = { current: initial });
        };
        try { mod.MaintenancePage(); }
        finally { Object.assign(React, originalHooks); }
    }
    render();
    return {
        state, render,
        get toggle() { return refs.find(r => typeof r.current?.toggle === 'function').current.toggle; },
    };
}

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

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

for (const states of [[true, false], [false, true], [true, false, true, false]]) {
    test(`overlapping toggles ${states.join(' → ')} preserve request order across renders`, async () => {
        const requests = [];
        let stored;
        mod.platform.checkTask = () => true;
        mod.platform.api.post = (url, body, options) => {
            const gate = deferred();
            requests.push({ ...gate, enabled: options.params.enabled });
            return gate.promise.then(() => { stored = options.params.enabled; });
        };
        const p = page();
        const completions = states.map((enabled, i) => {
            p.render();
            // Numeric and string representations still identify the same row.
            return p.toggle({ ...stale, id: i % 2 ? '7' : 7 }, enabled);
        });
        await flush();
        assert.equal(requests.length, 1, 'only one request for a window may be in flight');
        for (let i = 0; i < states.length; i++) {
            assert.equal(requests[i].enabled, states[i]);
            assert.equal(p.state[1], 0, 'do not refresh an intermediate state over the latest choice');
            requests[i].resolve();
            await completions[i];
            await flush();
            assert.equal(requests.length, Math.min(i + 2, states.length));
        }
        assert.equal(stored, states.at(-1), 'the last requested state must win');
        assert.equal(p.state[1], 1, 'refresh when this window has drained its queue');
    });
}

test('different windows remain independent while one update is slow', async () => {
    const requests = [];
    mod.platform.checkTask = () => true;
    mod.platform.api.post = url => {
        const gate = deferred();
        requests.push({ ...gate, url });
        return gate.promise;
    };
    const p = page();
    const first = p.toggle(stale, false);
    const other = p.toggle({ ...stale, id: 8 }, true);
    await flush();
    assert.equal(requests.length, 2);
    requests[1].resolve();
    await other;
    assert.equal(p.state[1], 1);
    requests[0].resolve();
    await first;
    assert.equal(p.state[1], 2);
});

test('an uncertain failed update cancels queued changes and allows a later explicit retry', async () => {
    const requests = [];
    let stored = false;
    mod.platform.checkTask = () => true;
    mod.platform.api.post = (url, body, options) => {
        const gate = deferred();
        requests.push({ ...gate, commit: () => { stored = options.params.enabled; } });
        return gate.promise;
    };
    const p = page();
    const first = p.toggle(stale, true);
    const opposite = p.toggle(stale, false);
    const repeated = p.toggle(stale, false);
    await flush();
    assert.equal(requests.length, 1);
    requests[0].reject(new Error('request timed out; engine may still be completing it'));
    await Promise.all([first, opposite, repeated]);
    assert.equal(requests.length, 1, 'never send queued changes after an uncertain predecessor');
    assert.equal(p.state[1], 1);
    requests[0].commit(); // The timed-out server operation finishes late.
    assert.equal(stored, true);
    const retry = p.toggle(stale, false);
    await flush();
    assert.equal(requests.length, 2, 'an explicit retry starts a new queue');
    requests[1].commit();
    requests[1].resolve();
    await retry;
    assert.equal(stored, false);
    assert.equal(p.state[1], 2);
});

test('queued updates recheck permission when they are about to be sent', async () => {
    const gate = deferred();
    let permitted = true, writes = 0;
    mod.platform.checkTask = () => permitted;
    mod.platform.api.post = () => { writes++; return gate.promise; };
    const p = page();
    const first = p.toggle(stale, true);
    const queued = p.toggle(stale, false);
    await flush();
    permitted = false;
    gate.resolve();
    await Promise.all([first, queued]);
    assert.equal(writes, 1, 'revoked permission also applies to already-queued changes');
    assert.equal(p.state[1], 1, 'refresh the state actually saved');
    permitted = true;
    await p.toggle(stale, false);
    assert.equal(writes, 2, 'a skipped queued request does not retain the queue');
    assert.equal(p.state[1], 2);
});


test('tab remounts preserve one queue and the latest explicit state', async () => {
    const requests = [];
    let stored = true;
    mod.platform.checkTask = () => true;
    mod.platform.api.post = (url, body, options) => {
        const gate = deferred();
        requests.push({ ...gate, enabled: options.params.enabled });
        return gate.promise.then(() => { stored = options.params.enabled; });
    };
    const oldPage = page();
    const first = oldPage.toggle(stale, false);
    const queued = oldPage.toggle(stale, true);
    await flush();
    // SentinelView replaces MaintenancePage on tab changes. Its new refs must
    // not create a second queue while the old page still has work in flight.
    const newPage = page();
    const latest = newPage.toggle({ ...stale, id: '7' }, false);
    await flush();
    const inFlight = requests.length;
    if (inFlight === 2) {
        // Drive the broken case to completion before asserting, so no pending
        // work from a failing regression can leak into a subsequent test.
        requests[1].resolve();
        await latest;
        requests[0].resolve();
        await first;
        await flush();
        requests[2].resolve();
        await queued;
    } else {
        for (const [i, completion] of [first, queued, latest].entries()) {
            requests[i].resolve();
            await completion;
            await flush();
        }
    }
    assert.equal(inFlight, 1, 'one window must have one in-flight write across page instances');
    assert.deepEqual(requests.map(r => r.enabled), [false, true, false]);
    assert.equal(stored, false, 'older work must not overwrite the latest explicit disable');
    assert.equal(oldPage.state[1], 0, 'the old page does not refresh intermediate states');
    assert.equal(newPage.state[1], 1, 'the latest page refreshes after its final write');
});

test('failure cancels queued toggles across remounts and a new page can retry', async () => {
    const requests = [];
    mod.platform.checkTask = () => true;
    mod.platform.api.post = (url, body, options) => {
        const gate = deferred();
        requests.push({ ...gate, enabled: options.params.enabled });
        return gate.promise;
    };
    const firstPage = page();
    const first = firstPage.toggle(stale, false);
    const secondPage = page();
    const queued = secondPage.toggle(stale, true);
    await flush();
    const inFlight = requests.length;
    requests[0].reject(new Error('uncertain request failure'));
    // Release an incorrectly concurrent request too, keeping the negative
    // control bounded without hiding the assertion below.
    if (inFlight === 2) requests[1].resolve();
    await Promise.all([first, queued]);
    const afterFailure = requests.length;
    const retry = page().toggle(stale, false);
    await flush();
    requests.at(-1).resolve();
    await retry;
    assert.equal(inFlight, 1);
    assert.equal(afterFailure, 1, 'a new page cannot bypass an uncertain predecessor');
    assert.equal(requests.length, 2, 'a later explicit retry starts a fresh queue');
});
