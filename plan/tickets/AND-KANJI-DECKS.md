# AND-KANJI-DECKS — Android: "Appears in decks" for kanji (review back + kanji detail), above the words-using-this-kanji list

- Phase: P9 · Model: sonnet · Risk: med
- Deps: AND-REVIEW-INFO (merged), AND-DECK-INSPECT (merged)
- Branch: t/AND-KANJI-DECKS
- Owned paths: android/src/**/review/ (BackExtras / BackExtrasIndex), android/src/**/search/ and android/src/**/word/ or kanji detail screen (wherever the kanji detail lives), android/src/**/ui/components (shared deck-chip row), android SQLDelight .sq read queries only (no schema change), android i18n, android/src/test/
- Module(s) for CI: android

## User request (2026-11-15)
"On kanji, above 'words used in', add an 'appears in decks' (i.e. this kanji / the words it is in are in these decks), like the vocab ones."

## Today
Vocab cards' review back shows deck chips (top 3 by deck priority, +N more) from local deck_item + deck priority (AND-REVIEW-INFO). Kanji cards show example words (core KanjiExamplePicker) but no decks: deck_item is keyed by word (e:<seq>) or note (n:<id>), so a kanji is "in" a deck through the words that contain it (plus its own kanji note, if that note is a deck item).

## Do
1. For a kanji, compute the decks that contain it: decks with a live deck_item for a word whose headword/forms contain the kanji (the same word set the example picker already considers on the device), plus decks holding the kanji's own note. Per deck: name + count of words with this kanji in that deck.
2. Show an "Appears in decks" row ABOVE the example-words section on the kanji review back, and on the kanji detail screen (dictionary). Same chip style as vocab: priority order, top 3 + "+N more" (tap expands); each chip shows the count (e.g. "Core 10k · 4"). Tapping a chip on the detail screen opens that deck's detail (the deck inspector); on the review back it is display-only (or opens with return-to-review if AND-BACK makes that safe).
3. Performance: build this inside BackExtrasIndex (per rebuild, set-based; reuse the AND-REVIEW-INFO index of words by kanji): no per-card table scans, no per-word queries, nothing on the tap path (see the AND-REVIEW-INFO review: t.all() and point queries per candidate were rejected).
4. Hide the row when there are no decks. All strings via i18n.

## Acceptance
- [ ] Test: fixture with words 日本, 日曜, 本 in decks A, B: kanji 日 -> A(count), B(count) in priority order; 本 -> correct decks; a removed deck_item doesn't count.
- [ ] Test: the deck lookup is computed once per index build (no queries per card).
- [ ] make check MODULE=android green.

## Out of scope
Web parity (follow-up), server/contract changes.
