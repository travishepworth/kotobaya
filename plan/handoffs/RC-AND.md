# HANDOFF RC-AND

STATUS: DONE
BRANCH: t/RC-AND
CHECK: `make check MODULE=android` → red, only because of `BackNavigationTest.searchBackClosesTheDetail` (Compose AppNotIdle). It fails the same way on unmodified main (400 tests, 1 failed) and passes when run alone. All review/vm/nav tests pass together.

## Changed (≤ 6 lines)
- ReviewSession: end-of-queue settle (show + rebuild) wrapped → `Failed` (Retry) on error; `Loading` while that build runs; periodic "refresh"/"ahead" build failures keep the shown card answerable.
- ReviewSession: `markStale` sets `staleQueue`; the next answer skips the in-memory queue and shows from a fresh build (failed build keeps it stale). `start`/`continueWithAll` show `Failed` on a build error.
- ReviewViewModel: `Summary` counts as idle (resume, dueChanged, day-roll timer reload it); `onFailure` treats session-set `Failed` like `Loading`.
- LocalQueueLoader: `LoadedQueue.materializeFailure` (thrown materialize; Today counts unchanged); `asQueueSource` throws `MaterializeFailed` when the session would get no items → Failed + Retry, not Summary.

## Acceptance → tests
- rebuild throws after last answer → Failed + retry → `ReviewHardeningTest.rebuildThrowingAfterTheLastAnswerShowsFailedAndRetryRecovers` (+ `aStaleQueueWhoseBuildFailsShowsFailed`, `aPeriodicRefreshFailingWithACardShownKeepsTheCardAnswerable`)
- Summary + dueChanged/resume/day roll → `ReviewSummaryReloadTest.*` (3 tests)
- stale after sync → `ReviewHardeningTest.afterSyncTheAlreadyAnsweredCardIsNotShownFromTheStaleQueue`, `withoutASyncTheNextCardComesFromMemory`
- materialize failure → `DeckEntriesTest.aFailedMaterializationIsSurfacedNotASummary`
- All new tests verified failing on the unfixed code.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Materialize returning an empty set is NOT a failure (entries may already have notes); only a thrown write is.
- `Loading` now replaces the answered card during end-of-queue builds (WebView torn down once per queue end).
- Pre-existing: an idle state reloaded to an equal state is not re-emitted, so the day-roll timer is not re-armed (second rollover on an open screen relies on resume/sync).
