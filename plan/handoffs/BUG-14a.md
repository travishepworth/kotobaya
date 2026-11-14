# HANDOFF BUG-14a
Status: DONE (attempt 2). `make check MODULE=web` green (41 tests).

- Fix: `web/src/lib/state/review.svelte.ts` adds `newPress()` (fresh answerId, reset flipped/editing/shownAt). A failed POST keeps the id, so retries stay idempotent. Undo also mints a fresh id.
- Attempt 2 (review findings 1, 2): once an answer is recorded, `grade()` drops the graded card locally (`items.slice(1)`) before the scheduled-queue refetch. A refetch failure toasts and keeps the rest of the old queue, but never shows the graded card as gradeable again. `newPress()` now runs after the refetch, in `finally`, so there is no front-side flash and `durationMs` excludes fetch time.
- Tests (4, jsdom) in `web/tests/review/answerId.svelte.test.ts`: same card served twice gives two ids; retry reuses the id; undo mints a fresh id; POST ok plus refetch fail leaves no gradeable card and no second POST.
- Deviation: the test lives under `web/tests/` because vitest only includes `tests/**`.
- Audit: no other client-minted idempotency keys. Android mints a fresh UUIDv7 per grade (`ReviewSession.kt:171`), so it is not affected.
- No new dependencies.
