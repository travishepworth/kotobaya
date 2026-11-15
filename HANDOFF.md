# HANDOFF AND-REVIEW-INFO (attempt 2)

STATUS: DONE
BRANCH: t/AND-REVIEW-INFO
CHECK: `make check MODULE=android` → green (on the final run; earlier full runs failed 3x on BackNavigationTest.searchBackClosesTheDetail, a 60 s Compose-idle hang. It also failed with my test cut down to the two pure tests, and it passes alone and on the final run, so I read it as flaky and unrelated.)

## Changed (≤ 6 lines)
- review/BackExtras.kt: `BackExtrasBuilder` runs the core picker over the local replica, builds deck chips (C-25 rules), and sets `ShownCard.extras`. It is computed in `ReviewSession.load`, so it rides the prefetch.
- review/ui/BackPanel.kt: example words (on/kun chip, gloss), deck chips with "+N more", and an "Open in dictionary" button inside the revealed card. The overflow-menu item is removed.
- ReviewViewModel: `openInDictionary` sets `keepOnLeave`, so the page going under the dictionary does not end the session. Back returns to the same revealed card, with no ops written.
- InstalledRenderData.kt: `InstalledKanjiWords` (candidates via `InstalledDictionary.formRows`); `ReviewBackend.kanjiWords` is new; strings are in strings_review.xml.

## Acceptance → tests
- picker inputs → 3 words incl. ON+KUN, blacklist dropped; chips in priority order → `ReviewInfoTest` (examples..., deckChips...)
- open-in-dictionary → back, same card still revealed, no review change → `ReviewInfoTest.openInDictionaryKeepsTheRevealedCardAndWritesNothing`

## New dependencies
- none

## Attempt 2 (review fixes)
- No whole-table reads per card any more. Candidate status, note and deck membership are point lookups on indexed columns (word_status PK, `note.entry_seq`, `deck_item.entry_seq`). Deck chips query `deck_item` by `note_id` / `entry_seq`.
- `InstalledKanjiWords` splits the packed value with `substringBeforeLast` / `substringAfterLast`.
- `ReviewInfoTest` runs both builders over a `LearningTables` wrapper that throws on `all()`.
- Not done: a fixture-DB test of `InstalledKanjiWords` SQL.
- The BackNavigationTest flake showed up again in one run; the final run is green.

## For the reviewer (≤ 3 lines)
- `InstalledKanjiWords` reuses `formRows` (the owned paths exclude dict/) by packing `reading|freq_rank` into the `norm` column; a dedicated query in InstalledDictionary would be cleaner.
- Per kanji card the cost is about 3 indexed lookups per dictionary word containing the kanji (thousands for very common kanji), each off the main thread; decks still use a `merged_into_id` WHERE scan.
