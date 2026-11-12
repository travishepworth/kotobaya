# HANDOFF T207c

STATUS: DONE
BRANCH: t/T207c-jpdb-wiring
CHECK: `make check MODULE=worker` → green (316+ passed, mypy, ruff); `make check MODULE=server` → green

## Changed (≤ 6 lines)
- Worker: `importers/jpdb_history/apply.py` (`fetch_blob`, `apply_plan`: step 2 adopt, races 8/9, 10-min queued bound); registered in `runner/__main__.py`. `plan.py`: NUL kanji to `kanji_unmapped`, `job_result` reports `reviews_id_collisions`.
- Server: `composeApp`/`main` wire `ImportsModule` (`ComposedApp.imports`). Runner: side-thread heartbeat every 10 s, stale re-queue capped at 3 then `failed`. `JpdbHistoryApply`: plan-only sums before batch 1, terse sum errors, no resurrection of user-deleted notes (counted in `reviews_already_imported`).
- Internal-kind filtering already lived in JobRepo; now tested (list/get/cancel/watcher) and `API_JOB_KINDS` is tested against openapi `JobKind`.

## Acceptance → tests
- Items 1,3,5 → `JpdbHistoryImportTest` (`aNoteTheUserDeletedStaysDeletedOnARerun`, `aDeletedKanjiNoteStaysDeletedToo`, `aPlanWhosePlanOnlySums…`); 4,11,12 → `aStaleChild…Cap…`, `theChildIsHeartbeatInsideALongBatch`
- 2, 7 → `test_jpdb_t207c_plan.py`; 7–10 → `test_jpdb_apply.py` (fake store), `test_jpdb_apply_pg.py` (real PG, docker)
- 6, 13 → `InternalJobsTest`; wiring → `JpdbImportWiringTest` (fixture plan through composeApp, parent counters)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Unowned edits: `worker/tests/jobs/test_dict_refresh_wiring.py` (registry kinds list); `Context` protocol gained `job_id`. The tombstone rule (provisional) skips a whole group (not a single review) and cannot catch known-only kanji cards with no reviews.
- `job_result` collisions only detect intra-plan ones (it has no card info for existing ids); the server result is authoritative.

## HUMAN_CHECKS (live run, from T207b; rule 3 is provisional)
1. Upload the real jpdb export (`PUT /blobs/{hash}`), `POST /import/jpdb/history`, poll `GET /jobs/<id>` until `succeeded` (needs worker with `S3_*` + server running).
2. Check (vocabulary_carded + vocabulary_status_only) / (vocabulary_total − vocabulary_en_jp_ignored) ≥ 0.95; read `unmapped`, `kanji_unmapped`; `reviews_id_collisions` expected 0.
3. Spot-check 20 cards' due dates against jpdb.
4. Re-run the import: 0 new ops; delete one imported note, re-run: it stays deleted (provisional ADR: no resurrection).
