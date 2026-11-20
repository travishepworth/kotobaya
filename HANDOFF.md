# HANDOFF LF-A2b

STATUS: DONE
BRANCH: t/LF-A2b
CHECK: `make check MODULE=android` → red, but only the 3 known baseline failures (RouteTableTest, 2x LocalParityTest). All new tests are green.

## Changed (≤ 6 lines)
- `ResponseCache`: `settle` checks the generation and writes the store under `lock`. `clear()` takes the same lock. Each operation counts its stale marks, so a mark made during a fetch keeps `stale=1`. `cache.updated` is sent outside the lock.
- New `cache/StaleMarks`: K-4 marks written straight to `response_cache`, with no fetch or warm pass.
- `ShellEvents`: `fallbackHooks` (default `dbStaleMarks()` via Koin) is used while `cacheHooks` is null. There is also a static `applyCacheMarks(outcome, hooks)`.
- `ProxySync`: uses `SyncTrigger.MANUAL` and rethrows `CancellationException`.
- `ShellModule.kt` (the orchestrator approved this edit outside my paths): when `events` is null, `ShellEventHub.onSyncOutcome` and `onLocalWrite` now write the stale marks to the DB through `dbStaleMarks()`.

## Acceptance → tests
- 1 → `CacheWiringTest.fromServicesWithAllFourPartsIsNonNullAndHooksTheEvents`
- 2 → `ResponseCacheSettleTest.clearDuringSettleLeavesNoRowOfTheOldPairing`, `fetchStartedBeforeClearStoresNothing`
- 3 → `ResponseCacheSettleTest.staleMarkDuringTheFetchSurvives{ANewBody,TheSameBytes,A304}`
- 4 → `CacheWiringTest.withoutHooks*`, `theDefaultFallbackFindsTheAppDatabaseThroughKoin`, `theHubWithNoShellEventsStillMarksRowsStaleThroughKoin`
- 5 → `CoordinatorProxySyncTest.requestPullRunsManualCycles`, `cancellationStopsTheRetry`
- I ran these against the pre-fix code: the six tests for 2, 3 and 5 failed. The tests for 4 did not compile, and the hub test failed without the `ShellModule` change.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The fallback opens a `StaleMarks` over Koin's `AppDatabase` on each call. If Koin has not started, it does nothing. A DB error is swallowed, as the other hooks do.
