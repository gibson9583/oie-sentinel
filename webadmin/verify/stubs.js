// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Minimal stand-ins for the host-provided @oie/* modules, used ONLY by
// verify.mjs to load the bundle outside a browser. They implement just enough
// surface for module evaluation and one register() call — they are not a
// simulation of the web administrator and must never grow into one. If a
// verification needs richer host behaviour than this, it wants a real browser,
// not a bigger stub.

const noop = () => {};
const Component = () => null;

/** Enough React for module scope, hook calls, and recharts' forwardRef/memo. */
const React = {
    useState: (initial) => [typeof initial === 'function' ? initial() : initial, noop],
    useEffect: noop,
    useLayoutEffect: noop,
    useMemo: (factory) => factory(),
    useRef: (initial) => ({ current: initial === undefined ? null : initial }),
    useCallback: (fn) => fn,
    useContext: () => ({}),
    useReducer: (reducer, initial) => [initial, noop],
    useImperativeHandle: noop,
    useDebugValue: noop,
    useId: () => 'stub-id',
    createElement: () => ({}),
    cloneElement: () => ({}),
    isValidElement: () => false,
    createContext: () => ({ Provider: Component, Consumer: Component }),
    forwardRef: (render) => Object.assign(Component, { render }),
    memo: (component) => component,
    Fragment: 'Fragment',
    Children: {
        map: (children) => children,
        forEach: noop,
        toArray: (children) => [].concat(children || []),
        count: () => 0,
        only: (children) => children,
    },
    Component: class {},
    PureComponent: class {},
    version: '18.0.0',
};

/**
 * Records what the plugin registered, so verify.mjs can assert on it.
 *
 * Held on globalThis rather than as a plain module export: esbuild BUNDLES
 * this file into the verification bundle (it is aliased in, not external), so
 * the bundle gets its own copy of this module. A module-local array would be
 * written by the bundle's copy and read by verify.mjs's separately-imported
 * copy — two different objects, and every assertion would see an empty list.
 * One global is the only state both copies agree on.
 */
export const registered = globalThis.__sentinelRegistered || (globalThis.__sentinelRegistered = {
    icons: [], navItems: [], views: [],
    dashboardColumns: [], commands: [],
});

/**
 * Router state, also global and for the same reason as {@link registered}: the
 * bundle holds its own copy of this module, so verify.mjs can only steer the
 * router — and see where the plugin tried to go — through something both
 * copies share.
 */
export const router = globalThis.__sentinelRouter || (globalThis.__sentinelRouter = {
    path: '/',
    navigations: [],
});

export const platform = {
    React,
    apiVersion: '4.6.0',
    ui: {
        h: () => ({ style: {}, appendChild: noop, addEventListener: noop, replaceChildren: noop }),
        toast: noop, modal: noop, clear: noop, icon: noop,
        fmtNumber: (v) => String(v), fmtDate: (v) => String(v),
    },
    api: {
        get: async () => ({}), post: async () => ({}),
        put: async () => ({}), del: async () => ({}),
    },
    store: { getState: () => null, setState: noop, subscribe: () => noop },
    router: {
        navigate: (path) => router.navigations.push(path),
        currentPath: () => router.path,
    },
    events: { on: () => noop, emit: noop },
    checkTask: () => true,
    reactView: (component) => component,
    registerIcon: (name, path) => registered.icons.push({ name, path }),
    registerNavItem: (item) => registered.navItems.push(item),
    registerView: (path, handler, meta) => registered.views.push({ path, handler, meta }),
    registerDashboardColumn: (column) => registered.dashboardColumns.push(column),
    registerCommand: (command) => { registered.commands.push(command); return noop; },
};

/* ---- @oie/web-ui ---- */

export class DataTable {
    constructor() { this.el = { appendChild: noop }; }
    setRows() {}
    selectedRows() { return []; }
    clearSelection() {}
}
export const fmtDate = (v) => String(v);
export const fmtNumber = (v) => String(v);
export const errorModal = noop;
export const detailModal = noop;
export const confirmDialog = async () => true;
export const promptDialog = async () => null;
export const saveFile = async () => {};
export const pickFile = async () => null;
