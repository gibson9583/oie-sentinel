// OIE Sentinel: a notification row is reached with Tab, opens with Enter or
// Space, and shows where the focus is.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { load, nodes } from './harness.mjs';

const mod = await load('web/pages/notifications.jsx',
    'export { platform, NotificationTable };');
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const LINE = 'ORA-00942 on the ADT feed';
const note = {
    id: 21, monitorId: 7, kind: 'NOTIFICATION', status: 'RESOLVED', severity: 'AVERAGE', subjectType: 'SERVER',
    channelId: null, suppressed: false, message: 'Matching ERROR log line from db', openedTime: '2026-11-21T10:00:00Z',
    detailsJson: JSON.stringify({ condition: 'EACH', count: 1, node: 'node-a',
        matches: [{ time: '2026-11-21T10:00:00Z', node: 'node-a', level: 'ERROR', logger: 'db', message: LINE }] }),
};

test('a notification row is focusable and opens with Enter or Space', async () => {
    const opened = [];
    const table = nodes(mod.NotificationTable({ items: [note], monitorNames: { 7: 'Deploys' }, channelNames: {},
        onOpen: (n) => opened.push(n) }));
    const row = table.find((n) => n.type === 'tr' && n.props.onClick);
    assert.equal(row.props.tabIndex, 0, 'reachable with Tab');
    assert.equal(row.props.role, 'button', 'announced as something to press');
    assert.match(row.props['aria-label'], /^Show details of the notification at .+ from Deploys$/);
    assert.match(String(row.props.className), /\bsn-row-link\b/);
    let prevented = 0;
    for (const key of ['Enter', ' ', 'a', 'Tab']) {
        row.props.onKeyDown({ key, preventDefault: () => { prevented += 1; } });
    }
    assert.deepEqual(opened, [note, note], 'Enter and Space open it, other keys do not');
    assert.equal(prevented, 2, 'Space does not also scroll the page');

    const css = await readFile(path.join(dir, 'web/plugin.jsx'), 'utf8');
    assert.match(css, /\.sn-row-link:focus-visible\s*\{[^}]*(outline|box-shadow)/, 'the focused row is visibly marked');
});
