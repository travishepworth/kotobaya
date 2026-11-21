# HANDOFF LF-W1

STATUS: DONE
BRANCH: t/LF-W1-web-outbox
CHECK: `make check MODULE=web` → green (354 tests)

## Changed (≤ 6 lines)
- Fix 1: `outboxStore.seedAddedAt(max)`. `Outbox.load()` raises the tap counter above every stored `addedAt`, and gives taps made before the load finished a new stamp (in the store too).
- Fix 2: any other 4xx is a final refusal (drop + toast). 408/429 are retried. 401 and 403 pause sending.
- Fix 4: the connection closes on `versionchange` and reopens on the next call. If a transaction can't start even after a reopen, the store switches to memory (`persistent` false). A failed `put` marks the entry unsaved, so `beforeunload` warns.
- Fix 5: a `blocked` open is waited out, for up to 10 s. A connection that opens after the timeout is closed at once. If the database is already at a newer version (`VersionError`), it is opened at its current version.
- Fix 6: when an answer is refused for good, any `undo:<id>` queued behind it is dropped too.

## Acceptance → tests
- Unchanged from attempt 1 (`outbox.svelte.test.ts`). New: "clock moved backward between loads…", "another 4xx (400)…", "403 keeps the outbox…", "an answer refused for good drops the undo…", plus 5 store tests in `outboxStore.test.ts`.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Fix 3 (send the outbox on app start, not only on /review) needs code outside my paths. Moved to LF-W2.
- 403 pauses instead of dropping, so a session without the scope loses no answers. C8 doesn't list 403.
- Still open: logout must call `clearOutbox()` in `session.svelte.ts`, which is not my path.
