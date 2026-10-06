# Cluster health review

Stacked on delivery inbox PR #30 / a8353ced, solely to sequence plugin schema v15 after v14. Review this PR's diff against the delivery branch; delivery behavior remains separately reviewed in its own matrix. No engine/web-client changes.

Delivered: read-only VIEW `/health` and Sentinel health tab. Leader/epoch/expiry/database clock, live Sentinel presence nodes, shared job starts/completions/last successes, authorized retained-row/pending-edge observations, configured retention and explicitly labelled row-capacity estimates. Each section fails independently to UNKNOWN. Existing dashboard heartbeat labels and Prometheus HELP now explicitly describe the serving node; a standby's absent local tick is not leader failure.

Schema v15 creates a four-row logical job-observation surface (collector/evaluator/rollup/prune). Starts and finishes use database clocks, the captured engine fencing epoch and transactional lease locks. Completion also matches a unique run ID. Previous success evidence keeps its own node/epoch/time across errors or a leader change. Telemetry write failures are logged and do not prevent normal job work; absent/unchanged timestamps cannot establish current health. No credentials, per-channel exception messages or internal stack traces enter health responses.

Success means completion without a detected exception in the job and its caught per-unit work. It is not a claim that every monitor had sufficient data, every external dependency answered correctly, or every asynchronous delivery succeeded. A start without a matching completion is labelled unknown, not running. Current-epoch success freshness uses two configured intervals plus 120 seconds (hourly rollup/daily prune). Earlier-epoch evidence remains historical.

## Behavior matrix

| Case | Expected behavior / check |
| --- | --- |
| Leader / standby request | Same database job evidence; local empty heartbeat is neutral, never leader-failure inference |
| Lease absent/expired | Explicit ABSENT/EXPIRED observation; no current-success claim |
| Clock, presence or job read failure | Independent UNKNOWN section; leadership/settings/authorized counts still available where reads succeed |
| No shared job row | No shared observation; never healthy by default |
| Prior leader last success | Own success node/epoch retained; no success asserted for current epoch |
| Successful tick | DB start/finish and success time persisted behind live fence |
| Caught per-unit/batch/top-level error | ERROR completion; prior success untouched; collector failure and partial prune regressions |
| Partial prune then retry | Already committed deletes remain; error recorded; next tick can complete without inherited thread error state |
| Superseded run in same epoch | Late completion rejected by run ID; newer RUNNING evidence untouched |
| Lease release/takeover | Old epoch completion rejected by real mapped fence transaction; successor evidence untouched |
| Standby tick / unmanaged tests | No shared observation writes |
| Observation store fails | Job work continues; no fabricated success/completion; logs retain failure evidence |
| Crash/failed completion write | Start remains; completion unknown; no process-is-running claim |
| Stale/malformed HTTP response | Sequence ignores old/unmounted response; error preserves timestamped previous observations |
| No authorized channels | Zero retained/pending counts before aggregation; estimates intersect deployed inventory with authorization |
| One pending edge lacks timestamp | Oldest pending age UNKNOWN, even if other pending events have known times |
| Retention/storage | Actual visible row counts; configured retention does not prove prune success; physical bytes unknown |
| Capacity estimation | Assumes stable currently deployed visible channels and successful cadence over retention; explicitly estimated, not physical storage or future volume guarantee |
| Migration/uninstall/reinstall | Fresh + all historical upgrades, idempotent table create, named PK archive and original archive table ordering preserved |

## Validation and cleanup

Full Maven package/test suite: 889 tests, zero failures/errors/skips. Final focused real Derby shared observation supersession/takeover, authorization/missing-edge, migration/mapper/reinstall checks; static/mock job and independent section failure/estimate tests. All five packaged mappers/DDL contracts checked; only Derby executed in this session. No live-engine or external-vendor execution claim.

Production frontend build/host registration verification and controlled real React/Playwright health fixture cover leader/standby evidence, missing/old/failed jobs, pending age, epoch change, partial read failure, stale unmounted response, permission failure, malformed rows, keyboard and 390px overflow with no page errors. Delivery fixture re-run on the stacked result. Fixture CSS is host-shaped, not a live host contrast certification. Impeccable detector and exhaustive full-diff call/data/error review completed.

No isolated engine installation was created. Owned in-memory test DBs drop in teardown; browser/server/temp fixture files close/remove in finally. Source, logs, reports and artifact hashes retained; no shared/reference resources touched.

Integration: schema15 depends on delivery schema14 and maintenance schema13; target the delivery branch until it merges. Additive servlet/interface/API/plugin registration; Dashboard heartbeat copy may overlap sibling coverage work. Existing process-local metric names/values remain stable; HELP discloses their scope. No unrelated feature changes, no PR19/dependabot commits and no merges.

Correction preserves complete maintenance13/inbox14/health15 chain. Notes16 can stack on corrected health. Original standalone v14 collided with maintenance allocation and must not be packaged. Cumulative migrations preserve v13 maintenance receipt/tombstone and run all maintenance tests.

Corrected-chain clean Maven package: 889 passing tests with zero failures/errors/skips. Maintenance, inbox and health browser suites passed. All 15 vendor/version DDL definitions verified against their intended source commits. Corrected zip SHA256 `0281daeb3e48beceb8c1d4d1524f5cd33c9338ec710015da84eca9624db744d9`; frontend `864dac7a0065dfa2ff1f07ecc266a999ca30a2b4607eeeb41bbc4ec2338d5c34`. Logs `/tmp/sentinel-health-cumulative-package.log`, `/tmp/sentinel-health-cumulative-browser.log`, `/tmp/sentinel-health-cumulative-delivery-browser.log`, `/tmp/sentinel-health-cumulative-maintenance-browser.log`, `/tmp/sentinel-health-cumulative-web.log`. This artifact combines maintenance/inbox/health only; it is not the parent all-feature package.
