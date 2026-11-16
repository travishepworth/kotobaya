# HANDOFF LOOP-AND (+ PORTRAIT-AND)

STATUS: DONE
BRANCH: t/LOOP-AND (from t/LOOP-SRV)
CHECK: `make check MODULE=android` → green

## Changed
- LocalQueueLoader: `LoadedQueue` carries `LoopResult` (core `DefaultReviewLoop`); `newShownThisSession` and `laterLearning()` gone; pending window B = max(1,batch); `new_per_session` ignored. QueueRoute renders `loop` + legacy fields from `LoopState` like the server.
- Widget/notification count = `readyLearning + readyReviews`. `LOCAL_API_VERSION = 2`.
- PORTRAIT-AND: `DisplayOps.kt` (routes, prefs, `ActivityOrientation`), `display` capability, ApiOperations + UNPAIRED_ALLOWED, MainActivity applies at create.

## Acceptance → tests
- Parity goldens incl. 4 `queue-loop-*` → LocalParityTest (17 match, 3 pending need createNote); follows server order for cap-keep-going-backlog
- `QueueRouteTest.loopUnlimitedLaterLearningServesNew`, `WidgetDataTest.countIsReadyNow`, `DueNotificationsTest.countIsReadyNow`
- KeepGoingTest kept, updated (session cap gone: 20 new in normal, 20 more kept)
- `DisplayRouteTest.defaultFalse/patchPersistsAndApplies/emptyPatch422/unpairedWorksAndRebuilding503`

## New dependencies
none

## For the reviewer
- `QueueCounts` type kept (now a view of `LoopState.legacy`) because unowned legacy Compose (DeckRepository, DemoPorts, TodayScreen) uses it; one-line compile fixes in DeckRepository, ReviewFeature. SHELL-Z1 can delete it.
- Ticket said `unpaired401` for display; contract says both ops work unpaired, so test asserts 200. Touched unowned `ApiOperations.kt` (2 ops), `LocalApi.kt`, and shell/parity + RouteTableTest + HomeQueueAgreementTest expectations for the re-recorded contracts.
- Widget "later today" text not added (no snapshot field/UI); only the count source changed.
