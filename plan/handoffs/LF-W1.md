# HANDOFF LF-W1

STATUS: DONE
BRANCH: t/LF-W1-web-outbox
CHECK: `make check MODULE=web` → green (354 tests)

## Changed (≤ 6 lines)
- 1: `seedAddedAt(max)`. `Outbox.load()` raises the tap counter above stored `addedAt`s and re-stamps taps made before the load finished.
- 2: other 4xx is final (drop + toast). 408/429 retry. 401/403 pause.
- 4: closes on `versionchange`, reopens on the next call. If a transaction still can't start, it falls back to memory (`persistent` false). A failed `put` makes `beforeunload` warn.
- 5: a `blocked` open is waited out for up to 10 s. A newer database version is opened as it is.
- 6: when an answer is refused for good, its queued `undo:<id>` is dropped.

## Acceptance → tests
- As before. New in `outbox.svelte.test.ts`: "clock moved backward…", "another 4xx (400)…", "403 keeps…", "an answer refused for good drops the undo…". Also 5 new tests in `outboxStore.test.ts`.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- 3 (send the outbox on app start) is outside my paths. Moved to LF-W2.
- 403 pauses rather than drops (C8 doesn't list it), so no answers are lost.
- `session.svelte.ts` logout must still call `clearOutbox()` (not my path).
