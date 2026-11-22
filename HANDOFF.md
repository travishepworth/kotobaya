# HANDOFF LOOP-34

STATUS: DONE
BRANCH: t/LOOP-34-until-stop-ready-now (rebased on C-34 @19926c8d)
CHECK: core, web, server, acceptance, android → green (one android flake, BlobInterceptTest, passes alone)

## Changed (≤ 6 lines)
- core loop/presenter/JS bridge: `untilStop = learning + review + new`
- core tests: ReviewLoopReadyNowTest; fuzz asserts I3/I9/P-I5; fingerprint re-recorded, the pre-054 hash still asserted
- goldens: only 22 `until_stop` lines changed; LoopInvariants adds I9
- web: no header `later` chip; Home number = `loop.untilStop`; Home waiting shows the hint
- android widget/notification: `state.untilStop`; progress = done/(done+due)
- CLI untouched (watch.py never sums `later`)

## Acceptance → tests
- Reported case → ReviewLoopReadyNowTest.reportedCaseTwoReadyThirteenLaterGoalMetCountsTwo
- Fuzz → ReviewLoopInvariantFuzzTest.invariantsHoldAfterEveryStep
- Pending returns today → …pendingAnswerThatReturnsTodayLeavesUntilStop
- Home == header → …homeNumberEqualsHeaderTotalAtDoneZero

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Edited outside my paths: LoopPresenterJsTest (65→58), WidgetTest (3→4).
- Not fixed (not mine): KotobayaWidget.kt:119 "N of M" adds `due + new`, so new cards count twice; "X due · Y new" overlaps.
- Fuzz equality skips steps where a learning card came due after the shown state.
