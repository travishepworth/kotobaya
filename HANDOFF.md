# HANDOFF AND-KEEPGOING-FIX

STATUS: DONE
BRANCH: t/AND-KEEPGOING-FIX
CHECK: `make check MODULE=android` → green except the known flake BackNavigationTest.searchBackClosesTheDetail (passes when rerun alone)

## Changed (≤ 6 lines)
- LocalQueueLoader: passes real pendingEntries: live `e:<seq>` items of enabled decks that have no live note, capped per deck at the build's largest new budget. When a build returns `toMaterialize`, an injected EntryMaterializer creates the notes and the loader builds again (at most 3 rounds). Review, Today counts, widget and deck sessions all get these cards.
- NoteCreator.materialize: in one transaction, mints `note.created` (origin `deck`, entry_seq, vocab type) and `card.created` for the deck's `card_kinds` (empty fronts skipped), plus add_kanji notes. Wired via Koin.
- ReviewSession: an empty memory queue always rebuilds before ending. In keep going, a learn-ahead card waits until a build has seen every answer, so a failed card no longer blocks the next batch (the phone's "same 2 cards").
- A config change keeps the session; the keep-going end screen says nothing is left today.

## Acceptance → tests
- 300 due (cap 200) + 40 new → KeepGoingTest.keepGoingServesEveryDueReviewAndEveryNewCardWithoutStopping
- Defect 1 (failed before the fix) → KeepGoingTest.aKeepGoingBuildThatSawEveryAnswerButOneIsNotFinal
- Materialized ops and counts → DeckEntriesTest.drawnDeckEntriesBecomeNotesAndCardsThroughOps
- Keep going through 60 deck entries → DeckEntriesTest.keepGoingDrawsThroughMoreThanFortyDeckEntries
- Repeat loop (failed before the fix) → DeckEntriesTest.aFailedCardDoesNotCrowdOutTheNextBatch
- Config change → ReviewInfoTest.aConfigurationChangeKeepsTheSession

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Convergence rests on SYNC.md 4 (skip an entry with a live local note) and 8.1 (the server merges a second note for the same entry_seq). No contract change needed.
- Entries the dictionary lacks are not materialized and still use budget each build. Process death still drops keep going (C-26 forbids saved state).
- Today/widget loads now mint ops (by design: same cards everywhere).
