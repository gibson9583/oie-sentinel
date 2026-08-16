// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Web administrator entry (community-store precedent): registers ONE nav item
// and ONE view at /sentinel; all page navigation is internal tab state. Pages
// live under ./pages/ and receive no props — they pull data through ./api.js
// and shared pieces through ./ui.jsx; editors and detail panes are page-local
// state.

import { platform } from '@oie/web-shell';
import { canManage, canManageSettings } from './ui.jsx';
import { INTENT_KEY, clearIntent, readIntent, registerHostSurfaces } from './host.jsx';
import { DashboardPage } from './pages/dashboard.jsx';
import { ProblemsPage } from './pages/problems.jsx';
import { MonitorsPage } from './pages/monitors.jsx';
import { ActionsPage } from './pages/actions.jsx';
import { MaintenancePage } from './pages/maintenance.jsx';
import { SettingsPage } from './pages/settings.jsx';

const React = platform.React;

/* Global stylesheet, injected by the root view. No CSS isolation exists, so
   every custom rule is .sn- prefixed and scoped under .sn-view. Colors come
   from host design tokens only (both themes automatic). The tab strip itself
   uses the host .tabs/.tab classes, same markup as community-store. Tailwind
   utilities are unsafe here unless a host file already emits them — anything
   custom belongs below. */
const SENTINEL_CSS = `
/* Pills never wrap their text into tall ovals. */
.sn-view .tag { white-space: nowrap; }

/* Severity chip: dot + label inside a host .tag pill. */
.sn-view .sn-sev { display: inline-flex; align-items: center; gap: 5px; }
.sn-view .sn-sev-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }

/* Severity toggle chips in the Problems filter bar. */
.sn-view .sn-sev-toggle { cursor: pointer; opacity: .45; background: none; }
.sn-view .sn-sev-toggle.on { opacity: 1; background: color-mix(in srgb, var(--bg3) 60%, transparent); }

/* KPI tiles (dashboard). */
.sn-view .sn-tiles { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.sn-view .sn-tile { padding: 10px 14px; min-width: 108px; }
.sn-view .sn-tile-click { cursor: pointer; }
.sn-view .sn-tile-click:hover { background: var(--bg3); }
.sn-view .sn-tile-value { font-size: 22px; font-weight: 700; line-height: 1.2; font-variant-numeric: tabular-nums; }
.sn-view .sn-tile-label { font-size: 10.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-dim); margin-top: 2px; }
.sn-view .sn-tile-hint { font-size: 10px; color: var(--text-faint); margin-top: 2px; }

/* Helper text outside a .field (the host only styles .hint inside .field). */
.sn-view .sn-hint { font-size: 11px; color: var(--text-faint); }

/* Filter bar (Problems) and condition builder rows. */
.sn-view .sn-filterbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.sn-view .sn-filterbar input, .sn-view .sn-filterbar select { max-width: 220px; }
.sn-view .sn-sev-toggles { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.sn-view .sn-cond-row { display: flex; gap: 8px; align-items: flex-start; margin-bottom: 8px; flex-wrap: wrap; }
.sn-view .sn-conds { margin-bottom: 8px; }
.sn-view .sn-picker-err { display: inline-flex; gap: 6px; align-items: center; }

/* DataTable host (useDataTable mounts DataTable.el inside). */
.sn-view .sn-dt-host { display: flex; flex-direction: column; min-height: 0; flex: 1; }

/* Charts and sparklines. */
.sn-view .sn-spark { display: inline-block; vertical-align: middle; line-height: 0; }
.sn-view .sn-chart { min-width: 0; }

/* Empty state for lists. */
.sn-view .sn-empty { color: var(--text-dim); padding: 18px 0; text-align: center; }

/* Open-problem row tint (Problems / Recent problems tables). */
.sn-view table.dt tbody tr.sn-problem,
.sn-view table.dt tbody tr.sn-problem:hover {
    background: color-mix(in srgb, var(--err) 14%, var(--bg1)) !important;
    box-shadow: inset 4px 0 0 var(--err);
}
`;

const TABS = [
    { key: 'dashboard', label: 'Dashboard', component: DashboardPage },
    { key: 'problems', label: 'Problems', component: ProblemsPage },
    { key: 'monitors', label: 'Monitors', component: MonitorsPage },
    { key: 'actions', label: 'Actions', component: ActionsPage },
    { key: 'schedules', label: 'Schedules', component: MaintenancePage },
    { key: 'settings', label: 'Settings', component: SettingsPage, manageOnly: true },
];

