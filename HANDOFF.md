# HANDOFF LOOP-CORE

STATUS: DONE
BRANCH: t/LOOP-CORE
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `core/.../queue/DefaultQueueBuilder.kt`: rule 4 budget = the day's remainder; batch truncates the draw to a prefix (`availableToday` from the same draw); session cap ignored; rule 5 interleave → proportional mix; internal `buildDetailed` (no probe).
- `core/.../queue/DefaultReviewLoop.kt` (new): two builds (N, K), learn-ahead forced 0, `LoopState` + `LegacyCounts`, phase rule.
- Tests changed (ADR-037 only): `QueueBuilderTest` burySiblingsOffKeepsSiblings, sessionCapLimitsNewCards→sessionCapIsIgnoredAndTheBatchBoundsOneBuild, addKanjiGroupMustFitTheSessionCapToo→addKanjiGroupMayOvershootTheBatchButMustFitTheDay, interleaveWorkedExamples→mixWorkedExamples; `QueueKeepGoingTest` keepGoingFalseIsTheDefault…, dueLearningStillLeads…; `QueueReviewsUnlimitedTest` neverStudiesAheadAndStillInterleaves→…StillMixes; `QueuePropertyTest` new-budget invariant. `QueuePendingGroupTest` unchanged.

## Acceptance → tests
- A1/B3→B4/A3/A4/B6/B7 → `ReviewLoopTest.unlimitedNoReadyLaterLearningStartsNew`, `capThenKeepGoingClearsBacklogFirst`, `newGoalReachedThenKeepGoingContinues`, `dueCardsPrecedeNewUnderKeepGoing`, `waitingOnlyWhenNothingAnywhere`, `settingChoosesDoneVsGoal`; also A2, B1, B2, B8, M1–M3, M5, M6
- E1–E6 → `goalThirtyNineOfForty…`, `deckNewCapBindsBeforeTheGoal`, `newPerDayZero`, `reviewsPerDayZero`, `dayRolloverRecomputesEverything`, `retiredCardsAreInNoCount`
- Legacy → `legacyCountsFrozen`; P1/M4 → 6 new `QueuePropertyTest` tests; jvm = js → `goldenFingerprintIsIdenticalOnEveryPlatform`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `LegacyCounts.new` comes from N's batch-unbounded draw (the contract allows this), not a third build.
- web/src/lib/core untouched: the queue is not in the JS export list.
