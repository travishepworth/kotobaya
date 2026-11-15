# HANDOFF AND-TAKO-PERF2

STATUS: DONE
BRANCH: t/AND-TAKO-PERF2-fast
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Insert cost was in notes/: `DuplicateCheck.byWord` read every kanji or vocab note; `liveKanji` read every kanji note.
- `notes/NoteChangeLog`: run-time triggers on `note` log changed ids for local mints, pulls, replay and rebuilds.
- `NoteWordIndex` and `ankiapi/store/ChecksumIndex` (csum, dictionary-stamped): one SQL per lookup (index ∪ pending), rows re-checked.
- Startup warms both indexes; rebuilds compute on a read and only write in the tx.
- One `KotobayaAnkiApi` line with per-phase ms for each add and each csum query.

## Acceptance → tests
- replay add/edit/delete → `NoteIndexReplayTest` (5), `DuplicateQueryCsumTest.indexed…`
- `TakobotoSendBenchmarkTest` (opt-in), real copy at 1x→4x notes: miss 35→146 is now 1→0 ms; insert 21–31→42–116 is now 15→13 ms.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Tables and triggers are created at run time, not in .sq: AppDatabase has no migrations.
- Index hits are only candidates, so a stale index cannot return wrong rows. Lost triggers mean reinstall and rebuild.
- One-time build: 178 ms on JVM, about 8 s in the background on the phone. Insert time left is dictionary match + mint.
