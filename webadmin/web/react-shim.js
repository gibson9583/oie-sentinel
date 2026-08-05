// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// 'react' alias target for bundled npm deps (recharts). Re-exports the host's
// one React instance so no second React ever enters the bundle — a duplicate
// would break hooks/context and render into a dead tree.
import { platform } from '@oie/web-shell';

const React = platform.React;

export default React;
export const {
    Children,
    Component,
    Fragment,
    Profiler,
    PureComponent,
    StrictMode,
    Suspense,
    cloneElement,
    createContext,
    createElement,
    createRef,
    forwardRef,
    isValidElement,
    lazy,
    memo,
    startTransition,
    useCallback,
    useContext,
    useDebugValue,
    useDeferredValue,
    useEffect,
    useId,
    useImperativeHandle,
    useInsertionEffect,
    useLayoutEffect,
    useMemo,
    useReducer,
    useRef,
    useState,
    useSyncExternalStore,
    useTransition,
    version,
} = React;
