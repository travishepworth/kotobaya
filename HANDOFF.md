# HANDOFF T207b

STATUS: CONTRACT_CHANGE_REQUEST (server side done and tested; the worker→server bridge needs a contract nod)
BRANCH: t/T207b-jpdb-server
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `imports/JpdbPlan.kt`: strict parser for T207a plan v1. `JpdbHistoryApply.kt`: plan → ops in batches, result, sum check.
- `ImportsModule.kt`: `importJpdbHistory` handler and `JpdbHistoryApplyRunner` (claims the internal `jpdb-history-apply` job; protocol in KDoc).
- Test plan: `imports/reviews-sample.plan.json`, made by worker `plan_import`/`job_result` from the sample.
- Not wired (not owned): Main.kt needs `ImportsModule(db, sync.opApply)`: `.handlers` and `.start(scope)`. The worker line is NOT added: `fetch_blob`/`apply_plan` don't exist in Python yet.

## Acceptance → tests (`JpdbHistoryImportTest`)
- Sample ops, due dates, sums → `sampleOpsMatchThePlanAndReplayGivesEveryReviewedCardADueDate`
- Re-run → `rerunningTheSameImportChangesNothing`
- Writing suspended / known-only → `writingCardsAreSuspendedAndNotQueuedAndAKnownOnlyWordHasStatusButNoCard`
- Kanji merge → `aKanjiNoteFromTheImportAndAnOfflineAdd…`, `aKanjiAddedOfflineBeforeTheImportIsReused…`
- Unmapped → `theReportListsEveryUnmappedItem`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Extra result member `reviews_id_collisions`. NUL kanji: rows go to `kanji_unmapped`. `Mapped.candidates`: not in plan v1.
- HUMAN_CHECKS (after the bridge exists): `PUT /blobs/<sha256>` the private export, `POST /import/jpdb/history`, then `GET /jobs/<id>`. Check that (vocabulary_carded + vocabulary_status_only) / (vocabulary_total − vocabulary_en_jp_ignored) ≥ 0.95. Then spot-check 20 `due_at` values against jpdb.

## If CONTRACT_CHANGE_REQUEST
CONTRACT: contracts/db/V1__init.sql (COMMENT on app.job.kind)
CHANGE: "...or a server-internal kind (e.g. `jpdb-history-apply`), which the API never lists or returns."
WHY: the job protocol gives the worker no way to hand data to the server's OpApply. Next: a worker ticket for `fetch_blob`/`apply_plan` (enqueue and poll).
ADDITIVE: yes
