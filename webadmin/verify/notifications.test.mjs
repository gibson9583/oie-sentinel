// OIE Sentinel: the Notifications tab lists notifications by monitor and date
// and shows one with its matches, as text, with no acknowledge or resolve.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, nodes, deep, textOf, renderHtml } from './harness.mjs';

const mod = await load('web/pages/notifications.jsx',
    'export { platform, notificationParams, NotificationTable, NotificationBody };'
    + "\nexport { getNotifications } from '../api.js';");
const React = mod.platform.React;

const HOSTILE = '<script>alert(1)</script> & <b>x</b>';
const note = {
    id: 21, monitorId: 7, kind: 'NOTIFICATION', status: 'RESOLVED', severity: 'AVERAGE', subjectType: 'SERVER',
    channelId: null, suppressed: true, message: HOSTILE, openedTime: '2026-11-21T10:00:00Z',
    detailsJson: JSON.stringify({ condition: 'EACH', count: 1, node: 'node-a',
        matches: [{ time: '2026-11-21T10:00:00Z', node: 'node-a', level: 'ERROR', logger: 'deploy', message: HOSTILE }] }),
};
const detail = { event: note, monitorName: 'Deploys', monitorType: 'LOG_EVENTS', channelName: 'Log',
    dispatches: [{ id: 1, actionId: 3, success: true, dispatchTime: '2026-11-21T10:00:01Z' }] };

/* Renders the page with its three useApi results in order: list, monitors, channels. */
function renderPage(results) {
    const queue = [...results];
    const original = React.useState;
    React.useState = (init) => [init && typeof init === 'object' && 'loading' in init ? queue.shift()
        : (typeof init === 'function' ? init() : init), () => {}];
    try {
        return nodes(mod.NotificationsPage());
    } finally {
        React.useState = original;
    }
}

test('the Notifications list filters by monitor and date and shows its detail as text, read only', async () => {
    const params = mod.notificationParams({ monitorId: '7', from: '2026-11-20', to: '2026-11-21' }, 2);
    assert.equal(params.monitorId, '7');
    assert.equal(params.from, new Date('2026-11-20T00:00:00').getTime());
    assert.equal(params.to, new Date('2026-11-21T23:59:59.999').getTime(), 'the To day is included in full');
    assert.equal(params.page, 2);
    assert.equal(params.pageSize, 25);
    assert.deepEqual(mod.notificationParams({ monitorId: '', from: '', to: '' }, 0),
        { monitorId: undefined, from: undefined, to: undefined, page: 0, pageSize: 25 });
    let asked;
    mod.platform.api.get = async (path, p) => { asked = { path, p }; return JSON.stringify({ items: [], total: 0 }); };
    await mod.getNotifications(params);
    assert.equal(asked.path, '/extensions/sentinel/notifications');
    assert.equal(asked.p.monitorId, '7');

    const monitors = [{ id: 7, name: 'Deploys', monitorType: 'LOG_EVENTS' }, { id: 8, name: 'ADT', monitorType: 'INACTIVITY' }];
    const page = renderPage([
        { data: { items: [note], total: 1 }, error: null, loading: false },
        { data: monitors, error: null, loading: false },
        { data: [], error: null, loading: false },
    ]);
    const monitorSelect = page.find((n) => n.type === 'select');
    assert.deepEqual(nodes(monitorSelect).filter((n) => n.type === 'option').map((n) => n.props.value), ['', '7'],
        'only Log monitors are offered');
    assert.equal(page.filter((n) => n.type === 'input' && n.props.type === 'date').length, 2);
    assert.equal(page.some((n) => n.type === 'button' && /Acknowledge|Resolve/.test(nodes(n.children).join(''))), false);

    let opened;
    const table = deep(mod.NotificationTable({ items: [note], monitorNames: { 7: 'Deploys' }, channelNames: {}, onOpen: (n) => { opened = n; } }));
    const row = table.find((n) => n.type === 'tr' && n.props.onClick);
    const cells = table.filter((n) => n.type === 'td').map((td) => nodes(td.children).filter((c) => typeof c === 'string').join(''));
    assert.deepEqual(cells.slice(1), ['Deploys', 'Log', HOSTILE, 'Held back']);
    row.props.onClick();
    assert.equal(opened, note);

    const body = textOf(mod.NotificationBody({ event: note, detail, label: 'Log' }));
    for (const expected of ['Deploys', '(Log lines)', 'Log', 'Held back from actions by a schedule', 'Each occurrence', 'deploy', '#3', 'Sent']) {
        assert.ok(body.includes(expected), `${expected} is shown`);
    }
    assert.doesNotMatch(body, /Acknowledge|Resolve|Server/);
    const windowNote = { ...note, detailsJson: JSON.stringify({ condition: 'AT_LEAST', count: 3, threshold: 2,
        windowSeconds: 300, lastBreach: '2026-11-21T10:00:00Z' }) };
    const windowBody = textOf(mod.NotificationBody({ event: windowNote, detail: { ...detail, event: windowNote }, label: 'Log' }));
    assert.match(windowBody, /Condition last true/);
    assert.doesNotMatch(windowBody, /counts from here/, 'a notification has nothing to clear');
    const html = renderHtml(mod, mod.NotificationBody, { event: note, detail, label: 'Log' })
        + renderHtml(mod, mod.NotificationTable, { items: [note], monitorNames: {}, channelNames: {}, onOpen: () => {} });
    assert.equal(html.includes('<script>') || html.includes('<b>x'), false, html);
    assert.equal(html.split('&lt;script&gt;alert(1)&lt;/script&gt; &amp; &lt;b&gt;x&lt;/b&gt;').length - 1, 3,
        'the message and the matched line in the detail, the message in the list');
});

