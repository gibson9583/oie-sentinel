// OIE Sentinel: Log problems show "Log" where a channel name would be, and
// their Matches panel shows the condition, counts and matched lines as text.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, nodes, deep, textOf, renderHtml } from './harness.mjs';

const problems = await load('web/pages/problems.jsx', 'export { platform, buildColumns, ProblemDetailPane };'
    + "\nexport { MatchesPanel, matchDetailsOf } from './matches-panel.jsx';");
const dashboard = await load('web/pages/dashboard.jsx', 'export { platform, ProblemInline };');

const HOSTILE = '<img src=x onerror="alert(1)"><b>bold</b> & more';
const details = {
    condition: 'AT_LEAST', count: 4, threshold: 2, windowSeconds: 300, perNode: { 'node-a': 3, 'node-b': 1 },
    lastBreach: '2026-11-21T10:00:02Z',
    matches: [
        { time: '2026-11-21T10:00:00.000Z', node: 'node-a', level: 'ERROR', logger: 'transformer', message: HOSTILE },
        { time: '2026-11-21T10:00:01.000Z', node: 'node-b', level: 'WARN', logger: 'js-connector', message: 'line two\nat Foo.bar' },
    ],
};
const logEvent = {
    id: 11, monitorId: 7, kind: 'PROBLEM', status: 'PROBLEM', severity: 'HIGH', subjectType: 'SERVER', channelId: null,
    message: '4 matching log lines in the last 300s (at least 2)', openedTime: '2026-11-21T10:00:02.000Z',
    detailsJson: JSON.stringify(details),
};

test('the Matches panel shows condition, count, window, nodes, last breach and the lines as text', () => {
    assert.equal(problems.matchDetailsOf(logEvent, null).count, 4);
    assert.equal(problems.matchDetailsOf({ ...logEvent, subjectType: 'CHANNEL', channelId: 'c' }, 'ERROR_RATE'), null);
    assert.equal(problems.matchDetailsOf({ ...logEvent, detailsJson: '{"invalidConfig":true}' }, 'LOG_EVENTS'), null);

    const tree = deep(problems.MatchesPanel({ details }));
    const shown = textOf(problems.MatchesPanel({ details }));
    for (const expected of ['At least 2', 'in the last 300 seconds', 'node-a', 'node-b', 'Condition last true',
        'transformer', 'js-connector']) {
        assert.ok(shown.includes(expected), `${expected} is shown`);
    }
    const pres = tree.filter((n) => n && n.type === 'pre');
    assert.deepEqual(pres[0].children, [HOSTILE], 'the message is one plain string child');
    assert.equal(tree.some((n) => n && n.props && 'dangerouslySetInnerHTML' in n.props), false);
    const html = renderHtml(problems, problems.MatchesPanel, { details });
    assert.ok(html.includes('&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&lt;b&gt;bold&lt;/b&gt; &amp; more'), html);
    assert.equal(html.includes('<img') || html.includes('<b>'), false);

    const { matches, ...withoutMatches } = details;
    assert.match(textOf(problems.MatchesPanel({ details: withoutMatches })), /No matched lines were kept for this alert/);
    assert.match(textOf(problems.MatchesPanel({ details: { ...details, matches: [] } })), /No matched lines were kept/);
    const each = textOf(problems.MatchesPanel({ details: { condition: 'EACH', count: 1, node: 'node-a' } }));
    assert.match(each, /Each occurrence/);
    assert.match(each, /node-a/);
    assert.doesNotMatch(each, /in the last/, 'an each-occurrence notification has no window');
});

test('the Matches panel never says the option is off when it cannot know', () => {
    // The summary notification for occurrences past the cap keeps no lines, whatever the option.
    const summary = { condition: 'EACH', count: 3, node: 'node-a', notSentIndividually: 3 };
    const shown = textOf(problems.MatchesPanel({ details: summary }));
    assert.match(shown, /No matched lines were kept for this alert/);
    assert.doesNotMatch(shown, /does not include/);

    // A deleted monitor: the server strips the lines, and the detail has no monitor type.
    const deleted = { ...logEvent, detailsJson: JSON.stringify({ condition: 'AT_LEAST', count: 4, threshold: 2 }) };
    const fromDeleted = problems.matchDetailsOf(deleted, null);
    assert.equal(fromDeleted.count, 4, 'still read as a Log alert');
    const text = textOf(problems.MatchesPanel({ details: fromDeleted }));
    assert.match(text, /No matched lines were kept for this alert/);
    assert.doesNotMatch(text, /does not include/);
});

test('a Log problem shows "Log" in the Problems grid and detail and on the Dashboard, never "Server"', () => {
    const namesRef = { current: { channels: { c1: 'ADT' }, monitors: { 7: 'Script errors' }, userNameOf: () => 'admin' } };
    const channel = problems.buildColumns(namesRef).find((c) => c.key === 'channel');
    assert.equal(channel.render(logEvent), 'Log');
    assert.equal(channel.render({ ...logEvent, subjectType: 'CHANNEL', channelId: 'c1' }), 'ADT');

    const React = problems.platform.React;
    const original = React.useState;
    let first = true;
    const detail = { event: logEvent, monitorName: 'Script errors', monitorType: 'LOG_EVENTS', channelName: 'Log', dispatches: [] };
    React.useState = (init) => [init === null && first ? (first = false, detail) : (typeof init === 'function' ? init() : init), () => {}];
    let tree;
    try {
        tree = nodes(problems.ProblemDetailPane({ id: 11, onBack: () => {}, onChanged: () => {} }));
    } finally {
        React.useState = original;
    }
    const named = (name) => tree.filter((n) => n && typeof n.type === 'function' && n.type.name === name);
    assert.equal(named('ChannelActivityPanel').length, 0, 'no channel chart');
    assert.equal(named('MatchesPanel')[0].props.details.count, 4);
    assert.ok(tree.includes('Matches'));
    assert.ok(nodes(named('DetailRow').find((n) => n.props.label === 'Channel').children).includes('Log'));

    const inline = textOf(dashboard.ProblemInline({ problem: logEvent, channelNameOf: () => '?', onBack: () => {} }));
    assert.match(inline, /Channel Log/);
    assert.match(inline, /At least 2/, 'the Dashboard detail shows the Matches panel');
    assert.doesNotMatch(inline, /Server/);
});
