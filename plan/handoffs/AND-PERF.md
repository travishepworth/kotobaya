# HANDOFF AND-PERF

STATUS: DONE
BRANCH: t/AND-PERF-todayreviews
CHECK: `make check MODULE=android` → green (293 tests, 0 failures; rebased on origin/main 79130c78)

## Changed (≤ 6 lines)
- `LocalQueue.sq` todayReviews: the previous row is found through `review_log INDEXED BY review_log_replay_idx` with `card_id IN (the card itself if not merged away ∪ cards merged into it)`. No row values. One subquery, run only for learning rows.
- `queue/LoadGate.kt` + `LocalQueueLoader`: concurrent loads with the same key share one build. Attempt 2: nothing is cached; a key's slot and its result are dropped when the last caller leaves. Doc now says it only coalesces same-key calls.
- `DueNotifications`: one load per trigger. `WidgetUpdater`: conflated, one refresh at a time. `DecksViewModel`: reloads cannot overlap (Mutex).

## Acceptance → tests
- 80k rows + merges, < 500 ms, plan check → `TodayReviewsQueryTest.eightyThousandRowsWithMergesAreCorrectFastAndIndexDriven` (fails on the old query: 4.5 s)
- Concurrent triggers → `LoadGateTest.*`, `WidgetUpdaterConflationTest`
- No retained results (review finding 1) → `LoadGateTest.theGateRetainsNoResultOnceItsCallersLeft`, plus a `liveSlots()==0` check in the concurrency test
- Index pinned by `INDEXED BY` (finding 2) → `LoadGateTest.theReplayIndexTodayReviewsPinsExistsWithItsColumns`
- Old-vs-new → `TodayReviewsQueryTest.oldAndNewQueriesReturnTheSameRows`, `handPickedCasesMatchTheOldQuery`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Finding 2: the index is defined in `contracts/` (not my path), so the warning comment sits at the query in `LocalQueue.sq`. Adding a comment at the index definition needs the architect.
- Robolectric (API 35, NATIVE) runs SQLite 3.44.3, the device's version. On the real-DB copy old and new return identical rows, and today's query went from 9.4 s to 2 ms.
- Item 3 needed no code change: home and review wait only for the local startup replay, never for pairing or sync.
