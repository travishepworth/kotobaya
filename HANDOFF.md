# HANDOFF RC-WEB (attempt 2)

STATUS: DONE
BRANCH: t/RC-WEB
CHECK: `make check MODULE=web` → green (130 tests)

## Changed
- Leak: `dispose()` sets a permanent `disposed` flag that refresh, fetchQueue, scheduleWake, wake and start check. An answer that finishes after leaving now starts nothing.
- 404/409/410/422 on answer: card dropped with a toast; taps behind it still send. Only timeout, network, 401/429 and 5xx keep the card pinned.
- While an answer is unconfirmed, grade buttons and keys are locked; Retry resends.
- Flicker (a): the queue drained while answers were saving, so the stage flashed a skeleton between cards. Fix: a prefetch refills it below 3 cards. It merges and never drops a local card, so an undo is kept. The last graded card stays on screen (dimmed) while saving.
- Flicker (b): a failed wake fetch swapped the countdown for a full-page error. Now waiting/pending stays with an inline notice and retries itself.
- Flicker (c): home showed a skeleton only until the account is known (cards due, a deck, or the summary), so the empty-account view no longer flashes.
- `reviewPhase`: counts are checked before doneForNow. A retry during a slow load aborts the old fetch.

## Acceptance → tests
- `web/tests/review/sessionEnd.svelte.test.ts`, `web/tests/review/leaks.svelte.test.ts` (each item above has a test)

## New dependencies
- none

## For the reviewer
- Per-session-cap case (counts.new > 0, more_available) is now `pending`, refetching up to every 30 s.
