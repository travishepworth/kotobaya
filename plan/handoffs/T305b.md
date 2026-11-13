# HANDOFF T305b

STATUS: DONE
BRANCH: t/T305b-note-creator
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `android/.../notes/EntryMatcher.kt`: matches word+reading, then word alone, then reading alone. Ties go to: headword, common, freq rank, seq.
- `notes/DuplicateCheck.kt`: finds a live local note by `entry_seq`, or an entry-less note by its expression. Used by T305c/T311.
- `notes/NoteCreator.kt`: matches, checks duplicates, picks card kinds (deck `card_kinds`, skips empty fronts), adds kanji notes per deck `add_kanji`, then mints everything in one transaction. Request ids are stored in sync_state under `client.note_request.*`.
- `notes/NotesFeature.kt` (Koin). **Also added one line to `di/FeatureModules.kt`** (`notesFeature`, right before `syncFeature`; main keeps T303e `syncUiFeature` after `syncFeature`, left as is). Rebased on main c0ba348 (keeps T304b reviewFeature).

## Acceptance → tests
- Matching → `EntryMatcherTest` (3 tests)
- Ops, rows, kill, retry → `NoteCreatorTest.createMints…`, `aKillBeforeCommit…`, `aRetriedRequestId…`
- Duplicate/force → `aSecondAddOfTheSameWord…`, `forceCreatesAKnowingDuplicate…`
- Two devices → `SameWordTwoDevicesTest` (test-side 8.1 merge emitter)
- Empty fronts → `emptyFrontKindsAreSkipped`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A kanji word is never matched by its reading alone, because a wrong `entry_seq` would merge the note into a homophone. A forced duplicate gets no `entry_seq` (SYNC §4) and is put in the deck as `n:<id>`.
- Unmatched notes get the tag `unmatched` and no deck item. `word.status_set` is not minted (nothing in the ticket needed it).
