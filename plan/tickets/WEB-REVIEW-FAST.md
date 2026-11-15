# WEB-REVIEW-FAST — Web review screen: fast/double clicks revert to "Show answer"; handle rapid input

- Phase: P9 hotfix · Model: sonnet · Risk: med (review answers must not be lost or double-submitted)
- Deps: none (parallel with WEB-PRIO, which only touches decks/settings)
- Branch: t/WEB-REVIEW-FAST-input
- Owned paths (only edit these): web/src/routes/review/, web/src/lib/state/review* (and lib/review/), shared CardView only if needed (say so), web tests
- Module(s) for CI: web

## Symptom (user, web 0.1.12)
User clicks "Show answer", and with the cursor already where "Good" appears, clicks again quickly. Sometimes the card does not progress and the screen reverts to the "Show answer" state. In general, rapid interaction (fast clicks, double clicks, key mashing 1-4/space) is fragile.

## Do
1. Find the race: likely the second click hits the grade button while reveal/answer state is mid-transition, or the answer request is in flight and a stale state/response resets the view (e.g. queue refetch overwriting the optimistic advance, or a double submit where the second gets 409/422 and rolls back the UI).
2. Make the state machine explicit: front → back → (answered, advancing) → next front. Each card instance has a token; input for a stale token/state is ignored. A grade click on the back is accepted once per card (debounce by state, not by time). Clicking "Show answer" twice never skips the back.
3. Optimistic advance like Android AND-REVIEW-SNAPPY: show the next card immediately (prefetch next card render), send the answer in the background in tap order, and on failure show an error and restore that card. Never send the same card's answer twice (idempotent per card instance; reuse the existing review/answer API, and its idempotency key if the contract has one).
4. Keyboard: same rules for 1-4/space/enter shortcuts and key repeat (ignore auto-repeat).
5. Keep undo working with in-flight answers (undo waits for or cancels the pending submit; no orphaned grade).

## Acceptance
- [ ] Test: reveal then immediate grade click (no await between) advances exactly once and submits exactly one answer.
- [ ] Test: double click on grade submits one answer; double "Show answer" lands on the back, not the next card.
- [ ] Test: a failed submit restores the card with an error, and later answers aren't lost or reordered.
- [ ] Test: key auto-repeat doesn't grade twice.
- [ ] make check MODULE=web green.

## Out of scope
Android, server, decks page.
