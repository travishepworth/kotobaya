# HANDOFF PERF-LF (attempt 3)

STATUS: DONE (real-replica queue p95 is 153–158 ms against the 150 target; synthetic is 127)
BRANCH: t/PERF-LF-local-handlers
CHECK: `make check MODULE=android` → green

## Changed (attempt 3)
- Every transaction now checks that the touch-log table and all of its triggers exist. If any are missing it re-runs the whole IF NOT EXISTS DDL; if they are still incomplete, the transaction reports every table as changed. Any exception, not just a RuntimeException, falls back the same way.

## Changed (attempt 2)
- Change tracking now uses TEMP triggers on the learning tables, not total_changes. Raw writes are attributed exactly; trigger, index and change-log rows are ignored. If the triggers can't be used, every table counts as changed.
- A note or word-status write refreshes only the affected cards and memberships. Pending deck entries are refreshed only for decks holding a touched entry.
- The snapshot is in card id order. Memos are an LRU (max 256); request gates are removed when idle. The background lane is now 1 wide.

## Real replica (JVM ms; origin/main → attempt 1 → now)
- queue, concurrent with sync + notes + Home, p95: 5645 → 544 → 153
- queue after a pulled note: — → 227 → 89; minting deck entries: 61–76
- decks 574→266 · hot 119→113 · summary 1394→107 · heatmap 7957→10 · retention 1645→35 (after a write)
- answerReview 2

## Acceptance → tests
- ReplicaCachesTest checks memo vs fresh after card, review, undo, merge, note, word-status, deck_item (tracked and raw), kanji-note and preset writes. It covers memberships, pending entries, kanji literals, listDecks and the hot lists, with the change-log triggers installed.
- HomeLoadBenchmarkTest adds the pulled-note and minting cases.
- aDamagedTouchLogIsRepairedOrReportsEverything drops one trigger, then the table, then everything, and makes prepare fail. After each it writes and checks the caches against a fresh read.

## New dependencies
none

## For the reviewer
- Background requests wait up to 1.3 s under a constant sync; they are instant when cached.
