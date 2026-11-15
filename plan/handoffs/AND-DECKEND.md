# HANDOFF AND-DECKEND

STATUS: DONE
BRANCH: t/AND-DECKEND-finish
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Cause: the session follows the builder, whose rule 5 serves a lone learn-ahead card ("only L1 → L1"). In a deck session this re-served the just-answered card, one real op per tap. ReviewSession was not buggy.
- ReviewSession `allDue` (the all-decks queue) marks a deck session. Its learn-ahead runs only if no deck has cards due now (each deck build also builds all decks), and never re-serves the card answered last.
- New `ReviewState.DeckDone(stats, nextLearningDueAt, othersDue)`; `continueWithAll()` switches to the all-decks queue.
- VM/screen: Deck done page with the next learning time, "Continue with all due cards" and "Back to decks". Reloads when the next learning card falls due. ReviewBackend passes `allDue`.
- Outside review/: decks/DecksFeature.kt (one wrapper providing `LocalDeckExit`), strings_review.xml (5 strings).

## Acceptance → tests
- Last card not re-shown; one op per answer → DeckEndTest.theDecksLastCardIsNotShownAgain…, theDecksOnlyLearningCard…, learnAheadInADeckOnly…, aLearningCardThatFallsDue…
- DeckDone; continue → global → DeckEndTest.anEmptyDeckIsDeckDoneAndContinue…, aDeckWithNothingDueStartsAsDeckDone
- Durability/async tests green

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The all-decks session keeps the learn-ahead rule (pinned by a test). Changing that needs a contract change.
- A deck session's rebuild now builds two queues (~2× rebuild time, after the advance).
