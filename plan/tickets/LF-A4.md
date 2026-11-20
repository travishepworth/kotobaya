# LF-A4 — Shell: all bulkCards actions and bulkScheduleCards local

- Phase: P10 local-first · Model: sonnet · Risk: med
- Deps: C-31
- Branch: t/LF-A4-bulk-local
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/actions/, tests under android/src/test/kotlin/kotobaya/android/shell/api/actions/, parity inputs
- Module(s) for CI: android

## Read
- Spec: Data model
- Contracts: openapi.yaml `bulkCards`, `bulkScheduleCards` (`x-emits-ops`); contracts/sync/OPS.md for each op; SHELL.md L-2…L-4, 4.3
- Decisions: ADR-050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
`set_preset` (`card.preset_set`), `add_tags` / `remove_tags` (`note.updated`), `move` (`deck.item_put` / `deck.item_removed`), `delete` (`card.deleted` / `note.deleted` as the server chooses) and, if C-31 classified it local, `bulkScheduleCards` (`review.logged` manual). All through `LocalWrites` in one transaction, all-or-nothing, `dry_run` identical to the server. Remove the `Fallthrough` at `BulkRoute.kt:261`.

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenario per action (and per selector: `card_ids`, `query`), incl. `dry_run`, empty selection, duplicate ids, a merge loser id, a tombstoned card.
- [ ] Idempotency test: the same request twice mints nothing the second time where the server documents a no-op.
- [ ] Op-apply tests: minted ops validate against the op schemas and are accepted by a server push in the parity harness.
- [ ] `make check MODULE=android` green.

## Out of scope
Query evaluation (LF-A5).

## Notes from previous attempts
