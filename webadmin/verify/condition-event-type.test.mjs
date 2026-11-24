// OIE Sentinel: the action condition builder offers Notification as an event
// type, so an action can take only Log notifications or only problems.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, deep } from './harness.mjs';

const ui = await load('web/ui.jsx', 'export { platform };');

const optionsOf = (select) => deep(select).filter((n) => n && n.type === 'option').map((n) => n.props.value);

test('the Event type condition offers Notification beside Problem and Resolved', () => {
    const rows = [{ field: 'EVENT_TYPE', operator: '=', value: 'NOTIFICATION' }];
    const tree = deep(ui.ConditionBuilder({ value: rows, onChange: () => {} }));
    const select = tree.find((n) => n && n.type === 'select' && n.props.value === 'NOTIFICATION');
    assert.ok(select, 'the event type select shows the stored value');
    assert.deepEqual(optionsOf(select), ['PROBLEM', 'RESOLVED', 'NOTIFICATION']);
    assert.deepEqual(ui.CONDITION_FIELDS.EVENT_TYPE.operators, ['='], 'the server matches event types with = only');
});
