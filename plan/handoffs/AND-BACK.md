# HANDOFF AND-BACK

STATUS: DONE
BRANCH: t/AND-BACK
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Review: `ReviewBackPolicy` + `ReviewIntents.backUndo()` (VM) + one BackHandler in ReviewScreen. Back after an answer = `session.undo()` (one review.undone op) with toast "Answer undone · back again to leave"; a second back within 3 s, nothing to undo, a revealed back side with no answer, or the finished/DeckDone pages = leave (LocalReviewExit pops to the starter).
- Search: back = deck sheet, then detail (also on wide), then `clearQuery()`, then leave. Dictionary jump from a pushed page (review, Struggle) stacks Search so back returns there.
- Manifest `enableOnBackInvokedCallback="true"`.
- Already correct (unchanged): tabs popUpTo Home (back → Home, Home exits); Decks, Settings, Sync, Struggle BackHandlers; KbSheet/dialogs consume back.

## Acceptance → tests
- Back undoes once, second leaves → ReviewBackTest
- Sheet closes first; search/tab stacks → BackNavigationTest (6)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- AppNav `navigateTo`/`TAB_ROUTES` made internal for the test. Toast shown via the existing ActionToast.
