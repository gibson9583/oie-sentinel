import {build} from 'esbuild';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const dir=path.dirname(fileURLToPath(import.meta.url));
await build({entryPoints:[path.join(dir,'entry.jsx')],outfile:path.join(dir,'bundle.js'),bundle:true,format:'iife',jsxFactory:'React.createElement',jsxFragment:'React.Fragment',alias:{'@oie/web-shell':path.join(dir,'host.jsx'),'@oie/web-ui':path.join(dir,'host.jsx'),'@oie/web-api':path.join(dir,'host.jsx')}});
