# HANDOFF AND-KEEPGOING-FIX

STATUS: DONE
BRANCH: t/AND-KEEPGOING-FIX
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- ReviewSession.settleLocked: removed the "build saw every answer but one is final" early finish. An empty in-memory queue now always rebuilds (keepGoing=true while keeping going) and ends only if that build shows nothing (defects 1, 4).
- ReviewViewModel.leave(changingConfigurations) and ReviewRoute: a config change keeps the session and its keep-going mode (defect 3).
- ReviewScreen: the end-of-keep-going summary body says "no more cards today" (defect 2). moreAvailable is false there, and that is now correct.

## Acceptance → tests
- 300 due (cap 200), 40 new (20/day, 10/session), refresh every 20 s; all served without a stop → KeepGoingTest.keepGoingServesEveryDueReviewAndEveryNewCardWithoutStopping
- Defect 1 regression (one new card per keep-going build; failed before the fix) → KeepGoingTest.aKeepGoingBuildThatSawEveryAnswerButOneIsNotFinal
- Config change keeps keep going; a real leave clears it → ReviewInfoTest.aConfigurationChangeKeepsTheSession

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The acceptance test passed before the fix: with batch 10 the defect needs a build holding only the shown card. The batch-of-1 test does reproduce it.
- Process death still drops keep going: C-26 "Scope" forbids saved state, though the ticket allowed it. No SavedStateHandle used.
- Remaining stall: 10+ unshowable new cards (e.g. a missing note type) at the head of new order fill every batch. Fixing it needs a core exclusion input.
