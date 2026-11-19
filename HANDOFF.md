# HANDOFF C-28c

STATUS: DONE
BRANCH: t/C-28c-contracts
CHECK: `make check MODULE=contracts` → green, no drift (attempt 2)

## Case resolutions (apply in CORE-28 attempt 2)
1. Open group promotes only the FIRST unit left in `N`; `N` is never reordered. Delete the "kanji first wins" search.
2. Rule stands, example fixed: buried → `[たべる, 校]`; next day `[学, 学校]`.
3. Same change as 1: goal 5 serves in draw order (`日 家 本 語 日本語`); rebuild = tail.
4. Rule 4 "Deck cost": whole unit only if `1 + kanji cards homed in the drawing deck <= deck remaining`; else kanji lead while they fit, deck stops. Limit 1 → `[学]`, then `[]`, word next day.
5. Rule 4 "Turn order" replaces round-robin: (a) source whose next word requires `lastLiteral` (last drawn, else today's last introduced) goes first; (b) else smallest `t(S)` (today's + this build's cards homed in S, deckless kanji not counted), ties decks by id, deckless last.

## Changed
- `QueueBuilder.kt` rules 4-5: deck cost, open group, "Rebuild = tail" (normative), examples
- Parity `queue-kanji-order-stable`, `-deck-limit`, `-turn-order`; SHELL.md 8.4, `check_shell.py`
- README tests, SUMMARY, REVIEW-LOOP §13, SCHEMA.md wording

## Acceptance → tests
- hand-evaluated expects → three parity scenarios; README lists core tests (tail property now includes multi-source tiers)

## New dependencies
- none

## For the reviewer
- Tail exclusions left: pending minting, multi-card kanji parts, two sources whose next words need the same just-introduced kanji (stateless-indistinguishable).
- I chose turn counts over the reviewer's resume pointer: a pointer cannot tell a unit kanji from a backfill kanji.
- "Deck's first new card of the day" now in KDoc edge cases.
