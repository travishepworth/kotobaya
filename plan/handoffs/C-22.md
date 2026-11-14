# HANDOFF C-22

STATUS: DONE
BRANCH: t/C-22-queue-learn-ahead
CHECK: `make check MODULE=core` → green; `make check MODULE=contracts` → green (no client drift)

## Changed (≤ 6 lines)
- Contract `QueueBuilder.kt` rules 2/5: learning splits into due (`dueAt <= now`, null counts as due) → first, and learn-ahead (`now < dueAt <= now+learnAheadMs`) → after the review/new interleave. Includes a worked example.
- `DefaultQueueBuilder.kt`: implements the split. `nextLearningDueAt` is unchanged.
- ADR-023 (PROVISIONAL) in docs/DECISIONS.md.

## Acceptance → tests
- relearn +15 min vs due review → `relearningDueInFifteenMinutesWaitsBehindDueReview`
- relearn due now beats reviews → `relearningDueNowStillBeatsDueReviews`
- only a future learning card → `onlyFutureLearningCardLeftIsServedAsLearnAhead`
- Also new: `learnAheadWaitsBehindNewCardsWithinLimitsToo`, `learnAheadCardsAreOrderedByDueThenIdAfterTheInterleavedPart`, `nullDueLearningCardCountsAsDueNow`.
- Changed expectations: none. Property test: added minute-scale dues and a phase-order invariant (due L < R/N < learn-ahead L).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- SPEC "Queue building" says nothing about learn-ahead, so there is no conflict. Server (ReviewModule, StatsModule) and Android use core `DefaultQueueBuilder`, and server QueueLoader only loads inputs. No re-implementation needs a follow-up.
- ADR number 023 may clash with a parallel ticket's ADR.
