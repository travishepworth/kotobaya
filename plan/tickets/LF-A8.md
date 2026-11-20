# LF-A8 — Shell: every device-mintable write is local (cards, presets, note types, confusables, grammar, media)

New ticket (not an extension of LF-A4: LF-A4 stays "bulkCards / bulkScheduleCards"; this one is 17 single-resource writes and 6 reads in other packages, and reuses LF-A4's card-op helpers).

- Phase: P10 local-first · Model: opus · Risk: high
- Deps: C-31, LF-A1 (non-blocking triggers), LF-A4 (card op minting helpers in `shell/api/actions/`)
- Branch: t/LF-A8-local-writes
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/cards/ (createCard, updateCard, deleteCard), shell/api/presets/, shell/api/notetypes/, shell/api/confusables/, shell/api/grammar/, shell/api/media/ (updateMedia, deleteMedia, listMedia, getMedia), the route table entries for these 23 operations in shell/api/LocalApi.kt, tests under android/src/test/kotlin/kotobaya/android/shell/api/{cards,presets,notetypes,confusables,grammar,media}/
- Module(s) for CI: android

## Read
- Spec: Data model; Sync and offline
- Contracts: contracts/api/shell/SHELL.md rules L-2, L-3, L-4, L-6, L-9, **L-10**, section 4.3 (the six rows marked "C-31, L-10"), 10.21, 11; contracts/api/shell/ops.json; openapi.yaml for each operation below (`x-emits-ops`, description, request schema); contracts/sync/OPS.md for `card.*`, `fsrs_params.created/updated/deleted`, `note_type.*`, `confusable.confirmed/rejected`, `grammar.*`, `media.updated/deleted`, `deck.deleted`; SYNC.md 4, 7.1–7.3, 8.1 (card collision); parity scenarios `card-writes-local`, `preset-writes-local`, `note-type-writes-local`, `confusable-writes-local`, `grammar-writes-local`
- Decisions: ADR-050 (local-first for everything), ADR-035 (retired reading kind)
- Design: scratchpad `local-first-design.md` section 4.

## Do
Implement these as `local` handlers through `LocalWrites` (mint as the device + apply + `pending_op` in one `store.transaction`, then `SyncTriggers.localChange()`); remove their `ServerUpstream.forward` routes. Write the tests first, from the parity scenarios and SHELL.md 4.3.

Writes (17): `createCard`, `updateCard`, `deleteCard`; `createPreset`, `updatePreset`, `deletePreset`; `createNoteType`, `updateNoteType`, `deleteNoteType`; `setConfusable`; `createGrammarPoint`, `updateGrammarPoint`, `deleteGrammarPoint`, `putGrammarSpan`, `removeGrammarSpan`; `updateMedia`, `deleteMedia`.
Reads (6): `listConfusables`, `listWordConfusables`, `listGrammarPoints`, `getGrammarPoint`, `listMedia`, `getMedia` — from the replica, with the server's filters, keyset pagination and `include_deleted` semantics.

Per operation the op(s), the payload and the request checks are the server's (openapi description + OPS.md); the device applies every check it has the data for **before** minting, so it never mints an op the server rejects (`409 builtin_immutable`, `409 in_use` against the replica, `422` for a vocab note type without `expression` / `reading` / `meaning`, `422` at `/kind` for an unknown or retired kind, the op-size rule → `413`). Idempotency by L-4 (create with an existing `id` → `200`, nothing minted; delete of a deleted row → `204`, nothing minted). Documented differences to implement exactly as SHELL.md 4.3 says: `detection_rules` syntax is not checked on the device; `GrammarPoint.example_count` counts spans on the device; `Media.segments` is `[]` without an installed snapshot; `deleteMedia` mints `media.deleted` then `deck.deleted` for the live media deck and applies the same local cleanup as the pull path.

## Acceptance (each bullet → at least one named test)
- [ ] The five parity scenarios above pass in the shell harness (after the server ticket recorded their goldens), with an `Upstream` that fails the test when called.
- [ ] `updateMedia` / `deleteMedia` / `listMedia` / `getMedia` (no parity: a seed cannot create a media): tests over a replica seeded by applying `media.created` + `deck.created` through the pull-apply path — rename, delete (media and deck tombstoned, installed sentences gone, manual spans kept), second delete `204` with nothing minted, list and get before and after, `segments` empty without a snapshot.
- [ ] Op tests for every minted op type (CLAUDE.md "Tests that must exist"): validates against `contracts/sync/op.schema.json`; accepted by the reference server (`contracts/sync/tools/model.py` through the parity harness push); applying the minted ops in a different order on a second in-memory store converges (core fuzz harness).
- [ ] Idempotency tests: each create twice with the same `id`, each delete twice, `createCard` twice for one `(note, kind)` without an `id` → one set of ops.
- [ ] Offline test: all 17 writes succeed with `NetworkMonitor` offline and a `SyncCoordinator` whose cycle never finishes; the ops are in `pending_op`; the six reads show the change in the same test (read-your-writes).
- [ ] Refusal tests: `409 in_use`, `409 builtin_immutable` (note type, Default preset), `422` cases of the scenarios, tombstone → `410`, merge loser → survivor with `content-location`; in each nothing is minted.
- [ ] Route-table test: every operationId whose class in `ops.json` is `local` has a local handler and none of the 23 reaches `ServerUpstream`.
- [ ] `make check MODULE=android` green.

## Out of scope
`bulkCards` / `bulkScheduleCards` (LF-A4); search evaluation (LF-A5); the response cache and the `cached` reads that embed spans (LF-A2); the SPA keeping a just-put span on screen until `cache.updated` (LF-W tickets); `putParseOverride` / `deleteParseOverride` (stay `proxy`: rule L-10's table); `createDeck` with `type: list` (stays forwarded); recording the goldens (server ticket); any contract change — if an operation's server behaviour cannot be reproduced from the replica, return `CONTRACT_CHANGE_REQUEST` naming the SHELL.md 4.3 row.

## Notes from previous attempts
