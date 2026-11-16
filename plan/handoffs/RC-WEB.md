# HANDOFF RC-WEB (attempt 3)

STATUS: DONE
BRANCH: t/RC-WEB
CHECK: `make check MODULE=web` → green (134 tests)

## Changed
- Per-session cap: `more_available` with learning 0 and review 0 is now doneForNow with Keep going and no refetch (`sessionCapReached` in `web/src/lib/review/model.ts`, also checked in scheduleWake).
- Pending polling stops after 5 tries (backoff 2 s up to 30 s). The screen then shows "Refresh"; `reload()` and `start()` restart it. It still never shows done while counts > 0.
- Prefetch: its result is dropped if a full queue fetch was applied after it started.
- 422 on `/reviewed_at` (e.g. `in_future`): card kept, grading locked, clock notice. Retry resends the same id without `reviewed_at`, so the server uses its own time. Other 404/409/410/422 still drop the card.
- Earlier attempts (still in): no review flow ends on the session-over screen while cards remain; no zombie wake loops after leaving /review; stats don't pile up on Home; the flicker fixes.

## Acceptance → tests
- `web/tests/review/sessionEnd.svelte.test.ts`: session cap, polling cap + Refresh, stale prefetch, in_future 422
- `web/tests/review/leaks.svelte.test.ts`

## New dependencies
- none

## For the reviewer
- A retry after a clock error records the server's time, not the tap time (off by at most the retry delay).
- Changed an older test (`web/tests/review/rapidInput.svelte.test.ts`): it got new cards only through the prefetch, which is now dropped when a newer refresh lands.
