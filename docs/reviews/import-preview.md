# Import preview and mapping review

Base `24de1c2` (`origin/main`), independent of the decision inspector and PR19. Sentinel-only changes; no schema or new vendor SQL. New read-only preview and reviewed-apply endpoints require Manage Monitoring and exclude credential bodies from audit. The legacy additive import remains available and now shares import serialization and early document-shape validation.

The preview and apply use the same import passes, secret planner, portable comparison, dependency ordering and service definition validation. Preview assigns only local negative IDs to planned creates; it never invokes create/update, test sends, audit writes or dispatch. The response contains creates/updates/unchanged/invalid entries and safe field diffs. Entire embedded configuration values are omitted; known numeric fields such as thresholds/durations can still be compared. Marker secrets missing on the target leave an action untouched rather than creating an uncredentialed destination. Secrets retained from the target remain explicit.

Optional mapping is explicit JSON keyed by CHANNEL/GROUP/TAG, each mapping source ID to existing target ID. It covers monitor/schedule scopes, CHANNEL action destinations and channel/group/tag condition values. The preview lists each changed reference path. Unresolved references, duplicate document names and monitor dependency cycles remain untouched. Monitor dependencies must use exported names, not environment-specific Sentinel IDs. Numeric action escalation IDs are rejected in the reviewed path; configure escalation on the target after import. This conservative limitation avoids binding an unrelated target action with the same serial. The existing export/legacy import format remains unchanged.

Target fingerprints include identity/audit/definition state, engine inventory and memberships. Stored encrypted action configurations are hashed only on the server to detect secret rotation even with an identical audit timestamp; no ciphertext or secret value enters preview/diffs. Input hashes bind the reviewed document and mappings. A target change during preview rejects the observation. Apply repeats the plan; a changed target/input returns a refreshed preview without entity writes. The operator must review and explicitly apply again.

Both legacy and reviewed imports acquire a dedicated `sentinel-config-import` lease and hold its row lock on a separate SQL session. This serializes imports across plugin nodes, even if a long import outlasts its lease expiry. Entity services commit individually on their own connections: there is no enclosing config transaction. A crashed owner releases its connection lock and leaves at most the 60-second lease wait. Editor writes remain independent; fingerprints/revalidation are stale guards, not a guarantee against every edit racing a service write.

A per-entry unexpected failure produces UNCERTAIN rather than a fabricated skip/success. A broader interruption returns the receipts collected so far with `incomplete=true`; unprocessed entries have no receipt. UI receipts remain visible across later previews and failures. Lost/malformed apply responses force a fresh preview before another apply. No automatic retry is added; reruns converge through existing name matching and unchanged comparisons.

## Behavior matrix

| Situation | Behavior and evidence |
| --- | --- |
| Happy path | No-write preview, explicit diff/remapping, parent-before-child apply; Java preview/apply/order tests |
| Unchanged rerun | No extra mutations/audit writes; Java repeated apply test |
| Missing dependency/target | Per-entry skip; no reference silently removed; cycle/missing scope tests |
| Missing secret | Field path and follow-up note, action untouched; no raw secrets/config bodies in serialized diffs; Java secret test |
| Invalid/duplicate entry | Shared definition validation and conservative duplicate rejection; Java invalid/cycle/duplicate tests |
| Invalid overall body | Rejected before inventory, import ownership or entity writes; shape regression |
| Inventory/API failure | Preview fails visibly with draft/mappings retained; Java inventory failure and UI malformed/read-response tests |
| Target/input changed | Replan and return stale without entity writes; Java target/input/secret-rotation tests; UI stale target test |
| Concurrent imports | Dedicated shared lease owner gate plus SQL fence; lock owner/failure tests and existing takeover/fencing JDBC tests |
| Partial/ambiguous write | Preserve landed receipts, UNCERTAIN row, fresh preview reconciles unchanged versus remaining; Java failure test |
| Lost apply response | Preserved previous receipts, explicit uncertain completion, apply disabled pending preview; browser network failure test |
| Confirmation stale | A document change cancels dependent apply; pending ref lock prevents duplicate operation; browser delayed-confirmation test |
| Retry | Manual preview/reconcile before rerun; no automatic apply or delivery retries |
| Permissions | Both endpoints Manage-gated, preview unaudited, bodies excluded from audit; contract test; action-time host enforcement |
| Keyboard/narrow screen | Labelled mapping textarea, labelled buttons, focus check and 390px overflow regression |
| Restart/failover | Shared lease lock uses existing vendor/fence contract; connection exit plus bounded expiry permits another importer |

## Validation and limits

Java import tests use the real service definition validators/redactor while substituting in-memory service persistence. Import-lock unit tests cover active ownership, stale fence and release on failure. The additional real Derby JDBC probe verifies committed writes on an independent connection survive rollback of the import lock session; existing takeover tests verify row-lock/fence ordering. Full Java/frontend/package results are recorded with the PR.

`SENTINEL_PLAYWRIGHT_PACKAGE=/path/to/playwright/index.mjs node webadmin/verify/import-review.mjs` renders the shipped component with real React and controlled host/API replies. It verifies preview-before-write, pending confirmation, stale target, preserved uncertain/partial receipts, network-loss reconciliation, stale-document confirmation, malformed response, explicit mappings, keyboard focus, 390px overflow and zero page errors. Impeccable detector found no issues on the changed UI; `git diff --check` passed.

No live engine or live five-vendor import was run. Browser CSS is host-shaped fixture CSS, not a host visual/contrast certification. SQL operations reuse existing mapper contracts for all five vendors; this feature introduces no migration. Configuration import is additive and partially committed, not atomic; concurrent editor writes retain existing service semantics.

## Cleanup and evidence

No engine installation or external database was created. Real persistence probes use uniquely named in-memory Derby databases and drop them after connections/test workers exit. Browser runner closes its owned Chromium and HTTP server and removes its runnable temporary fixture directory in `finally`. Source tests, Surefire reports and `/tmp/sentinel-import-*.log` are preserved; shared/reference resources remain untouched.

Integration overlap: shared servlet/interface/API additive declarations overlap other PRs; service validation extraction may overlap authoring; settings import panel is owned by this PR. Keep independent additions and shared validators when resolving conflicts. No dependence on the diagnostics inspector branch.
