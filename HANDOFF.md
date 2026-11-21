# HANDOFF PERF-SNAP

STATUS: DONE
BRANCH: t/PERF-SNAP-instant-home
CHECK: `make check MODULE=web` → green (381 tests)

## Changed (≤ 6 lines)
- New `web/src/lib/state/snapshot.svelte.ts`: per-principal (`token_id`) snapshot in IDB `kotobaya-snapshot` (memory fallback, format field); sync reads after `loadSnapshot`.
- `outboxStore.ts`: IDB/memory code made generic (`idbRecordStore`, `memoryRecordStore`); outbox behaviour unchanged.
- `session.svelte.ts` + layout: `bootSession` reads the snapshot, goes `in` optimistically, then confirms; logout clears snapshot with the outbox.
- Today/decks/struggle/stats stores seed from the snapshot and save after each successful load; `stale` drives an "Updating…" note.
- `ReviewPage.start` serves the snapshot queue synchronously (pending outbox cards removed, also after the outbox is read back); first fetch keeps the card on screen and replaces the rest; the queue is saved after every change.

## Acceptance → tests (`web/tests/state/snapshot.svelte.test.ts`)
- 30 s delays, Home instant → "Home counts and an enabled Start review render synchronously"
- First card at once → "the first card shows at once, rendered, and can be graded"
- Refresh, no blanking → "a refresh replaces the values…", "the review refresh replaces…"
- Pending card not re-served → "a card answered…", "a stale snapshot listing…"
- Logout → "logout clears the snapshot…"; "another principal drops the snapshot"

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Home now fetches `getReviewQueue` with limit 30 (was 1) so it can save the queue page. That makes the Android local handler slower.
- The session is optimistic when a snapshot exists: Home shows before `/auth/session` answers, and a 401 then redirects to /login.
- When the snapshot's queue fetch fails, the review keeps the snapshot (marked "Updating…") rather than showing the error page.
