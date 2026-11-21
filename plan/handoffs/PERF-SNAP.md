# HANDOFF PERF-SNAP (attempt 3)

STATUS: DONE
BRANCH: t/PERF-SNAP-instant-home
CHECK: `make check MODULE=web` → green (390 tests)

## Changed (≤ 6 lines)
- `review.svelte.ts`: added `confirmSnapshot()`. If the first fetch fails while a snapshot card is unconfirmed, `refreshFailed` is set and retries follow a backoff (`CONFIRM_RETRY_MS` 1 s, 2 s, 5 s, then every 15 s). One fetch runs at a time, and retries stop once the card is confirmed or the screen is left.
- The confirm runs at once on `online` (listener removed on dispose), from `onVisible`, and from `resume()` when the session was left with its fetch in flight.
- `routes/review/+page.svelte`: a quiet "Try again" (`reload()`) shows next to the disabled grade bar while the card is unconfirmed and the fetch failed.
- Attempt 1–2 behaviour unchanged (snapshot, grade lock, keep-going/acked filtering, day check, 401 drop, stale notes).

## Acceptance → tests (`web/tests/state/snapshot.svelte.test.ts`)
- Failed first fetch, later success unlocks → "a failed first fetch: Retry offered, retried with backoff, and a later success unlocks grading"
- Retry button → "the Retry button (reload) confirms the snapshot card at once"
- Online → "an online event retries the confirming fetch at once"
- Leave mid-fetch, resume → "leaving while the confirming fetch is in flight, then coming back, fetches again"
- These three tests fail when the retry, online or resume paths are removed.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- After the backoff reaches its cap, an offline page fetches every 15 s for as long as it stays unconfirmed.
- Home still fetches the queue with limit 30 (it was 1).
