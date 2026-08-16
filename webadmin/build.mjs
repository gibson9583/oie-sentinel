// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Build the Sentinel web administrator plugin: web/plugin.jsx (+ its relative
// imports and npm deps) -> web/plugin.js, the file plugin.json's client.entry
// points at. Absolute entry/outfile paths (tls-manager precedent) so Maven's
// frontend-maven-plugin can invoke this from any cwd.
//
// Classic JSX runtime: esbuild emits bare React.createElement(...) calls, so
// `const React = platform.React;` must sit at module scope in every JSX file.
//
// @oie/* stay EXTERNAL — the host page's import map resolves them at runtime to
// its single framework instance. Everything else (recharts and its deps) is
// bundled. recharts imports 'react'/'react-dom' directly; bundling a second
// React would break hooks/context (the host page's import map has no 'react'
// entry, so externalizing would 404). The aliases below point both specifiers
// at tiny shims that re-export the host's platform.React instead.
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const dir = path.dirname(fileURLToPath(import.meta.url));

await build({
    entryPoints: [path.join(dir, 'web/plugin.jsx')],
    outfile: path.join(dir, 'web/plugin.js'),
    bundle: true,
    format: 'esm',
    target: 'es2022',
    jsx: 'transform',
    jsxFactory: 'React.createElement',
    jsxFragment: 'React.Fragment',
    external: ['@oie/web-api', '@oie/web-ui', '@oie/web-shell'],
    alias: {
        'react': path.join(dir, 'web/react-shim.js'),
        'react-dom': path.join(dir, 'web/react-dom-shim.js'),
    },
});
console.log('built web/plugin.js');
// A bundle that builds but throws while EVALUATING is invisible to every other
// check in this toolchain and to the engine — see verify.mjs.
await import('./verify.mjs');
