# HANDOFF T303b

STATUS: DONE
BRANCH: t/T303b-sync-client-apply
CHECK: `make check MODULE=core` → green (jvm + js)

## Changed (≤ 6 lines)
- `sync/client/apply/`: `DeviceOpApplier` (all op types, 7–9), `AppendOnlyReviewLog` (7.9 guard), `DeviceCardCache` + `DeviceDiagnostics` (9.4), `StartupReplay` (10.5), `MergeReaders` (8.5), `LearningTables`/`InMemoryLearningTables`/`BuiltIns`
- `sync/client/rebuild/`: `Rebuild` (10.3 steps 0–3, restartable), `DeviceSyncCycle` (startup replay → cycle → `resetLog` → rebuild → 6.2 auto-retry, cap 10)

## Acceptance → tests
- Examples, idempotency, out-of-order per op type → `ExampleOpsApplyTest`, `ScenarioApplyTest`
- review_log append-only, earliest undo → `ReviewLogAppendOnlyTest`
- Refused cache (page commits, diagnostics, undo/preset heals) → `RefusedCacheTest`
- 12.3 merge, jpdb kanji dedupe, readers → `MergeTest`
- Startup replay once/kill/precedes fold → `StartupReplayTest`
- 12.5, 12.5.1, killed rebuild, auto-retry → `RebuildTest`
- Convergence fuzz, 112 seeds over all scenarios → `DeviceConvergenceFuzzTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `word_status.auto_status` re-derivation (OPS.md) is not done: it is local-only and excluded from §11. Follow-up for T303c/T304.
- The fuzz arms T303a's "replayed page" fault only while no rebuild is pending. After a wipe, a stale page for an older `since` would skip seqs, and the engine cannot see the gap.
