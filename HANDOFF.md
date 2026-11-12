# HANDOFF T207b

STATUS: CONTRACT_CHANGE_REQUEST (server side done, tested)
BRANCH: t/T207b-jpdb-server
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `imports/JpdbPlan.kt` parses plan v1. `JpdbHistoryApply.kt` turns it into batched ops and the result (sums checked).
- `ImportsModule.kt`: `importJpdbHistory` handler, `JpdbHistoryApplyRunner` (internal `jpdb-history-apply` job, protocol in KDoc).
- Main.kt not wired (not owned): `ImportsModule(db, sync.opApply)` `.handlers` + `.start(scope)`. Worker line not added: Python `fetch_blob`/`apply_plan` don't exist.

## Acceptance → tests (`JpdbHistoryImportTest`)
- Sample ops/due/sums → `sampleOpsMatchThePlanAndReplay…`
- Re-run → `rerunningTheSameImportChangesNothing`
- Suspended writing, known-only → `writingCardsAreSuspendedAndNotQueued…`
- Merge → `aKanjiNoteFromTheImportAndAnOfflineAdd…`
- Unmapped → `theReportListsEveryUnmappedItem`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Extra member `reviews_id_collisions`. NUL kanji → `kanji_unmapped`. Plan v1 lacks `Mapped.candidates`.
- HUMAN_CHECKS: upload export, `POST /import/jpdb/history`, `GET /jobs/<id>`. Need (carded+status_only)/(total−en_jp) ≥ 0.95, plus 20 due dates spot-checked.

## If CONTRACT_CHANGE_REQUEST
CONTRACT: contracts/db/V1__init.sql (comment on app.job.kind)
CHANGE: allow server-internal kinds (e.g. `jpdb-history-apply`) that the API never exposes.
WHY: the worker has no way to hand the plan to the server's OpApply. A worker ticket then writes `fetch_blob`/`apply_plan`.
ADDITIVE: yes
