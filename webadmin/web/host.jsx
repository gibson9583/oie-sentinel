// OIE Sentinel — channel monitoring & alerting plugin.
// Published under the terms of the Mozilla Public License 2.0.
//
// Sentinel's presence OUTSIDE its own view: the open-problem column on the
// host Dashboard's channel table, and the command-palette entries. Everything
// here registers against host surfaces the rest of the plugin never touches
// (registerDashboardColumn, registerCommand) and is deliberately kept out of
// pages/ — those render inside /sentinel and may assume the plugin's own tab
// shell; these render inside somebody else's view and may assume nothing.
//
// The guiding rule: an operator watching the host Dashboard during an incident
// should not have to know Sentinel exists to find out that a channel is
// alarming.
//
// Scope: this file READS Sentinel state from where operators already are, and
// navigates into the plugin. Anything that changes a monitor, an action or a
// schedule belongs in Sentinel's own UI, not bolted onto somebody else's view.

import { platform } from '@oie/web-shell';
import { errorModal } from '@oie/web-ui';
import {
    getProblems, listMonitors, getCoreChannelGroups, getCoreTags, errText,
} from './api.js';
import { SEVERITY_META, resolveCoverage } from './ui.jsx';

const React = platform.React;

/* ---- host stylesheet ---------------------------------------------------- */

/**
 * Styles for everything Sentinel renders OUTSIDE its own view, plus the
 * plugin-owned severity variables.
 *
 * <p><b>Why this cannot live in plugin.jsx's SENTINEL_CSS.</b> That block is
 * returned from {@code SentinelView}'s render, so it is in the DOM only while
 * the Sentinel view is mounted — and it is scoped under {@code .sn-view}. The
 * Dashboard column renders in the host's Dashboard, where neither holds: the
 * user may never have opened the Sentinel tab in this session, and the cell is
 * nowhere near {@code .sn-view}. A cell styled from there silently loses every
 * rule (which is exactly what happened to the first cut of this column — the
 * severity dot had no width and vanished). Installed into {@code <head>} at
 * registration instead, so it is present from plugin load for the life of the
 * page.</p>
 *
 * <p>It also carries {@code --sn-sev-average}, because the ramp variable has
 * to resolve for {@code SeverityChip} inside the view as well — see
 * {@code SEVERITY_META} in ui.jsx for why the host's own {@code --amber}
 * cannot be used.</p>
 */
