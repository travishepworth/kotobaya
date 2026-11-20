# LF-A8b — Shell: confusables, grammar and media writes local (rest of LF-A8)

- Phase: P10 local-first · Model: opus · Risk: high
- Deps: LF-A8
- Branch: t/LF-A8b
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/confusables/, shell/api/grammar/, shell/api/media/ (updateMedia, deleteMedia, listMedia, getMedia), the route table entries for these ops in shell/api/LocalApi.kt (LocalRoutes.all), tests under android/src/test/kotlin/kotobaya/android/shell/api/{confusables,grammar,media}/
- Module(s) for CI: android

## Read
- plan/tickets/LF-A8.md (full original scope + acceptance), plan/handoffs/LF-A8.md (what part 1 did, LocalWrites + CardMints patterns)
- contracts/api/shell/SHELL.md L-10, ops.json; contracts/sync/; parity scenarios confusable-writes-local, grammar-writes-local

## Do
The remaining 14 ops from LF-A8: confusables (3), grammar (7), media (4), plus the media pull-apply tests. Use the same LocalWrites pattern and the same acceptance as LF-A8 (parity scenario without upstream, op schema, convergence in any order, idempotency, refusals mint nothing, offline + read-your-writes).

## Acceptance
- [ ] As in LF-A8, for these 14 ops. RouteTableTest.everyLocalOperationIsRegisteredOrPending then lists only what LF-GOLD/LF-X1 still own (ideally nothing).

## Out of scope
Recording goldens (LF-GOLD); STILL_FORWARDS cleanup (LF-X1).

## Orchestrator notes (LF-A8 part 1 review minors — do these too)
- Add one convergence test that interleaves a REMOTE op (SYNC.md 7.3): e.g. remote `fsrs_params.deleted` arriving after a local `card.preset_set`, remote note created after a local `note_type.deleted`. Part 1's convergence test only reorders the device's own ops.
- NoteTypeWrites.kt:46: read `BuiltIns` directly instead of building a throwaway `InMemoryLearningTables.seeded()` store, or add a test asserting its keys equal the server's `OpRules.BUILTIN_SEEDS` (if the server list isn't reachable from android tests, compare against a checked-in fixture).
- PresetWrites.kt:62: reject non-integral literals (30.0) for integer fields like the server (422), if JsonNumber can distinguish them.
- model.py replay: waived unless a parity push harness exists (goldens via LF-GOLD cover payloads).
