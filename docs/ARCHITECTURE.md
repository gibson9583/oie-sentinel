# OIE Sentinel — Zabbix-inspired monitoring & alerting plugin

## Context

OIE (this workspace's Mirth Connect fork) has no channel-activity monitoring or trigger-based
alerting today. The core engine's own alert system (`AlertController`/`AlertWorker`) only fires
on channel *error* events, delivers via email or in-app notice only, and nothing in any installed
plugin tracks message throughput, detects silence, or watches connector up/down state over time.
`simple-channel-history` looked like a plausible reuse target but is channel-*configuration*
version control, not activity data — confirmed it has no relevant columns/hooks.

Sentinel adds that missing layer as a new, standalone plugin: a Zabbix-style monitor/trigger/action
engine over channel activity, with its own schema, a background collector + evaluator, and a full
multi-page web dashboard (no Swing client — server + web UI only, per the original request). Decisions
made along the way: repo name **oie-sentinel**, monitor types **inactivity, low-volume, anomaly
detection, connection-status**, alert delivery **Email + Channel(VM Router) + SNS** only for v1,
**all 5 DB vendors** (derby/mysql/postgres/oracle/sqlserver), full scope in one plan (not phased),
and **user actions audited into OIE's core event log**.

This plan was cross-checked against two independent architecture reviews and spot-verified against
engine source (`/mnt/Development/OIE/oie`) for every load-bearing claim below.

## Module layout (no client/ module)

Follows the RBAC plugin's structure minus the Swing client, since this plugin is explicitly server
+ web-UI only:

```
oie-sentinel/
├── pom.xml                 aggregator (groupId org.openintegrationengine, matches tls-manager-plugin)
├── shared/                 SentinelServletInterface (JAX-RS), DTOs, enums — package
│                            org.openintegrationengine.plugins.sentinel.shared
├── server/                 all Java implementation — package
│                            org.openintegrationengine.plugins.sentinel.server
├── package/                plugin.xml, sqlmap*.xml, assembly.xml, oie.json
├── webadmin/                the ENTIRE dashboard UI, embedded in the OIE Web Administrator via the
│                            oie-web-client plugin contract (see "Web UI" below) — no standalone SPA
└── build.sh                orchestrates mvn package (which builds webadmin/ via frontend-maven-plugin) + zip
```

Engine jars pulled in via `scripts/install-engine-jars.sh` (RBAC's pattern: installs mirth-server,
donkey-server, mirth-crypto, mirth-client-core into `~/.m2` from a local engine checkout — the
repsy mirror doesn't carry this engine version).

**`plugin.xml` generation**: hand-written, following role-based-access-control's proven approach
(rather than tls-manager-plugin's `@MirthApiProvider` annotation-processor generator) — chosen
deliberately since the generator's exact annotation shape couldn't be verified against a real build
in this environment, and a hand-written `plugin.xml` is fully inspectable/deterministic. Revisit if
the ~30+ REST operations make manual upkeep painful.

## Database schema (9 tables, MyBatis, per-vendor DDL)

Follows RBAC's established pattern exactly: `SentinelMigrator extends Migrator`, registered via
`<migratorClass>` in `plugin.xml`, self-tracks its own `schema_version` under
`ConfigurationController` (the base engine `Migrator` has no built-in version tracking — confirmed
RBAC only added `detectAndAlignSchemaVersion()` reactively; we include it from day one), runs
`executeScript("/" + getDatabaseType() + "-sentinel-tables.sql")` per vendor, and
`getUninstallStatements()` returns child-first `DROP TABLE`s. All CRUD SQL lives in MyBatis XML
under namespace `Sentinel`, accessed via `SqlConfig.getInstance().getSqlSessionManager()` — no raw
JDBC, no annotation mappers.

**Correction from the initial draft**: dropped a Sentinel-owned channel-group table. OIE core
already has `com.mirth.connect.model.ChannelGroup` + `ChannelController.getChannelGroups(Set<String>)`
(verified: `ChannelController.java:99`) — the exact grouping operators already manage in the channel
list. GROUP-scoped monitors/actions/maintenance-windows store a core `channel_group.id` directly in
`scope_id`; the web UI's channel/group pickers call a thin `GET /core/channelGroups` passthrough.
No new CRUD, no membership table, no duplicate taxonomy to desync.

1. **`sentinel_monitor`** — `id, name UNIQUE, description, monitor_type
   (INACTIVITY|LOW_VOLUME|ANOMALY|CONNECTION_STATUS), scope_type (CHANNEL|GROUP|TAG|ALL), scope_id
   (CHAR(36) NULL — channel id or core channel-group id), enabled, severity
   (INFORMATION|WARNING|AVERAGE|HIGH|DISASTER — Zabbix's exact scale), config_json (type-specific,
   see below), min_consecutive_breaches (hysteresis, default 1), suppressed_by_monitor_id (nullable
   self-FK, dependency suppression), audit columns`.
2. **`sentinel_trigger_state`** — one row per `(monitor_id, channel_id, metadata_id)` — `metadata_id`
   is required (not in the original draft) because a channel has independent source/destination
   connectors that can each be up/down for CONNECTION_STATUS monitors. `state`
   (**INSUFFICIENT_DATA**|OK|PROBLEM — the 3rd state matters: without it a monitor with no history
   yet either lies "OK" or gives no signal it's still warming up), `consecutive_breach_count`,
   `last_value_json` (transparency: `{current, mean, stddev, z, tier, sampleCount}` for the alert
   detail view), `open_alert_event_id` (back-reference so the evaluator knows which alert row to
   resolve), `last_change_time`, `last_evaluated_time`.
3. **`sentinel_channel_activity_sample`** — raw collector output, `BIGINT` identity (high row count
   expected): `channel_id, sample_time, received_delta, sent_delta, error_delta, filtered_delta,
   queued_snapshot` (queued is a point-in-time gauge, not a delta — confirmed
   `ChannelStatistics.getQueued()`/`DonkeyEngineController` source-queue-size read is instantaneous).
   Index `(channel_id, sample_time)`. Default retention 7 days.
4. **`sentinel_channel_activity_trend`** — hourly rollup: `channel_id, hour_bucket UNIQUE-with-channel,
   received_sum, sent_sum, error_sum, avg_queued, min_queued, max_queued`. Justification: the
   anomaly detector's 28-day hour-of-day lookup would otherwise scan ~40k raw rows per
   channel/evaluation; against this table it's a ~672-row indexed scan. Default retention 90 days.
5. **`sentinel_connector_status_event`** — event-driven (a row only on state transition, not every
   poll, to avoid bloat): `channel_id, metadata_id, previous_state, new_state, changed_time`.
6. **`sentinel_alert_event`** — the "Problems" list: `monitor_id, channel_id, metadata_id, severity,
   status (PROBLEM|RESOLVED), message, opened_time, resolved_time, acknowledged_by,
   acknowledged_time, ack_comment, details_json, suppressed` (locked at creation time from a
   maintenance-window/dependency check, never re-evaluated later). Indexes:
   `(monitor_id, channel_id)`, `(status)`, `(opened_time)`.
7. **`sentinel_action`** — `id, name UNIQUE, enabled, action_type (EMAIL|CHANNEL|SNS),
   condition_json` (Zabbix-style field/operator/value condition rows — `SEVERITY`, `MONITOR_TYPE`,
   `CHANNEL`, `CHANNEL_GROUP`, `EVENT_TYPE` fields with `=`/`!=`/`>=`/`IN` operators — evaluated
   against each new/resolved `alert_event`, no monitor↔action join table needed), `operation_mode
   (ON_PROBLEM|ON_RESOLVE|BOTH)`, `repeat_interval_seconds`/`max_repeats` (nullable — optional
   Zabbix-style escalation re-notify while a problem stays open), `config_json` (delivery config;
   secret fields pre-encrypted via `ConfigurationController.getEncryptor()` before storage, and
   **redacted on every GET response** — RBAC's CLAUDE.md documents pulling `getServerSettings` from
   its allow-list specifically because it leaked SMTP credentials; same discipline applies here).
8. **`sentinel_action_dispatch_log`** — troubleshooting/audit trail for notification delivery:
   `alert_event_id (FK CASCADE)`, `action_id (FK SET NULL — deleting an action shouldn't destroy its
   dispatch history)`, `dispatch_time, success, error_message`.
9. **`sentinel_maintenance_window`** — one-off and recurring windows (schema v2 added
   recurrence + modes): `name, scope_type, scope_id, window_mode (SUPPRESS|ACTIVE),
   repeat_type (NONE|WEEKLY|MONTHLY), days_of_week, days_of_month, start_time, end_time,
   active_from, active_until (nullable bounds for recurring), enabled`. ACTIVE windows are
   alerting schedules: covered channels notify only inside the window's times.

Uninstall order (FK-safe, child-first): `action_dispatch_log → alert_event → trigger_state →
connector_status_event → activity_trend → activity_sample → action → maintenance_window → monitor`.

**Pagination portability**: `sentinel_alert_event` and the activity tables will have real row
counts, unlike RBAC's tiny tables. The engine's own message-browser pagination
(`donkey/donkeydbconf/{default,derby,oracle,sqlserver}.xml`) uses dialect-specific SQL for
`LIMIT/OFFSET` vs the ANSI `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY` clause. In implementation this
resolved to two idioms: postgres/mysql use `LIMIT #{limit} OFFSET #{offset}`; derby/oracle/sqlserver
(modern versions of all three support the ANSI row-limiting clause) use
`OFFSET #{offset} ROWS FETCH NEXT #{limit} ROWS ONLY` — avoiding the older `ROWNUM`-subquery /
`ROW_NUMBER() OVER` windowing workarounds. Sort column/direction are allow-listed in the Java
repository layer before being substituted raw (`${sortColumn} ${sortDir}`) into the mapper's
`ORDER BY` — the one place in the persistence layer with a real injection consideration.

**Monitor `config_json` shapes**:
- `INACTIVITY`: `{ noDataForSeconds }`
- `LOW_VOLUME`: `{ windowSeconds, compareTo: FIXED|BASELINE_RELATIVE, minCount?, baselinePercent?, baselineLookbackDays? }`
- `ANOMALY`: `{ metric: RECEIVED|SENT|ERROR, zScoreThreshold, direction: LOW_ONLY|HIGH_ONLY|BOTH, baselineWindowDays, useWeekendBucket }`
- `CONNECTION_STATUS`: `{ alertOnStates: [...ConnectionStatusEventType], minDurationSeconds }`

## Collection & evaluation pipeline

Four scheduled jobs, using **Quartz** (`org.quartz.Job`) — the engine's own established convention
for scheduled plugin work (`DataPrunerJob` via `PollConnectorJobHandler`, already on the runtime
classpath, no new dependency), registered from `SentinelServicePlugin.start()`. Each job body is
wrapped in its own try/catch so one bad tick can't take down the schedule.

| Job | Interval | Responsibility |
|---|---|---|
| `ActivityCollectorJob` | 30-60s, configurable | Poll `EngineController.getChannelStatisticsList(null, false)` (cheap in-memory counters, confirmed no DB hit). Diff against an in-memory `lastStats` map to compute deltas; write `activity_sample` rows. Connector up/down state is **not** collected here: `ConnectionStatusLogController.getConnectionStatesForServer(...)` NPEs whenever a non-TCP connector has reported state (`DefaultConnectionLogController:245` only populates its count maps from `ConnectorCountEvent`), so Sentinel instead registers its own `EventListener` on `EventType.CONNECTION_STATUS` (`SentinelConnectorStatusListener`) which maintains the live per-connector state map and writes `connector_status_event` rows on transitions — dropping only the transient `INFO`/`FAILURE` message types, so `DISCONNECTED` is a recorded state. |
| `TriggerEvaluatorJob` | 60s, configurable | Resolve each enabled monitor's scope to concrete **deployed** (`DeployedState.STARTED`, not just "not undeployed" — a paused channel's source isn't polling, evaluating INACTIVITY against it is a guaranteed false positive) channel list, evaluate type-specific rule, apply hysteresis, transition trigger_state, open/resolve `alert_event`, dispatch actions (incl. escalation repeat-checks for still-open problems). Auto-resolves alerts for channels that leave STARTED. |
| `ActivityRollupJob` | hourly at :05 | Roll the just-completed hour from `activity_sample` → `activity_trend` (delete+reinsert, idempotent — avoids vendor-inconsistent `MERGE`/`ON CONFLICT` syntax). |
| `RetentionPruneJob` | daily | Prune `activity_sample` > 7d, `activity_trend` > 90d, **only RESOLVED** `alert_event` > 180d (open PROBLEM rows are never pruned regardless of age). Cascades `action_dispatch_log`. |

**Counter-delta correctness** (a real bug caught in review): `ChannelStatistics` counters are
cumulative and can go backwards on channel redeploy, server restart, or an operator hitting "Clear
Statistics" (a real, code-confirmed path — `DonkeyEngineController` → `dao.resetStatistics`). Clamp
`delta = Math.max(0, current - previous)`; on first observation of a channel, record `delta = 0`
rather than the full cumulative value. Document as a known limitation (RBAC-CLAUDE.md style): a
manual "Clear Statistics" during Sentinel's uptime can cause one legitimate false PROBLEM tick on
volume-based monitors for that channel.

**Anomaly detection algorithm** (hour-of-day seasonality, 3-tier baseline fallback so a brand-new
channel gets coverage almost immediately and full confidence within ~2 weeks):
```
tier1: activity_trend rows for this channel, same hour-of-day AND same weekend/weekday,
       within baselineWindowDays        — need ≥8 samples
tier2: drop the weekend/weekday filter  — need ≥16 samples
tier3: drop the hour-of-day filter too  — need ≥30 samples
below tier3 threshold → trigger_state = INSUFFICIENT_DATA, no evaluation, no alert

mean/stddev over the resolved tier's received_sum values (n-1 denominator)
if stddev ~0 (flat historical baseline): breach = |current - mean| > max(1, mean * 0.10)
else: z = (current - mean) / stddev; breach per `direction` vs `zScoreThreshold`
```
`INSUFFICIENT_DATA → OK` never notifies; only `→PROBLEM` and `PROBLEM→` transitions do. The tiered
hour-of-day/weekend bucketing happens in Java (portable via `java.time`), not in SQL — the mapper
just returns all trend rows in the lookback window and `BaselineResolver` does the tiering, shared by
`AnomalyEvaluator` and `LowVolumeEvaluator`'s `BASELINE_RELATIVE` mode.

**Suppression** (maintenance windows + `suppressed_by_monitor_id` dependency): evaluated once per
alert *creation*, stored on the row (`suppressed` flag), never re-checked later — evaluation and
hysteresis counting continue underneath even while suppressed, avoiding an alert burst the instant
a window/dependency clears. Dependency graph must be cycle-checked on save (`MonitorService`, simple
DFS) and evaluated in topological order each tick. A dependency on a GROUP-scoped monitor that
doesn't cover the dependent's channel fails open (never silently swallows a real alert).

## Alert delivery (Email, Channel, SNS)

All three confirmed against engine source, no new plumbing needed for Email/Channel:

- **Email**: `com.mirth.connect.server.util.ServerSMTPConnectionFactory.createSMTPConnection().send(to, cc, subject, body)` — reuses the engine's already-configured SMTP settings (`ConfigurationController.getServerSettings()`), Apache Commons Email under the hood. No new SMTP config surface in Sentinel at all.
- **Channel**: `new com.mirth.connect.server.userutil.VMRouter().routeMessageByChannelId(channelId, rawMessage)` → `EngineController.dispatchRawMessage(...)`. This is the *exact* mechanism the core alert system's `ChannelProtocol` and the VM Router/"Channel Writer" destination connector both already use — not a workaround, the canonical way to inject a message into a channel from plugin code. Alert JSON goes in the message body; alert fields also flattened into the `RawMessage`'s source map so the receiving channel's transformer can reference them without parsing JSON. This is what makes Email+Channel+SNS sufficient for v1 — a channel destination (HTTP Sender, etc.) lets users fan out to Slack/Teams/PagerDuty/SMS themselves without Sentinel building native integrations for each.
- **SNS**: AWS SDK v2 (`software.amazon.awssdk:sns` + `:sts`, default/compile scope — engine doesn't bundle these, confirmed via `sqs-source-connector/server/pom.xml`'s identical shape), mirroring `sqs-source-connector`'s `AwsConnectorCredentials` factory (DEFAULT/STATIC/ROLE auth types, `StsAssumeRoleCredentialsProvider` for cross-account). Unlike that connector (which stores AWS secrets in plaintext), Sentinel encrypts `secretAccessKey` via `ConfigurationController.getInstance().getEncryptor()` before persisting.

## Audit logging (two-layer, matching RBAC's actual pattern)

Verified directly: `@MirthOperation.auditable()` defaults to `true`, and the engine's own
`AuthorizationController.auditAuthorizationRequest()` *automatically* dispatches a generic
`ServerEvent` for every such call, dumping each `@Param` argument. RBAC doesn't override this
default on any mutating operation — it only sets `auditable=false` on pure reads, and *adds* its own
curated `RbacAuditLog` on top purely for readability. Sentinel follows the identical pattern:
- Leave `auditable` at its default (`true`) on every mutating `SentinelServletInterface` method — free framework-level audit trail satisfying "ensure user actions are audited."
- Set `auditable=false` on GET/list methods.
- `SentinelAuditLog` (mirrors `RbacAuditLog`: `EventController` + `serverId`) dispatches curated, human-readable `ServerEvent`s from the Service layer for monitor/action/maintenance-window CRUD and alert acknowledge. Channel attribution rides event *attributes*, not setters — `ServerEvent.setChannelName` does not exist and `setChannelId` writes a dead field with no backing column. Instead the log adds an attribute literally named `"channel"` whose value is `channel.toAuditString()` (`Channel[id=...,name=...]` — the exact form the engine's own auditor produces, and what `ServerEvent.getChannelId()/getChannelName()` parse back out), plus readable `"Channel ID"`/`"Channel Name"` attributes (RBAC never needed this; Sentinel's events usually *are* channel-scoped) so operators filtering the engine's System Events log by channel see related Sentinel activity.
- Scope is human-initiated REST mutations only — the automated alert open/resolve lifecycle and notification outcomes are already captured in `sentinel_alert_event`/`sentinel_action_dispatch_log`, the right place for that trail (matches RBAC's actual scope; routing automated ticks through the human-action audit log would just be noise).

## REST API — `SentinelServletInterface`, `@Path("/extensions/sentinel")`

**Wire format — raw JSON strings, not typed DTOs over XStream.** The engine's JSON layer is
XStream→StaxON (`ObjectJSONSerializer`), which wraps every payload in a root key, collapses
one-element lists to bare objects, and serializes empty collections as `""` — the reason RBAC's
webadmin resorts to hand-built XML request bodies. Sentinel instead follows `oie-community-store`'s
proven pattern: every servlet method returns a Jackson-produced JSON `String` (shared
`ObjectMapper` with `JavaTimeModule`; `Instant`s as ISO-8601 strings) and receives request bodies
as raw JSON `String` parameters (Jersey's built-in `String` reader delivers the raw body). The
engine still wraps the response as `{"string": "..."}`, which `platform.api` unwraps and the
webadmin's `parseMaybeJson` parses. No Sentinel type ever crosses the XStream boundary, so no
`allowTypes` registration is needed.

Three permissions (wider than RBAC's view/manage pair — a NOC operator needs to acknowledge without
reconfiguring monitors/actions/SNS credentials): `PERMISSION_VIEW` ("View Monitoring"),
`PERMISSION_ACKNOWLEDGE` ("Acknowledge Problems"), `PERMISSION_MANAGE` ("Manage Monitoring").

| Resource | Endpoints |
|---|---|
| Monitors | `GET/POST /monitors`, `GET/PUT/DELETE /monitors/{id}`, `POST /monitors/{id}/_setEnabled`, `POST /monitors/_test` (dry-run against recent samples) |
| Actions | `GET/POST /actions`, `GET/PUT/DELETE /actions/{id}`, `POST /actions/{id}/_test` |
| Maintenance | `GET/POST /maintenanceWindows`, `GET/PUT/DELETE /maintenanceWindows/{id}`, `POST /maintenanceWindows/{id}/_activateNow` |
| Problems | `GET /problems` (paginated/filtered — see below), `GET /problems/{id}` (detail incl. dispatch log), `POST /problems/{id}/_acknowledge`, `POST /problems/{id}/_resolve`, `POST /problems/_bulkAcknowledge` |
| Settings | `GET/PUT /settings` — collector/evaluator intervals + retention windows, persisted in the engine `CONFIGURATION` property store |
| Dashboard | `GET /dashboard/summary` — severity counts, top problem channels, monitor health, recent problems |
| Activity | `GET /channels/{id}/activity?from&to&granularity=AUTO|RAW|HOURLY` (AUTO picks RAW for short ranges, HOURLY rollup for long), `GET /activity/summary?channelIds=...` (batched sparkline data, avoids N+1) |
| Core passthrough | `GET /core/channels` (id/name/state, for pickers), `GET /core/channelGroups` (core groups, for scope pickers) |

`GET /problems` filters: `status, severity(CSV), channelId(CSV), monitorId, monitorType,
acknowledged, from/to (epoch millis), q (free text), sort (allow-listed column names only — never
string-concatenated from client input), sortDir, page, pageSize`. Response
`{items, total, page, pageSize}` — feeds `@oie/web-ui`'s `DataTable` pagination directly. (No
`channelGroupId` filter: `AlertEventFilter` stores only `channelIdIn`; a group filter would be a
service-layer expansion of group → member channel ids, deferred until the UI needs it.)

Auth: requests are made through the host OIE Web Administrator's `/api` reverse proxy, riding the
browser's existing engine session cookie and `X-Requested-With` header via `platform.api.*` — no
JWT/API keys, no CORS plumbing, and **no separate login screen**: the UI is embedded in the Web
Administrator (see the Web UI section below), so the host's authenticated session is the only auth
there is.

## Web UI — entirely embedded in the OIE Web Administrator (no standalone WAR)

**Revised from the original draft**: the dashboard is NOT a standalone React/Vite SPA with its own
WAR, login page, and servlet-container deployment (the `tls-manager-plugin/web-ui` pattern). Instead
it's built entirely against the **`oie-web-client` plugin UI contract** — the same `webadmin/`
mechanism every other plugin in this workspace uses — and loads inside the main OIE Web
Administrator. This was a deliberate correction after reviewing that contract in depth: it turned out
to support everything Sentinel needs (real client-side routing, an unbounded number of nav items/
views, a full DOM/table/dialog toolkit, and zero-setup authenticated API calls), and building inside
it means **no separate login, no WAR, no servlet container, no CORS/session-sharing concerns** — the
plugin rides the host page's already-authenticated session for free.

**Contract mechanics** (confirmed against `oie-web-client`'s source, not assumed):
- A plugin is a `webadmin/` folder with `plugin.json` (`id`, `name`, `version`, `client.entry:
  "web/plugin.js"`) plus the JS the engine serves at `/api/webplugins/<path>/…`. The bundle is a
  plain `esbuild` build (`webadmin/build.mjs`, same as every other plugin here) —
  `external: ['@oie/web-api','@oie/web-ui','@oie/web-shell']`, React itself is **never bundled**
  (`const React = platform.React` — the plugin runs against the host's single shared React
  instance via an import map, per `oie-web-client/CLAUDE.md`'s "single framework instance"
  invariant). Only a chart library needs to be brought in and bundled (see below) — nothing else in
  the toolchain changes from the `webadmin/` pattern already used for `plugin.xml` packaging.
- `platform.registerNavItem({ id, label, icon, path: '/sentinel', section, order, task })` +
  `platform.registerView('/sentinel', platform.reactView(SentinelView), { title: 'OIE Sentinel' })`
  — confirmed the registries are plain unbounded arrays (`registries.navItems.push`,
  `router.register`), so nothing caps how much a plugin can register. In practice, though, **every
  existing multi-page plugin in this workspace (`oie-community-store` at 1121 lines, the richest
  real precedent) registers exactly one nav item + one view**, and does its own page/tab navigation
  *inside* that view with plain React `useState` — not multiple `registerNavItem` calls. Sentinel
  follows that same proven pattern rather than being the first plugin to try multiple nav items.
- Auth: **there is no separate login.** `@oie/web-api`'s `platform.api.get/post/put/del` calls are
  automatically proxied through the host's `/api` reverse proxy carrying the browser's existing
  engine session cookie and CSRF header — confirmed in `packages/web-api/README.md`. Sentinel's REST
  client code is therefore just thin wrappers over `platform.api.*`, no axios instance, no
  `AuthContext`, no `ProtectedRoute`, no `/login` page.
- Permission gating: the `task` field on `registerNavItem` plus `platform.checkTask(group, task)`
  calls inside the view (exactly `oie-community-store`'s `canInstall()`/`canEditSettings()` pattern)
  hide nav items/controls a user's role can't use, resolved through RBAC's extension task-permission
  merge when RBAC is installed and defaulting to "always visible" when it isn't (same graceful
  degradation every other plugin here already relies on).

**UI toolkit — `@oie/web-ui`** (confirmed via `packages/web-ui/types/core/ui.d.ts`, an imperative DOM
toolkit the React view calls into, not a props-driven React component library):
- `DataTable<T>(columns, options)` — the table for Problems/Monitors/Actions/Maintenance. Sortable,
  single/multi-selectable, optional persisted column visibility/order. It's an imperative class (own
  `.el` HTMLElement, `.setRows()`), so each list view creates one in a `useRef`+`useEffect` (mount
  `table.el` into a container div once, call `table.setRows(data)` on every data change) — a small
  shared `useDataTable(columns, options, rows)` hook wraps this bridge once and every table view
  reuses it.
- `modal`, `confirmDialog`, `promptDialog`, `detailModal`/`errorModal`, `toast` — used directly for
  the acknowledge dialog (single + bulk), delete confirmations, and error surfacing (`errorModal` is
  explicitly designed for "long engine exception" content instead of a corner toast — use it for
  action test-send failures). No custom modal/toast components to build.
- Plain form controls (`<input>`, `<select>`, etc.) styled with the existing design-system classes
  (`.field`, `.btn`, `.btn-primary`, `.panel`, `.panel-body`, `.tag`) rather than bridging every
  imperative form primitive (`textInput`/`numberInput`/`select`/`checkbox`) through refs — those
  helpers exist for non-React callers; inside a React view, writing the equivalent JSX directly
  against the same CSS classes is simpler and is what `oie-community-store`'s settings form does.
- **No charting library ships with the toolkit** (confirmed — grepped the whole workspace). Bundle
  **recharts** (React-native/SVG, no CSS collisions with the host's Tailwind-token design system,
  existing org precedent in `BridgeLink-WebAdmin/package.json`) as a plugin-local dependency; `build.mjs`
  only externalizes `@oie/*`, so a bundled chart lib is exactly how the DICOM viewer plugin already
  ships `dicom-parser` the same way.
- Styling: the host's Tailwind-v4/design-token system (`.btn`, `.panel`, `.dt`, `.tag`, CSS variables
  like `var(--ok)`/`var(--warn)`/`var(--err)`/`var(--accent)`/`var(--bg1)`/`var(--line)`) — light/dark
  theming is automatic through the tokens, no `dark:` variants needed. There is **no CSS isolation**
  (no Shadow DOM anywhere in this codebase), so any Sentinel-specific styles get their own
  `<style>{SENTINEL_CSS}</style>` block with a `.sn-` class prefix, exactly `oie-community-store`'s
  `.cs-` convention (`STORE_CSS`/`DOCS_CSS` constants in its `plugin.jsx`).

**Internal navigation** (single `SentinelView` root component, `page` state via `useState`, no
router beyond the one `registerView('/sentinel', ...)` registration): a `.tabs`/`.tab` strip
(Dashboard, Problems, Monitors, Actions, Maintenance, Settings — Zabbix's own top-nav grouping,
identical CSS pattern to `oie-community-store`'s Browse/Installed/Settings tabs) with a `selected`
override state that swaps the tab body for a detail/editor view (problem detail + ack, monitor
editor with the four type-specific config fieldsets, action editor with the condition builder) —
mirroring `oie-community-store`'s `selected`-drives-`<DetailView>` master-detail pattern exactly.

**Shared components worth building once**: `SeverityChip` (single source of truth for the 5-level
severity → color/icon, built from the host's `.tag` class + a severity-keyed color), `ActivityChart`
(recharts wrapper: received/sent/error series, error series uses the fixed "critical" status color
since it's semantically a problem signal not an arbitrary 3rd identity, not the categorical palette),
`TimeRangePicker`, `StatTile` (value+delta+sparkline), `FilterBar`, `ChannelPicker`/
`ChannelGroupPicker` (against `/core/channels`+`/core/channelGroups`), `ConditionBuilder`. Follow the
dataviz skill's palette/ordinal rules for severity (ordinal ramp, not categorical) and chart series
(categorical + one reserved status color for error) — adapted to the host's CSS-variable palette
rather than an independently chosen one, so Sentinel visually matches the rest of the console.

**Packaging**: identical to every other plugin's `webadmin/` — `plugin.json` + `esbuild`-built
`web/plugin.js`, zipped flat into the extension archive (`package/assembly.xml`'s existing
`webadmin/plugin.json` + `webadmin/web/plugin.js` file entries, already scaffolded). No WAR, no
`web.xml`, no Docker/Express prod server, no `build.sh` web-tier step beyond the `frontend-maven-plugin`
npm build already wired into `package/pom.xml`.

## `oie.json` / `plugin.xml`

`oie.json`: `id: "sentinel"`, `type: "plugin"`, `ui: ["web"]`, `keywords: ["monitoring", "alerting",
"dashboard", "zabbix", "channel-activity"]`, conforming to `oie-community-catalog`'s
`meta.schema.json` for future catalog listing. `plugin.xml`: `path="sentinel"`, `<serverClasses>`
(no `<clientClasses>` — confirmed `oie-totp-mfa` is a real server+web-only precedent with no client
entry), `<migratorClass>`, `<sqlMapConfigs>` (5 per-vendor entries + an `all` fallback to derby,
matching `oie-totp-mfa`'s confirmed convention), `<apiProvider type="SERVLET_INTERFACE"/SERVER_CLASS">`
pair for `SentinelServletInterface`/`SentinelServlet`.

## Execution approach

Given the size (9-table schema across 5 DB dialects, ~8 REST resources, 4 background jobs, 3 alert
senders, 7+ web-ui pages with shared components), implementation is running as a series of Workflow
tool invocations rather than one linear pass — most of the work decomposes into independent files
(per-vendor SQL, per-resource repository/service/servlet triads, per-page React components) that
fan out cleanly, with a verification pass (build + targeted manual checks) after each phase:
1. **Schema + persistence**: migrator, 5×DDL files, sqlmap XML, repositories — pipeline per table/resource.
2. **Engine + REST**: collector, evaluator, baseline resolver, action senders, servlet/service layer — pipeline per component, since most only depend on the persistence layer, not each other.
3. **Web UI**: the `SentinelView` shell + tab navigation first (everything else depends on it), then each tab's page component (Dashboard, Problems, Monitors, Actions, Maintenance, Settings) fanned out in parallel, then the shared components (`SeverityChip`, `ActivityChart`, `useDataTable`, pickers) — all inside the single `webadmin/` bundle, no separate companion app.
4. **Packaging + verification**: assembly, build.sh, deploy to a local OIE instance, end-to-end check per the Verification section below.

## Verification

- `mvn clean package` builds cleanly across all modules; `package/target/sentinel-*.zip` produced.
- Deploy the zip to a local OIE instance (or `docker/` setup like `tls-manager-plugin/docker`);
  confirm `SentinelMigrator` creates all 9 tables on first start, and cleanly drops them on uninstall.
- Create a monitor (start with INACTIVITY on a real channel), confirm `ActivityCollectorJob` populates
  `sentinel_channel_activity_sample` within one interval, and `TriggerEvaluatorJob` moves
  `trigger_state` from `INSUFFICIENT_DATA` → `OK`.
- Pause the channel's source or stop sending messages long enough to breach the threshold; confirm a
  `sentinel_alert_event` opens, an `sentinel_action_dispatch_log` row is written, and the configured
  Email/Channel/SNS action actually delivers (verify the channel-delivery path lands a message in the
  target channel; verify SES/SNS delivery via AWS console or a subscribed test endpoint).
- Resume activity; confirm the alert auto-resolves and (if configured) a resolve notification fires.
- Acknowledge a problem from the web UI; confirm the ack is visible in `/problems` and a curated
  `ServerEvent` appears in OIE's System Events log (System Events, filtered by channel, should show
  it) alongside the automatic framework-level audit event.
- Install the plugin into a running OIE Web Administrator and confirm the "OIE Sentinel" nav item
  appears (gated correctly if RBAC is installed) and loads with no separate login prompt — it should
  ride the already-authenticated session automatically.
- Exercise the embedded UI end-to-end in a browser: dashboard KPIs render, Problems list
  filters/paginates/bulk-acks via `DataTable`, Monitor/Action editors round-trip correctly, the
  activity chart renders real data for a channel, ack/confirm dialogs use `@oie/web-ui`'s `modal`/
  `confirmDialog`/`toast` (not custom-built equivalents).
