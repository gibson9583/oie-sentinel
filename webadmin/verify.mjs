// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Load-time smoke test for the web bundle. Runs after every build.
//
// WHY THIS EXISTS
//
// The plugin is a single ES module the web administrator imports and whose
// register() it calls. If that module throws while EVALUATING — before
// register() is ever reached — the host has nothing to register, and Sentinel
// simply is not in the UI. There is no server-side symptom: the engine starts
// clean, the extension is installed, the schema migrates, the scheduler runs.
// Everything looks healthy and the plugin is invisible.
//
// Nothing else in the toolchain catches that. Specifically, a stray backtick
// inside one of the CSS template literals (easy to type when quoting a
// selector in a comment) ends the literal early, and the CSS after it compiles
// as JavaScript. When that text happens to be valid JS — `.tag.sn-sev-top`
// parses perfectly well as member access and subtraction — esbuild succeeds,
// `node --check` succeeds, the zip builds, and the failure appears only in a
// browser. That shipped once. Checking the output for intact CSS markers does
// not catch it either, because a balanced pair of backticks can destroy the
// middle of a stylesheet while the tail re-enters a literal and survives.
//
// The only reliable check is to evaluate the module, so that is what this
// does: rebuild the same source graph with the @oie/* imports aliased to
// stubs, import it, and call register(). Anything that throws at import time
// fails the build here rather than in front of an operator.
import { build } from 'esbuild';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const dir = path.dirname(fileURLToPath(import.meta.url));
const stubs = path.join(dir, 'verify/stubs.js');

/** The DOM surface the bundle touches at load/register time. */
function installDomStubs() {
    const element = () => ({
        style: {}, id: '', textContent: '',
        setAttribute() {}, appendChild() {}, remove() {}, replaceChildren() {},
        addEventListener() {}, click() {},
    });
    globalThis.document = {
        createElement: element,
        getElementById: () => null,
        head: element(),
        body: element(),
    };
    globalThis.window = globalThis;
}

async function main() {
    const workDir = await mkdtemp(path.join(tmpdir(), 'sentinel-verify-'));
    const outfile = path.join(workDir, 'bundle.mjs');
    try {
        // Same entry, same options as the real build, except @oie/* resolve to
        // stubs instead of staying external — so this exercises the very
        // module graph that ships, not a rewritten copy of it.
        await build({
            entryPoints: [path.join(dir, 'web/plugin.jsx')],
            outfile,
            bundle: true,
            format: 'esm',
            target: 'es2022',
            jsx: 'transform',
            jsxFactory: 'React.createElement',
            jsxFragment: 'React.Fragment',
            alias: {
                'react': path.join(dir, 'web/react-shim.js'),
                'react-dom': path.join(dir, 'web/react-dom-shim.js'),
                '@oie/web-shell': stubs,
                '@oie/web-ui': stubs,
                '@oie/web-api': stubs,
            },
        });

        installDomStubs();

        const plugin = await import(pathToFileURL(outfile).href);
        if (typeof plugin.register !== 'function') {
            throw new Error('the bundle does not export a register() function');
        }
        plugin.register();

        // register() completing is the headline assertion; these confirm it
        // actually did its job rather than returning early past a swallowed
        // error. Kept deliberately shallow — this is a smoke test, and every
        // assertion added here is one the real UI has to keep satisfying.
        //
        // Read from the global, not from an import of stubs.js: the stub is
        // bundled INTO the verification bundle, so importing it here would
        // yield a second copy that never saw the registrations.
        const registered = globalThis.__sentinelRegistered
            || { navItems: [], views: [], dashboardColumns: [], commands: [] };
        const expectations = [
            ['nav item', registered.navItems.length >= 1],
            ['view at /sentinel', registered.views.some((v) => v.path === '/sentinel')],
            ['dashboard column', registered.dashboardColumns.length >= 1],
            ['palette commands', registered.commands.length >= 1],
        ];
        const failed = expectations.filter(([, ok]) => !ok).map(([what]) => what);
        if (failed.length) {
            throw new Error(`register() ran but did not register: ${failed.join(', ')}`);
        }

        console.log(`verified web/plugin.js loads: ${registered.navItems.length} nav item(s), `
            + `${registered.dashboardColumns.length} dashboard column(s), `
            + `${registered.commands.length} command(s)`);
    } catch (error) {
        console.error('\nweb/plugin.js FAILS TO LOAD — the plugin would not appear in the UI.\n');
        console.error(error && error.stack ? error.stack : error);
        console.error('\nIf the message mentions an undefined property or an unexpected token,');
        console.error('check the CSS template literals (HOST_CSS in web/host.jsx, SENTINEL_CSS in');
        console.error('web/plugin.jsx) for a stray backtick — it ends the literal early and the');
        console.error('CSS after it is compiled as JavaScript.\n');
        process.exitCode = 1;
    } finally {
        await rm(workDir, { recursive: true, force: true });
    }
}

await main();
