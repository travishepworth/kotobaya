# HANDOFF C-28b

STATUS: DONE (attempt 4, deck-homed backfill kanji no longer starve)
BRANCH: t/C-28b-contracts (from main at 1617bcdb)
CHECK: `make check MODULE=contracts` → green, no drift; core compiles (jvm + js)

## Changed (≤ 6 lines)
- `QueueBuilder.kt` rule 4: a backfill part is `kc(L)` whatever the cards' home (attempt-3 narrowing reverted); a deck-homed backfill kanji is drawn by the share/remainder step **or** on its deck's own turn, whichever comes first
- `b` / `bToday` count every backfill-kanji card whatever path drew it; only the share step is refused by the share test
- Deckless backfill kanji still have no own turn (attempt 2)
- Deck-homed KDoc example now `[日, 本, うっかり, Ｔシャツ]`, plus own-turn variant `[日, 本, うっかり, 語]`
- README test list, openapi, REVIEW-LOOP §13; clients regenerated

## Acceptance → tests
- contracts green → `make check MODULE=contracts`
- expects hand-evaluated → `queue-kanji-backfill-share`, `queue-kanji-backfill` (all kanji deckless): unchanged

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- An own-turn draw of a backfill kanji counts in `bToday` (the build cannot tell paths apart) but is never refused, so an enabled kanji deck in a reached tier can carry backfill kanji past the share.
- Deck-homed cases have KDoc examples only, no parity step.
- FOLLOWUPS minors untouched.
