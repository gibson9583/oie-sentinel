# Queue growth and net stall detection

Independent branch from main 24de1c2. Existing QUEUE_DEPTH config defaults to mode DEPTH, preserving its evaluator behavior. Optional GROWTH/STALL modes use retained raw channel queue gauges, never hourly trends. No enum, schema, mapper SQL, engine or destination APIs change.

Config: `mode` DEPTH/GROWTH/STALL; `threshold` depth floor; trend `windowSeconds` 1..86400 (default300); `maxSampleGapSeconds` 1..1200 (default120); GROWTH `growthPerMinute` finite positive (default100); STALL `maxNetDecreasePerMinute` finite nonnegative (default0). The UI explains defaults and retains type/mode drafts. Growth requires latest depth at least threshold and estimated endpoint net change at least growth rate. Stall requires every sampled depth at least threshold and net change at least negative maxNetDecreasePerMinute: equality is included. Zero detects flat/growing depth. This is **net shrink/growth**, not proof of throughput failure.

A complete observed window is anchored at the latest sample and spans at least windowSeconds; latest freshness, baseline boundary and all intersample gaps must be within tolerance. Queries cover at most window+2*gap seconds. Fewer than two samples, retention loss/missing baseline, gaps, stale endpoint, duplicate/unsorted/future/null timestamps, negative depth and more than10000 samples yield INSUFFICIENT_DATA. Gauge differences are calculated in integers before converting to a rate, preserving small differences at large depths. Captured evidence includes actual sample range/count, observed duration, latest/minimum depth, configured thresholds, estimated net messages/min and limitations.

Only started channels remain eligible through the existing QUEUE_DEPTH evaluator job routing. Collection gaps are unknown and do not resolve open problems. Existing consecutive opening hysteresis, suppression, repeat/escalation, leadership fence and transaction behavior are inherited. Restart uses persisted raw samples; no local trend counter introduced. No exact drain time/latency, destination message age, causal explanation or historical deployment certainty is claimed.

## Behavior matrix / adversarial review

| Situation | Behavior / verification |
|---|---|
| Happy path | Full sample window yields inclusive growth/stall breach and captures source samples/rate. GROWTH threshold and STALL equality tests. |
| Missing prerequisites | Fresh monitor/raw retention loss/partial window stays unknown; no hourly fallback. Samples must cover an actual full observed window even when latest reading is within freshness tolerance. |
| Read/API failure | Repository failures propagate to existing per-monitor job/draft-test error handling; no fallback healthy observation. Mock failure test. |
| Write failure | New feature performs no extra write. Existing atomic trigger/outbox behavior unchanged; lifecycle persistence regressions run. Draft test/save uses existing endpoints; failed tests retain input. |
| Stale render/action | Live evaluation rereads current raw samples; old endpoint beyond tolerance is unknown. Editor Test uses captured draft payload; mode-specific fields round-trip and inactive-mode parameters are omitted. Existing authoring locks/dirty guard improvements live independently in PR20. |
| Concurrency / ordering | SQL sample_time order required; duplicate/unsorted samples unknown. Existing leader/collector/evaluator and lifecycle fences unchanged; no process-local slope state. |
| Partial completion | Each trigger retains the existing per-outcome transaction; one lookup failure cannot establish health. Bounded sample count is explicit unknown. |
| Retry / idempotence | Evaluator is pure over samples/config; same window deterministic. No new side effects or sends. Existing lifecycle adopts retained incidents instead of opening duplicates. |
| Restart/failover | Reconstructs rates from retained raw samples; gaps across restarts remain unknown. Persisted lifecycle tests run, no live failover claim. |
| Boundaries | Inclusive thresholds, exact gap tolerance, fractional gap rejection, full observed span and Long.MAX_VALUE-adjacent gauge arithmetic tested. Numeric strings NaN/Infinity and invalid whole windows/gaps rejected at save. |
| Permissions | Uses existing QUEUE_DEPTH monitor/save/test permissions and scope routing; no new read endpoint or aggregate counts. |
| Accessibility/layout | Existing host form style; real-component fixture verifies mode controls, bounds, payloads, failed-test draft retention, unknown results and copied defaults at1440/390 with no page errors/overflow. No full keyboard/AT claim. |

Full diff reviewed end-to-end: config seed/build, per-mode UI, server create/update/import validation, draft-test routing, QueueDepthEvaluator defaults/new branch, raw query bounds, window selection, evidence, job started gate and lifecycle outcome handling. Review fixes: anchor full width at latest sample, prevent double-to-double loss of small gauge deltas, reject fractional gaps above tolerance, validate finite rates, update type description to include the added modes. No previously reported fix alone was treated as complete review.

Validation: focused Java queue/config/scope/lifecycle suite50 passed; production build/smoke passed; real React/Chromium fixture10 checks each1440/390, no page errors/overflow; Impeccable detector `[]`. Full shared/server suite830 passed, zero failures/errors/skips. Browser fixture uses controlled API and approximate host CSS, not live-engine validation.

Resources: no engine/reference/shared databases touched. Lifecycle regressions create isolated UUID Derby memory databases dropped in AfterEach, with final row assertions captured before teardown. Test logs retained locally, source/fixture results/screenshots committed. Owned browser/HTTP processes close after evidence.

Merge conflicts: monitors.jsx and ui.jsx type copy with PR20/recovery authoring; MonitorService validation with recovery/import. Changes can merge semantically (new queue fields plus optional recovery field), but require rerunning combined config/editor tests. No dependency on PR19 or another feature branch.
