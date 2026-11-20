# LF-A1 — Shell: materialize without waiting for sync; debounced push; short pull slices

- Phase: P10 local-first · Model: opus · Risk: high
- Deps: C-31
- Branch: t/LF-A1-materialize-sync
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/queue/LocalQueueLoader.kt (`materializeAllowed` and callers), shell/api/review/QueueRoute.kt (materializer wiring only), android/src/main/kotlin/kotobaya/android/sync/ (SyncFeature.kt, SyncWork.kt, SqlDelightDeviceStore.kt busy wait), tests under android/src/test/kotlin/kotobaya/android/{queue,sync}/
- Module(s) for CI: android

## Read
- Spec: Sync and offline
- Contracts: contracts/sync/SYNC.md 6.1, 8.1; SHELL.md 4.1 L-2, L-9, 4.3; contracts/core/README.md C-27
- Decisions: ADR-032, 050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Root cause RC-1/RC-3 of the design.
1. `materializeAllowed` = `!paired || (lastSyncAt != null && !rebuildPending)`; delete the `syncRunning` / `failures` inputs and their plumbing. Idempotency per `entry_seq` stays in the one store transaction.
2. `SyncTriggers.localChange()`: debounce 2 s trailing, 10 s max, still survives process death (WorkManager request stays; coalesce in-process).
3. Pull sub-pages ≤ 50 ops with a yield between them (constructor parameter already exists: `pullChunkOps`); a local write waits at most 5 s for the write connection, then `503 shell_rebuilding`-free retry inside `LocalWrites` (no problem surfaced before 5 s).
Write the tests first.

## Acceptance (each bullet → at least one named test)
- [ ] Test: with `coordinator.running = true` and `consecutiveFailures = 3`, `getReviewQueue` over a replica whose only cards are deck entries returns items and `to_materialize = 0`.
- [ ] Test: before the first complete pull (`lastSyncAt == null`) nothing is materialized and the entries are counted, as today.
- [ ] Test: device and server materialize the same entry → after push/pull one live note, all reviews kept (SYNC.md 8.1 scenario, convergence).
- [ ] Test: 30 local answers in 20 s start at most 4 cycles; the last answer is pushed within 12 s of the tap when online.
- [ ] Test: a local answer issued while a 500-op page is being applied commits before the page finishes.
- [ ] `make check MODULE=android` green.

## Out of scope
Upstream changes (LF-A2), the SPA.

## Notes from previous attempts