const HOST_CSS = `
/* Average: the host's --warn and --amber are the same value in dark theme and
   --amber is undefined in light, so this rung owns its colour. Orange sits
   correctly between Warning's ochre and High's red on the ordinal ramp. */
:root { --sn-sev-average: #f08c3c; }
[data-theme="light"] { --sn-sev-average: #c2410c; }

/* The "clear" green. Dark theme can use the host token as-is: --ok (#3ddc84)
   against --bg1 is about 9.6:1, so a thin ring and a small glyph are easily
   read. Light theme cannot: --ok there is #2e9e4f, which is only ~3.5:1 on
   white — enough for a filled shape (the badge used to be filled) but thin for
   a 1.5px ring and an 11px glyph, which is what "hollow" turns it into. The
   light value below is darkened to ~4.9:1 so the outline survives being
   hollow. Same pattern, and same reason, as --sn-sev-average above. */
:root { --sn-clear: var(--ok); }
[data-theme="light"] { --sn-clear: #248239; }

/* Right-aligned so the counts stack into a scannable column, like Rev Δ.
   The host maps plugin columns without passing an "align" field, so alignment
   has to be done inside the cell; the header label stays where the host puts
   it. */
.sn-host-cell { display: block; text-align: right; }

/* A FIXED circle, not a min-width pill. The first cut used a 20px min-width
   with 6px of horizontal padding, which sizes to content — a one-digit count
   sat on the 20px floor while the wider check glyph pushed its badge out to an
   oval, so "clear" rows read as visually louder than the problem rows they are
   supposed to defer to. Every single-glyph state is now exactly 20x20, and
   box-sizing keeps the outer size identical whether the border is a visible
   ring or a transparent spacer. */
.sn-host-badge {
    display: inline-flex; align-items: center; justify-content: center;
    box-sizing: border-box;
    width: 20px; height: 20px; padding: 0;
    border-radius: 999px;
    font-size: 11.5px; font-weight: 700; line-height: 1;
    font-variant-numeric: tabular-nums;
    color: #fff; cursor: pointer;
    border: 1.5px solid transparent;
}
.sn-host-badge:hover { filter: brightness(1.08); }

/* Only a count of 10+ is allowed to grow past the circle. */
.sn-host-badge.sn-wide { width: auto; min-width: 20px; padding: 0 6px; }

/* Ink for the two rungs whose fill flips lightness between themes. White on
   dark theme's Warning (#f0b541) is about 1.9:1 — the count is effectively
   unreadable — while white on light theme's Warning (#b97f0a) is fine. The
   luminance cannot be measured here because the fill is a CSS variable, so the
   rule is expressed where the theme is actually known: in CSS. Only WARNING
   and AVERAGE need it; Information, High and Disaster are dark fills in both
   themes and keep white. */
.sn-host-badge.sn-ink-dark { color: #21160a; }
[data-theme="light"] .sn-host-badge.sn-ink-dark { color: #fff; }

/* ---- the top rung (DISASTER) ----
   One principle, two expressions, because the two surfaces have opposite
   defaults. In the CHIP every rung is outlined, so Disaster earns its weight by
   being the only FILLED one. Here every badge is already filled, so filling
   buys nothing — and worse, since Disaster now shares --err with High (see
   SeverityChip for why it no longer darkens), colour alone would make the two
   badges identical. So the badge spends the one axis it has left: a halo.

   The halo is a translucent mix rather than a solid colour so it composites
   over whatever is behind it — the Dashboard's rows alternate backgrounds and
   change again on hover, and a solid ring would show a seam on some of them.
   box-shadow also draws outside the border box, so this adds emphasis without
   changing the badge's size and breaking the uniform circle. */
.sn-host-badge.sn-top {
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--err) 42%, transparent);
}

/* The chip for the same rung. Only the weight lives here — its tint, border
   and label colour are set inline from SEVERITY_META (see SeverityChip), so
   the ramp has one source of truth and no theme-specific ink is needed. */
.tag.sn-sev-top { font-weight: 650; }

/* Monitored and nothing open: hollow green ring + green check.
   Making this outlined rather than filled is what gives the column a real
   hierarchy — FILLED now means "something needs you", outlined means
   "informational". A screen of green discs competed with the counts for
   attention, which is backwards. */
.sn-host-badge.sn-clear {
    background: transparent;
    border-color: var(--sn-clear);
    color: var(--sn-clear);
    font-size: 11px;
}

/* No enabled monitor covers this channel. Also hollow, but DASHED and grey —
   three separate cues (stroke style, hue, glyph) keep it from being mistaken
   for the green ring, because "nothing is watching" and "watched and healthy"
   are opposite statements. */
.sn-host-badge.sn-none {
    background: transparent; border-color: var(--line-strong);
    border-style: dashed; color: var(--text-faint); font-weight: 600;
    cursor: default;
}
.sn-host-badge.sn-none:hover { filter: none; }

/* Coverage not yet resolved: hold the row's height without asserting anything. */
.sn-host-idle { color: var(--text-faint); }
`;

/** Installs {@link HOST_CSS} once per page. Idempotent — a second plugin load
    (hot reload during development) must not stack duplicate stylesheets. */
function installHostStyles() {
    const id = 'sentinel-host-css';
    if (typeof document === 'undefined' || document.getElementById(id)) {
        return;
    }
    const style = document.createElement('style');
    style.id = id;
    style.textContent = HOST_CSS;
    document.head.appendChild(style);
}

/* ---- cross-view intent -------------------------------------------------- */

/**
 * The store key a host-surface action uses to tell the Sentinel view what the
 * operator actually wanted before navigating to it. Values look like
 * `{ kind: 'dashboard' | 'problems' | 'unacknowledged' | 'schedules', channelId, at }`.
 *
 * <p>A store handoff rather than a URL parameter because the plugin registers
 * exactly one route. The `at` stamp is not decoration: `setState` notifies on
 * every call even when the value is unchanged, so two consecutive requests for
 * the same channel must still be distinguishable to anything comparing values
 * rather than identities.</p>
 *
 * <p>The producer sets and navigates; the CONSUMER clears. That ordering is
 * what makes the handoff safe against mount timing — the view may not exist
 * yet when the action fires, so it reads the current value on mount as well as
 * subscribing, and nothing but the page that acted on it may throw it away.</p>
 */
export const INTENT_KEY = 'sentinel:intent';

