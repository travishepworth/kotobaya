# HANDOFF WEB-TAKO-SEARCH

STATUS: DONE
BRANCH: t/WEB-TAKO-SEARCH-web
CHECK: `make check MODULE=web` -> green (85 tests, svelte-check 0 errors)

## Changed (<= 6 lines)
- Wrong text: `rowLabel` took the first stored field; the server returns jsonb key order (shortest first), so Takoboto rows showed "notes"/"meaning". Now expression, headword, kanji, reading, then note-type field order; `noteLabel` returns null while headwords load and rows show a skeleton, never an id (`cards/model.ts`, cards+notes pages).
- Preview fail: rendering itself is fine on the exact Takoboto shape (tested). Likely cause is a missing note-type row (`listNoteTypes` hid deleted types, and one failed call skipped it). Now loads with `includeDeleted`, loads independently, and falls back to the base type's built-in type (`resolveNoteType`). Core DTOs get `?? null` for template front/back.
- New `/search` (nav item "Search"): `lib/state/mergedSearch.svelte.ts`, `lib/search/`, `routes/search`. Plain query: dictionary words flagged "In my cards", then uncovered notes. Operators (core `parseQuery` key token): cards only. `/cards` and `/notes` stay for bulk work.

## Acceptance -> tests
- Label -> `tests/cards/takoboto.test.ts`; preview ok -> same file; merged order + flag -> `tests/search/merged.test.ts`.

## New dependencies
- none

## For the reviewer (<= 3 lines)
- Not reproducible without live data: if previews still fail, check that the note's `note_type_id` exists on the server (server/sync side).
- Contract gap: no deck on dict summary or note, so rows show word status and "In my cards" but no deck name.
- Root cause is web-side; nothing for core/server. Fixed a side effect: `core/build` symlink was local only, not committed.
