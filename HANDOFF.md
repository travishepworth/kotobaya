# HANDOFF C-28b

STATUS: DONE (attempt 3, own-turn exclusion narrowed)
BRANCH: t/C-28b-contracts (from main at 1617bcdb)
CHECK: `make check MODULE=contracts` → green, no drift; core compiles (jvm + js)

## Changed (≤ 6 lines)
- `QueueBuilder.kt` rule 4 "Own turn": only a **deckless** NEW card of a backfill kanji loses its own turn; a deck-homed one keeps its deck's turn
- Backfill part = the `kc(L)` cards whose home new-source is not a deck; `bToday` counts only those (stateless, from `TodayReview.deckIds`)
- New rule 4 worked example (日 in an enabled kanji deck → `[本, 語, うっかり, 日]`, `bToday = 2`)
- README test list, openapi, REVIEW-LOOP §13 wording; clients regenerated
- Attempts 1–2 otherwise unchanged (half share, remainder step, first-part overshoot, keep-going test, parity scenario)

## Acceptance → tests
- contracts green → `make check MODULE=contracts`
- expects hand-evaluated → `queue-kanji-backfill-share` (all kanji deckless) and `queue-kanji-backfill`: unchanged

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A deck-homed backfill kanji is drawn **only** on its deck's turns (never by the share or remainder step); otherwise `bToday` could not tell the two paths apart. So it waits for its deck position and per-deck limit.
- The deck-homed case has a KDoc example, no parity step.
- Minors for FOLLOWUPS untouched.
