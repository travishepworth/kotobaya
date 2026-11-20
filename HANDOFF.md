# HANDOFF LF-A1

STATUS: DONE
BRANCH: t/LF-A1 (based on origin/t/LF-int)
CHECK: `make check MODULE=android` → red only from pre-existing failures (RouteTableTest ×2, LocalParityTest ×2, ParityExpectTest ×1: missing goldens and routes owned by other tickets). Nothing new fails.

## Changed
- `materializeAllowed(paired, lastSyncAt, rebuildPending)`: removed the running and failures inputs (QueueRoute, QueueModule).
- `SyncTriggers.localChange()`: debounced in-process (2 s trailing, 10 s max). The cycle starts after a running one and never joins it. Backstop is a `SyncWork.LOCAL` request (KEEP, 12 s), cancelled after a clean debounced cycle.
- `SyncClient` pull sub-pages are 50 ops. `SqlDelightDeviceStore` adds a fair writer lock with a 5 s wait, then throws `StoreBusyException` with nothing written.

## Acceptance → tests
- running + 3 failures → `MaterializeWhileSyncingTest.aRunningCycleAfterThreeFailuresDoesNotStopMaterialization`
- before first pull → `…beforeTheFirstCompletePullNothingIsMaterializedAndTheEntriesAreCounted`
- 8.1 convergence → `…deviceAndServerMaterializeTheSameEntryConvergeToOneNoteKeepingEveryReview`
- 30 answers in 20 s → `LocalChangeDebounceTest.thirtyAnswers…` (+4 more)
- answer during a 500-op page → `PullYieldsToLocalWriteTest.aLocalAnswerIssuedDuringAFiveHundredOpPage…` (+3 more)

## New dependencies
- none

## For the reviewer
- I edited tests outside my owned paths: `QueueMaterializeIdempotentTest` (it tested the removed gate) and `MaterializeRaceTest` (the waiter detector now sees the lock; the control uses two stores).
- `StoreBusyException` reaches the SPA as a 500. It needs mapping to `503 shell_rebuilding` + `Retry-After: 2` in LocalApi (not mine).
- The debounced trigger uses `SyncTrigger.REVIEW_SESSION`. A `LOCAL_CHANGE` enum value needs SyncCoordinator.kt.
