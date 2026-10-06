# Saved triage views review

Independent PR from origin/main 24de1c2. Browser-local named filters, sort and page size, plus last-used preferences, scoped by authenticated engine ID and user ID. No problem payloads, selections, credentials or server writes are persisted. Search terms are explicitly saved filter text; localStorage is not a confidentiality boundary against someone with local browser access. No sharing URL: the existing public intent does not express the complete filter contract.

The read-only View-authorized context endpoint identifies the engine/account. Current core user identity must match before mounting the preference workspace. Context failures offer an explicit operation-only fallback. Every loaded view still calls the existing Problems endpoint, which independently checks current View permission and permitted channel IDs before results/counts; preference values confer no access. Saving is local and creates no audit event. No schema change.

| Scenario | Behavior / checks |
|---|---|
| Save/replace/load/delete | Validated named snapshots (20 maximum, 80-character names), page reset and selection clear on load; browser checked |
| Reload | Last filters/sort/page size govern first request; browser checked nondefault search, 100 rows, severity ASC |
| Engine/account change | Distinct keys; anonymous/mismatched identity disabled; user switch remounts without prior filters; node/browser checked |
| Permission or channel changes | Existing authorized server list/count path remains authoritative; no stored incidents; reviewed servlet → ProblemService visibility predicates |
| Invalid/corrupt/versioned storage | Reject rather than apply; defaults remain operable; visible error and explicit reset; node/browser checked |
| Storage unavailable/full | Save cannot report success; error preserved; injected node failure |
| Delayed requests / debounce | Query identity + sequence gate; loading saved view invalidates pending read before next request; browser checked |
| Concurrent tabs | Synchronous read-before-write preserves latest observed entries; storage events reload names; simultaneous writes are last-writer-wins, no distributed merge promise |
| Host intent | Explicit problem/unacknowledged intent overrides saved filters, public navigation retained; source reviewed |
| Narrow screen / keyboard | Labelled native inputs/buttons; wrapping layout at 390px, no horizontal overflow; Chromium screenshots inspected |
| API failure / partial completion / retry | Context retry or fallback; local write is atomic setItem; existing incident mutation semantics unchanged and not broadened |

Validation: 821 Java tests, zero failures/errors/skips; six focused Node tests and Chromium React 18 contract interaction harness; frontend build and Maven package. Harness substitutes public host contracts and validates actual Problems component, not a live engine or host theme. Host DataTable remains unchanged. Screenshot inspection found no saved-form overflow; no additional visual polish loop. Scoped detector evidence recorded in target/saved-views-validation. No isolated engine install/external database created; owned browser/server exited and existing in-memory Derby tests teardown their databases. Ignored logs/screenshots/build artifacts retained, no shared resources removed.

Full accumulated diff review covered account gate, filter validation, list polling, paging, sort headers and existing problem actions. Integration conflicts expected with triage/maintenance PRs in problems.jsx, api.js, plugin.jsx and package files; combine the saved form into PR22's workspace and retain PR22's freshness/receipt/selection behavior. No dependency on those PRs.
