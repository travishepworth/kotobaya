# HANDOFF PERF-SNAP (attempt 2)

STATUS: DONE
BRANCH: t/PERF-SNAP-instant-home
CHECK: `make check MODULE=web` → green (386 tests)

## Changed (≤ 6 lines)
- `review.svelte.ts`: snapshot card shown at once, grading disabled (`unconfirmed`, GradeBar `disabled`) until first full fetch; kept in front only if in `fresh`, else replaced; no prefetch merge while unconfirmed. `dueFirstAt` set from snapshot.
- Queue never saved while `keepGoing` (review screen and Home, which saves only `keep_going=false` responses).
- `today.svelte.ts`: mark (outbox seq + pending) per request; saves items minus `answeredSinceMark` (as fetchQueue).
- `snapshot.svelte.ts`: queue section with `dayEndsAt <= now` reads as missing. Boot 401 drops snapshot (memory + IDB).
- Decks/struggle/stats get `stale`; Home shows "Updating…" per panel.

## Acceptance → tests (`web/tests/state/snapshot.svelte.test.ts`)
- 1 → "…not graded until confirmed", "…lists the snapshot card…can be graded", "…does not list the snapshot card…replaced, never graded", "a snapshot of an ended study day is not used"
- 2 → "Keep going pages are never saved (review screen and Home)"
- 3 → "Home does not save a card answered (and acknowledged) while its fetch was in flight"
- 6/7 → Home render test checks all notes; "a 401 at start-up drops the snapshot…"
- 1–3 guards mutation-checked (tests fail without them).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- If the first fetch fails or times out, the snapshot card stays shown but cannot be graded (marked "Updating…"). It unlocks when a later full fetch succeeds.
- Home still fetches the queue with limit 30 (it was 1).
