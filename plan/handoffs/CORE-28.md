# HANDOFF CORE-28

STATUS: DONE (attempt 2, C-28c applied)
BRANCH: t/CORE-28-core (rebased on main)
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `DefaultQueueBuilder`: kanji-first units, leads, backfill half share, deck cost, turn order `t(S)`/rule (a); `N` never reordered
- `DefaultReviewLoop`: `untilStop`, `kanjiToMaterialize` counts as non-empty
- New `DefaultKanjiRequirement`, `DefaultDeckProgress`, `DefaultKanjiParts`; furigana front (HTML ruby, text `（）`, JS)
- Tests: every README list, 11 parity mirrors (`QueueParityScenarioTest`), shared `QueueRandom`/`QueueSim`

## Acceptance → tests
- parity → `QueueParityScenarioTest`
- rule 4/deck cost → `QueueKanjiFirstTest`, `QueueBackfillShareTest`
- turn order, 50 kanji, rebuild = tail → `QueueMixUnitsTest`
- `ReviewLoopTest` (untilStop), `KanjiRequirementTest`, `KanjiPartsTest`, `DeckProgressTest`, `FuriganaFrontTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Rebuild ≠ tail, contract as written (follow-up): (a) keep going. Studied 日; new 学校 gives `学 校 学校 日`. After 学, the rebuild gives `日 校 学校`: the share step admits the part mid-unit.
- (b) A capped-deck lead with a kanji homed elsewhere loses that part once its deck hits 0.
- Properties exclude these. Exclusion 3 is widened to any home.
