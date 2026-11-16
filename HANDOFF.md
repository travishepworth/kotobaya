# HANDOFF SHELL-L2b — DONE
`make check MODULE=android` green. New test: `QueueKeepGoingBatchTest` (3 tests: keep_going limit=20 serves 3/3 deck entries; limit=1 creates exactly 1 note; counts equal with/without keep_going).

- /tmp leak fixed at source: `ParityTemp` (one per-JVM root, removed by shutdown hook) used for `parity-dictstore*`, `parity-dict.db`, and OfflineSearchTest's `parity*` dir. `ls /tmp | grep parity` = 0 after a full run.
- `LocalQueueLoader.load(keepGoingNewBatch)` added, in the pending window and the builder limits; QueueRoute passes `limit`. Native callers keep default 10.
- kanji_examples: one `IN` read per table (chunk 500) via new `SqlLearningTables.whereIn` (additive; outside owned paths, needed since core's `LearningTables` has no IN).
- `headwordOf` per-request cache; deck rows cached in QueueViews.
- AnswerRoute: out_of_range says "at most $max" when n > max; undo no longer fires DueTrigger for drill/cram reviews.
- QueueViews now uses `AnswerViews.card` (shared card view; duplicate removed).
- Theme reset to system default at page start: `SystemBars.reset`, wired in `MainActivity` onPageReset (outside owned paths, one line). Unverified visually (no device).

New dependencies: none.
