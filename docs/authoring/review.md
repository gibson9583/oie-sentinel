# Monitor authoring and safe copies

Based on `origin/main` 24de1c2. Plugin-only UI change; no migrations, evaluator changes, engine changes, or action copying. The host wizard frame was inspected in `oie-web-client/web-administrator/client/react/views/wizard-frame.tsx`: its components are private, so Sentinel implements local visited steps against `platform.React` and existing public UI classes.

New monitors use Basics → Condition → Scope → Review & Test. Existing monitors retain Quick edit and can visit any wizard step. Both views share one set of controlled draft fields. Recipes supply editable defaults, start disabled, and preserve the current scope/dependency. Threshold language follows the evaluators' inclusive comparison. Duplicate **saved** monitor/schedule opens a disabled, unsaved allowlisted configuration draft; audit fields, identity and activeNow are excluded. Dependencies, dates, recurrence and timezone are deliberately copied and must be reviewed. Action credentials are outside scope.

Review displays configured scope labels; the draft Test response supplies server-resolved eligible channel names/statuses. This is a current dry-run, not persisted evaluation progress or proof of notification delivery. Lookup failures stay visible, raw IDs can remain visible, and insufficient data stays insufficient data. Scope/type/server permissions remain authoritative.

## Behavior matrix and adversarial review

| Situation | Behavior / evidence |
|---|---|
| Happy path | Recipe → validated steps → named scope → review → Test → create; browser checks verify shared draft and copy create payloads. |
| Missing prerequisites | Empty name, invalid numeric condition, missing single-channel scope block Next; missing inventory is not healthy. Java scope completeness tests cover unknown inventory. |
| Read failure | Lookup error banner preserves draft; test failure is inline and preserves fields. No history is queried for copies. |
| Write failure | Inline rejection preserves draft. Lost-connection warning says to inspect the list before retrying; no automatic retry or idempotency claim. Existing create APIs have no request-key contract. |
| Stale render/action | Manage Monitoring / Manage Schedules is checked on action, and again after delete confirmation; server remains authority. Browser revocation test proves no create is dispatched. |
| Ordering / concurrency | Ref locks stop same-tick duplicate save/test/delete; fields, close, steps, switch and duplication lock during operations and leave confirmation. Internal navigation request generation discards older intent/tab results. |
| Partial completion | One entity per create/update; no batch or fan-out introduced. Malformed/empty test data never supplies healthy scope evidence. |
| Retry / idempotence | Tests are explicitly repeated by operators; copies POST with no id, never update/delete originals. Unknown write completion is not automatically retried. |
| Restart / failover | No durable server behavior changed. Drafts are retained in the public session store across host SPA unmount and restored on returning to the editor, but not persisted across browser restart. Explicit discard/save clears the retained draft. |
| Boundaries | Existing evaluator rules unchanged: error/queue comparisons include threshold equality; duration is evaluation-observed, not retroactive. Schedule timezone and overnight recurrence copied unchanged. Focused Java tests cover these contracts. |
| Permissions | View-only editor fieldset is disabled; copy and mutation controls require matching permission. No action secret copying implemented. |
| Accessibility | Step nav has an accessible label and aria-current; recipe/name/basic/config fields have labels; errors/status announced; responsive footer wraps. Real-component fixture checked at 1440px and 390px. |

Full accumulated diff reviewed across monitor and schedule create/update/delete/test paths, copy identity, editor key remounting, lookup failure, guarded internal tabs/intents and browser unload. Review fixes: disable editing while awaiting test/write/leave, ref lock before awaits, newer navigation generation, inclusive-threshold recipe wording, wrap schedule footer, retain copy dependencies and omit saved history. Existing unrelated unguarded list toggles are unchanged.

## Validation and limits

- `node --test webadmin/verify/authoring.test.mjs`: 3 passed (allowlist/identity/audit/secret stripping, recipe contracts, guard ownership cleanup).
- `npm run build --prefix webadmin`: production bundle and registration/host-intent smoke verifier passed.
- `mvn -pl server -am test -Dtest=ChannelStateEvaluatorTest,ConnectionStatusEvaluatorTest,ErrorRateEvaluatorTest,QueueDepthEvaluatorTest,TriggerEvaluatorJobOrderingTest,ScopeInventoryCompletenessTest,WindowScheduleTest -Dsurefire.failIfNoSpecifiedTests=false`: 217 passed, zero failures/errors/skips. Maven log retained locally as `authoring-java-validation.log` (ignored).
- Real React components in Chromium with controlled host/API boundaries: 31 checks at each of 1440px and 390px; no page errors/document overflow. Results/screenshots under `webadmin/verify/authoring/evidence`. Fixture styling approximates host classes; this is interaction verification, not a live-host screenshot or live-engine validation.
- Impeccable mechanical detector returned `[]` for edited editor surfaces.

Public `RouterApi` exposes navigate/currentPath, without guard registration. This PR guards Sentinel tabs/intents, Back/Cancel/copy, and browser unload; **host SPA navigation can still unmount without a confirmation**. Sentinel retains unsaved monitor/schedule drafts in its own public-store keys and restores them when returning, avoiding silent loss while staying inside public contracts. It intentionally avoids the host's private `navGuard` store key. A host public guard contract is needed for confirmation on host navigation. No engine installation, database or shared resource was created. Owned fixture HTTP/browser processes were closed after verification; generated fixture bundle is ignored and can be rebuilt.

Potential merge conflicts: `webadmin/web/pages/monitors.jsx` for recovery/coverage authoring integrations; `maintenance.jsx` for contextual maintenance work; small navigation additions in `plugin.jsx`. No dependency on PR19 or sibling feature branches.
