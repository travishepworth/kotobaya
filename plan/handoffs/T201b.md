# HANDOFF T201b

STATUS: DONE
BRANCH: t/T201b-notes-api
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- server/.../kotobaya/notes/: `NotesModule(db, opApply).handlers` with all 10 operations; `NoteApi`, `NoteTypeApi`, `NoteViews` (read SQL), `Support` (validation, errors).
- Every write is `OpApply.transaction` + `emit`; `createNote` emits note.created, card.created per `CardGenerator` kind (ascending kind order), optional deck.item_put.
- Not wired: Main.kt is not mine and SyncModule is not wired. Needed line: `handlers += NotesModule(db, sync.opApply).handlers` (same OpApply as sync).

## Acceptance → tests (NotesApiTest, 12 tests)
- Default cards and exact ops per built-in type → `createNoteForEachBuiltInNoteTypeCreatesTheDefaultCardsAndEmitsExactlyTheDeclaredOps`
- Idempotent retry, scopes → `anIdempotentRetryReturnsTheOriginal…`, `readAndWriteScopesAreEnforced…`
- Optional-kind toggle → `togglingAnOptionalKindOn…` (see below)
- Also: tags U+3000/NBSP → 422, duplicates, update registers, merge-loser redirect, type protections, list filters/paging.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Contract gap: `NotePatch` has no card-kind input and `updateNoteType` declares only `note_type.updated`, so "toggle adds missing cards" cannot be expressed. I emit no backfill; `default` affects new notes only, and `card_kinds` on createNote picks kinds. Per-note adds belong to `createCard` (T210a). Confirm or raise a contract change.
- Re-match collisions and REST duplicate creates reach the merge path, which is 503 until T202c. `getNote`/`updateNote`/`deleteNote` already follow `merged_into_id` (tested by setting that state in SQL).
