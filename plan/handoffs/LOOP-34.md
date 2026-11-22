# HANDOFF LOOP-34

STATUS: DONE
BRANCH: t/LOOP-34-until-stop-ready-now (rebased on C-34 @19926c8d), attempt 2
CHECK: core, web, server, acceptance, android → green (one android flake, BlobInterceptTest JDBC driver, run-order dependent, passes alone)

## Changed (≤ 6 lines)
- core loop/presenter/JS bridge: `untilStop = learning + review + new`; fuzz asserts I3/I9/P-I5; ReviewLoopReadyNowTest
- goldens: only 22 `until_stop` lines changed; LoopInvariants adds I9
- web: no header `later` chip; Home hero + ETA (`routes/+page.svelte`) use `loop.untilStop`
- android (paths added in the orchestrator's scope messages: DueNotifications.kt, widget/): headline = `untilStop`; "X due · Y new" = (learning+review, `untilStopParts.new`), sums to it; widget "of" = done + untilStop
- CLI untouched (waybar → CLI-34)

## Acceptance → tests
- Reported case → ReviewLoopReadyNowTest.reportedCaseTwoReadyThirteenLaterGoalMetCountsTwo
- Fuzz → ReviewLoopInvariantFuzzTest.invariantsHoldAfterEveryStep
- Pending returns today → …pendingAnswerThatReturnsTodayLeavesUntilStop
- Home == header → …homeNumberEqualsHeaderTotalAtDoneZero
- No double count → WidgetTest.noDoubleCountOfNewCards, DueNotificationsTest.newCardsAreAPartOfTheCountNotAddedTwice

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Accepted out-of-path edits: LoopPresenterJsTest, WidgetTest.
- /tmp quota filled during android runs; I ran them with JAVA_TOOL_OPTIONS java.io.tmpdir set to the worktree's build dir (no config change).
