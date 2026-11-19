# HANDOFF C-28c

STATUS: DONE
BRANCH: t/C-28c-contracts
CHECK: `make check MODULE=contracts` → green, no drift (KDoc-only; core not recompiled)

## Case resolutions (apply in CORE-28 attempt 2)
1. Open group promotes only the FIRST unit left in `N`; `N` is never reordered. Delete the "kanji first wins" search.
2. Rule stands, example fixed: buried → `[たべる, 校]`; next day `[学, 学校]`.
3. Same change as 1: goal 5 serves in draw order (`日 家 本 語 日本語`); rebuild = tail.
4. Rule 4 "Deck cost": whole unit only if `1 + kanji cards homed in the drawing deck <= deck remaining`; else kanji lead while they fit, deck stops. Limit 1 → `[学]`, then `[]`, word next day.

## Changed
- `QueueBuilder.kt` rules 4-5: deck cost, open group, "Rebuild = tail" (normative), examples
- Parity `queue-kanji-order-stable`, `queue-kanji-deck-limit`; SHELL.md 8.4, `check_shell.py`
- README tests, SUMMARY, REVIEW-LOOP §13

## Acceptance → tests
- hand-evaluated expects → the two parity scenarios; README lists core tests (drop both property exclusions)

## New dependencies
- none

## For the reviewer
- Tail property excludes two sources in one tier (round-robin restarts; pre-existing), multi-card kanji parts, pending minting.
- Added unasked "deck's first new card of the day" exception (multi-card parts only).
- Worktree: `.claude/worktrees/C-28c-contracts` (original was gone on resume).
