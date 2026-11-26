// OIE Sentinel: the Notifications date filter says which time zone its days
// are in. The days start at the browser's midnight while the rows follow the
// host's display zone, and the host does not yet give plugins that zone.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, nodes, textOf } from './harness.mjs';

const mod = await load('web/pages/notifications.jsx', 'export { platform };');
const React = mod.platform.React;

/* Renders the page with its three useApi results in order: list, monitors, channels. */
function renderPage() {
    const queue = [
        { data: { items: [], total: 0 }, error: null, loading: false },
        { data: [], error: null, loading: false },
        { data: [], error: null, loading: false },
    ];
    const original = React.useState;
    React.useState = (init) => [init && typeof init === 'object' && 'loading' in init ? queue.shift()
        : (typeof init === 'function' ? init() : init), () => {}];
    try {
        return mod.NotificationsPage();
    } finally {
        React.useState = original;
    }
}

test('the date filter says its dates are in the browser\'s time zone', () => {
    const page = renderPage();
    const filterBar = nodes(page).find((n) => n && n.props && n.props.className === 'sn-filterbar');
    assert.ok(filterBar, 'the filter bar is rendered');
    assert.ok(nodes(filterBar).some((n) => n.type === 'input' && n.props.type === 'date'), 'beside the date fields');
    assert.match(textOf(filterBar), /Dates in your browser's time zone/);
});
