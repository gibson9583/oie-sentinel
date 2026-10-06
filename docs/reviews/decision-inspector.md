# Alert decision inspector review

Base: `origin/main` at `24de1c2`. Scope: Sentinel only; no schema changes or dependency on PR19. The Problems integration adds a separate component and a parent-problem navigation callback. The triage PR may conflict at those small integration points; preserve both changes.

`GET /problems/{id}/decision` requires View Monitoring and the existing per-problem channel restriction guard. It reads the event, current suppression policy, current trigger and enabled matching actions. It performs no writes, dispatches, test sends, outbox consumption, leadership acquisition or storm/flap counter updates. Matching action responses contain ID, name, transport and phase; never configuration or credentials.

The dispatcher and inspector share the same suppression decision and lifecycle/condition matcher. The explanation preserves policy gate ordering: missing/disabled monitor, covering schedules, then open enabled parent problem on the same channel. It exposes the first decisive gate; it is not an exhaustive list of every possible gate. Lookup failure returns UNKNOWN with a generic error; repository details remain in server logs. Malformed schedules retain the existing mode-dependent fail-open behavior.

The panel labels current observation time, acknowledgement, current breach progress, pending opened/recovery edges and current routing separately from the event's captured Last Value and Action Dispatches. A current trigger can belong to a newer problem and is labelled accordingly. Opening consecutive-breach progress was not persisted. An ALLOW policy does not promise a send: acknowledgement, phase/routing, repeat timing, escalation, flap/storm controls, ordering and leadership still apply. Matching follows the existing fail-closed matcher, whose invalid conditions or failed scope reads can produce a non-match. Historical flap/storm decisions are unavailable and are never inferred.

Schedule reasons include ID/name and timezone plus navigation to Schedules. A parent reason links directly to that problem. Next boundaries are shown only for absolute one-time bounds; recurring/DST boundaries are intentionally unavailable until a reliable calculator exists. The existing schedule policy includes its absolute upper bound; the displayed bound does not claim a precise first allowed instant.

## Behavior matrix

| Case | Delivered behavior | Verification |
| --- | --- | --- |
| Normal read | Current policy, trigger, pending flags and enabled matching phase/actions; no credentials or mutation | Service test verifies read-only repository interactions and serialized response |
| Disabled/missing monitor | Named gate, no invented historical decision | Policy explanation regression + existing missing/disabled policy tests |
| Schedule edits/expiry | Reads fresh schedules and returns named blocking schedule or ALLOW | Fixed-clock edit/expiry tests; existing recurring/overnight/DST suite |
| Dependency resolution/disable | Links current open parent; resolves to ALLOW after parent changes | Explanation test + existing transient-state/dependency policy tests |
| Policy lookup failure | UNKNOWN, empty definite reasons, sanitized retry message | Policy regression test; existing dispatcher fail-closed tests |
| Evaluation/routing failure | Captured event and policy remain; section explicitly reports failed read | Service partial-read regression |
| Missing event/channel restriction | Existing 404 guard before policy/action reads; View permission annotation | Servlet restricted/visible regression; full interface/call-path review |
| Stale render/action state | Refresh reads current entities; panel explains current versus captured evidence | Policy edit tests; no mutation in this feature |
| Request ordering/unmount | Request sequence rejects old response; new entity clears prior data | Real React/browser delayed-response regression |
| Refresh failure/permission change | Keeps last observation with persistent failure and its observation time | Browser rejection regression |
| Malformed API reply | Visible invalid-response error, never definite new policy | Browser regression |
| Partial fan-out/pending | Existing dispatch history remains independent; pending edge is not an attempt | Service pending serialization; history path unchanged and reviewed |
| Retry/idempotence | Explicit read refresh only; no send/retry controls or write path | Read-only interaction and service checks |
| Restart/failover | Current persisted reads; no process-local leader verdict or invented history | Policy/dispatcher existing durability/fencing suites in full build |
| Keyboard/narrow view | Labelled refresh/parent controls, keyboard refresh, 390px wrapping | Real browser interaction harness; zero document overflow/page errors |

## Validation

- Full `mvn -q package`: 872 tests, 0 failures/errors/skips; builds installable package and runs frontend bundling/import/registration smoke verification.
- After the final parent-name refinement: focused suppression, service, authorization, matcher and dispatcher suppression suites; package rebuilt with `-DskipTests` after those checks.
- `SENTINEL_PLAYWRIGHT_PACKAGE=/path/to/playwright/index.mjs node webadmin/verify/decision-inspector.mjs`: real React rendering with controlled read API replies; parent navigation, refresh failure, permission-change messaging, stale-response rejection, malformed response, keyboard refresh, 390px overflow and zero page errors.
- Impeccable detector on the new component: no findings. `git diff --check` passed.
- The browser harness uses host-shaped CSS and mocked host/API bindings. It establishes component interactions, not live host integration or contrast certification. No live-engine validation was performed.

All accumulated changes were reviewed together, including the policy extraction's interaction with existing dispatcher suppression, condition matching, creation-time snapshots, and recovery fallback. No outstanding source finding was found in that review; runtime limitations above remain explicit.

## Resource cleanup and evidence

No isolated engine installations or external databases were created. Existing Derby persistence tests use uniquely named in-memory databases and drop them in test teardown; test JVM exit also releases in-memory state. Browser fixture resources are owned: the runner closes Chromium and its local HTTP server, then removes its temporary runnable directory in `finally`. Source tests and Surefire reports remain; validation logs are preserved under `/tmp/sentinel-diagnostics-*.log`. Shared/reference resources were not modified or signalled.