/**
 * Records an intent and, only if we are not already there, navigates to the
 * Sentinel view to act on it.
 *
 * <p><b>The conditional navigate is the whole point.</b> The host router
 * treats navigation to the path you are already on as a forced re-render
 * rather than a no-op:</p>
 *
 * <pre>
 * if (target === currentPath()) { handleChange(); return; }   // re-render in place
 * </pre>
 *
 * <p>and {@code handleChange} is async. Calling it unconditionally from an
 * already-mounted Sentinel view produced a race that discarded the handoff
 * every time: the store write notified the view's subscriber, which switched
 * tabs and remounted the target page, whose initializer read <em>and
 * cleared</em> the intent — and only then did the re-render land, rebuilding
 * the view from scratch so its own initializer found nothing and fell back to
 * the Dashboard. The commands and channel actions worked from anywhere else in
 * the console and silently did nothing from inside Sentinel.</p>
 *
 * <p>When the view is already mounted its store subscription is the delivery
 * mechanism and no navigation is needed. When it is not, there is no
 * subscriber to race with and the navigation mounts a view whose initializer
 * picks the intent up. Checking the path picks the right one of those.</p>
 */
function dispatchIntent(kind, channelId) {
    try {
        platform.store.setState(INTENT_KEY, { kind, channelId, at: Date.now() });
        if (!String(platform.router.currentPath() || '').startsWith('/sentinel')) {
            platform.router.navigate('/sentinel');
        }
    } catch (e) {
        errorModal('Sentinel', errText(e));
    }
}

/** Reads the pending intent, or null. Safe before the store has the key. */
export function readIntent() {
    try {
        const intent = platform.store.getState(INTENT_KEY);
        return intent && intent.kind ? intent : null;
    } catch (e) {
        return null;
    }
}

/** Consumes the pending intent so it cannot be replayed on the next mount. */
export function clearIntent() {
    try {
        platform.store.setState(INTENT_KEY, null);
    } catch (e) { /* the handoff is best-effort; a stale key is harmless */ }
}

/* ---- open-problem snapshot ---------------------------------------------- */

/* One poll shared by every dashboard cell. A per-cell fetch would be one
   request per channel per refresh — on a 200-channel dashboard that is the
   kind of load a monitoring plugin has no business adding to the server it is
   monitoring. */
const POLL_MS = 30000;

/* Open problems read per refresh. Deliberately finite: this feeds a glanceable
   column, and a server with more than this many problems open at once has a
   storm, which the column says plainly rather than pretending to count. */
const MAX_PROBLEMS = 500;

/* How often coverage (which channels any enabled monitor watches) is re-read,
   as a multiple of POLL_MS. Problems change minute to minute; coverage changes
   only when somebody edits a monitor, so re-reading three lists every 30s to
   catch that would be three quarters of this feature's request volume spent on
   the part that almost never moves. */
const COVERAGE_EVERY_N_POLLS = 10;   // 5 minutes at POLL_MS = 30s

const snapshot = {
    byChannel: {},      // channelId -> { count, worst }
    truncated: false,   // more open problems exist than were read
    loaded: false,      // problems have been read at least once
    coverage: { known: false, coversAll: false, covered: new Set() },
};
const listeners = new Set();
let pollTimer = null;
let pollCount = 0;

function severityRank(severity) {
    const meta = SEVERITY_META[severity];
    return meta ? meta.rank : -1;
}

/** The rungs whose badge fill is light enough in the DARK theme that white ink
    on it is unreadable (~1.9:1). See the .sn-ink-dark rule in HOST_CSS. */
const LIGHT_FILL_SEVERITIES = new Set(['WARNING', 'AVERAGE']);

function notify() {
    listeners.forEach((fn) => {
        try { fn(); } catch (e) { /* one bad cell must not stop the others */ }
    });
}

/* Same gate the in-view pages use: an ungated interval outlives logout and
   keeps resetting the engine's session-inactivity timeout. Unlike those, this
   one deliberately does NOT require the current path to be /sentinel — the
   whole point of the column is that it works while the operator is on the host
   Dashboard. */
function pollGate() {
    try {
        return !!platform.store.getState('user');
    } catch (e) {
        return false;
    }
}

/**
 * Re-reads coverage. Failures leave the previous answer in place and, on the
 * very first attempt, leave {@code known} false — which the cell renders as
 * "still resolving" rather than as "not watched". Reporting a channel as
 * unmonitored because a lookup failed would be the worst possible error for
 * this column to make: it invites somebody to go create a monitor that already
 * exists, or worse, to trust a green badge that was never earned.
 */
