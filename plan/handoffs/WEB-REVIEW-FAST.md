# HANDOFF WEB-REVIEW-FAST

STATUS: DONE
BRANCH: t/WEB-REVIEW-FAST-input
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- web/src/lib/state/review.svelte.ts: grade is now optimistic. The tap synchronously takes the card off the queue and shows the next front. A per-tap PendingAnswer is sent in tap order, once, with the existing answer `id` (the contract's idempotency key). Cause of the revert: the old code awaited the POST and a queue refetch while the graded card stayed on screen, so a stale response or second click hit a half-updated state.
- Failure: the card comes back on its back with the same answer id (a safe retry), and later unsent taps are withdrawn in order (as on Android). Queue refetches are epoch-guarded, so a stale fetch cannot bring back an answered card.
- Undo: withdraws an unsent tap locally, or waits for an in-flight send, then calls undoReview. `key()` ignores auto-repeat. `settling` shows the skeleton while the queue is empty but answers are still pending.
- web/src/routes/review/+page.svelte: wired to `key()`, `e.repeat` guard, `settling`.

- Attempt 2: pending answers, tail and retry ids now live in a module-level Outbox, so they survive leaving /review. Fetched queues drop cards still pending, a new page refreshes once the outbox is idle, and `beforeunload` warns while answers are pending. A refresh keeps the on-screen card first with its flip state, and only replaces what follows it. `reviewedAt` is sent from the tap time. A failed post-drain refresh shows LoadError with retry, not "all done".

## Acceptance → tests
- All four ticket bullets, plus tap order, undo, stale-fetch, navigation-away and keep-current-on-refresh → web/tests/review/rapidInput.svelte.test.ts

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Riskiest: cross-page restore in the Outbox (a failed send of an earlier visit's tap; its card is still on the server queue and keeps its retry id). Also the `fail()` withdraw/restore path and the undo-while-sending wait in review.svelte.ts.
- Decision: the failure message is the API client's existing toast; no extra inline error.
- CardView was not touched.
