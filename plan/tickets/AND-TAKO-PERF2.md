# AND-TAKO-PERF2 — Takoboto send still ~7.6 s on the phone (dup-check 4.0 s + bulkInsert 3.6 s)

- Phase: P9 hotfix · Model: opus · Risk: med
- Deps: AND-TAKO-PERF (merged abc4499a)
- Branch: t/AND-TAKO-PERF2-fast
- Owned paths (only edit these): android/src/**/ankiapi/, android/src/**/notes/ or NoteCreator only if the insert cost lives there (say so), android SQLDelight .sq for device-only indexes/tables (contracts/db unchanged; a device-only cache table is OK if it's rebuilt from learning data and never synced), android/src/test/
- Module(s) for CI: android

## Evidence (0.1.13 debug APK on phone, user 10, 2026-11-15 08:39 local)
notes_v2 csum query: 4 021 ms (was 37 692 ms; JVM bench said 91 ms). Then bulkInsert notes?deckId=1 count=1: 3 600 ms with "SQLiteConnection: Long db operation". All other provider calls 5–25 ms.
Note: the phone runs a DEBUGGABLE build (ART mostly interpreted/JIT, roughly 40x slower than JVM). Fixes must remove O(notes) work, not just make it cheaper per note. ~6.6k vocab notes, ~90k review_log, 10.7k deck_item.
Phone DB copy (PRIVATE, read-only): /tmp/claude-1000/-home-travmonkey-repos-kotobaya/c6911de0-217a-4b52-b8c2-af6c5b745fd0/scratchpad/dbcopy/kotobaya-app.db (+wal, ankiapi.db).

## Do
1. Duplicate check: answer `csum in (...)` without scanning every note. E.g. a device-only index keyed by first-field checksum (maintained on note insert/update/replay, or lazily built once and kept fresh), or SQL on an expression/headword column. Must stay correct after sync pulls new/edited notes.
2. bulkInsert of one note: find the 3.6 s (likely one-tx mint plus duplicate check plus queue/word-status recompute or a full-table query). Make it O(1)-ish in note count.
3. Add a debug log line with per-phase ms for both calls (tag KotobayaAnkiApi) so the next phone check can see where time goes.

## Acceptance
- [ ] Test: csum lookup stays correct after a note is added, edited (first field changed) and deleted via sync replay.
- [ ] Benchmark on the real DB copy (JVM): both paths under 50 ms, and show they no longer scale with the number of notes (count rows touched or time at 1x vs 4x notes).
- [ ] make check MODULE=android green.

## Out of scope
Deck content, search, UI.
