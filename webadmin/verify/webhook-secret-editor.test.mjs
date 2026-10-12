// OIE Sentinel — adversarial regression against the production source graph.
import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(dir, "web/pages/actions.jsx");
const work = await mkdtemp(path.join(tmpdir(), 'sentinel-regression-'));
const outfile = path.join(work, 'bundle.mjs');
after(() => rm(work, { recursive: true, force: true }));
try {
    await build({
        stdin: { contents: await readFile(source, 'utf8') + "\nexport { platform, WebhookConfigFields, REDACTED, normalizeConfig, pruneConfig };",
            sourcefile: source, resolveDir: path.dirname(source), loader: 'jsx' },
        outfile, bundle: true, format: 'esm', target: 'es2022',
        jsxFactory: 'React.createElement', jsxFragment: 'React.Fragment',
        alias: {
            react: path.join(dir, 'web/react-shim.js'),
            'react-dom': path.join(dir, 'web/react-dom-shim.js'),
            '@oie/web-shell': path.join(dir, 'verify/stubs.js'),
            '@oie/web-ui': path.join(dir, 'verify/stubs.js'),
            '@oie/web-api': path.join(dir, 'verify/stubs.js'),
        },
    });
} catch (error) {
    await rm(work, { recursive: true, force: true });
    throw error;
}
const mod = await import(pathToFileURL(outfile).href);
const React = mod.platform.React;
React.createElement = (type, props, ...children) => ({ type, props: props || {}, children });
const nodes = (node) => node == null || typeof node === 'boolean' ? []
    : Array.isArray(node) ? node.flatMap(nodes)
        : [node, ...(typeof node === 'object' ? nodes(node.children) : [])];


for (const masked of [true, false]) {
    test(`${masked ? 'retained marker blocks' : 'replacement template restores'} token click and drop controls`, () => {
        const config = { url: mod.REDACTED, bodyTemplate: masked ? mod.REDACTED : '{}', headers: [] };
        const tree = nodes(mod.WebhookConfigFields({ config, setConfig: () => {} }));
        const textarea = tree.find(node => node.type === 'textarea');
        const chips = tree.find(node => typeof node.type === 'function' && node.type.name === 'TokenChips');
        const buttons = nodes(chips.type(chips.props)).filter(node => node.type === 'button');
        assert.equal(buttons.length > 0, !masked);
        assert.equal(typeof textarea.props.onDrop === 'function', !masked);
        assert.equal(typeof textarea.props.onDragOver === 'function', !masked);
        assert.equal(textarea.props['data-sn-token-target'] === '1', !masked);
    });
}
const maskedConfig = {
    url: mod.REDACTED,
    bodyTemplate: mod.REDACTED,
    headers: { Authorization: mod.REDACTED, 'Content-Type': mod.REDACTED, 'X-Auth': mod.REDACTED },
};
test('normalizing and saving an unrelated edit preserves every webhook marker', () => {
    const outgoing = mod.pruneConfig('WEBHOOK', mod.normalizeConfig('WEBHOOK', maskedConfig));
    assert.equal(outgoing.url, mod.REDACTED);
    assert.equal(outgoing.bodyTemplate, mod.REDACTED);
    assert.deepEqual(outgoing.headers, maskedConfig.headers);
});
test('explicitly clearing a template retains the documented default-payload choice', () => {
    const outgoing = mod.pruneConfig('WEBHOOK', {
        ...mod.normalizeConfig('WEBHOOK', maskedConfig), bodyTemplate: '',
    });
    assert.equal('bodyTemplate' in outgoing, false);
    assert.equal(outgoing.url, mod.REDACTED);
    assert.deepEqual(outgoing.headers, maskedConfig.headers);
});
