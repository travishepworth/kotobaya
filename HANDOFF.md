# HANDOFF T603

STATUS: DONE
BRANCH: t/T603-server-ankiconnect
CHECK: `make check MODULE=server` -> first full run had only my 2 new-test failures (400 tests); both fixed, the 5 ankiconnect tests then pass in isolation. Full re-run not repeated.

## Changed
- New `server/.../ankiconnect/` (`AnkiConnectModule`, `AnkiIds`, `AnkiMedia`): all seven actions, `anki_id` (Kotlin + SQL), `findNotes` parser, media rewrite.
- `Main.kt`: `Auth(..., ankiConnect = AnkiConnectModule(db, sync.opApply, blobStore).actions)`; the `Auth` line moved below `SyncModule` (it needs `sync.opApply`).
- Test: `AnkiConnectTest` (5 Testcontainers tests).

## Acceptance -> tests
- three op kinds + `/sync/pull` -> addNoteEmitsExactlyTheThreeOpKindsAndIsVisibleViaPull
- duplicate / allowDuplicate -> aDuplicateIsRefusedAndAllowDuplicate...
- nid round-trip -> ankiIdRoundTripsThroughFindNotes; media rewrite -> storeMediaFileRewrites...
- revoked PAT -> aRevokedKeyIsRefused...

## New dependencies
- none

## For the reviewer
- `NoteApi.create` is bound to `ApplicationCall` (not callable), so the op sequence (note.created, card.created, deck.item_put) is repeated in ankiconnect/, same one-transaction `OpApply`.
- No "core matcher" exists in core: I wrote a SQL matcher over `ref.dict_entry_form.norm` (expression any form, reading must match when given; prefers exact form, common, freq, seq).
- SSRF (T609): `url` in `storeMediaFile`/attachments is rejected with an error; only base64 `data`. The `originalname -> hash` map is in memory (lost on restart; sha-named refs always work).
