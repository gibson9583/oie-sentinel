// OIE Sentinel — adversarial regression against the production source graph.
import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(dir, "web/pages/maintenance.jsx");
const work = await mkdtemp(path.join(tmpdir(), 'sentinel-regression-'));
const outfile = path.join(work, 'bundle.mjs');
const uiStub = path.join(work, 'ui.mjs');
await writeFile(uiStub, `export * from ${JSON.stringify(path.join(dir, 'verify/stubs.js'))};
import { platform } from ${JSON.stringify(path.join(dir, 'verify/stubs.js'))};
export const promptDialog = (...args) => platform.schedulePrompt(...args);`);
after(() => rm(work, { recursive: true, force: true }));
try {
    await build({
        stdin: { contents: await readFile(source, 'utf8') + "\nexport { platform, WindowEditor }; export { importConfiguration, createMaintenanceWindow } from '../api.js';",
            sourcefile: source, resolveDir: path.dirname(source), loader: 'jsx' },
        outfile, bundle: true, format: 'esm', target: 'es2022',
        jsxFactory: 'React.createElement', jsxFragment: 'React.Fragment',
        alias: {
            react: path.join(dir, 'web/react-shim.js'),
            'react-dom': path.join(dir, 'web/react-dom-shim.js'),
            '@oie/web-shell': path.join(dir, 'verify/stubs.js'),
            '@oie/web-ui': uiStub,
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
        get activate() { return refs.find(r => typeof r.current?.activate === 'function').current.activate; },
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


function editor(window, callbacks = {}) {
    const state = [];
    function render() {
        let index = 0;
        React.useState = initial => {
            const i = index++;
            if (!(i in state)) state[i] = typeof initial === 'function' ? initial() : initial;
            return [state[i], value => { state[i] = typeof value === 'function' ? value(state[i]) : value; }];
        };
        try {
            return mod.WindowEditor({ window, channels: [], groups: [], tags: [], manage: true,
                onClose() {}, onChanged() {}, ...callbacks });
        } finally { Object.assign(React, originalHooks); }
    }
    return {
        setEnabled(enabled) {
            nodes(render()).find(n => n.type === 'input' && n.props.type === 'checkbox')
                .props.onChange({ target: { checked: enabled } });
        },
        save() { return nodes(render()).find(n => n.type === 'button' && n.children.includes('Save changes')).props.onClick(); },
        delete() { return nodes(render()).find(n => n.type === 'button' && n.children.includes('Delete')).props.onClick(); },
    };
}
const editable = { ...stale, activeFrom: '2026-10-14T10:00:00Z', activeUntil: '2026-10-14T11:00:00Z' };

// Commit each request as it arrives, but hold the first response. This models
// a slow reply after a successful server write without inventing server ordering.
function recordChanges() {
    const firstResponse = deferred(), trace = [];
    let stored = true, deleted = false;
    const write = (kind, enabled) => {
        trace.push(kind + ':' + enabled);
        if (deleted) return Promise.reject(new Error('window was deleted'));
        stored = enabled;
        if (kind === 'delete') deleted = true;
        return trace.length === 1 ? firstResponse.promise : Promise.resolve();
    };
    mod.platform.checkTask = () => true;
    mod.platform.schedulePrompt = async () => '60';
    mod.platform.api.post = (url, body, options) => url.endsWith('/_activateNow')
        ? write('activate', true) : write('toggle', options.params.enabled);
    mod.platform.api.put = (url, body) => write('editor', body.enabled);
    mod.platform.api.del = () => write('delete', undefined);
    return { firstResponse, trace, get stored() { return stored; }, get deleted() { return deleted; } };
}

for (const operation of ['editor', 'activate', 'delete']) {
    test(`a later ${operation} waits for older toggles and owns the final state`, async () => {
        const writes = recordChanges();
        const p = page();
        const first = p.toggle(stale, operation === 'editor' ? false : true);
        const queued = p.toggle(stale, operation === 'editor' ? true : false);
        await flush();
        const e = editor(editable);
        e.setEnabled(false);
        const latest = operation === 'editor' ? e.save() : operation === 'activate' ? p.activate(stale) : e.delete();
        await flush();
        const beforeResponse = [...writes.trace];
        writes.firstResponse.resolve();
        await Promise.all([first, queued, latest]);
        assert.equal(beforeResponse.length, 1, 'every same-window mutation waits for its predecessors');
        assert.deepEqual(writes.trace.map(x => x.split(':')[0]), ['toggle', 'toggle', operation]);
        if (operation === 'delete') assert.equal(writes.deleted, true);
        else assert.equal(writes.stored, operation === 'activate');
    });
}

for (const operation of ['editor', 'activate']) {
    test(`toggles wait for a pending ${operation}, including across page mounts`, async () => {
        const writes = recordChanges();
        const e = editor(editable);
        e.setEnabled(true);
        const first = operation === 'editor' ? e.save() : page().activate(stale);
        await flush();
        const latest = page().toggle({ ...stale, id: '7' }, false);
        await flush();
        const beforeResponse = [...writes.trace];
        writes.firstResponse.resolve();
        await Promise.all([first, latest]);
        assert.equal(beforeResponse.length, 1);
        assert.deepEqual(writes.trace, [`${operation}:true`, 'toggle:false']);
        assert.equal(writes.stored, false);
    });
}

test('a failed toggle cancels a queued editor save without reporting success, then permits explicit retry', async () => {
    const writes = recordChanges();
    let closed = 0;
    const first = page().toggle(stale, false);
    const e = editor(editable, { onClose() { closed++; } });
    const save = e.save();
    await flush();
    writes.firstResponse.reject(new Error('response lost'));
    await Promise.all([first, save]);
    const afterFailure = [...writes.trace];
    const closedAfterFailure = closed;
    await e.save();
    assert.deepEqual(afterFailure, ['toggle:false']);
    assert.equal(closedAfterFailure, 0);
    assert.equal(closed, 1, 'only a successful explicit save closes the editor');
    assert.deepEqual(writes.trace, ['toggle:false', 'editor:true']);
});

for (const operation of ['editor', 'activate', 'delete']) {
    test(`a failed ${operation} cancels already queued toggles`, async () => {
        const writes = recordChanges();
        const e = editor(editable);
        const p = page();
        const first = operation === 'editor' ? e.save() : operation === 'activate' ? p.activate(stale) : e.delete();
        await flush();
        const later = p.toggle(stale, false);
        await flush();
        writes.firstResponse.reject(new Error('response lost'));
        await Promise.all([first, later]);
        assert.equal(writes.trace.length, 1, 'uncertain completion cancels later queued mutations');
    });
}

test('an editor save rechecks permission after waiting for a toggle', async () => {
    const writes = recordChanges();
    let permitted = true, closed = 0;
    mod.platform.checkTask = () => permitted;
    const first = page().toggle(stale, false);
    const e = editor(editable, { onClose() { closed++; } });
    const save = e.save();
    await flush();
    permitted = false;
    writes.firstResponse.resolve();
    await Promise.all([first, save]);
    assert.deepEqual(writes.trace, ['toggle:false']);
    assert.equal(closed, 0);
});


function recordImportChanges() {
    const firstResponse = deferred(), trace = [];
    let stored = true;
    mod.platform.checkTask = () => true;
    mod.platform.api.post = (url, body, options) => {
        const kind = url.endsWith('/import') ? 'import' : url.endsWith('/maintenanceWindows') ? 'create' : 'toggle';
        const enabled = kind === 'toggle' ? options.params.enabled
            : kind === 'import' ? body.maintenanceWindows?.[0]?.enabled : body.enabled;
        trace.push(`${kind}:${enabled}`);
        if (enabled !== undefined) stored = enabled;
        const result = { created: 0, updated: 1, skipped: 0 };
        return trace.length === 1 ? firstResponse.promise.then(() => result) : Promise.resolve(result);
    };
    return { firstResponse, trace, get stored() { return stored; } };
}
const scheduleDocument = enabled => ({ schemaVersion: 3, maintenanceWindows: [{ ...editable, enabled }] });

test('a settings import waits for queued toggles before applying its later enabled state', async () => {
    const writes = recordImportChanges();
    const p = page();
    const first = p.toggle(stale, false);
    const queued = p.toggle(stale, true);
    const imported = mod.importConfiguration(scheduleDocument(false));
    await flush();
    const before = [...writes.trace];
    writes.firstResponse.resolve();
    await Promise.all([first, queued, imported]);
    assert.deepEqual(before, ['toggle:false']);
    assert.deepEqual(writes.trace, ['toggle:false', 'toggle:true', 'import:false']);
    assert.equal(writes.stored, false);
});

test('later toggles on all IDs wait for a settings import, then resume independently', async () => {
    const writes = recordImportChanges();
    const imported = mod.importConfiguration(scheduleDocument(true));
    const first = page().toggle(stale, false);
    const other = page().toggle({ ...stale, id: 8 }, false);
    await flush();
    const before = [...writes.trace];
    writes.firstResponse.resolve();
    await Promise.all([imported, first, other]);
    assert.deepEqual(before, ['import:true']);
    assert.deepEqual(writes.trace, ['import:true', 'toggle:false', 'toggle:false']);
    assert.equal(writes.stored, false);
});

for (const firstKind of ['create', 'import']) {
    test(`${firstKind} and name-matched schedule creation/import preserve call order`, async () => {
        const writes = recordImportChanges();
        const create = () => mod.createMaintenanceWindow({ ...editable, id: undefined, enabled: true });
        const apply = () => mod.importConfiguration(scheduleDocument(false));
        const first = firstKind === 'create' ? create() : apply();
        const later = firstKind === 'create' ? apply() : create();
        await flush();
        const before = [...writes.trace];
        writes.firstResponse.resolve();
        await Promise.all([first, later]);
        assert.equal(before.length, 1);
        assert.deepEqual(writes.trace, firstKind === 'create' ? ['create:true', 'import:false'] : ['import:false', 'create:true']);
        assert.equal(writes.stored, firstKind === 'import');
    });
}

test('a failed predecessor cancels an import and a later explicit retry can apply it', async () => {
    const writes = recordImportChanges();
    const first = page().toggle(stale, true);
    const imported = mod.importConfiguration(scheduleDocument(false));
    const rejection = assert.rejects(imported, /earlier schedule change failed/);
    await flush();
    writes.firstResponse.reject(new Error('response lost'));
    await Promise.all([first, rejection]);
    assert.deepEqual(writes.trace, ['toggle:true']);
    await mod.importConfiguration(scheduleDocument(false));
    assert.deepEqual(writes.trace, ['toggle:true', 'import:false']);
});

test('a failed import cancels queued creates and toggles across IDs, then releases the barrier', async () => {
    const writes = recordImportChanges();
    const imported = mod.importConfiguration(scheduleDocument(true));
    const rejection = assert.rejects(imported, /response lost/);
    const first = page().toggle(stale, false);
    const other = page().toggle({ ...stale, id: 8 }, false);
    const create = mod.createMaintenanceWindow({ ...editable, id: undefined });
    const canceled = assert.rejects(create, /earlier schedule change failed/);
    await flush();
    writes.firstResponse.reject(new Error('response lost'));
    await Promise.all([rejection, first, other, canceled]);
    assert.deepEqual(writes.trace, ['import:true']);
    await page().toggle(stale, false);
    assert.deepEqual(writes.trace, ['import:true', 'toggle:false']);
});

for (const task of ['doManageSentinel', 'doManageSentinelMaintenance']) {
    test(`a queued schedule import rechecks ${task} permission`, async () => {
        const writes = recordImportChanges();
        let denied;
        mod.platform.checkTask = (context, requested) => requested !== denied;
        const first = page().toggle(stale, false);
        const imported = mod.importConfiguration(scheduleDocument(true));
        const rejection = assert.rejects(imported, /permission changed/);
        await flush();
        denied = task;
        writes.firstResponse.resolve();
        await Promise.all([first, rejection]);
        assert.deepEqual(writes.trace, ['toggle:false']);
    });
}

test('monitor-only imports remain independent of schedule queues and maintenance permission', async () => {
    const writes = recordImportChanges();
    const first = page().toggle(stale, false);
    await flush();
    mod.platform.checkTask = (context, task) => task === 'doManageSentinel';
    await mod.importConfiguration({ schemaVersion: 3, monitors: [], maintenanceWindows: [] });
    const before = [...writes.trace];
    writes.firstResponse.resolve();
    await first;
    assert.deepEqual(before, ['toggle:false', 'import:undefined']);
});


test('a failed batch predecessor cannot release another ID while its earlier request is still running', async () => {
    const first = deferred(), other = deferred(), requests = [];
    let imports = 0;
    mod.platform.checkTask = () => true;
    mod.platform.api.post = (url, body, options) => {
        if (url.endsWith('/import')) { imports++; return Promise.resolve({}); }
        const id = url.match(/maintenanceWindows\/(\d+)\//)[1];
        requests.push(id);
        if (requests.length === 1) return first.promise;
        if (requests.length === 2) return other.promise;
        return Promise.resolve({});
    };
    const p = page();
    const a = p.toggle(stale, false);
    const b = p.toggle({ ...stale, id: 8 }, false);
    await flush();
    const imported = mod.importConfiguration(scheduleDocument(true));
    const rejected = assert.rejects(imported, /earlier schedule change failed/);
    const queued = p.toggle(stale, true);
    other.reject(new Error('other schedule response failed'));
    await flush();
    await flush();
    const later = p.toggle(stale, false);
    await flush();
    const beforeFirstCompleted = [...requests];
    first.resolve();
    await Promise.all([a, b, rejected, queued, later]);
    assert.deepEqual(beforeFirstCompleted, ['7', '8'], 'same-ID ownership lasts until every inherited predecessor settles');
    assert.equal(imports, 0);
    await p.toggle(stale, false);
    assert.deepEqual(requests, ['7', '8', '7'], 'explicit retry works after the entire failed chain drains');
});
