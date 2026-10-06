# Expiring channel maintenance review

Independent origin/main `24de1c2` PR; no dependency on triage PR 22 or PR 19.
This adds a Problems detail shortcut for one-time CHANNEL/SUPPRESS schedules.
It affects all notifications on that channel, including recovery, repeats and
escalation; monitoring/problem lifecycle remain governed by existing policy.
This is channel maintenance, not event-specific snooze.

## Persistence, permissions and audit

Both new endpoints use the existing Manage Schedules permission. Creation
checks current channel visibility/existence; cancellation checks visibility
from the request ledger. Required reason (1–200 characters) becomes the schedule
name. Expiry is an absolute instant within 30 days. The service uses existing
schedule validation and existing windowCreated/windowUpdated audit verbs.
No notification or evaluator algorithms change.

Schema v13 adds sentinel_maintenance_request on all five vendors: UUID primary
key, SHA-256 payload fingerprint, channel ID and schedule ID. A managed
transaction claims the unique key, inserts the ordinary maintenance schedule,
and completes its receipt. Concurrent retry attempts serialize on the DB key.
On failure, rollback closes the managed session before reading any committed
receipt. Retry with different parameters is rejected. Replays never extend
expiry or re-create a deleted schedule. Request receipts intentionally remain
when schedules are disabled/deleted; there is no automatic retention pruning
that would silently remove the deduplication guarantee.

Cancellation conditionally disables only the original CHANNEL/SUPPRESS/NONE
scope. A schedule moved to another channel/scope/policy must be reviewed through
Schedules. Cancellation retries are idempotent state updates; audit entries
may repeat. Existing audit delivery remains best-effort; this adds no exactly-
once System Events guarantee after an uncertain transaction commit.

The UI freezes request identity and absolute expiry after the first attempt.
Malformed/failed responses keep the request for safe Check/retry. The completed
receipt shows server activeNow, expiry, schedule name/ID and cancellation.
The existing public schedules intent opens the normal persisted schedule list.
Client pending locks start synchronously; client permission is rechecked at
Apply, with endpoint permission/channel checks authoritative.

## Adversarial matrix

| Situation | Behavior | Evidence |
| --- | --- | --- |
| Valid create | Persist CHANNEL/SUPPRESS/NONE, required reason and exact expiry | Derby persistence + browser preview |
| Missing reason/invalid duration | No request/schedule writes | Java invalid inputs + browser validation |
| Missing/hidden channel | No creation; channel lookup/visibility before service | Servlet path review |
| Failed inventory/API read | No mutation from unavailable inventory; error remains actionable | Servlet fail-closed path review |
| Write failure | Transaction rolls back claim and schedule; client retains stable request | Derby rejected insert; browser malformed response |
| Lost/malformed response | UI never claims success; same-key retry reconciles | Browser frozen payload equality |
| Concurrent retry callers | DB primary key permits exactly one schedule | Two-thread Derby test using distinct sessions |
| Changed retry payload | Reject; never retime original | Derby fingerprint mismatch |
| Partial transaction | No committed orphan request claim/schedule | Derby insert constraint failure |
| Permission revoked while review open | Client prevents Apply; existing server permission remains authoritative | Browser zero-write assertion; annotations reviewed |
| Scope edited before cancel | Conditional update refuses another channel/policy | Derby moved-scope test |
| Cancelled/deleted schedule replay | Disabled/removed receipt, no recreation | Derby tombstone test |
| Expiry/restart | Stored absolute expiry; no timer/re-enable job required | Repository roundtrip, existing schedule tests; no live restart |
| Time boundary/DST | Browser local input becomes one absolute instant; server owns activeNow | Existing WindowSchedule tests; no new recurrence math |
| Keyboard/narrow screen | Labelled fields and native buttons; effect/expiry readable at 390px | Chromium harness, mobile screenshot |

## Validation and cleanup

Final full shared/server test run: 827 tests, zero failures/errors/skips.
Initial/focused runs exposed an uninstall-script test's order assumption after
adding the ledger first; preserved existing table ordering and re-reviewed the
full change. Failed-run evidence is retained. Vendor matrix includes packaged
statement contracts and Derby upgrades from every historical version through
v13, uninstall/reinstall and all new statements; other vendors were not live
executed. New dedicated persistence tests cover concurrency, rollback,
cancellation, tombstones and changed payloads.

Web bundle/registration smoke and Chromium contract harness pass. Browser
harness uses React 18 and mocked public host APIs, not a live engine/theme.
Impeccable detector and whitespace check have no findings. Maven package also
passes. Local evidence stays in target/maintenance-validation and
 target/maintenance-browser. Owned in-memory Derby databases are dropped in
teardown after worker termination. Browser and local HTTP server close in
finally. No engine installation or shared database was created/changed; pinned
inputs and browser cache were retained.

## Integration

Expected schema v13 conflict with other independent schema PRs: assign a final
migration sequence when integrating; update detection, uninstall lists and
vendor resources together. Small Problems detail mount can merge with PR 22
and the inspector. Interface/servlet/API additions and npm test scripts also
overlap sibling feature PRs. Existing schedule CRUD remains compatible.
