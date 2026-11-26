// OIE Sentinel: a notification detail that fails to load shows the error and
// none of the matched lines the earlier list response carried.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, deep, textOf } from './harness.mjs';

const mod = await load('web/pages/notifications.jsx',
    'export { platform, NotificationDetail };');
const React = mod.platform.React;

const LINE = 'ORA-00942 on the ADT feed';
const note = {
    id: 21, monitorId: 7, kind: 'NOTIFICATION', status: 'RESOLVED', severity: 'AVERAGE', subjectType: 'SERVER',
    channelId: null, suppressed: false, message: 'Matching ERROR log line from db', openedTime: '2026-11-21T10:00:00Z',
    detailsJson: JSON.stringify({ condition: 'EACH', count: 1, node: 'node-a',
        matches: [{ time: '2026-11-21T10:00:00Z', node: 'node-a', level: 'ERROR', logger: 'db', message: LINE }] }),
};

/* Renders the detail with its one useApi result. */
function renderDetail(result) {
    const original = React.useState;
    React.useState = (init) => [init && typeof init === 'object' && 'loading' in init ? result
        : (typeof init === 'function' ? init() : init), () => {}];
    try {
        return deep(mod.NotificationDetail({ item: note, label: 'Log', onBack: () => {} }));
    } finally {
        React.useState = original;
    }
}

test('a detail that fails to load shows the error and none of the list\'s matched lines', () => {
    const failed = textOf(renderDetail({ data: null, error: 'HTTP 404 Not Found', loading: false }));
    assert.ok(failed.includes('Could not load the notification.'), failed);
    assert.ok(failed.includes('HTTP 404 Not Found'), failed);
    assert.equal(failed.includes(LINE), false, failed);

    const loading = textOf(renderDetail({ data: null, error: null, loading: true }));
    assert.equal(loading.includes(LINE), false, 'nor while the detail is still loading');

    const loaded = textOf(renderDetail({ data: { event: note, monitorType: 'LOG_EVENTS', dispatches: [] },
        error: null, loading: false }));
    assert.ok(loaded.includes(LINE), 'the detail\'s own matched lines are shown');
});
