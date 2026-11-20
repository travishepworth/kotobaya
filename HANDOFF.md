# HANDOFF LF-A3

STATUS: DONE
BRANCH: t/LF-A3
CHECK: `make check MODULE=android` → red, no new failures. 663 tests, 4 failed, all known: RouteTableTest.everyLocalOperationIsRegisteredOrPending, ParityExpectTest (bulk-cards-all-actions-local, queue-keep-going-target), LocalParityTest x2 (goldens still missing; the stats-* scenarios differ only by "no golden").

## Changed
- `android/.../shell/api/stats/`: Routes.kt, StatsRoutes.kt (summary/heatmap/retention, ports server StatsModule), StudyDays.kt (study days by the user's zone, row offset, rollover; range validation)
- `android/src/main/sqldelight/.../Stats.sq`: read queries without window functions (minSdk 26)
- LocalApi.kt: `+ statsRoutes` in LocalRoutes

## Acceptance → tests
- Parity, day boundary, undone → StatsParityTest (both scenarios: expect + invariants, nothing forwarded); StatsStudyDayTest (Tokyo rollover, row offset, streak); StatsLocalTest.aLocalAnswer… (undo included)
- Unpushed answer counted → StatsLocalTest.aLocalAnswerNotYetPushedCountsInTodaysSummaryAndHeatmapCell
- due = queue → StatsLocalTest.summaryDueEqualsTheQueueAtTheSameInstant, theSummaryNeverMaterializesDeckEntries

## New dependencies
- none

## For the reviewer
- The summary uses the queue's loader wiring with a materializer that never creates anything (ShellEntryMaterializer { false }), batch 20.
- UiPathNeverTouchesNetworkTest.STILL_FORWARDS still lists the three stats ops (not my path). Remove them there.
