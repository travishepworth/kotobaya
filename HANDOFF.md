# HANDOFF F-2

STATUS: DONE
BRANCH: t/F-2-core-c8
CHECK: `make check MODULE=core` → green (jvm + js); `make check MODULE=server` → green

## Changed (≤ 6 lines)
- `DefaultReplayEngine.decide`: step 1b. A refused cache gives FULL_REPLAY, checked before any lookup.
- `IncrementalReplay`: a refused fold never throws. The row stays in the set, `cache` becomes the refused cache, and the new `refusal` property holds the exception for the caller to log. Undoing the bad row or changing params heals it.
- `DefaultQueueBuilder` rule 4:
  - Each card is charged to its own home new-source (`QueueItem.deckId` = home); a kanji card is never skipped because its home deck is at its limit.
  - The vocab+kanji group fits the budget whole or is skipped whole. BEFORE puts kanji first.

## Acceptance → tests
- Step 1b → `decideStep1bRefusedCacheAlwaysFullReplays`
- Refusal handling → `incrementalReplayHoldsRefusedCacheAndNeverThrows`, `incrementalReplayRefusesMalformedManualRowsLikeReplay`
- Rule 4:
  - Groups and the session cap → `addKanjiGroupThatDoesNotFitTheBudgetIsNotDrawnInPart`, `addKanjiGroupMustFitTheSessionCapToo`
  - Charging and attribution → `addKanjiCardsAreNotChargedToTheDrawingDeck`, `addKanjiCardsAreChargedToTheirOwnHomeDeckAndNeverSkippedForIt`, `todaysKanjiCountAgainstTheirHomeDeckNotTheDrawingDeck`, `rebuildAfterAnsweringKeepsTheSamePerDeckCount`
- Breaking change: `QueueBuilderTest.kt:345` became `addKanjiGroupThatDoesNotFitTheBudgetIsNotDrawnInPart`. The old test expected a partial group, with the kanji charged to the drawing deck. In the BEFORE test, the kanji `deckId` is now null (their deckless home).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Refused cards are not skipped in the builder: `QueueCandidate` has no `lastReviewId`, and the contract puts this on the loader (F-3).
- A group that does not fit uses up its source's turn, and the source continues next round.
