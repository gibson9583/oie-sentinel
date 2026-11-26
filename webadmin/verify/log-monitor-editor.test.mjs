// OIE Sentinel: the monitor editor picks a kind first, locks it once saved,
// shows the Log fields that apply, and round-trips a Log monitor's config.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { load, nodes, deep, field, flat, textOf, mount } from './harness.mjs';

const mod = await load('web/pages/monitors.jsx', 'export { platform, scopeLabel };'
    + "\nexport { MonitorEditor } from './monitor-editor.jsx';"
    + "\nexport { seedConfigs, buildConfig } from './monitor-config.js';"
    + "\nexport { LogMonitorFields, LOG_PREVIEW_HINT } from './log-monitor-fields.jsx';");

const options = (node) => nodes(node).filter((n) => n.type === 'option').map((n) => String(n.props.value));
const selectOf = (node) => nodes(node).find((n) => n.type === 'select');
/* The editor's own tree plus its kind, type and scope fields, which hold no state. */
const expand = (tree) => tree.flatMap((n) => (n && typeof n.type === 'function'
    && ['KindAndType', 'ScopeField'].includes(n.type.name) ? [n, ...nodes(n.type(n.props))] : [n]));
const settingsOf = (tree) => {
    const configFields = tree.find((n) => typeof n.type === 'function' && n.type.name === 'ConfigFields');
    return configFields.type(configFields.props);
};

const channelMonitor = { id: 3, name: 'ADT inactivity', monitorType: 'INACTIVITY', scopeType: 'ALL', enabled: true, severity: 'HIGH', configJson: '{}' };
const logMonitor = { id: 4, name: 'Script errors', monitorType: 'LOG_EVENTS', scopeType: 'ALL', enabled: true, severity: 'HIGH', configJson: '{}' };
const otherLog = { ...logMonitor, id: 5, name: 'Heartbeat' };
const base = { channels: [], groups: [], tags: [], manage: true, onClose: () => {}, onChanged: () => {} };
const CHANNEL_TYPES = ['INACTIVITY', 'LOW_VOLUME', 'ANOMALY', 'CONNECTION_STATUS', 'ERROR_RATE', 'QUEUE_DEPTH', 'CHANNEL_STATE'];

test('kind comes first, Log preselects its one type, and a saved monitor keeps its kind', async () => {
    const editor = mount(mod, mod.MonitorEditor, { ...base, monitor: null, monitors: [channelMonitor, logMonitor] });
    const render = () => expand(editor());
    let tree = render();
    const [first] = tree.filter((n) => n.type === 'label');
    assert.equal(flat(first.children).join(''), 'Kind');
    assert.deepEqual(options(field(tree, 'Kind')), ['CHANNEL', 'LOG'], 'no Event kind yet');
    assert.deepEqual(options(field(tree, 'Type')), CHANNEL_TYPES);
    selectOf(field(tree, 'Suppressed by')).props.onChange({ target: { value: '3' } });

    selectOf(field(tree, 'Kind')).props.onChange({ target: { value: 'LOG' } });
    tree = render();
    assert.equal(selectOf(field(tree, 'Type')).props.value, 'LOG_EVENTS');
    assert.deepEqual(options(field(tree, 'Type')), ['LOG_EVENTS']);
    assert.equal(selectOf(field(tree, 'Suppressed by')).props.value, '', 'changing kind clears Suppressed by');
    assert.deepEqual(options(field(tree, 'Suppressed by')), ['', '4']);
    assert.match(textOf(field(tree, 'Kind')), /Only users without channel restrictions can create or change Log monitors/);
    assert.equal(settingsOf(tree).type.name, 'LogMonitorFields');
    assert.equal(tree.some((n) => n.type === 'button' && flat(n.children).includes('Test')), false, 'no Test for Log');
    assert.ok(tree.includes(mod.LOG_PREVIEW_HINT));
    const shown = textOf(tree);
    assert.match(shown, /The engine log on every cluster node/);
    assert.doesNotMatch(shown, /Server/);

    const saved = { ...logMonitor, scopeType: 'CHANNEL', scopeId: 'x',
        configJson: JSON.stringify({ condition: 'AT_MOST', threshold: 0, windowSeconds: 600, outcome: 'PROBLEM' }) };
    tree = expand(mount(mod, mod.MonitorEditor, { ...base, monitor: saved, monitors: [channelMonitor, saved, otherLog] })());
    assert.equal(selectOf(field(tree, 'Kind')).props.disabled, true);
    assert.match(textOf(field(tree, 'Kind')), /A saved monitor keeps its kind/);
    assert.deepEqual(options(field(tree, 'Suppressed by')), ['', '5']);
    let body;
    mod.platform.api.put = async (p, b) => { body = b; return '{}'; };
    await tree.find((n) => n.type === 'button' && flat(n.children).includes('Save changes')).props.onClick();
    assert.equal(body.monitorType, 'LOG_EVENTS');
    assert.equal(body.scopeType, 'ALL');
    assert.equal(body.scopeId, null);
    assert.deepEqual(JSON.parse(body.configJson), { minLevel: 'ERROR', condition: 'AT_MOST', threshold: 0,
        windowSeconds: 600, outcome: 'PROBLEM', clearMode: 'CONDITION', includeMatches: false });

    tree = expand(mount(mod, mod.MonitorEditor, { ...base, monitor: channelMonitor, monitors: [channelMonitor, logMonitor] })());
    assert.equal(selectOf(field(tree, 'Kind')).props.value, 'CHANNEL');
    assert.equal(selectOf(field(tree, 'Kind')).props.disabled, true);
    assert.deepEqual(options(field(tree, 'Type')), CHANNEL_TYPES);
    assert.deepEqual(options(field(tree, 'Suppressed by')), ['']);
    assert.equal(mod.scopeLabel(logMonitor, {}, {}, {}), 'Log');
    assert.equal(mod.scopeLabel(channelMonitor, {}, {}, {}), 'All channels');
});

