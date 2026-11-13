# BUG-9 HANDOFF — DONE

**Changes (server/stats, Dockerfile only)**
- `scheduledReviews` and `knownSeries` now load history in chunks of 500 cards and replay per chunk, so at most about 4k reviews are in memory at a time. `dayRows` streams through a cursor (fetchSize 2000).
- Retention and calibration share one replay (`scheduledShared`). Concurrent callers join the same in-flight computation, which is dropped on completion, so nothing is served stale. Calibration filters by `presetId`, added to `ScheduledReview`.
- A `Semaphore(1)` serialises the heavy computations: the shared replay, heatmap and known-words.
- `server/Dockerfile`: `ENV JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70`, same as the chart.
- No contract changes, no new dependencies.

**Tests**
- Golden equality: the existing `StatsEquivalenceTest` (new code against `LegacyStatsQueries`, plus hand fixture) passes.
- Added `StatsPerfTest.concurrentStatsPanelsStayWithinABoundedHeap` (5 panels at once).
- `make check MODULE=server` is green (about 5 min).

**Numbers (89,775 reviews / 9,975 cards)**
- Every /stats/* endpoint takes 7 to 607 ms. The slowest is known-words?interval=day.
- Concurrent panels all finish within 979 ms. Peak heap was 335 MB in the 2 GB test JVM.

**Caveat:** I did not run a constrained `-Xmx256m` test. `server/build.gradle.kts` (maxHeapSize 2g) is not in my owned paths, so the OOM-free claim rests on the bounded design and the peak-heap number above, not on a 256 MB run.
