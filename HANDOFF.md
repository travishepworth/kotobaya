# HANDOFF C-27

STATUS: DONE
BRANCH: t/C-27-materialize
CHECK: `make check MODULE=contracts|server|web|cli` → green

## Changed (≤ 6 lines)
- GET /review/queue passes real pendingEntries (QueueLoader, exact 2×budget window per deck) and, for a review/write caller, mints the builder's toMaterialize as server device (DeckMaterializer: note.created origin deck + default/card_kinds cards, auto_kanji if deck add_kanji present), rebuilds ≤3 rounds.
- openapi: x-emits-ops on getReviewQueue (check_contract GET_EMITS), `to_materialize`, counts.new incl. drawn entries; SUMMARY, core README "C-27" (pending-entry rules), SYNC.md 8.1 convergence note + §14, ADR-032.
- Web: keep going refetches empty/stale keep-going responses; ends only on an empty keep-going fetch.
- CLI daemon/kby: `keep_going` action, state `keep_going`/`more_available`, README.

## Acceptance → tests
- notes via ops, idempotent, new_per_day, keep going ≥5 batches, read-only, deleted note, device race → DeckMaterializeTest (7, incl. add_kanji + card_kinds)
- web stream/no repeats → keepGoing.svelte.test.ts; CLI → test_review keep_going tests

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A GET that emits ops (only review/write callers materialize).
- New rule: a user-deleted note blocks re-materializing its entry; AND-KEEPGOING-FIX's Android loader should follow core README "C-27".
- Edited contracts/sync/SYNC.md (additive note) per the user's correctness requirement.
