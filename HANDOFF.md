# HANDOFF C-25

STATUS: DONE
BRANCH: t/C-25-review-extras
CHECK: `make check MODULE=contracts` → green; `MODULE=core` → green (jvm+js); `MODULE=server` → green. No client drift.

## Changed (≤ 6 lines)
- core: `contracts/dict/KanjiExamples.kt` (`KanjiExamplePicker`, normative KDoc) + `dict/DefaultKanjiExamplePicker.kt`.
- contracts/api: `QueueItem.decks` / `deck_count` / `kanji_examples`, schemas `QueueItemDeck`, `KanjiExample`; optional `deck_ids` on `DictEntry`, `DictEntrySummary`, `Note`; SUMMARY "C-25".
- contracts/core: README rows (`CardTextRenderer`, picker), tests, "C-25: offline (Android)"; SUMMARY.
- server: `decks/DeckMembership.kt`, `review/QueueExtras.kt`; every producer sends `deck_ids`.
- clients regenerated.

## Acceptance → tests
- core goldens (15 kanji, jvm=js) → `KanjiExamplePickerTest`
- decks order/max 3, kanji_examples, statement count → `QueueExtrasTest` (`extrasCostAFixedNumberOfStatementsPerPage`: 3 per page)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decisions beyond the ticket: blacklisted/ignored words never shown; picker looks at the first 50 candidates only; smart decks never listed; `DictEntry.deck_ids` counts `e:<seq>` items only.
- Classification is a heuristic (longest fitting reading; 日本 にほん → other, 下手 → kun).
- The statement-count test covers the C-25 loader only; `ReviewViews.items` keeps its older per-item `entry()`/`kanji()` reads.
