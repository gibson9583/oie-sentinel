// OIE Sentinel: the host Dashboard column counts open channel problems only.
// A Log problem belongs to no channel and a notification is never open.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, nodes } from './harness.mjs';

const mod = await load('web/host.jsx', 'export { platform, snapshot, refresh, SentinelCell };');

mod.platform.store.getState = () => ({ id: 1 });
const coverage = { coversAll: false, channelIds: ['a', 'b'] };
const serve = (problems) => async (p) => p.endsWith('/dashboard/coverage') ? coverage
    : p.endsWith('/problems') ? problems : [];
const cell = (id) => nodes(mod.SentinelCell({ channelId: id }));
const clear = (id) => cell(id).some((n) => n.props?.className?.includes('sn-clear'));
const badgeTitle = (id) => cell(id).map((n) => n.props?.title).find((t) => t && t.includes('open Sentinel problem'));

test('Log problems and notifications add nothing to channel badges', async () => {
    mod.platform.api.get = serve({
        items: [
            { id: 1, subjectType: 'SERVER', channelId: null, severity: 'DISASTER', status: 'PROBLEM' },
            { id: 2, subjectType: 'CHANNEL', channelId: 'a', severity: 'WARNING', status: 'PROBLEM' },
            { id: 3, subjectType: 'SERVER', channelId: 'b', severity: 'HIGH', status: 'PROBLEM' },
            { id: 4, kind: 'NOTIFICATION', subjectType: 'CHANNEL', channelId: 'b', severity: 'HIGH', status: 'RESOLVED' },
        ],
        total: 4,
    });
    await mod.refresh();
    assert.deepEqual(mod.snapshot.byChannel, { a: { count: 1, worst: 'WARNING' } });
    assert.equal(mod.snapshot.truncated, false);
    assert.match(badgeTitle('a'), /^1 open Sentinel problem, highest severity Warning/);
    assert.equal(clear('b'), true, 'neither the Log problem carrying a channel id nor the notification lights b');
});
