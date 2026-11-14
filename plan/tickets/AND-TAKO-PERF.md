# AND-TAKO-PERF — Takoboto "send to Anki" takes ~38 s (duplicate-check query in provider)

- Phase: P9 hotfix · Model: opus · Risk: med
- Deps: none
- Branch: t/AND-TAKO-PERF-dupcheck
- Owned paths (only edit these): android/src/**/ankiapi/ (provider, AnkiStore, AnkiIdMap, DuplicateCheck), android/src/test/ for those; android SQLDelight .sq files only for an index on device-only tables (say which; contracts/db unchanged)
- Module(s) for CI: android

## Evidence (logcat 2026-11-15 07:13 local, 0.1.12, user 10)
KotobayaAnkiApi: query caller=jp.takoboto uri=content://com.ichi2.anki.flashcards/notes_v2 projection=[_id, flds, tags] selection="mid=1600000000001 and csum in (567984260)"
→ returned after 37 692 ms (Binder: "took 37692ms"). All other provider calls were 5–15 ms. Takoboto's "Working on it" dialog stayed up 28 s.
Phone DB copy (PRIVATE, read-only): /tmp/claude-1000/-home-travmonkey-repos-kotobaya/c6911de0-217a-4b52-b8c2-af6c5b745fd0/scratchpad/dbcopy/kotobaya-app.db, ankiapi.db alongside. Device has ~90k review_log rows, large card/note tables.
Also seen: insert decks {deck_name="Takoboto"} returned decks/1 but the next decks query still lists only "Test" (1001). Check whether that is intended (ADR-013/T305) and where the Takoboto note ends up; fix only if it's a bug, else explain in HANDOFF.

## Do
1. Reproduce the notes_v2 csum query against the DB copy on the JVM; find why it's slow (full scan + per-row checksum over all notes? field join? dictionary lookups per row?).
2. Make it fast: indexed/narrowed lookup (e.g. by first-field checksum or expression), target < 200 ms on the real DB copy. Semantics (Anki csum of first field, stripped HTML) unchanged.
3. Audit the other provider paths Takoboto uses (decks, models, notes insert, notes/{id}/cards) for the same pattern.

## Acceptance
- [ ] Test: csum duplicate query returns the same rows as before (existing DuplicateCheck tests + one for csum selection).
- [ ] Benchmark/test note in HANDOFF: before/after ms on the real DB copy.
- [ ] make check MODULE=android green.

## Out of scope
Search UI, deck UI, deck content download.
