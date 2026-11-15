# HANDOFF AND-TAKO-PERF2

STATUS: DONE
BRANCH: t/AND-TAKO-PERF2-fast
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Insert cost was in notes/ `DuplicateCheck.byWord`: it read every `entry_seq IS NULL` note (all kanji notes) on a match, or every vocab note on a miss. `liveKanji` also read every kanji note.
- New `notes/NoteChangeLog`: triggers on `note` log changed ids to device-only tables, created at run time. They see local mints, pulls, replay and rebuilds.
- `NoteIndex` + `NoteWordIndex` (cleaned expression) + `ankiapi/store/ChecksumIndex` (expression/reading csum, stamped with the dictionary). Each lookup is one SQL: index ∪ pending changes, every row re-checked.
- Startup warms both indexes. A rebuild is computed on a read and only written in the write tx.
- One `KotobayaAnkiApi` line per add and per csum query, with ms for each phase.

## Acceptance → tests
- replay add/edit/delete → `NoteIndexReplayTest` (5 tests), `DuplicateQueryCsumTest.indexed…`
- benchmark (`TakobotoSendBenchmarkTest`, opt-in) on the real copy, 1x→4x notes: miss 35→146 became 1→0 ms; insert 21–31→42–116 became 15→13 ms. One-time build: 178/401 ms.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The index tables and triggers are created at run time, not in .sq: AppDatabase has no migrations, so existing installs would never get them.
- Index answers are only candidates, so a stale index costs time but cannot return wrong rows. If the triggers disappear, the index is reinstalled and rebuilt.
- The one-time build is about 8 s in the background on the phone. Insert time left is dictionary match + mint.
