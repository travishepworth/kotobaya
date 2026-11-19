# HANDOFF CORE-28

STATUS: CONTRACT_CHANGE_REQUEST (code done and green; can merge as-is if the cases below are accepted)
BRANCH: t/CORE-28-core
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `queue/DefaultQueueBuilder.kt`: rule 4 (units, leads, hold, mint claims, backfill with half share, own turns, companions) and rule 5 (mix on units, open group)
- `DefaultReviewLoop`: `untilStop`; `kanjiToMaterialize` counts as non-empty
- New `DefaultKanjiRequirement`, `DefaultDeckProgress`, `DefaultKanjiParts`
- Furigana front: HTML ruby, text `（）`, JS mapping
- add_kanji tests replaced; loop fingerprint re-recorded (only `untilStop` changed)

## Acceptance → tests
- parity → `QueueParityScenarioTest` (all 8)
- rule 4 → `QueueKanjiFirstTest`, `QueueBackfillShareTest`
- mix → `QueueMixUnitsTest`; untilStop → `ReviewLoopTest`
- `KanjiRequirementTest`, `KanjiPartsTest`, `DeckProgressTest`, `FuriganaFrontTest`

## New dependencies
- none

## If CONTRACT_CHANGE_REQUEST
CONTRACT: `QueueBuilder.kt` rules 4-5
CHANGE: settle these cases:
1. Open group can put a word before its own-turn kanji. I let kanji-first win.
2. Buried example says N=`[たべる]`. The own-turn rule gives `[たべる, 校]`. I followed the rule.
3. Goal 5 gives `生 学 V 食 X`. After answering 生 and 学, the rebuild gives `V X 食`.
4. Deck limit 1 holding word+学 gives `[学, word]`. After answering 学, the queue is `[]`.
WHY: 3 and 4 break "rebuild = tail" (the properties exclude them).
ADDITIVE: no
