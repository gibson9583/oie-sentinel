# Coverage and evaluation health review

Independent origin/main 24de1c2 branch. Read-only Sentinel endpoint and Coverage
tab; no schema, engine, notification or evaluator mutations. Create monitor
uses the existing public newMonitor intent with a channel ID.

Configured membership uses the same scoped resolver as evaluation. Channel
state includes stopped/undeployed channels; activity monitors use the existing
started resolver; Connection status uses active shared deployment inventory.
Connection health additionally requires all current connector identities (or
the configured channel rollup), with evaluations following deployment times.
Empty inventory is distinct from failed inventory. Missing group/tag scopes
are diagnostics for unrestricted callers; empty existing scopes are distinct.
Unresolved membership conservatively marks visible-channel coverage unknown.

Rows, evaluations and row counts are restricted to visible channels before
emission. Restricted callers receive no global monitor-scope diagnostics.
The View permission gates the endpoint; Create monitor is cosmetic Manage
permission gating and the existing editor/save path remains authoritative.
No state, sample, policy or audit rows are written by this endpoint.

Healthy means every expected current trigger was evaluated OK within
max(120 seconds, 3 evaluator intervals), after configuration/deployment changes.
Future timestamps, absent/stale evaluations, pending breach confirmation,
insufficient data and lifecycle mismatch cannot establish health. Disabled or
runtime-ineligible monitors still expose latest recorded state/time as history.
Confirmed open events remain visible even if their evaluation is stale.
Current trigger inventory is computed at read time; observations are not an
atomic snapshot across configuration/runtime/database reads, and can change
on the next refresh. No inferred causality or exactly-current system health
claim is made.

| Situation | Expected behavior | Verification |
| --- | --- | --- |
| Recent current OK triggers | Recently evaluated healthy | Type-specific and rollup Java tests |
| Stopped channel | State monitor eligible; activity ineligible | Java parity test + existing scope tests |
| Remote/paused deployment | Connection status uses shared inventory | Java shared deployment test |
| New connector/config edit | Prior OK cannot establish health | Current identity/deployment/config tests |
| Missing/stale/future evaluation | Unknown with latest recorded evidence | Java boundary tests + browser visible-time test |
| Insufficient/pending breach | Warming up; never healthy | Java state tests |
| Empty/failed inventory | Known empty differs from unavailable | Java and browser empty/failure tests |
| Missing group/tag | Unresolved diagnostic; no invented target | scopeExists strict lookup + service tests/review |
| Membership edit | Re-resolve each request | Java changed membership test |
| Restricted channel | No hidden row, name, evaluation or count | Java serialized-response restriction test |
| Failed scope membership | Visible channel coverage unknown | Java failure test |
| Closed/stale event reference | Lifecycle mismatch unknown; actual open remains open | Java lifecycle tests |
| API failure | Previous evidence retained with persistent last-success/error | Browser failure retention |
| Late refresh ordering | Old response cannot replace newer snapshot | Deferred browser refresh test |
| Create navigation | Existing intent only; no write/send | Browser intent + zero POST assertion |
| Read-only/permission change | View remains usable, Create unavailable/guarded | Browser permission test + endpoint annotation review |
| Keyboard/mobile | Native controls; full evidence in stacked 390px layout | Chromium interaction/screenshot check |
| Retries/restart | Read retry only, no persistent mutation | Endpoint call-path review; no live restart |

Final shared/server Java suite: 831 tests, zero failures/errors/skips, including
10 focused coverage tests. Web bundle/registration and Maven package pass. Browser
harness uses React 18 and mocked public host API contracts, not a live engine
or authentic host theme. No live vendor/failover checks were performed.
Failed compile attempts (repository signature corrected before tests) and final
logs are retained under target/coverage-validation; browser evidence under
 target/coverage-browser. Java persistence suites drop owned in-memory DBs;
browser/server close in finally. No engine install or shared DB was touched.

Integration conflicts: additive endpoint/interface/API entries, Coverage tab
and CSS in plugin.jsx, test scripts/dependencies. ScopeResolver adds only a
read-only scope-existence diagnostic; it does not alter evaluator resolution.
