# HANDOFF AND-KEEPGOING-FIX

STATUS: DONE
BRANCH: t/AND-KEEPGOING-FIX
CHECK: `make check MODULE=android` → green except the known flake BackNavigationTest.searchBackClosesTheDetail

## Changed (≤ 6 lines)
- Aligned with C-27 (read from t/C-27-materialize, contracts/core/README.md "C-27"; not on main yet). Pending entries: enabled decks' live `e:<seq>` items whose entry is in the local dictionary, has no live note and no user-deleted note, override filter applied, first 2×budget per deck in (position, item_key) order.
- Pending entries are passed only after a complete pull (`materializeAllowed`: unpaired, or no sync running, 0 failures, last_sync_at set, no rebuild).
- NoteCreator.materialize mints C-27 ops: vocab `note.created` {entry_seq, fields {}, origin deck}, `card.created` for every kindsForNewNote kind, add_kanji `auto_kanji` notes (deduped in the mint). Skips an entry with a live note. Loader rebuilds after.
- ReviewSession: always rebuilds before ending. In keep going, learn-ahead waits for a fresh build. A config change keeps the session.

## Acceptance → tests
- KeepGoingTest: 300 due + 40 new without a stop; defect-1 regression
- DeckEntriesTest (8): C-27 ops; user-deleted entry dropped; unknown entries don't starve; mid-pull gate; refused mint; add_kanji dedup; keep going through 60 entries; repeat loop
- ReviewInfoTest.aConfigurationChangeKeepsTheSession

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Deviation: a normal build's bound uses max(own budget, keep-going batch). Otherwise budget 0 hides entries from rule-6 `moreAvailable`, and the button disappears.
- Convergence: SYNC.md 4 + 8.1. Rebase after C-27 merges (no contract edits here).
