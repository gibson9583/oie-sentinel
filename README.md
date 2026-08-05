# OIE Sentinel

Zabbix-inspired monitoring and alerting for [Open Integration Engine](https://openintegrationengine.org)
channel activity — inactivity, low-volume, anomaly-detection and connection-status monitors, with
email, channel (VM Router) and AWS SNS alert delivery, and a dashboard embedded directly in the OIE
Web Administrator.

No Swing desktop client, and no standalone web app either — the UI is built entirely against the
`oie-web-client` plugin contract (`@oie/web-api`/`web-ui`/`web-shell`) and loads inside the main
console, riding its existing session (no separate login).

![Sentinel dashboard](docs/screenshots/dashboard.png)

## Features

**Monitors**
- Four monitor types: **Inactivity** (no messages for N minutes), **Low volume** (fewer than N
  messages per window), **Anomaly** (volume deviates from a rolling baseline), and
  **Connection status** (connector state matching).
- Scoped to a single channel, a channel group, a **channel tag**, or all channels — group and tag
  membership resolve live, so reorganizing channels never requires touching monitors.
- Per-monitor severity (Information → Disaster), minimum consecutive breaches, and dependency
  suppression (a parent monitor's open problem silences its dependents on the same channel).

**Problems**
- Problem list with server-side filtering/paging, bulk acknowledge, and a detail pane with the
  captured value and dispatched-action log.
- Acknowledge and manual resolve, each confirmed in a single dialog with an optional comment;
  acknowledgments display the engine username.
- **Acknowledging stops repeat notifications** without resolving the problem — the alarm stays open
  so it cannot re-fire while the condition persists, and the resolve notification still goes out
  when it clears.

**Actions (alert delivery)**
- Email (engine SMTP), Channel (route the alert payload into an OIE channel via VM Router), and
  AWS SNS senders.
- Condition-based routing: severity (including `>=` thresholds), monitor type, **specific
  monitors** (multi-select), channel, channel group, **channel tag**, and event type — rows are
  ANDed; an action with no conditions fires for everything its mode covers.
- Repeat notifications per action (`repeat interval` + optional `max repeats`), paced through the
  dispatch log so repeat state survives server restarts.
- Drag-and-drop template tokens (`${monitorName}`, `${channelName}`, `${severity}`, `${status}`,
  `${message}`) for the email subject template.

**Maintenance windows & alerting schedules**
- **Suppress** windows: classic maintenance — alerts born inside the window never notify.
- **Alerting schedule** windows: the inverse — alerts on covered channels notify *only inside* the
  window (business hours, on-call rotations).
- One-time, weekly (days of week), or monthly (days of month) recurrence with start/end times on
  the server clock; end at or before start wraps past midnight (e.g. 22:00–06:00).
- "Activate now" re-times a one-time window mid-incident without editing it.

**Operations**
- Dashboard tab with problem counts by severity, per-channel activity charts, and top channels.
- Five RBAC permissions published for authorization plugins (see [Permissions](#permissions)).
- Mutations audited through the engine's event log; alert history, trigger state, and activity
  samples persist across restarts with configurable retention.

## Screenshots

| | |
| --- | --- |
| ![Problems list](docs/screenshots/problems.png) | ![Monitors](docs/screenshots/monitors.png) |
| ![Action editor](docs/screenshots/action-editor.png) | ![Settings](docs/screenshots/settings.png) |

## Requirements

- OIE engine **4.6.0+** (`minEngineVersion` in `oie.json`)
- [oie-web-client](https://github.com/gibson9583/oie-web-client) (OIE Web Administrator) to host
  the dashboard UI
- Server restart after install/upgrade

## Install

1. Download `sentinel-<version>.zip` from [Releases](https://github.com/gibson9583/oie-sentinel/releases)
   (or install from an OIE Community Store that lists this plugin).
2. Install via the Web Administrator (Settings → Extensions) or drop the zip into the engine's
   extension install flow, then restart the server.
3. On first start the migrator creates nine `sentinel_*` tables in the engine database (Derby,
   PostgreSQL, MySQL, Oracle, and SQL Server DDL included); schema upgrades run automatically on
   later versions.

> **Warning:** uninstalling the extension drops all `sentinel_*` tables — monitors, actions,
> windows, and alert history — on the next startup. Upgrade by installing the new version over the
> old one; only uninstall when you mean to wipe.

## Permissions

Sentinel publishes five extension permissions so RBAC-style authorization plugins can map real
operational roles:

| Permission | Grants |
| --- | --- |
| View Monitoring | All reads: dashboard, problems, monitors, actions, windows, activity |
| Acknowledge Problems | Acknowledge, bulk acknowledge, manual resolve |
| Manage Maintenance Windows | Window create/update/delete and "activate now" (on-call tier) |
| Manage Monitoring | Monitor and action authoring, including test sends |
| Manage Settings | Scheduler intervals, retention, mail/SNS delivery credentials |

Without an authorization plugin installed, the engine permits everything.

## Build

Requires **JDK 21+** (the 4.6.0 engine jars are Java 21 bytecode; the plugin itself targets
`--release 17`). On a fresh machine or wiped `~/.m2`, first install the 4.6.0 engine jars into the
local Maven repository (the public repsy mirror does not carry every engine version) — the engine
must be built first so its `setup/` output jars exist:

```bash
ENGINE_DIR=/path/to/engine ./scripts/install-engine-jars.sh
```

Then:

```bash
./build.sh          # or: mvn clean package
```

Output: `package/target/sentinel-<version>.zip` — the installable extension archive, including the
built `webadmin/` dashboard bundle. CI does the same, sourcing the engine jars from the published
OIE distribution tarball instead of a local checkout (see `.github/workflows/build.yaml`).

## Project structure

```
oie-sentinel/
├── shared/     JAX-RS interface (SentinelServletInterface) + DTOs
├── server/     ServicePlugin, migrator, repositories, collector/evaluator jobs, alert senders
├── package/    plugin.xml, per-vendor MyBatis mappers, assembly.xml
├── webadmin/   the entire dashboard UI (embedded plugin for the OIE Web Administrator)
└── build.sh    orchestrates mvn package (builds webadmin/ via frontend-maven-plugin) + zip
```

## Releasing

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The tag must match `oie.json`'s `version`. CI builds the bundle, stages a **draft** GitHub Release
with the zip and its `.sha256` sidecar; publishing the release (a human step) triggers the optional
catalog PR that files the version manifest to the community catalog configured via the
`CATALOG_REPO` Actions variable and `CATALOG_TOKEN` secret. Tags containing a hyphen (e.g.
`v0.2.0-rc1`) publish as pre-releases.

## Design

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full schema, REST API,
collection/evaluation pipeline, and web-UI design this project was scaffolded from.

## License

[MPL-2.0](LICENSE)