async function refreshCoverage() {
    try {
        const [monitors, groups, tags] = await Promise.all([
            listMonitors(), getCoreChannelGroups(), getCoreTags(),
        ]);
        snapshot.coverage = resolveCoverage(monitors, groups, tags);
    } catch (e) {
        // Keep whatever we had; see the Javadoc for why we never downgrade to
        // "not watched" on a failure.
    }
}

async function refresh() {
    if (!pollGate()) {
        return;
    }
    if (pollCount % COVERAGE_EVERY_N_POLLS === 0) {
        await refreshCoverage();
    }
    pollCount += 1;
    try {
        const page = await getProblems({ status: 'PROBLEM', page: 0, pageSize: MAX_PROBLEMS });
        const items = page && Array.isArray(page.items) ? page.items : [];
        const byChannel = {};
        items.forEach((event) => {
            if (!event || !event.channelId) return;
            const entry = byChannel[event.channelId] || { count: 0, worst: null };
            entry.count += 1;
            if (severityRank(event.severity) > severityRank(entry.worst)) {
                entry.worst = event.severity;
            }
            byChannel[event.channelId] = entry;
        });
        snapshot.byChannel = byChannel;
        snapshot.truncated = typeof page?.total === 'number' && page.total > items.length;
        snapshot.loaded = true;
        notify();
    } catch (e) {
        // A failed refresh keeps the previous snapshot rather than blanking the
        // column: stale counts during a blip are far less alarming to read than
        // every channel suddenly reporting clear.
    }
}

/** Subscribes a cell to the shared snapshot, starting the poll on the first. */
function subscribe(fn) {
    listeners.add(fn);
    if (pollTimer === null) {
        refresh();
        pollTimer = setInterval(refresh, POLL_MS);
    }
    return () => {
        listeners.delete(fn);
        if (listeners.size === 0 && pollTimer !== null) {
            // Nothing is watching — the operator navigated off the dashboard.
            clearInterval(pollTimer);
            pollTimer = null;
        }
    };
}

/* ---- dashboard column --------------------------------------------------- */

/**
 * One channel's Sentinel state in the host Dashboard's table, as a filled
 * badge: <b>the number is how many problems are open, the colour is the
 * highest severity among them.</b> Those are two independent facts and the
 * badge carries both — three Information problems and one Disaster are very
 * different situations that a count alone, or a severity alone, would render
 * identically.
 *
 * <p>Three states, and the distinction between the last two is the point of
 * the column:</p>
 * <ul>
 *   <li><b>Problems</b> — severity-coloured fill, count.</li>
 *   <li><b>Clear</b> — green fill, check. A monitor is watching this channel
 *       and nothing is open.</li>
 *   <li><b>Not watched</b> — dashed hollow outline. No enabled monitor covers
 *       this channel, so Sentinel has nothing to say about it. Rendering that
 *       the same as "clear" would be the single most misleading thing a
 *       monitoring column could do: it would report the absence of a watcher
 *       as the absence of a problem.</li>
 * </ul>
 *
 * <p>A React component rather than a rendered string because the host calls
 * {@code cell(status)} once per row per ITS refresh, which has nothing to do
 * with Sentinel's. Mounting a component lets each cell subscribe and repaint
 * itself when the shared snapshot changes, instead of showing whatever was
 * true when the host last happened to re-render.</p>
 */
function SentinelCell({ channelId }) {
    const [, setTick] = React.useState(0);
    React.useEffect(() => subscribe(() => setTick((t) => t + 1)), []);

    const entry = snapshot.loaded ? snapshot.byChannel[channelId] : null;

    if (entry) {
        const meta = SEVERITY_META[entry.worst] || { label: entry.worst || 'Open', color: 'var(--err)' };
        const title = `${entry.count} open Sentinel problem${entry.count === 1 ? '' : 's'}`
            + `, highest severity ${meta.label}`
            + (snapshot.truncated ? ` (only the first ${MAX_PROBLEMS} open problems were counted)` : '')
            + ' — click to view';
        return (
            <span className="sn-host-cell">
                {/* Only a two-digit count earns extra width; everything else
                    stays the same circle so no state outsizes another. The ink
                    class covers the two rungs whose fill is light in the dark
                    theme — see HOST_CSS. */}
                <span className={'sn-host-badge'
                        + (entry.count > 9 ? ' sn-wide' : '')
                        + (LIGHT_FILL_SEVERITIES.has(entry.worst) ? ' sn-ink-dark' : '')
                        + (meta.top ? ' sn-top' : '')}
                    title={title}
                    style={{ background: meta.color }}
                    onClick={() => dispatchIntent('problems', channelId)}>
                    {entry.count}
                </span>
            </span>
        );
    }

    // Nothing open. Which of the two "nothing open" meanings applies depends on
    // coverage, and until that has resolved we assert neither.
    const { known, coversAll, covered } = snapshot.coverage;
    if (!snapshot.loaded || !known) {
        return <span className="sn-host-cell sn-host-idle" title="Sentinel is still loading">·</span>;
    }
    if (coversAll || covered.has(channelId)) {
        return (
            <span className="sn-host-cell">
                <span className="sn-host-badge sn-clear"
                    title="Monitored by Sentinel, no open problems — click to view"
                    onClick={() => dispatchIntent('problems', channelId)}>
                    ✓
                </span>
            </span>
        );
    }
    return (
        <span className="sn-host-cell">
            <span className="sn-host-badge sn-none"
                title="No enabled Sentinel monitor covers this channel — nothing is watching it">
                –
            </span>
        </span>
    );
}

