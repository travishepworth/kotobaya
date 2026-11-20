# HANDOFF LF-W1

STATUS: DONE
BRANCH: t/LF-W1-web-outbox
CHECK: `make check MODULE=web` → green (344 tests)

## Changed (≤ 6 lines)
- New `web/src/lib/review/outboxStore.ts`: IndexedDB `kotobaya`/`outbox` (key `id`), falls back to memory if IndexedDB is missing or won't open.
- `review.svelte.ts` Outbox: each tap is stored before it is sent. One send at a time, in order. Retry backoff starts at 1 s and doubles to a 30 s cap; it also retries on `online`/visibility. A 422 at `/reviewed_at` is resent without the timestamp. Stored answers are replayed on load and their cards filtered from fetches. A 401 pauses sending until `start`.
- No restore and no `gradeLocked` any more. Undo removes an answer that was never sent; otherwise it queues an `undo:<id>` entry.
- Old restore/lock tests rewritten for C8. Review minors done.

## Acceptance → tests
- reload → `outbox.svelte.test.ts` "tap, reload before the response…"
- 10 offline → "10 answers with the network down…"
- dup / order → "duplicate delivery…", "out-of-order completion…", "a timed-out request that lands later…"
- undo / logout → "undo before send removes…", "logout clears the store…"
- Minors → `loopPresenter.svelte.test.ts` "answer acked…while an unguarded reload…", `trimAnswerLog` block

## New dependencies
- none (small fake IndexedDB inside `outboxStore.test.ts`)

## For the reviewer (≤ 3 lines)
- **Logout clearing is not wired yet.** `clearOutbox()` is exported, but `session.svelte.ts logout()` (not my path) must call it. The store is not scoped per principal: `token_id` changes on every login, and C8 says to keep entries across a 401 and re-login.
- Where ticket and contract C8 disagree I followed C8: backoff cap 30 s (ticket: 60 s); 401 pauses instead of retrying.
- The minor's test calls `fetchQueue` directly, because `reload()` sets `busy` and that blocks taps.
