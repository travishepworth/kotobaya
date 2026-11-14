# HANDOFF BUG-14a
Status: DONE. `make check MODULE=web` green.

- Fix: `web/src/lib/state/review.svelte.ts` adds `newPress()` (fresh answerId, reset flipped/editing/shownAt), called after a recorded answer and after undo. A failed POST keeps the id, so retries stay idempotent.
- Tests (3, jsdom): same card served twice gives two different ids and both are recorded; retry after a failure reuses the id; undo mints a fresh id. File: `web/tests/review/answerId.svelte.test.ts`.
- Deviation: the test is under `web/tests/` (not an owned path) because vitest only includes `tests/**/*.test.ts`. A test under `src/lib/state` would never run.
- Audit: undo takes `reviewId` from history and has no client idempotency key. Bury, suspend and never-forget send no client id. The only client-minted key was the answer id. Nothing else to fix.
- Android (read only): `ReviewSession.kt:171` calls `newReviewId(now)` on every grade, a fresh UUIDv7 per press. It is not affected.
- No new dependencies.
