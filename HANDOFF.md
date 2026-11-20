# HANDOFF LF-A2

STATUS: DONE
BRANCH: t/LF-A2
CHECK: `make check MODULE=android` → red. 635 tests ran and 4 failed, the same 4 that failed before this change:
- `RouteTableTest.everyLocalOperationIsRegisteredOrPending` (LF-A3, LF-A4, LF-A6, LF-A8)
- `ParityExpectTest.everyScenarioMeetsItsOwnExpectations`, plus the 2 `LocalParityTest` tests (LF-A4, LF-A8, LOOP-31-AND)
- The orchestrator approved one edit outside my paths: `WordAudioPrefetchTest` now expects schema version `3L`, because `2.sqm` raised it.

## Changed (≤ 6 lines)
- `ApiOperations`: classes regenerated from `ops.json`; new `OpClass.CACHED`.
- `ServerUpstream` / `ProxySync`: P-2 pre-push and P-3 hold deleted. A seq ahead of the cursor returns `kotobaya-shell-stale: 1` at once and schedules a background `requestPull`.
- New `shell/cache/`: K-1 key, `ResponseCache` (hit, miss, revalidate, warm, invalidate, LRU 16 MiB) and `SqlResponseCacheStore`. Plus `ResponseCache.sq` and the migration `2.sqm`.
- `LocalApi`: `cached` dispatch without gates. A `2xx` from `unpairShell` / `pairShell` clears the cache; a parse-override write marks its dependent entries stale.
- `ShellEvents`: `cache.updated` (merged per key) and the hooks for invalidation and the warm pass.

## Acceptance → tests
- Invariant → `UiPathNeverTouchesNetworkTest`
- Cache hit, update and identical body → `CachedDispatchTest.cachedHitAnswersWithoutTheUpstream`, `revalidationUpdatesTheRowAndEmitsExactlyOneCacheUpdated`, `identicalBodyEmitsNoneAndOnlyConfirmsTheRow`
- Remote write returns stale at once → `ServerUpstreamTest.remoteWriteReturnsWithoutWaitingForTheCursorAndCarriesShellStale`
- Offline → `CachedDispatchTest.offlineCachedHitStillAnswersMissIsUncachedAndRemoteIsShellOfflineWithNoNetworkAttempt`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- I could not edit `ShellModule` (outside my paths). So `LocalApi` builds the cache from `ShellServices` itself, and it is null in kernel tests.
- Local operations with no handler still forward. `STILL_FORWARDS` in the invariant test lists them with the ticket that fixes each.
- A replica change marks every replica-dependent entry stale, because the hooks don't say which tables changed. `LOCAL_API_VERSION` is still 2 and needs to go to 3 when the round lands.
