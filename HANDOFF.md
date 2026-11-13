# HANDOFF UI-WEB

STATUS: DONE
BRANCH: t/UI-WEB-rebuild
CHECK: `make check MODULE=web` → green · `make screenshots-web` → 172 passed, curated set unchanged

## Changed (≤ 6 lines)
- Earlier round: every route rebuilt on lib/ui, FEAT-x boxes on /notes, coverage deckId fix, 176 curated shots.
- FEAT-x: `PreviewInput.fieldRegions` added; notes and cards previews pass true. A region click opens the box on /notes; in /cards it goes to `/notes?note=&field=`.
- `loadFailed` + `retry()` added to ReviewPage, Cards, Notes, DecksList, NoteTypesList, MediaList, Struggle, Leeches, Confusables, Mapping and Sentences. New `lib/ui/LoadError` renders them.
- Deleted 24 dead lib/<feature>/*.svelte, Toaster and the legacy token block (it lives in design/tokens/web-legacy.css; generator and one tokens.test assertion edited).
- app.html sets data-theme before first paint. 12 `--error` shots were curated.

## Acceptance → tests
- Loop regressions now mount routes/notes/NoteEditor and routes/cards/CardDetail.
- Region clicks, retry and pre-paint passed a one-off Playwright run (not committed).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Follow-ups: confusable headwords, deck colour and due count (both need API changes). DeckItems and UnknownWords still have no error flag.
- Existing bug: client-side nav from /cards to /notes shows id-prefix labels in the notes list (a full load is fine).
