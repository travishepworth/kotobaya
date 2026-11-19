# HANDOFF C-28

STATUS: DONE (attempt 2, ADR-043 applied)
BRANCH: t/C-28-contracts (rebased on main)
CHECK: `make check MODULE=contracts` → green, no drift; core compiles (jvm + js)

## Changed (≤ 6 lines)
- `QueueBuilder.kt` rule 4: backfill also mints missing required kanji of introduced words (oldest word first, within budget); "Fits or leads" replaces atomic-or-skip (kanji that fit, word later, source stops); buried required kanji holds the word; open group covers leads
- `ReviewLoop.kt` 39/40 edge case; `KanjiRequirement.kt`: `uk` beats jōyō (お化け, 彼)
- SCHEMA.md 6.2 (`off` no longer opts out, new rows), 6.5 backfill wording; SHELL.md 10.7 documented difference
- openapi / README / REVIEW-LOOP / SUMMARY wording; clients regenerated
- parity: `queue-kanji-backfill` pins 学, 校 minted; new `queue-kanji-partial-unit` (+ mandatory tag); `queue-reviews-unlimited`, `queue-learning-wait` moved to kana-only

## Acceptance → tests
- contracts green → `make check MODULE=contracts`
- findings 1, 2 → `queue-kanji-backfill`, `queue-kanji-partial-unit`; finding 3 → rule 4 worked example (no scenario)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A held (buried-kanji) word lets its source move on; only a budget lead stops the source. My reading of ADR-043 (3).
- Backfill order is by the oldest introduced word, not by kanji-note age, so it is stable across minting.
- `parent-deleted-by-pull` left unchanged (note is deleted before the fetch). All expects hand-evaluated; goldens stale until SRV-28.
