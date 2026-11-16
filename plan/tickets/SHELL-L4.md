# SHELL-L4 — Local review actions and notes: bulkCards, word status, note read/edit/delete, card history

- Phase: SHELL · Model: opus · Risk: high
- Deps: SHELL-L3
- Branch: t/SHELL-L4-local-actions
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/actions/**, android/src/test/kotlin/kotobaya/android/shell/api/actions/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 5, 6
- Contracts: openapi.yaml (bulkCards, setWordStatus, clearWordStatus, getWordStatus, getNote, updateNote, deleteNote, getCard, listCardReviews), contracts/sync/OPS.md (card.*, note.updated, note.deleted, word.status_*), SYNC.md 7
- Decisions: ADR-035, 036

## Do
- `bulkCards`: local for actions suspend, unsuspend, bury, unbury, flag (reuse `ReviewActions` mint requests, `WordCardSuspension`); all ids validated first,
  then one transaction (all or nothing, as the server). Any other action → forward to `Upstream` (so offline gives `503 shell_offline`).
- `setWordStatus` / `clearWordStatus` / `getWordStatus`: `ReviewActions.toggleStatus`, `DeckWordOps`.
- `getNote`, `updateNote` (absent = unchanged; per-field register writes; `NoteChangeLog`; empty-front warnings as the server reports them), `deleteNote`
  (already deleted → success, nothing minted), `getCard`, `listCardReviews` (cursor paging as the contract).

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenarios for each operation (add scenario files via a CONTRACT_CHANGE_REQUEST list if C1 lacks one; do not edit contracts/)
- [ ] `BulkAtomicTest`: one unknown id in a batch → 4xx and zero ops
- [ ] `NoteEditLwwTest`: offline edit vs newer pulled edit of the same field → newer HLC wins; different fields → both kept; pulled delete beats the older local edit
- [ ] `DeleteIdempotentTest`: second delete mints nothing
- [ ] All minted ops validate against op.schema.json; handler replay is idempotent where the contract says so

## Out of scope
createNote (L5). listCards/listNotes (L6).

## Notes from previous attempts

## Orchestrator note (from SHELL-A2)
Also owned: delete your ops' lines from PENDING in the android RouteTableTest (shell/api tests). Routes are top-level vals in shell/api/<group>/Routes.kt; get deps via call.services.
