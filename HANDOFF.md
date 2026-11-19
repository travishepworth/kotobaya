# HANDOFF C-28b

STATUS: DONE (attempt 2, reviewer major applied)
BRANCH: t/C-28b-contracts (from main at 1617bcdb)
CHECK: `make check MODULE=contracts` → green, no drift; core compiles (jvm + js)

## Changed (≤ 6 lines)
- Attempt 2: `QueueBuilder.kt` rule 4 "Own turn": a NEW card of a backfill kanji has no turn of its own in any source (deck or deckless, both builds); drawn only by the share step, the remainder step or a new word's unit
- Rule 4 half-share example, README `QueueBackfillShareTest` list, openapi, REVIEW-LOOP §13 and the scenario narrative say so; clients regenerated
- Attempt 1 (unchanged): half share `max(1, newPerDay / 2)`, remainder step, stateless `bToday`, first-part-of-the-day overshoot, keep-going share test, parity `queue-kanji-backfill-share` + mandatory tag

## Acceptance → tests
- contracts green → `make check MODULE=contracts`
- expects hand-evaluated → `queue-kanji-backfill-share` steps 0, 3, 6, 7 re-evaluated under the new rule: unchanged

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Side effect: a kanji deck studied for itself gets the kanji of already studied words at the backfill pace, not on its own turns.
- Keep going is "backfill ≤ half of today's introductions", not a share per batch (unchanged from attempt 1).
- The two minors (newPerDay 1; scoped `deck_id` builds and `bToday`) are untouched, for FOLLOWUPS.