/* ---- registration ------------------------------------------------------- */

/**
 * Registers everything Sentinel contributes to host views. Called from the
 * plugin entry alongside the nav item and route.
 *
 * <p>Everything registered here READS Sentinel state or navigates into the
 * plugin; nothing mutates. The palette entries are gated through {@code task},
 * which RBAC resolves and which fails open when no authorization plugin is
 * installed, so the host filters them through the same {@code checkTask} its
 * own entries use. The servlet remains the real gate.</p>
 */
export function registerHostSurfaces() {
    // Before any registration: the Dashboard column renders outside .sn-view
    // and possibly before the Sentinel view has ever mounted, so its styles
    // cannot come from there.
    installHostStyles();

    // Every registration below is optional-chained. These are supplementary
    // surfaces, and a host that predates one of them must lose that surface
    // rather than throw out of register() and take the whole plugin — nav
    // item, route and all — down with it.
    platform.registerDashboardColumn?.({
        id: 'sentinel',
        label: 'Sentinel',
        // Last, after the engine's own statistics columns: this is a
        // supplementary signal, and pushing the message counts an operator came
        // to the Dashboard for further right would be a poor trade.
        order: 100,
        cell: (status) => {
            const channelId = status && (status.channelId || status.id);
            return channelId ? <SentinelCell channelId={channelId} /> : '';
        },
        // No connectorCell: alert events are keyed to a channel, and a
        // CONNECTION_STATUS problem's connector is a detail of the problem
        // rather than a row this column could fill honestly.
    });

    // One entry per Sentinel tab worth reaching directly. All are gated on the
    // VIEW task and not on acknowledge: filtering a list to the unacknowledged
    // rows is a read, and a NOC operator who can see problems but not close
    // them still needs to find the open ones fastest. `task` is a NAME,
    // evaluated by the host each time it renders the palette — calling
    // checkTask here instead would freeze the answer at plugin-load time,
    // which can precede login.
    //
    // The Dashboard entry is not redundant with the nav item. The palette
    // already lists the VIEW (the host reads platform.navItems()), but that
    // only navigates to /sentinel, which lands on whichever tab the view
    // decides — and from inside Sentinel it is the router's same-path
    // re-render, not a tab change. This asks for the tab explicitly, so it
    // behaves the same wherever it is invoked from.
    const commands = [
        {
            id: 'sentinel-dashboard',
            label: 'Sentinel: Dashboard',
            keywords: ['overview', 'summary', 'health', 'monitoring', 'kpi'],
            run: () => dispatchIntent('dashboard', null),
        },
        {
            id: 'sentinel-open-problems',
            label: 'Sentinel: Open problems',
            keywords: ['alert', 'alarm', 'monitoring', 'incident'],
            run: () => dispatchIntent('problems', null),
        },
        {
            id: 'sentinel-unacknowledged',
            label: 'Sentinel: Unacknowledged problems',
            keywords: ['alert', 'alarm', 'ack', 'on-call'],
            run: () => dispatchIntent('unacknowledged', null),
        },
        {
            id: 'sentinel-schedules',
            label: 'Sentinel: Schedules',
            keywords: ['maintenance', 'window', 'suppress', 'mute', 'on-call'],
            run: () => dispatchIntent('schedules', null),
        },
    ];
    commands.forEach((command) => platform.registerCommand?.({
        icon: 'helmet',
        section: 'Monitor',
        task: 'doShowSentinel',
        ...command,
    }));
}
