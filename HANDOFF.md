# HANDOFF C-28b

STATUS: DONE
BRANCH: t/C-28b-contracts (from main at 1617bcdb)
CHECK: `make check MODULE=contracts` → green, no drift; core compiles (jvm + js)

## Changed (≤ 6 lines)
- `QueueBuilder.kt` rule 4 "Half share": `share = max(1, newPerDay / 2)`; share step before every source turn, remainder step after the sources; `bToday` derived statelessly from `today`
- Rule 4 "fits the day": a kanji part may be the day's first new card even if it overshoots (lead and backfill parts)
- Rule 6: keep-going form of the share test
- README (new `QueueBackfillShareTest` list), SUMMARY, openapi, SHELL.md 8.4, REVIEW-LOOP §13; clients regenerated
- parity: new `queue-kanji-backfill-share` + mandatory tag `queue.kanji_backfill_share`; `queue-kanji-backfill` unchanged (goal 20 → share 10)

## Acceptance → tests
- contracts green → `make check MODULE=contracts`
- expects hand-evaluated → `queue-kanji-backfill-share` (no golden until the server implements it)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Keep going: I made it "backfill ≤ half of today's introductions" (word, kanji in turn), not a share per batch: a per-batch share is not stable when rebuilding after every answer.
- Overshoot needs "nothing introduced today AND drawn == 0", not `drawn == 0` alone (that would overshoot on every rebuild).
- `newPerDay` 1 → backfill takes the whole goal until done (ADR-044 "at least 1").
