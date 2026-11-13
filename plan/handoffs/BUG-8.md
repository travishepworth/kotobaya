# HANDOFF BUG-8

STATUS: DONE
BRANCH: t/BUG-8-android-sync-perf
CHECK: `make check MODULE=core|android|acceptance` → green

## Changed (≤ 6 lines)
- core SyncSchemas: no-message mode for if/oneOf/anyOf probes; `$ref`s resolved once.
- core SyncEngine: pull pages applied in 200-op transactions (each one is a smaller page), `PullProgress` hook; `OpApplier.batch`.
- core DeviceOpApplier: per-chunk row cache; full replays and word_status re-derives run once at chunk end, same transaction.
- android store: `read{}` has no transaction or PRAGMA (WAL reader connection), refuses writes; driver opens on first use.
- android: main-thread DB access removed (Takoboto, Devices, Dict, Search, Word, due trigger); debug StrictMode; DbThreadGuard.
- "Syncing N of M" chip (i18n string).

## Acceptance → tests
- worked examples + fuzz → existing core/acceptance suites; BatchApplyEquivalenceTest
- 100k pull < 2 min → PullBenchTest: **55.8 s** (on disk, FULL sync; baseline ≈ 70 s on tmpfs)
- validation equivalence → SchemaEquivalenceTest (LegacySyncSchemas = old code verbatim)
- no main-thread DB → NoDbOnMainThreadTest
- WAL reads → WalReaderTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Replay deferral is per chunk, not per cycle: 9.3 requires the triggering op's transaction.
- StrictMode cannot see SQLite under Robolectric; the guard is the detector. Server not run.
- Batched word_status.updated_at can differ (not compared, SYNC.md 11).
