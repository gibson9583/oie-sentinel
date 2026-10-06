# Consecutive healthy recovery hysteresis

Based on main 24de1c2; independent of authoring PR20 and PR19. Optional `configJson.minConsecutiveRecoveries` is an integer 1–10000; absent/null/1 retains existing immediate recovery and byte-identical raw evidence. Every monitor type can use it. This version adds consecutive healthy evaluations rather than a second metric threshold; all existing metric comparison/equality rules are unchanged.

For an open incident, each confirmed OK advances recovery. BREACH and INSUFFICIENT_DATA reset the sequence to zero; unknown data never recovers an incident. Below the requirement, the incident stays open and continues the existing repeat/escalation/suppression pipeline. Operational eligibility/departure closure bypasses metric recovery; manual resolve keeps its existing trigger reset/reopen behavior. Existing flap control remains in the notification pipeline.

Progress is stored under `lastValueJson.sentinelRecovery` as `{healthyCount,required,alertEventId}` with the latest raw observation in the existing trigger transaction. Incident and requirement identity prevent stale progress reuse after manual/new closure or policy changes. Trigger rows are loaded each evaluation, so there is no process-local counter to lose on restart/leadership takeover. Type-specific fields (including stateSinceIso) are preserved. This uses existing five-vendor CLOB mappings and has no schema migration or vendor JSON SQL.

`GET /monitors/{id}/recovery` is a read-only View Monitoring operation. It returns at most 1,000 visible latest trigger rows; restrictions apply before rows, truncation or event lookups. It exposes current opening/recovery progress, evaluation time and actual current problem status. Metadata from a closed/missing incident is not presented as active recovery progress. It sends nothing and performs no state mutation. The editor separates saved progress from unsaved configuration and dry-run tests; read/malformed-response failures do not imply health. The decision inspector can consume the same namespace/read API independently.

## Behavior matrix / full accumulated review

| Situation | Expected behavior and verification |
|---|---|
| Happy path | N healthy evaluations resolve once, preserve durable resolution pending, and keep repeats active while awaiting N. Actual Derby persistence tests. |
| Old configuration / boundaries | Missing/null/1 resolves on first OK; raw value remains identical. Integer bounds 1..10000 validated; floats, strings, booleans, overflow rejected. Existing metric threshold equality unchanged. |
| Unknown / missing data | Unknown resets consecutive progress and retains incident; OK after unknown begins at one. Mixed breach/OK/unknown persistence test. Missing legacy progress begins at zero. |
| Read/API failure | Progress panel marks retained observations stale, offers Refresh; malformed response shows error. Endpoint lookup errors propagate and never emit invented zero healthy values. |
| Write/persistence failure | Recovery/trigger/outbox changes share existing fenced transaction; constraint failure rolls back closure/progress and no after-commit notification runs. Explicit retry succeeds once. |
| Stale render/action state | Saved progress is labelled separately from editable draft. Requirement change resets sequence. Manually resolved incident with failed trigger reset clears without delaying/renotifying. |
| Concurrency / ordering | Existing single evaluator scheduling, transaction/fence and durable notification edge contracts preserved; full lease/lifecycle/queue suites run. Progress is written with the same trigger transaction, not separately. |
| Partial completion | One trigger transaction per outcome; one failure leaves prior progress. Progress response caps visible rows and labels truncation. Hidden rows cannot affect truncation. |
| Retry / idempotence | Additional healthy evaluations after closure do not resolve/notify again. Rollback retry emits one committed resolution. New incident identity prevents inheriting a prior sequence. |
| Restart / failover | Fresh monitor/trigger repository reads preserve count, with existing fenced persistence. No live process failover was exercised; no claim of live-engine validation. |
| Operational closure | Departure remains INSUFFICIENT_DATA and bypasses even a requirement of 100, separate from metric recovery. Existing state/connector eligibility sweeps are unchanged. |
| Permissions | Endpoint has VIEW MirthOperation; servlet passes authorized channel set. Service tests filter hidden rows and prohibit hidden event lookup; response contains no secrets. Existing mutation permissions unchanged. |
| Accessibility / layout | Existing NumField form style maintained. Real React/Chromium fixture verifies input validation, draft test payload, failure preserving input, visible saved progress and long channel wrapping at 1440/390. This is not full keyboard/AT certification. |

Adversarial review traced service create/update/import validation, config building for all seven types, applyOutcome/onBreach/onOk/onInsufficientData, retained incidents, manual reset, departure, repeats/escalation, fenced transaction rollback, DTO serialization and authorized progress reads. Newly fixed during review: count tied to incident+requirement; stale manually resolved event bypasses delay; progress API validates shape and checks actual problem status; hidden rows/event lookups filtered; long channel identifiers wrap. No detached process counters or changed transport behavior.

## Validation and resource cleanup

- Full shared/server suite: 832 tests, zero failures/errors/skips; includes vendor SQL mapping/binding contracts and Derby migrations/persistence, lease/fence, queue, ordering, suppression, flap control and all evaluators.
- After two additional operational/manual-closure regressions: focused recovery/lifecycle/progress suite recorded in `recovery-focused-validation.log` (ignored), 35 tests, zero failures/errors/skips.
- Frontend production build and registration/host-intent smoke passed.
- Chromium fixture: 6 checks at each of 1440px and 390px, zero page errors/overflow; evidence under `webadmin/verify/recovery/evidence`. Real components and controlled public API boundary; fixture CSS approximates host classes.
- Impeccable detector returned `[]`.

Derby tests use isolated UUID memory databases and drop them in AfterEach; assertions capture final row state before teardown. No engine installations or shared/reference databases created or touched. Owned Chromium/HTTP fixture processes closed after evidence. Maven logs and source/fixture evidence preserved. No external five-vendor or live engine/failover validation claimed.

Potential merge conflicts: monitors.jsx config builder/editor/history placement with PR20, servlet/shared interface additions with diagnostics features, and TriggerEvaluatorJob with future queue/replay work. No merge performed.
