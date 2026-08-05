// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// 'react-dom' alias target for bundled npm deps (recharts). The host exposes no
// ReactDOM handle, so this stubs the few entry points recharts references.
// createPortal is only exercised when a Tooltip gets an explicit `portal` prop —
// Sentinel never passes one, so the in-place fallback below is never rendered
// through a real portal.
import { platform } from '@oie/web-shell';

const React = platform.React;

export function createPortal(children /* , container */) {
    return children;
}

export function findDOMNode() {
    return null;
}

export function flushSync(fn) {
    return typeof fn === 'function' ? fn() : undefined;
}

export function unstable_batchedUpdates(fn, a) {
    return typeof fn === 'function' ? fn(a) : undefined;
}

export const version = React && React.version;

export default { createPortal, findDOMNode, flushSync, unstable_batchedUpdates, version };
