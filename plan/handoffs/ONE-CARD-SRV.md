# HANDOFF ONE-CARD-SRV (attempt 3)

STATUS: DONE
BRANCH: t/ONE-CARD-SRV (from t/C-28-ONE-CARD-CORE)
CHECK: `make check MODULE=server` → green (492); `make check MODULE=acceptance` → green

## Changed (attempt 3)
- QueueLoader passes retired candidates again (the contract). Core rule 0 drops them and their today-reviews, as on Android.
- New `LoadedQueue.servable` = candidates minus `CardKinds.isRetired(…, input.readingEnabled)`. `laterLearning` in ReviewApi and StatsModule now counts `servable`.
- Still in place: attempt 2's next_due_at/forecast SQL exclusion and the no-op re-derive skip. Attempt 1 is unchanged. I dropped attempt 2's README wording question.

## Acceptance → tests
- Retired learning card is not counted, and the queue ends done → DeckMaterializeTest `aRetiredLearningCardIsNeverCountedAndTheQueueEndsDone` (still passes).
- Retired card introduced today, new_per_day=1, bury on → `aRetiredCardIntroducedTodayUsesNoLimitAndBuriesNoSibling`:
  - the server serves the meaning sibling (new=1);
  - this equals `DefaultQueueBuilder.build(loader input)`, and that input holds the retired card and its today-review;
  - with attempt 2's loader filter put back, the test fails.

## New dependencies
- none

## For the reviewer
- Nothing open.
