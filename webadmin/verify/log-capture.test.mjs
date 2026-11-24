// OIE Sentinel: the Dashboard shows the log capture status to unrestricted
// users, and the Settings page sends the log queue capacity on every save.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, nodes, textOf } from './harness.mjs';

const dashboard = await load('web/pages/dashboard.jsx',
    "export { platform };\nexport { LogCaptureStatus } from './log-capture-status.jsx';");
const settings = await load('web/pages/settings.jsx', 'export { platform };');

/* Calls a page with the first null-initialized states and the useApi states replaced in order. */
function renderWith(mod, page, { nulls = [], apis = [] }) {
    const React = mod.platform.React;
    const original = React.useState;
    const n = [...nulls];
    const a = [...apis];
    React.useState = (init) => {
        if (init === null && n.length) return [n.shift(), () => {}];
        if (init && typeof init === 'object' && 'loading' in init && a.length) return [a.shift(), () => {}];
        return [typeof init === 'function' ? init() : init, () => {}];
    };
    try { return nodes(page()); } finally { React.useState = original; }
}

const pipeline = { nodeId: 'node-a', queueCapacity: 1000, queueDepth: 3, droppedSinceStart: 0,
    matcherAlive: true, appenderAttached: true, notCountingReason: null };

test('the Dashboard shows the log capture status when the server sends it', () => {
    const counting = textOf(dashboard.LogCaptureStatus({ status: pipeline }));
    for (const expected of ['Log capture is counting', 'node-a', 'Queue 3 of 1000 lines', '0 lines dropped since start',
        'Log matcher running', 'Log hook attached']) {
        assert.ok(counting.includes(expected), `${expected} in: ${counting}`);
    }
    const reason = "Sentinel's log appender is not attached to the engine's logging.";
    const stopped = textOf(dashboard.LogCaptureStatus({ status: { ...pipeline, droppedSinceStart: 12,
        matcherAlive: false, appenderAttached: false, notCountingReason: reason } }));
    for (const expected of [`Log capture is not counting: ${reason}`, '12 lines dropped since start',
        'Log matcher not running', 'Log hook not attached']) {
        assert.ok(stopped.includes(expected), `${expected} in: ${stopped}`);
    }

    const summary = { openTotal: 0, unacknowledged: 0, monitorsEnabled: 1, monitorsTotal: 1, channelsWatched: 0,
        openBySeverity: [], recentProblems: [], topChannels: [] };
    const placed = (s) => renderWith(dashboard, dashboard.DashboardPage, { nulls: [s] })
        .filter((x) => x && typeof x.type === 'function' && x.type.name === 'LogCaptureStatus');
    assert.equal(placed({ ...summary, logPipeline: pipeline }).length, 1);
    assert.equal(placed({ ...summary, logPipeline: pipeline })[0].props.status, pipeline);
    assert.equal(placed(summary).length, 0, 'a channel-restricted user gets no logPipeline and sees nothing');
});

test('Settings shows the log queue capacity and sends it on every save', async () => {
    const saved = { collectorIntervalSeconds: 30, evaluatorIntervalSeconds: 60, sampleRetentionDays: 7,
        trendRetentionDays: 90, resolvedAlertRetentionDays: 180, logQueueCapacity: 2500 };
    const form = (over) => Object.fromEntries(Object.entries({ ...saved, ...over }).map(([k, v]) => [k, String(v)]));
    const render = (f) => renderWith(settings, settings.SettingsPage,
        { nulls: [f, saved], apis: [{ data: saved, error: null, loading: false }] });

    let tree = render(form({}));
    const label = tree.find((n) => n.type === 'label' && n.children.includes('Log queue capacity (lines)'));
    assert.ok(label, 'the field is shown');
    const input = tree.filter((n) => n.type === 'input').find((n) => n.props.value === '2500');
    assert.equal(input.props.min, 100);
    assert.equal(input.props.max, 10000);

    let body = null;
    settings.platform.api.put = async (p, b) => { body = b; return JSON.stringify(b); };
    const save = (t) => t.find((n) => n.type === 'button' && n.children.includes('Save')).props.onClick();
    await save(tree);
    assert.deepEqual(body, saved, 'every setting, the queue capacity included');

    body = null;
    await save(render(form({ logQueueCapacity: 50 })));
    assert.equal(body, null, 'an out-of-range capacity is refused before the round trip');
});
