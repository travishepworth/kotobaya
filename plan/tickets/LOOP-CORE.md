# LOOP-CORE — Core: DefaultReviewLoop + queue rule 4/5 changes

- Phase: P10 · Model: opus · Risk: high (queue order and counts on every replica)
- Deps: LOOP-C
- Branch: t/LOOP-CORE-review-loop
- Owned paths (only edit these): core/src/commonMain/kotlin/kotobaya/queue/**, core/src/commonTest/kotlin/kotobaya/queue/**, web/src/lib/core/** (only if the JS export list needs the new symbols)
- Module(s) for CI: core
- Gradle-heavy: yes

## Read
- Design: docs/REVIEW-LOOP.md §1, §2, §10
- Contracts: core/src/commonMain/kotlin/kotobaya/contracts/queue/{QueueBuilder.kt,ReviewLoop.kt}, contracts/core/README.md (C-26, C-27)
- Decisions: ADR-037 (and 033, 034, 035 for what stays)

## Do
Tests first (kotlin.test, jvm + js): `ReviewLoopTest` with the golden table A1-A4, B1-B8, E1-E6 of design §10 as data rows, and the P1 properties added to `QueuePropertyTest`. Then:
1. `DefaultQueueBuilder`: rule 5 = `learningNow + reviews + new` for every build (delete `interleave`); rule 4 normal budget = day remaining, drawing stops once `drawn >= max(1, keepGoingNewBatch)`, a group is atomic against the day remaining (a 3-card group with batch 1 is drawn; with 1 left in the day it is skipped); ignore `newPerSession` / `newShownThisSession`. Rule 6 unchanged.
2. `DefaultReviewLoop : ReviewLoop`: build K once and N once (an internal entry that skips the `moreAvailable` probe and sets it from K, so there are exactly two builds), then derive `LoopState` per design §2. `laterToday` uses the same eligibility as rule 1 for learning cards and rule 0 (retired); this replaces server `ReviewApi.laterLearning` and android `LoadedQueue.laterLearning`. `legacy` = frozen wire meaning (design §5).
3. Update existing tests that encode the interleave or the session cap (`QueueBuilderTest`, `QueueKeepGoingTest`, `QueuePendingGroupTest`, `QueueReviewsUnlimitedTest`): change expectations only where ADR-037 changes the rule, and list each changed test in HANDOFF.

## Acceptance (each bullet → at least one named test)
- [ ] `ReviewLoopTest.unlimitedNoReadyLaterLearningStartsNew` (A1: phase reviewing, 10 NEW, later 26, never waiting)
- [ ] `ReviewLoopTest.capThenKeepGoingClearsBacklogFirst` (B3 → B4: done_for_today 135/135; keep going: learning, backlog oldest first, no NEW before the backlog ends)
- [ ] `ReviewLoopTest.newGoalReachedThenKeepGoingContinues` (A3: 40/40 goal_reached; keep going serves NEW for 60 more answers)
- [ ] `ReviewLoopTest.dueCardsPrecedeNewUnderKeepGoing` (A4), `waitingOnlyWhenNothingAnywhere` (B6), `settingChoosesDoneVsGoal` (B7), E1-E6 one test each
- [ ] `QueuePropertyTest`: counts invariant under keepGoing; no LEARNING item with dueAt > now; phase ⇔ served queue; input-order independence
- [ ] `ReviewLoopTest.legacyCountsFrozen`: legacy.learning = ready + later, legacy.review = readyReviews, moreAvailable as before
- [ ] `make check MODULE=core` green on jvm and js

## Out of scope
Loaders and API rendering (LOOP-SRV, LOOP-AND); any contract edit (return CONTRACT_CHANGE_REQUEST).

## Notes from previous attempts
