# HANDOFF T412+T413

STATUS: DONE
BRANCH: t/T412+T413-web-dict-notes
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- T412: web/src/routes/dictionary/+page.svelte, web/src/lib/dictionary/ (debounced search, paging, entry detail with senses/kanji/pitch/freq/examples, "Add as note" via createNote with first vocab note type).
- T413: web/src/routes/notes/+page.svelte, web/src/lib/notes/ (windowed NoteTable, NoteEditor: fields/tags/mnemonic/source sentence via updateNote, core renderCard preview, delete with confirm; `?note=<id>` deep link).
- No nav key needed (`nav.dictionary`/`nav.notes` already exist).

## Acceptance → tests
- make check green → no tests (UI, lean mode)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Contract: `listNotes` takes plain `q` (substring), not the card query language, so Notes search uses `q` + type/tag filters, not the core parser. All needed operations exist in the contract; none were stubbed (server 501 status not verified).
- Notes table reuses `windowRange`/`PAGE_SIZE` from lib/cards/model (not edited). Dictionary "Add as note" picks the first live vocab note type.
