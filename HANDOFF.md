# HANDOFF T206

STATUS: DONE
BRANCH: t/T206-review-api (rebased on main after W-2)
CHECK: `make check MODULE=server` green (ReviewApiTest, 9 tests)

## Changed
- `server/.../review/`: ReviewModule (7 handlers), QueueLoader (core QueueBuilder inputs), ReviewSettings (SCHEMA 6.1 tests, StudyDay with DST), ReviewApi, DrillApi, WordStatusApi, ReviewViews, ReviewOps (`ReviewOps.logReview/undo`, reusable by T210a).
- `Main.kt` (one line + import, as allowed): `ReviewModule(db, sync.opApply).handlers`.
- Tests: undo restores cache (new/learning/review/relearning), 5-button mapping, idempotent id, never-forget/blacklist, drill/cram, sibling bury + limits, deleted-deck fallback, settings, DST days (Berlin, New York, gap).

## Decisions / gaps
- Not done: `listCardReviews` (left to T210a, avoids duplicate handler key); drill sources `hot_words`/`confusables` return 501.
- Queue emits no ops (contract), so deck entries without a note (`pendingEntries`) are not passed: CONTRACT_CHANGE_REQUEST if web must introduce them.
- `utc_offset_min` stored NULL when absent (contract), not derived from zone. `sentence` always null (no sentence tables). `newShownThisSession` = 0. Kind order: built-in order, else alphabetical. Rollover in a DST overlap uses the earlier instant.
- Dict entry/kanji JSON copied from DictApi (private there); dedupe later.
- Wiring test not extended; flow tested in own module.
