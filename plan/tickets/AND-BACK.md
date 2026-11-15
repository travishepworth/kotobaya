# AND-BACK — Android: smart back navigation across the whole app (review back = undo)

- Phase: P9 · Model: sonnet · Risk: med
- Deps: none
- Branch: t/AND-BACK
- Owned paths (only edit these): android/src/**/nav/ (or wherever the nav shell lives), android/src/**/review/ (only BackHandler wiring + VM undo entry point), per-screen BackHandler wiring in android/src/**/*/ui/, android i18n, android/src/test/
- Module(s) for CI: android

## User request (2026-11-15)
"Android should be looked over to properly make use of back smartly across the whole app (i.e. back button undoes the review, etc)."

## Decision (ADR-030, provisional)
- Review screen: if an answer was given in this session, system back = Undo last answer (same path as the Undo action; shows the undone card on its front, unrevealed is fine/consistent with existing undo), with a snackbar "Answer undone · back again to leave". Repeated back keeps undoing while answers remain in this session's undo history? NO, only one undo per back-press burst: a second back within 3 s, or back with nothing to undo, leaves the review screen. Revealed back side with no answer yet: back = hide answer (return to front)? No: leave as "back = leave" to avoid surprises; document.
- DeckDone / review summary: back returns to the screen that started the session (Decks or Home).
- Sheets/dialogs/pickers (KbSheet, deck picker, menus): back closes them first.
- Search: back clears an open detail → results → clears query → leaves tab. Dictionary/word detail opened from review: back returns to the review in the same state.
- Bottom-nav tabs: back from a non-Home tab goes to Home; back on Home exits (standard Android).
- Enable predictive back (`android:enableOnBackInvokedCallback="true"`; logs showed the warning) with correct callbacks.
## Do
Audit every screen; implement the above; list the per-screen behaviour in HANDOFF.
## Acceptance
- [ ] Test: review back after an answer undoes exactly once (one review.undone op) and the second back leaves.
- [ ] Test: back closes an open sheet before navigating.
- [ ] Tests (Robolectric or VM-level) for search and tab back stacks.
- [ ] make check MODULE=android green.
