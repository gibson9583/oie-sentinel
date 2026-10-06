# Delivery inbox review

Base: maintenance commit 9663474, itself based on origin/main24de1c2. Stacked on maintenance PR24 (9663474); independent of decision inspector/import preview.

Delivered: VIEW-only paged attempts with SQL authorization before both rows and counts; optional channel, action, transport, event, phase, success and time filters. Pending lifecycle accounting is a separate event page and never a promise of queued/due delivery. No inbox sends or retry endpoints. Schema v14 adds nullable action/name/transport/phase snapshots to future attempts, plus deterministic time/id ordering and an index. Existing rows remain unknown. Current-name fallback is explicitly labelled current; deleted-action identity is asserted only when a captured ID exists. Event retention may cascade-delete its dispatch history.

Saved action tests continue to use the persisted definition fetched at action time. The editor displays its saved destination snapshot, identifies unsaved changes, guards duplicate pending clicks, and retains an inline server-authoritative transport receipt. WEBHOOK receipts expose only origin, omitting paths, query, fragment and user info. No test logs are added to the event inbox. A transport acknowledgement is not human receipt; failure or lost response can have an uncertain external outcome. No automatic resend.

## Behavior matrix

| Case | Expected behavior / evidence |
| --- | --- |
| Happy attempted delivery | Captured identity, transport, phase; read-only page; dispatcher regressions plus real Derby queries |
| Historical record missing snapshots | Unknown transport/phase; current name labelled current if available; never backfilled from present state |
| Action renamed/deleted | Captured name wins; ID survives FK nulling; real Derby delete/filter regression |
| No authorized channels / requested hidden channel | Empty rows AND zero count; SQL filter applies before paging; all-five mapper binding contract |
| API/database/permission failure | Error with last successful observation timestamp; filter changes clear previous page |
| Invalid IDs/filters/time order | Validation rejects request; no fallback to global results |
| Stale responses / rapid filters | Request sequence ignores superseded response; controlled real React browser regression |
| Same tab clicked while loading | Selected tab disabled; cannot invalidate the pending request without a new load |
| Paging and concurrent arrivals | Bound size <=100, offset overflow clamped, time/id total order; offset paging is a current observation, not a snapshot and can shift under new arrivals/deletes |
| Pending versus attempted | Separate query and copy; retained PROBLEM/RESOLVED flags represent lifecycle accounting, not external delivery |
| Send succeeds before log commit fails | Existing dispatcher retries/accounting unchanged; copy does not claim exactly-once or full external delivery census |
| Partial resolution/retry | Existing partial-completion tests now assert captured phase/identity; earlier durable rows are still skipped |
| Unsaved edits before test | Test uses saved ID only; explicit warning and saved destination snapshot visible |
| Saved definition changed elsewhere | Server refetches current persisted definition; receipt identifies actual saved transport/destination used |
| Test transport failure / malformed receipt / lost response | Inline failed/uncertain outcome; no auto-retry; duplicate pending guard |
| Fresh/old/interrupted migration, reinstall | v14 per-column/index idempotence, historical upgrade matrix, partial-column resume, archive index renamed with tables |

## Validation

- Full Maven server/shared test suite; focused final Derby migration/mapper/authorization/deletion/paging, dispatcher retry and receipt regressions.
- All five packaged mappers parsed and authorization/UNKNOWN bindings verified; all five v14 DDL resources checked. Only Derby was executed here; no claims of live PostgreSQL/MySQL/Oracle/SQL Server execution.
- Frontend production build and host registration verifier.
- Real React/Playwright controlled API/host fixture: read-only request kinds, navigation, paging, stale responses, permission refresh failure, pending isolation, malformed rows, saved destination, unsaved warning, authoritative receipt, duplicate pending guard, loss, keyboard focus, 390px overflow and zero page errors. Fixture CSS is host-shaped, not a live host contrast certification.
- Impeccable detector on changed UI and full accumulated diff review.

No isolated engine installations were created. Owned in-memory databases are dropped by test teardown; fixture browser/server/temp files close/remove in finally. Source, logs, reports and artifacts retained. Existing shared/reference resources were not touched.

Integration overlap: additive servlet/interface/API imports and registrations; a small ProblemsPage initializer consumes the new event-navigation intent (triage and inspector may touch nearby code). Maintenance v13 is preserved; inbox v14 depends on PR24, health v15 follows inbox, notes v16 follows health. No engine/web-client changes, no PR19 or dependabot commits.

Migration correction: original standalone inbox v13 collided with maintenance. This revised chain preserves the complete maintenance migration, request ledger/mappers/transaction tests and uninstall handling. Full cumulative clean/historical upgrade and maintenance tests are re-run; old standalone hashes are not corrected-chain hashes.

Corrected cumulative validation: clean Maven package 879 tests, zero failures/errors/skips; final all-historical/partial v14 migration checks preserve a v13 maintenance receipt/tombstone and ledger schema. Inbox and maintenance browser suites passed. Corrected-chain zip SHA256 `330c9fe6bc8c872f2dcab12094413fff6ddfb8669d5ce530b227405fee0462e2`. Logs `/tmp/sentinel-inbox-cumulative-package.log`, `/tmp/sentinel-inbox-cumulative-final.log`, `/tmp/sentinel-inbox-cumulative-browser.log`, `/tmp/sentinel-inbox-cumulative-maintenance-browser.log`. No live engine/external vendor runtime claim.
