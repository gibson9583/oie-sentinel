# Incident triage review and validation

Base: origin/main `24de1c2`. Scope: Sentinel only; no engine, host table, schema,
notification policy, or evaluator changes. Draft PR is independent of PR 19.

Problems now has explicit keyboard selection/open controls, independent queue
and detail freshness/error indicators, retained selection for unapplied work,
pending mutation locks from confirmation through refresh, and receipts for
single-request uncertainty and each distinct bulk ID. Desktop keeps queue and
detail visible; narrow screens return focus to the opening control (or queue
if that row disappears). Runbook, delivery failures, and captured evidence
precede lifecycle context. Technical IDs/raw JSON are in a disclosure.

## API, permission and audit contract

Bulk endpoints retain `acknowledged` / `resolved` counts and add ordered
`receipts: [{id, status}]`. Distinct IDs are processed once. APPLIED means this
request's conditional update succeeded. ALREADY_ACKNOWLEDGED and
ALREADY_RESOLVED describe a visible row observed before mutation; NOT_APPLIED
means the conditional update lost a race. UNAVAILABLE combines absent and
forbidden IDs to preserve non-enumeration. FAILED means no write was attempted;
UNKNOWN means a write threw and could have committed. Responses contain no
channel names, messages, comments, or exception details.

Both operations retain the existing Acknowledge permission annotation and
check channel visibility on each freshly fetched event before reporting its
state or writing it. Existing conditional writes, acknowledgement ownership,
trigger reset, and aggregate audit verbs remain shared with single operations.
No database migrations are needed; existing SQL remains shared by all vendors.

Older servers with a valid aggregate count remain supported. A partial count
cannot identify applied IDs, so those IDs remain uncertain until individually
read. Missing/contradictory counts, duplicate/foreign receipt IDs, unknown
statuses, or mismatched applied totals never produce success claims.
Reconciliation describes observed current state without attributing it to this
request. If a read continues failing, uncertainty and retry locks remain.
Receipts and selection last for the mounted page, not across a reload/logout.

## Adversarial behavior matrix

| Case | Required/resulting behavior | Evidence |
| --- | --- | --- |
| Happy path | Applied IDs deselected, unapplied IDs retained, counts match receipts | Java mixed receipt test; browser partial batch |
| Missing/hidden ID | Same UNAVAILABLE status, no write/state disclosure | Java visibility/missing test; servlet visibility predicate traced |
| Already ack/resolved | Specific visible-row receipts without overwriting ownership | Java mixed receipt test; Derby lifecycle tests |
| Read failure | Queue/detail retain previous evidence with independent persistent error and last-success timestamp | Browser independent failures |
| Malformed read | Invalid envelope/detail identity rejected before replacing evidence | Envelope/detail guards reviewed |
| Write failure/response loss | UNKNOWN per ID or whole request; no optimistic success | Java throw-after-write test; malformed single/bulk browser paths |
| Stale filters/order | Request sequence and query identity reject old results even during search debounce | Browser deferred old response |
| Permission changes during confirmation | Client rechecks; server remains authoritative under existing permission annotation | Browser no-write assertion; annotation and call-path inspection |
| Concurrent operator | Conditional update false returns NOT_APPLIED; first ack ownership preserved | Java conditional race; existing Derby lifecycle concurrency tests |
| Partial completion | Other IDs continue after read/write failure; per-ID result remains visible | Java mixed failure test; browser selection assertions |
| Pending/double invocation | Shared synchronous lock blocks queue/detail mutations from modal opening through reload | Browser delayed write, disabled controls; shared ref inspected |
| Retry/idempotence | Deduplicated batch IDs; uncertain results require individual reads before same-page retry | Java duplicate ID test; browser reconciliation |
| Paging/sorting/filtering | Server page size 25, zero-based page, allowed sort keys and all filter params remain authoritative | Browser query/page/sort assertions; unchanged filter builder |
| Detail navigation/accessibility | Keyboard checkboxes/Open, heading focus, return focus, labelled filters and retry controls | Browser keyboard/focus and 390px assertions |
| Long evidence | Wrap messages, isolate wide tables in horizontal scroll, raw JSON disclosure | Desktop/mobile harness screenshots inspected |
| Restart/failover | No new persistent state or lifecycle guarantees introduced | Existing lifecycle/persistence suite; no live failover run |

## Validation

- `mvn -pl server -am test`: 825 tests, zero failures/errors/skips (shared and
  server reactors; includes all vendor SQL contract tests, not vendor live DBs).
- Focused `ProblemBulkReceiptTest,AlertLifecyclePersistenceTest`: 26 tests pass.
- `npm run test:triage --prefix webadmin`: pure receipt regressions and Chromium
  interaction harness using actual React 18 and mocked public host API contracts.
- `npm run build --prefix webadmin`: bundle plus registration/host-intent smoke
  verifier pass.
- Impeccable mechanical detector: no findings. `git diff --check` passes.

Browser evidence is produced in `target/triage-browser`; run logs and design
results are retained locally in `target/triage-validation`. The harness is not
live-engine or authentic host-theme validation. No engine installations or
external/shared databases were created or changed. Lifecycle tests drop their
owned in-memory Derby databases in teardown; browser/server processes terminate
in harness cleanup. Shared browser binaries and Maven inputs are retained.

## Integration

Expected conflicts with siblings: `problems.jsx` detail additions, `plugin.jsx`
CSS, small FilterBar/ChannelPicker accessibility additions in `ui.jsx`, and bulk
endpoint portions of `SentinelServlet.java`. Merge the shared receipt/lock props
and evidence order when integrating decision-inspector or maintenance work.
