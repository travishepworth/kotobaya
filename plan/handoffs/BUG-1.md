# HANDOFF BUG-1

STATUS: DONE
BRANCH: t/BUG-1-stats-perf
CHECK: `make check MODULE=server` → green; `make check MODULE=web` → green (attempt 1; web not touched since)

## Changed (≤ 6 lines)
- StatsQueries.kt: dayRows uses LAG per replay root (card→root CTE, hash joins); history uses unnest join; knownSeries binary search; streak paged
- QueueLoader.loadToday: same LAG-per-root rewrite (`TODAY_SQL`); the quadratic coalesce LATERAL is gone. Rows are the same, NULLs included
- +page.svelte/messages.ts: each panel loads and fails on its own; deleted stray `calibrationP` (partial copy of +page.svelte)

## Acceptance → tests
- perf (89,775 reviews / 9,975 cards; ms cold/warm): summary 153/151 (was 19.7 s), review queue load+build 48/37 (was ~16.6 s), heatmap 201/167, heatmap 10y 209/196, retention 368/280, retention by day 313/289, forecast 14/7, known week 334/298, known day 579/592, calibration 279/285 → StatsPerfTest.* (summary test un-ignored, plus reviewQueueLoadsWithinOneSecond)
- equivalence: StatsEquivalenceTest.*; QueueTodayEquivalenceTest (hand values + legacy SQL oracle over 127 windows)
- EXPLAIN check (now covers TODAY_SQL, including a full-history window) → noStatsQueryPlansANestedLoopOverReviewLogTwice

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Both old and new loadToday give SQL NULL (read as false) for a learning row that follows a non-reset row. Kept as is.
- No new index needed.
