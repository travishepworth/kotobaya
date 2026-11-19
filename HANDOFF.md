# HANDOFF AND-28

STATUS: DONE (goldens pending SRV-28)
BRANCH: t/AND-28-android
CHECK: `make check MODULE=android` → red only on 4 golden tests (599 run): LocalParityTest x2, AnswerParityTest, QueueParityTest. The goldens in `fixtures/shell-parity/golden` are server-recorded and outside `android/**`, so they stay stale until SRV-28 re-records; nothing else fails.

## Changed (≤ 6 lines)
- `LocalQueueLoader`: required and missing kanji per vocab note/entry (new `KanjiSource` + core `KanjiRequirement`), companions for `deck_id`, `reviewedAt`, add_kanji OFF, all vocab/kanji cards passed (backfill needs them). `NoteCreator.materialize(kanji, entries)` mints step A then B. `QueueRoute`: `until_stop`, `to_materialize` incl. kanji.
- `QueueViews`: `kanji_parts` (core `KanjiParts`), `front_furigana`, furigana in `text`. `DeckViews`/`DeckProgressView`: `Deck.progress` via core `DeckProgress`.
- Tests pinned to 1000220/1000440/1000450 now 1001010/1001150/1001580.

## Acceptance → tests
- device matches the scenarios' `expect` → new `ParityExpectTest` (all scenarios, passes without goldens)
- mint once, never re-create a deleted kanji → `QueueKanjiMintTest`; progress → `DeckParityTest`
- materialize race tests → `MaterializeRaceTest` green. Re-record goldens → SRV-28 (cannot from here).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The candidate query now returns every card of vocab and kanji notes (not only due ones): the builder reads "introduced" and "started" from candidates. This costs rows per build; the server query needs the same widening or backfill parity breaks.
- `KanjiSource` caches dictionary hits for the process life; cold first build reads every vocab entry once.
