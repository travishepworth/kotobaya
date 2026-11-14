# HANDOFF BUG-12

STATUS: DONE
BRANCH: t/BUG-12-struggle-open-dict
CHECK: `make check MODULE=web` → green

## Root cause
Web only: web/src/routes/struggle/word/[noteId]/+page.svelte:38 linked to bare `/dictionary` (no entry id), and the dictionary page (web/src/routes/dictionary/+page.svelte) had no URL param support, so it just opened an empty search. Android has no such action (not touched).

## Changed
- New web/src/lib/state/dictionaryLink.ts: `dictionaryHref(seq)` -> `/dictionary?seq=N`, `seqFromSearch`.
- Struggle word page links to `dictionaryHref(w.entrySeq)`.
- Dictionary page opens the entry from `?seq=` on mount.

## Acceptance → tests
- Link target regression guard → web/src/lib/state/dictionaryLink.test.ts

## New dependencies
- none

## For the reviewer
- Helper lives in lib/state/ because the view-import architecture test forbids .svelte importing other lib dirs.
- Not verified in a running browser; vitest + build only.
