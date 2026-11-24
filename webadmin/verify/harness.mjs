// OIE Sentinel: shared loader for the Log monitor tests. Bundles one web
// source with the @oie/* stubs, appends the export line a test asks for, and
// swaps in a createElement that returns plain { type, props, children } trees.
import { after } from 'node:test';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';
import RealReact from 'react';
import ReactDOMServer from 'react-dom/server';

const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const stubs = path.join(dir, 'verify/stubs.js');

export async function load(relative, exportLine) {
    const source = path.join(dir, relative);
    const work = await mkdtemp(path.join(tmpdir(), 'sentinel-test-'));
    after(() => rm(work, { recursive: true, force: true }));
    const outfile = path.join(work, 'bundle.mjs');
    await build({
        stdin: { contents: `${await readFile(source, 'utf8')}\n${exportLine}`, sourcefile: source,
            resolveDir: path.dirname(source), loader: 'jsx' },
        outfile, bundle: true, format: 'esm', target: 'es2022',
        jsxFactory: 'React.createElement', jsxFragment: 'React.Fragment',
        alias: {
            react: path.join(dir, 'web/react-shim.js'),
            'react-dom': path.join(dir, 'web/react-dom-shim.js'),
            '@oie/web-shell': stubs, '@oie/web-ui': stubs, '@oie/web-api': stubs,
        },
    });
    const mod = await import(pathToFileURL(outfile).href);
    mod.platform.React.createElement = (type, props, ...children) => ({ type, props: props || {}, children });
    return mod;
}

/** Every node of a tree, depth first. */
export const nodes = (node) => node == null || typeof node === 'boolean' ? []
    : Array.isArray(node) ? node.flatMap(nodes)
        : [node, ...(typeof node === 'object' ? nodes(node.children) : [])];

/** Like nodes(), and renders the function components the tree leaves unrendered. */
export const deep = (node) => nodes(node).flatMap((n) => (n && typeof n.type === 'function'
    ? [n, ...deep(n.type({ ...n.props, children: n.children }))] : [n]));

export const flat = (children) => (children || []).flat(Infinity);

/** The text a tree shows, joined with spaces. */
export const textOf = (node) => deep(node).filter((n) => typeof n === 'string' || typeof n === 'number')
    .join(' ').replace(/\s+/g, ' ');

/** The .field div whose <label> reads `label`. */
export function field(tree, label) {
    return tree.find((n) => n && n.type === 'div' && String(n.props.className || '').startsWith('field')
        && flat(n.children).some((c) => c && c.type === 'label' && flat(c.children).includes(label)));
}

/** Static HTML from real React, with the bundle's React swapped back after. */
export function renderHtml(mod, component, props) {
    const React = mod.platform.React;
    const tree = { ...React };
    Object.assign(React, RealReact);
    try {
        return ReactDOMServer.renderToStaticMarkup(RealReact.createElement(component, props));
    } finally {
        Object.assign(React, tree);
    }
}

/** Calls a component with useState kept between renders, so a setter shows in the next render. */
export function mount(mod, component, props) {
    const React = mod.platform.React;
    const state = [];
    return () => {
        let i = 0;
        const original = React.useState;
        React.useState = (init) => {
            const k = i++;
            if (!(k in state)) state[k] = typeof init === 'function' ? init() : init;
            return [state[k], (v) => { state[k] = typeof v === 'function' ? v(state[k]) : v; }];
        };
        try { return nodes(component(props)); } finally { React.useState = original; }
    };
}
