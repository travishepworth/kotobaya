# HANDOFF RC-WEB

STATUS: DONE
BRANCH: t/RC-WEB
CHECK: `make check MODULE=web` → green (122 tests)

## Changed
- `web/src/lib/review/model.ts` `reviewPhase`: done only when counts are 0 and there is no next_learning_due_at. Counts > 0 with nothing served → `pending`, which refetches with backoff (2 s doubling to 30 s).
- `web/src/lib/state/review.svelte.ts`: 30 s timeouts on queue/answer/undo. Retry during a slow load drops the older load. Answer/undo failures show a notice with a retry that resends the same answer id, and the failed card stays in front. dispose() aborts fetches.
- `web/src/routes/review/+page.svelte`: switches on `phase`. Separate waiting screen with countdown. After 5 s, a "taking longer" message with retry.
- Home (`web/src/routes/+page.svelte`): hero counts come from `/review/queue` (`web/src/lib/state/today.svelte.ts`). Stats start after the queue answers, home loads 3 panels instead of 6, each fails inline with its own retry (no toasts, via `quietApiConfig` in `web/src/lib/api/client.ts`). Leaving the page aborts them.
- Slowdown cause (measured): leaving /review during a fetch re-armed the wake timer on the dead page. Old code: 27 extra fetches in 30 min, one more loop per visit.
- Second slowdown cause: every Home visit fired 6 stats calls (up to 76 s each) that were never cancelled, which also held HTTP/1.1 connections the review fetch needed.

## Acceptance → tests
- waiting / 504→retry / done → `tests/review/sessionEnd.svelte.test.ts`; leaks → `leaks.svelte.test.ts`

## New dependencies
- none

## For the reviewer
- `more_available` + counts 0 → "done for now" with Keep going, per the contract; no polling there.
- Live trace was waiting state, not done: I couldn't reproduce a done path with next_learning_due_at set. Old waiting screen looked like the done screen.
