# HANDOFF LF-A1b

STATUS: DONE
BRANCH: t/LF-A1b (fast-forwarded to t/LF-int d57bc485 before committing)
CHECK: `make check MODULE=android` → red, only the 3 known baseline failures (LocalParityTest x2, RouteTableTest); same 3 in the own flavor

## Changed (≤ 6 lines)
- LocalWrites.write: a StoreBusyException is retried once, then becomes ProblemException(shellRebuilding) (503, Retry-After: 2)
- LocalApi: a StoreBusyException anywhere in the cause chain from any handler maps to 503 shell_rebuilding, never 500
- SyncTriggers.localChange: counts the change before requesting the backstop; the end-of-cycle check and cancel run together under the lock; new LocalChangeBackstop seam (WorkManagerBackstop in prod)
- SyncWork.requestDebounced: no-op if a not-started LOCAL request exists, otherwise APPEND_OR_REPLACE behind a RUNNING one

## Acceptance → tests
- Answer while lock held >5 s → retry, then 503 + Retry-After → AnswerWhileStoreBusyTest; StoreBusyTest (3 tests)
- Item 2 fails before fix → LocalChangeBackstopRaceTest.aChangeLandingWhileTheDebouncedCycleFinishesKeepsItsBackstop
- Item 3 fails before fix → SyncWorkTest.aChangeWhileTheBackstopRunsAppendsAnotherBackstopBehindIt

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Item 4: SyncTrigger lives in SyncCoordinator.kt, which this ticket doesn't own, so REVIEW_SESSION stays (noted in the KDoc).
- A busy answer can now wait up to 2x5 s before its 503. requestDebounced blocks on WorkManager, so it runs on the IO sync scope.
- Removed AppOpenSyncTest, an out-of-scope test from the dead session.
