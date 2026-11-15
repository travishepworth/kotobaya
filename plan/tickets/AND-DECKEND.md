# AND-DECKEND — Deck review on Android loops on the last card instead of finishing / moving to the global queue

- Phase: P9 hotfix · Model: opus · Risk: high (possible repeated grading of one card)
- Deps: AND-REVIEW-SNAPPY (merged cb54637e)
- Branch: t/AND-DECKEND-finish
- Owned paths (only edit these): android/src/**/review/ (ReviewSession, ReviewViewModel, ReviewScreen), android/src/**/decks/ui only for the deck-study entry/exit wiring (AND-ADD2DECK is editing decks/ concurrently, so keep that minimal and say what you touched), android/src/**/queue/ only if the deck-scoped queue input is wrong (say so), android/src/test/
- Module(s) for CI: android

## Symptom (user, phone 0.1.14 release)
Reviewing a specific deck (Decks → deck → study, ReviewScreen with deckId): when it gets to the deck's last item, the same card shows over and over after each grade instead of finishing. The user suspects each repeat grades it again. Web, when a deck's reviews are done, switches to the global queue.

## Suspects
- AND-REVIEW-SNAPPY in-memory queue: requeueLocked() for a learning/relearning card due within learn-ahead puts it straight back; with a deck-scoped queue of 1 it's shown immediately. Or settleLocked's `commitCount - commitsAtBuild <= 1` / rebuild rule keeps re-serving the same card. Or the deck-scoped rebuild ignores the card's new due (stale card cache / reads before the commit lands).
- Check whether each repeat really mints a new review op (KotobayaReview "answer ... committed" lines per tap, and review_log rows). Learn-ahead repeats of a learning card ARE legitimate reviews only if the card is actually due by the learn-ahead rule. Otherwise it's a bug that corrupts scheduling.

## Do
1. Reproduce in a JVM test: deck session with 1 new card (and 1 learning card) → grade Good/Again repeatedly; assert the shown sequence matches the queue builder rules (core QueueBuilder learn-ahead), with no immediate re-show of a card that isn't due, and exactly one review op per real answer.
2. Fix the loop.
3. Deck-session end behaviour (match web): when the deck has nothing left for now, show a "Deck done" state with "Continue with all due cards" (switches the session to the global queue) and "Back to decks". If learning cards in the deck come due later today, say when. Don't silently keep serving learn-ahead cards beyond the queue builder's rules.
4. i18n strings.

## Acceptance
- [ ] Regression test: the last card in a deck session isn't re-shown unless the queue builder says it's due; one review op per answer.
- [ ] Test: deck exhausted → DeckDone state; "continue" switches to the global queue.
- [ ] Existing ReviewDurabilityTest/ReviewAsyncCommitTest green. make check MODULE=android green.
