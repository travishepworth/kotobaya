# HANDOFF AND-KANJI-DECKS (attempt 1)

STATUS: DONE (with one gap, below)
BRANCH: t/AND-KANJI-DECKS
CHECK: `make check MODULE=android` green (first run hit the known-flaky BackNavigationTest; rerun green)

## Changed
- review/BackExtras.kt, SqlBackExtrasIndex.kt: BackExtrasIndex now holds seq -> decks and kanji-note -> decks (live items, live non-smart decks), built per rebuild by 2 more narrow queries. `kanjiDecks(literal, wordSeqs)` = per-deck word counts (+1 for the kanji's own note), priority order, hash lookups only. `BackExtras.kanjiDecks` holds all decks.
- ui/components/KbKanjiDecks.kt: shared "Appears in decks" row (top 3, "+N more" expands, "Name · count"); BackPanel shows it above the example words. Strings in strings_review.xml.
- search/: there is no separate kanji detail screen; the kanji tile is a word search for the literal. A single-kanji query now shows the row above the word list (SearchPort.kanjiDecks, KanjiDecksLookup, one index read per search).

## Tests (ReviewInfoTest)
- 日/本 fixture, priority order, removed item excluded; no queries per card (50 cards, 0); device SQL = replica rules (incl. smart decks excluded).

## Gap
- Chip tap does not open the deck inspector: that needs a nav hook in AppNav (not an owned path). Chips are display-only (+N more expands).

## New dependencies
- none
