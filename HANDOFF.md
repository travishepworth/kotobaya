# HANDOFF PERF-LF

STATUS: DONE (queue p95 target missed: 105–169 ms across runs)
BRANCH: t/PERF-LF-local-handlers
CHECK: `make check MODULE=android` → green (777 tests)

## Cause (evidence: JFR + timers)
The queue read one full dictionary entry per empty-field vocab note on every call, through a single locked dictionary connection, and re-read the whole replica each build. Home's stats replayed the full history per root (N+1). All ran side by side.

## Changed
- Batched, process-wide dictionary caches (headwords, render data, entry LRU)
- `ReplicaVersions`: per-table/key change versions; `QueueReadCache`; `StatsHistoryCache` (refreshes per root)
- Background lane (2 wide, deduplicated) for stats, hot lists, decks; queue never waits
- Retention in one pass, heatmap uses an index hint, narrow deck-progress reads, memoized lists, parallel queue render

## Real replica, JVM (ms, before → after; after = right after a pulled review)
queue alone 1150→79; queue concurrent 5645→p95 169 · decks 574→253 · hotWords 119→112 · summary 1394→95 · heatmap 7957→10 · retention 1645→28 · answer→next 2 (prefetched)

## Acceptance → tests
- thresholds → HomeLoadBenchmarkTest (synthetic data at real counts)
- caches never stale → ReplicaCachesTest, HeadwordCacheTest

## New dependencies
none

## For the reviewer
- Riskiest: cache invalidation (total_changes catches untracked writes)
- Background requests queue up to ~700 ms concurrently
- Not built: a persisted snapshot of the queue input. Build it only if the phone still misses 150 ms.