test('the Notifications list pages through the results 25 at a time', () => {
    const items = (n) => Array.from({ length: n }, (_, i) => ({ ...note, id: 100 + i }));
    const state = [];
    /* Keeps the page's own state across renders; each render gets fresh list, monitors and channels results. */
    const render = (shown, total) => {
        const results = [{ data: { items: items(shown), total }, error: null, loading: false },
            { data: [], error: null, loading: false }, { data: [], error: null, loading: false }];
        let k = 0;
        const original = React.useState;
        React.useState = (init) => {
            if (init && typeof init === 'object' && 'loading' in init) return [results.shift(), () => {}];
            const i = k++;
            if (!(i in state)) state[i] = typeof init === 'function' ? init() : init;
            return [state[i], (v) => { state[i] = typeof v === 'function' ? v(state[i]) : v; }];
        };
        try {
            return nodes(mod.NotificationsPage());
        } finally {
            React.useState = original;
        }
    };
    const button = (tree, label) => tree.find((n) => n.type === 'button' && nodes(n.children).includes(label));
    const range = (tree) => tree.find((n) => n.type === 'span' && /of \d+$/.test(nodes(n.children).join('')));

    let tree = render(25, 60);
    assert.equal(nodes(range(tree).children).join(''), '1-25 of 60');
    assert.equal(button(tree, '‹ Prev').props.disabled, true);
    assert.equal(button(tree, 'Next ›').props.disabled, false);
    button(tree, 'Next ›').props.onClick();

    tree = render(25, 60);
    assert.equal(nodes(range(tree).children).join(''), '26-50 of 60');
    assert.equal(button(tree, '‹ Prev').props.disabled, false);
    button(tree, 'Next ›').props.onClick();

    tree = render(10, 60);
    assert.equal(nodes(range(tree).children).join(''), '51-60 of 60');
    assert.equal(button(tree, 'Next ›').props.disabled, true, 'no page after the last');
    button(tree, '‹ Prev').props.onClick();
    tree = render(25, 60);
    assert.equal(nodes(range(tree).children).join(''), '26-50 of 60');

    tree.find((n) => n.type === 'select').props.onChange({ target: { value: '7' } });
    tree = render(5, 5);
    assert.equal(nodes(range(tree).children).join(''), '1-5 of 5', 'a filter change goes back to the first page');
    assert.equal(button(tree, 'Next ›').props.disabled, true);

    tree = render(0, 0);
    assert.equal(nodes(range(tree).children).join(''), '0 of 0');
});
