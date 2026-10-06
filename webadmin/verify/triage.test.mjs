import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseBulkReceipt } from '../web/triage.js';

test('mixed receipts preserve applied and unapplied IDs', () => {
    const result = parseBulkReceipt({ acknowledged: 1, receipts: [{id: 1, status: 'APPLIED'}, {id: 2, status: 'UNAVAILABLE'}] }, [1,2], 'ack');
    assert.equal(result.applied, 1); assert.equal(result.uncertain, false);
    assert.deepEqual(result.items.map(r => r.status), ['APPLIED', 'UNAVAILABLE']);
});
test('legacy partial counts cannot identify applied rows', () => {
    assert.equal(parseBulkReceipt({resolved: 1}, [1,2], 'resolve').uncertain, true);
    assert.equal(parseBulkReceipt({resolved: 2}, [1,2], 'resolve').uncertain, false);
});
test('malformed, contradictory or incomplete responses never claim success', () => {
    for (const response of [null, {}, 2, {acknowledged: '2'}, {acknowledged: -1}, {acknowledged: 3},
        {acknowledged: 2, receipts: null}, {acknowledged: 2, receipts: []},
        {acknowledged: 1, receipts: [{id:1,status:'APPLIED'},{id:1,status:'UNKNOWN'}]},
        {acknowledged: 1, receipts: [{id:1,status:'APPLIED'},{id:3,status:'UNKNOWN'}]},
        {acknowledged: 2, receipts: [{id:1,status:'APPLIED'},{id:2,status:'UNKNOWN'}]},
        {acknowledged: 1, receipts: [{id:1,status:'APPLIED'},{id:2,status:'NEW_STATUS'}]}]) {
        const result = parseBulkReceipt(response, [1,2], 'ack');
        assert.equal(result.applied, null); assert.equal(result.uncertain, true);
    }
});
test('server write uncertainty remains uncertain even with a valid applied count', () => {
    const result = parseBulkReceipt({resolved:0,receipts:[{id:1,status:'UNKNOWN'}]}, [1], 'resolve');
    assert.equal(result.applied, 0); assert.equal(result.uncertain, true);
});