/* Which tab an intent from a host surface (see host.jsx) lands on. For the
   kinds carrying a payload the page needs, the intent is left in the store for
   that page to consume and clear — this only decides where to look. */
const INTENT_TABS = {
    dashboard: 'dashboard',
    problems: 'problems',
    unacknowledged: 'problems',
    schedules: 'schedules',
    newMonitor: 'monitors',
};

/* Kinds that are nothing but "open this tab". No page reads them, so nobody
   downstream would ever clear them — the shell must, or the next time this
   view mounts it would obey a months-old palette click. */
const SHELL_CONSUMED = new Set(['dashboard', 'schedules']);

function tabForIntent(intent) {
    return intent && INTENT_TABS[intent.kind];
}

function SentinelView() {
    // Read the pending intent in the initializer as well as subscribing below:
    // a palette command sets it and navigates, so it is already there when
    // this view first mounts and there is no notification left to catch.
    const [tab, setTab] = React.useState(() => {
        const intent = readIntent();
        const target = tabForIntent(intent);
        if (target && SHELL_CONSUMED.has(intent.kind)) clearIntent();
        return target || 'dashboard';
    });
    /* Bumped on each arriving intent and mixed into the page key, so a second
       hand-off REMOUNTS the target page. Without it, an intent naming a
       different channel while that tab is already open changes nothing: pages
       read the intent in a state initializer (to get the first fetch right),
       and an initializer only runs at mount. */
    const [intentStamp, setIntentStamp] = React.useState(0);

    React.useEffect(() => {
        try {
            return platform.store.subscribe(INTENT_KEY, (value) => {
                // Null is the consuming page clearing it, not a new request.
                const target = tabForIntent(value);
                if (target) {
                    setTab(target);
                    setIntentStamp((n) => n + 1);
                    if (SHELL_CONSUMED.has(value.kind)) clearIntent();
                }
            });
        } catch (e) {
            return undefined;   // no store: host surfaces simply do not hand off
        }
    }, []);

    // checkTask is synchronous and fails open without RBAC; hidden-tab decision
    // is cosmetic — the servlet's permissions are the real gate. Settings is
    // reachable by either tier: Manage Settings is a standalone permission, so
    // a user holding it without full Manage must still see the tab.
    const visible = TABS.filter((t) => !t.manageOnly || canManage() || canManageSettings());
    const active = visible.find((t) => t.key === tab) || visible[0];
    const Page = active.component;
    return (
        <div className="view sn-view flex flex-col flex-1 min-h-0">
            <style>{SENTINEL_CSS}</style>
            <div className="tabs flex-none">
                {visible.map((t) => (
                    <button key={t.key}
                        className={`tab ${active.key === t.key ? 'active' : ''}`}
                        onClick={() => setTab(t.key)}>
                        {t.label}
                    </button>
                ))}
            </div>
            <div className="view-body">
                <Page key={`${active.key}-${intentStamp}`} />
            </div>
        </div>
    );
}

export function register() {
    // Sentinel's own glyph — a front-view spartan helmet (dome, cheek guards,
    // angled eye slits, nose guard) as a 24x24 stroke path in the host icon
    // format. Hosts predating platform.registerIcon fall back to the info
    // glyph, never a blank.
    platform.registerIcon?.('helmet', 'M9 21l-3-1.5V11a6 6 0 0 1 12 0v8.5L15 21M8 11.8h8M12 11.8V19.5M8.4 13.8l1.8.9M15.6 13.8l-1.8.9');
    platform.registerNavItem({
        id: 'sentinel',
        label: 'Sentinel',
        icon: 'helmet',
        path: '/sentinel',
        section: 'Monitor',
        order: 40,
        // Declared in SentinelServicePlugin's ExtensionPermissions (VIEW →
        // doShowSentinel); RBAC merges it, without RBAC the nav always shows.
        task: 'doShowSentinel',
    });
    platform.registerView('/sentinel', platform.reactView(SentinelView), { title: 'OIE Sentinel' });
    // Registered after the icon, which the dashboard column and channel
    // actions both reference by name.
    registerHostSurfaces();
}
