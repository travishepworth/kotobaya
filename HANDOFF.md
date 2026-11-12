# HANDOFF T209

STATUS: DONE
BRANCH: t/T209-web-review
CHECK: `make check MODULE=web` → green

## Changed
- `web/src/routes/review/+page.svelte`: review screen (queue, flip, grade 1-4/1-5 per `scheduler.grading_buttons`, previews via core `previewIntervals`, u undo, e edit, b bury, s suspend, n never-forget, f furigana, p audio, done state, `?drill=<source>`; 501 shows "not available yet").
- `web/src/lib/review/`: `CardView.svelte` (exported via `index.ts`, no session state, furigana is a CSS class), `NoteEditForm.svelte`, `model.ts`, `api.ts`, `messages.ts`.
- `web/src/lib/note-types/Editor.svelte`: import switched to review `CardView`; T211 stand-in `CardView.svelte` deleted.

## Acceptance → tests
- Manual items only (UI); no tests per CLAUDE.md. Not run against a live server.

## New dependencies
- none

## For the reviewer
- API has no furigana alignment: the headword gets the whole reading as one ruby segment. Card is always rendered with furigana on; toggle hides `rt`.
- Bury uses `bulkCards` until `dayEndsAt`; suspend uses `bulkCards`. Queue is refetched after every answer/undo/bury/suspend; answer id is reused on retry.
- `sentence`/cloze not wired (server returns null; no span data in API).
