# HANDOFF UI-WEB

STATUS: CONTRACT_CHANGE_REQUEST (state layer, for FEAT-x region clicks; everything else is done)
BRANCH: t/UI-WEB-rebuild
CHECK: `make check MODULE=web` → green · `make screenshots-web` → 172 passed, 688 PNGs, no sideways scroll except /styleguide at 200 %

## Changed (≤ 6 lines)
- Every route rebuilt on lib/ui. New primitives: Page, Panel, Menu, Banner, Select, Switch, TextArea, charts, VirtualTable, QueryField, CardFrame, Stepper, FileDrop. AppShell has an immersive mode. GradeBar takes option lists (5 buttons, drill).
- Review: thumb-zone grade bar with intervals, Space/1–5/U/E/? shortcuts, reveal animation, finished, drill and 900-due states.
- FEAT-x: /notes field boxes (From dictionary / Your override / Reset); overridden regions are marked in the card.
- Fixed a bug: coverage "unknown words" was queried with the media id instead of the coverage deckId.
- 176 curated screenshots in docs/design-screenshots/web/screens/. Full matrix in web/screenshots/.

## Acceptance → tests
- make check green → existing 37 tests. UI is untested per CLAUDE.md; screen quality was checked on the screenshot matrix.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Outside my paths (follow-up): delete dead lib/<feature>/*.svelte (two tests use them), the legacy tokens block, and Toaster; add the app.html theme pre-paint.
- State gaps: ReviewPage and the list pages have no load-error flag; confusables have no headwords; Deck has no colour or due count.

## If CONTRACT_CHANGE_REQUEST
CONTRACT: web/src/lib/state/preview.svelte.ts (+ notes.svelte.ts, cards.svelte.ts)
CHANGE: `PreviewInput.fieldRegions?: boolean`, passed to `options`; NoteEditorState and CardPreviewState pass `true`.
WHY: without it the card has no `data-field` regions, so clicking a field in the card does nothing (the field list still works).
ADDITIVE: yes
