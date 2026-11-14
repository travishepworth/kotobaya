# HANDOFF AND-PERF

STATUS: DONE
BRANCH: t/AND-PERF-todayreviews
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `LocalQueue.sq` todayReviews: prev row via `review_log INDEXED BY review_log_replay_idx`, `card_id IN (self if live ∪ cards merged into it)`, no row values; one subquery, only for learning rows.
- `queue/LoadGate.kt` (new) + `LocalQueueLoader`: per-key single-flight; Koin shares one gate across all loaders.
- `DueNotifications`: one load per trigger (was two). `WidgetUpdater`: conflated channel, one refresh at a time.
- `DecksViewModel`: Mutex so a cancelled (still-blocking) reload never overlaps the next.

## Acceptance → tests
- 80k rows + merges, < 500 ms, plan → `TodayReviewsQueryTest.eightyThousandRowsWithMergesAreCorrectFastAndIndexDriven` (old query: 4.5 s, fails)
- Concurrent triggers → `LoadGateTest.*`, `WidgetUpdaterConflationTest`
- Old-vs-new → `TodayReviewsQueryTest.oldAndNewQueriesReturnTheSameRows`, `handPickedCasesMatchTheOldQuery`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Robolectric (API 35, NATIVE) SQLite is 3.44.3 = device's; old plan reproduced there. Real-DB copy: identical rows (163 today, 5171 over 2 weeks), 9.4 s → 2 ms (desktop sqlite3).
- Item 3: no code change needed; home/review gate only on the local startup replay (`SyncBootstrap.ready`), never on pairing/sync.
- `INDEXED BY` errors (not slows) if the index ever disappears; core untouched.
