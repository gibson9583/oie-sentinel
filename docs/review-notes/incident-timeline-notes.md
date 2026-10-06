# Incident timeline and append-only notes review

Stacked on corrected health PR32 commit 7535974a7db70bb7089c5ee32d8700dd96872381. Prerequisites: maintenance PR24/9663474 v13 → inbox PR30/a8353ced v14 → health PR32/7535974 v15 → notes v16. Problems detail gains a chronological view of stored open/acknowledgement/resolution timestamps, dispatch attempts, and immutable handoff notes. Nearby incidents group by exact channel and opened time ±24 hours, newest 25 with total explicitly shown. Temporal proximity is not causality; dispatch logs do not establish notification edge/type or human receipt, and the UI does not infer either. Existing lifecycle row fields are snapshots, not a new immutable lifecycle event store. Current/deleted action IDs remain attributable without fabricated names.

## Permission, audit and persistence

View Monitoring gates reads; existing Acknowledge Problems tier gates append, including resolved incidents. Both servlet paths recheck incident visibility before service calls, using the existing channel restriction guard. Nearby SQL restricts the exact already-visible channel before list/count; arbitrary cursor rows cannot expose other incidents' notes. Server alone stamps actor/time. Tests verify hidden incidents return 404 without reaching the service, session actor overrides a supplied actor field, malformed body rejects before writes, and permission annotations match existing reflective registration.

Schema v16 adds Sentinel-owned sentinel_incident_note and event/time/UUID index for all five vendors. UUID primary key avoids generated-key differences and is the durable retry identity. No update/delete API or statement. No FK or retention prune: incident/monitor deletion cannot erase the retry ledger or unlock key reuse. Notes retained after parent retention/deletion become inaccessible through these endpoints. Indefinitely retained text consumes storage. Uninstall archives notes with existing tables (Derby preserves live schema). Append-only is a plugin API property, not protection against a database administrator.

Same key/event/actor/trimmed text replays the original persisted receipt; changed payload/actor/event rejects. PK concurrency produces one note. After insert, read back persisted content before success. Insertion/connection uncertainty reconciles by reading the original key; errors remain visible if committed state cannot be read. Timestamp+UUID keyset pagination (50+1 probe) avoids skips when newer notes arrive; equal times order by UUID without causal claims. Server milliseconds fit all vendors. UTF-8 maximum 4000 bytes fits Oracle's BYTE bound; NUL/unpaired surrogates reject before insert. Database charset must support submitted characters.

Explicit sanitized audit stores note ID, incident/channel, actor and size, not another copy of text; framework body auditing is disabled. Existing engine audit dispatch is best effort, not atomic with notes. Lost acknowledgement after commit can omit that secondary event; the immutable note still records actor/time. No exactly-once audit guarantee.

Client retains an immutable request within the mounted pane until a matching receipt. Pending writes lock synchronously; malformed/failed replies freeze text and expose same-key retry. Drafts/unresolved keys are not persisted across navigation/reload: keep the pane open while reconciling; a fresh note after reload is not replay. Render/action permission checks supplement server authorization.

## Adversarial behavior matrix

| Situation | Behavior / verification |
|---|---|
| Happy / resolved incident | Append stamped plain text; lifecycle/delivery facts ordered by recorded time; persistence, Node, Chromium |
| Empty/oversize/invalid text or body | Reject before writes/audit; UTF-8 astral boundaries and unsupported characters tested |
| Missing / hidden incident | 404; service not reached for hidden IDs; read and append guards tested |
| Failed timeline/prerequisite query | Retain previous facts with last-read time/persistent error and Refresh; repository/browser fault |
| Insert failure | No success receipt/audit; same request works after fault removed; real Derby CHECK fault |
| Connection loss after commit | Read original UUID, return replayed receipt; injected post-commit exception |
| Malformed response | Immutable payload, visible uncertainty, same-key retry; simulated commit + malformed reply |
| Stale render/action permission | Check before POST; framework authorizes current operation; revoked UI permission and hidden server channel tested |
| Concurrent identical requests | One PK row/same receipt; two-worker real Derby test |
| Changed actor/content / cross-event cursor | Reject without exposing original text or adding row; focused checks |
| New note during paging | Stable equal-time UUID ordering, no older skips/duplicates; 105 notes + concurrent newer insert |
| Background / older history | Older notes pause automatic replacement; explicit refresh resets latest 50; browser paging/source review |
| Delayed/unmounted read | Sequence/current-event guards; browser checked |
| Interrupted migration / restart | Missing index repaired without recreating/loss; repeated real Derby migration |
| Partial read / retry | Incomplete envelope rejected; one append per request, no multi-note transaction claim |
| Narrow / keyboard / text | Labelled native controls, keyboard append, 390px wrapping, escaped script text; inspected screenshots |
| Retention/deletion race | Parent deletion prevents later reads; a concurrent append can remain inaccessible with durable identity; no cascade or causal claim |

Full accumulated diff review traced contract → servlet authorization/redaction → service → five mapper/DDL variants. Reviewed permission tiers, archive namespaces, result-map casing, error envelope, persisted receipts, paging, ordering, lifecycle/outbox/retention interactions. Initial checks exposed missing test JAX-RS runtime and engine SQL comment parsing; test-only Jersey and executable DDL fixed before final validation.

Original independent-branch validation: 834 Java tests; 12 focused persistence/permission checks; four Node checks; Chromium React 18 contract interactions; frontend build and Maven package. Derby executes v16 and statements. Other four vendor definitions/packaged contracts reviewed, but live database matrix/live engine/theme were not run. Test-only React/Playwright/Jersey stay out of runtime plugin dependencies. No isolated engine installs/external databases created; Derby teardown drops owned in-memory DBs, browser/HTTP server close in finally. No shared/reference resources touched. Ignored logs/screenshots/artifacts retained under target/incident-notes-validation and target/incident-notes-browser.

Integration: schema v16 follows the complete agreed v13–v15 prerequisites. All predecessor mapper, detection, archive and matrix contracts remain present. PR31 targets the health branch; no omitted prerequisites. Problems/API/plugin/package edits overlap PR22/24/29 and inspector: mount this keyed panel in final detail while preserving independent freshness/receipts. No PR19 dependency; no engine/web-client changes.

Correction validation: 903 Java tests (48 shared, 855 server), zero failures/errors/skips, in the full clean cumulative v13–v16 package, including all historical/fresh Derby upgrades, interrupted notes-index repair, stable note replay and explicit v15→v16 maintenance-tombstone/health-observation preservation. All four maintenance/inbox/health/notes Chromium suites passed on the rebuilt branch. Root target/notes-v16-validation preserves cumulative logs/reports; the controlled browser adapter selects the existing pinned Chromium binary. This chain excludes independent authoring/inspector/import/triage/coverage/saved-view PRs, so it is not the parent all-feature package.