test('the Log fields shown follow the condition, the result and how a problem clears', () => {
    const cases = [
        // condition, outcome, clearMode -> number of lines, window, clears, clear after
        ['EACH', 'NOTIFICATION', 'CONDITION', [false, false, false, false]],
        ['EACH', 'PROBLEM', 'CONDITION', [false, true, true, false]],
        ['AT_LEAST', 'NOTIFICATION', 'TIMEOUT', [true, true, false, false]],
        ['AT_LEAST', 'PROBLEM', 'MANUAL', [true, true, true, false]],
        ['AT_MOST', 'PROBLEM', 'TIMEOUT', [true, true, true, true]],
    ];
    for (const [condition, outcome, clearMode, expected] of cases) {
        const cfg = { ...mod.seedConfigs(null).LOG_EVENTS, condition, outcome, clearMode };
        const tree = deep(mod.LogMonitorFields({ cfg, setCfg: () => {} }));
        const present = ['Number of lines', 'Window (seconds)', 'Clears', 'Clear after (seconds)'].map((l) => !!field(tree, l));
        assert.deepEqual(present, expected, `${condition} ${outcome} ${clearMode}`);
        assert.deepEqual(options(field(tree, 'When')), ['EACH', 'AT_LEAST', 'AT_MOST']);
        assert.deepEqual(options(field(tree, 'Result')), ['NOTIFICATION', 'PROBLEM']);
    }
    let patch;
    const tree = deep(mod.LogMonitorFields({
        cfg: { ...mod.seedConfigs(null).LOG_EVENTS, condition: 'EACH', outcome: 'NOTIFICATION' }, setCfg: (p) => { patch = p; },
    }));
    assert.match(textOf(field(tree, 'When')), /up to 100 per node per collector run/);
    assert.deepEqual(options(field(tree, 'Minimum level')), ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL']);
    const box = tree.find((n) => n.type === 'input' && n.props.type === 'checkbox');
    assert.ok(tree.some((n) => n.type === 'label' && flat(n.children).includes('Include matched items in alerts')));
    box.props.onChange({ target: { checked: true } });
    assert.deepEqual(patch, { includeMatches: true });
    assert.deepEqual(options(field(deep(mod.LogMonitorFields({ cfg: { minLevel: 'SEVERE' }, setCfg: () => {} })), 'Minimum level')),
        ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL', 'SEVERE'], 'an unknown stored level stays visible');
});

test('the hints say what the condition and result change for a Log monitor', () => {
    const editorFor = (config) => {
        const saved = { ...logMonitor, configJson: JSON.stringify(config) };
        return expand(mount(mod, mod.MonitorEditor, { ...base, monitor: saved, monitors: [saved, otherLog] })());
    };
    const hint = (tree, label) => textOf(field(tree, label));

    let tree = editorFor({ condition: 'EACH', outcome: 'NOTIFICATION' });
    assert.match(hint(tree, 'Min consecutive breaches'), /Not used when each occurrence is a notification/);
    assert.match(hint(tree, 'Suppressed by'), /held back and never sent to actions/);
    assert.doesNotMatch(hint(tree, 'Suppressed by'), /become eligible/);
    assert.match(textOf(tree), /counts problems only/, 'the history panel says notifications are not in it');

    tree = editorFor({ condition: 'AT_LEAST', threshold: 2, windowSeconds: 300, outcome: 'NOTIFICATION' });
    assert.match(hint(tree, 'Min consecutive breaches'), /before a notification is sent/);
    assert.match(hint(tree, 'Suppressed by'), /held back and never sent to actions/);

    tree = editorFor({ condition: 'AT_LEAST', threshold: 2, windowSeconds: 300, outcome: 'PROBLEM' });
    assert.match(hint(tree, 'Min consecutive breaches'), /before a problem opens/);
    assert.match(hint(tree, 'Suppressed by'), /become eligible when the dependency clears/);
    assert.doesNotMatch(textOf(tree), /counts problems only/);

    tree = expand(mount(mod, mod.MonitorEditor, { ...base, monitor: channelMonitor, monitors: [channelMonitor] })());
    assert.match(hint(tree, 'Min consecutive breaches'), /before a problem opens/);
    assert.match(hint(tree, 'Suppressed by'), /on the same channel/);

    const eachProblem = deep(mod.LogMonitorFields({
        cfg: { ...mod.seedConfigs(null).LOG_EVENTS, condition: 'EACH', outcome: 'PROBLEM' }, setCfg: () => {},
    }));
    assert.match(textOf(field(eachProblem, 'When')), /A problem opens when at least one matching line is within the window/);
});

test('a Log config round-trips and writes only the fields that apply', () => {
    const seeded = mod.seedConfigs(null).LOG_EVENTS;
    assert.deepEqual(mod.buildConfig('LOG_EVENTS', seeded), { minLevel: 'ERROR', condition: 'AT_LEAST', threshold: 1,
        windowSeconds: 300, outcome: 'PROBLEM', clearMode: 'CONDITION', includeMatches: false });

    const stored = { minLevel: 'WARN', loggerPrefix: 'transformer', pattern: 'timed out| refused', condition: 'AT_LEAST',
        threshold: 3, windowSeconds: 900, outcome: 'PROBLEM', clearMode: 'TIMEOUT', clearAfterSeconds: 1800, includeMatches: true };
    const cfg = mod.seedConfigs({ monitorType: 'LOG_EVENTS',
        configJson: JSON.stringify({ ...stored, minLevel: 'warn', clearMode: 'timeout', includeMatches: 'true' }) }).LOG_EVENTS;
    assert.equal(cfg.threshold, '3', 'numbers become input strings');
    assert.deepEqual(mod.buildConfig('LOG_EVENTS', cfg), stored);

    assert.deepEqual(mod.buildConfig('LOG_EVENTS', { ...cfg, condition: 'EACH', outcome: 'NOTIFICATION', loggerPrefix: '  js ' }),
        { minLevel: 'WARN', loggerPrefix: 'js', pattern: 'timed out| refused', condition: 'EACH', outcome: 'NOTIFICATION', includeMatches: true });
    assert.equal(mod.buildConfig('LOG_EVENTS', { ...cfg, condition: 'AT_MOST', threshold: '0' }).threshold, 0);
    assert.throws(() => mod.buildConfig('LOG_EVENTS', { ...cfg, threshold: '0' }), { message: 'Number of lines must be at least 1.' });
    assert.throws(() => mod.buildConfig('LOG_EVENTS', { ...cfg, windowSeconds: '1.5' }), { message: 'Window (seconds) must be a whole number.' });
    assert.throws(() => mod.buildConfig('LOG_EVENTS', { ...cfg, clearAfterSeconds: '' }), { message: 'Clear after (seconds) is required.' });
    assert.equal('pattern' in mod.buildConfig('LOG_EVENTS', { ...cfg, pattern: '' }), false);
});
