# HANDOFF T608

STATUS: DONE
BRANCH: t/T608-export
CHECK: `make check MODULE=server` → green (final run after last fix)

## Changed (≤ 6 lines)
- server/.../export/: `ExportModule` (exportAll, exportAnki), `JsonDump` (REPEATABLE READ read-only snapshot, `to_jsonb` rows streamed by cursor, generated `search_text` removed), `AnkiPackage` (collection.anki21 via sqlite-jdbc, media map, blobs streamed from store).
- Main.kt: `+ ExportModule(db, blobStore).handlers`; server/build.gradle.kts: sqlite-jdbc.
- WiringAuditTest (outside owned paths): removed exportAll/exportAnki from the 501 allowlist; the audit failed otherwise.

## Acceptance → tests
- JSON counts incl. tombstones/losers, excluded tables absent → ExportApiTest.jsonDumpHasEveryRowOfEveryTableTombstonesAndLosersIncluded (+ includeMediaContent... test)
- .apkg counts, revlog excludes manual/drill/cram/undone, media present, empty query valid, bad query 422 → ExportApiTest.apkg* tests

## New dependencies
- org.xerial:sqlite-jdbc:3.47.1.0 — writes the Anki SQLite collection (ticket-mandated).

## For the reviewer (≤ 3 lines)
- Anki templates cannot be per-note, so each model carries hidden fields `kb_front_<kind>`/`kb_back_<kind>` holding the core-rendered HTML per note (dictionary fill-in kept; furigana alignment/pitch/cloze sentence not supplied). `assets/card/card.css` not bundled (not on server classpath); only note_type.css.
- revlog ivl/lastIvl/factor are 0 (not stored per review); media blobs absent from the store are skipped with a warning.
