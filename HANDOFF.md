# HANDOFF F-3

STATUS: DONE
BRANCH: t/F-3-server-c8
CHECK: `make check MODULE=server` → green (292 tests)

## Changed (≤ 6 lines)
- `CoreCardCache`: a refusal writes the refused cache, and a refused cache always gets a full replay. `QueueLoader` skips refused cards.
- `durable_seq` is now always reported as `min(row, hw)`. With backups disabled, `OpTx` raises the row with `greatest()` in every tx that inserts `change`. `DurableSeq.raiseOnStartup` handles 6.3 step 3.
- New `RemintScenarioTest` runs the 3 remint-scenarios and honours `after_seq`. It also checks that SimDevice's selection and 6a payloads match the scenario.
- `SimDevice.remintSelect` ports `model.remint_select`. `RefReplica` and `ServerProjection` gain decks, items, word statuses.

## Acceptance → tests
- refused cache → `CardCacheTest.aReplaySetTheEngineRefusesWritesTheRefusedCache…`, `ReviewApiTest.aCardHoldingTheRefusedCacheIsNeverQueued`
- durable_seq toggle → `RestoreTest.durableSeqNeverDecreasesAcrossABackupsDisabledPeriod`
- scenarios → `RemintScenarioTest`
- fuzz → `ConvergenceFuzzTest.remintWindowsConverge` (asserts every listed case is reached)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- FOLLOW-UP outside my paths: `server/Startup.kt` must call `DurableSeq.raiseOnStartup(dsl, backupsDisabled)` at the end of `finalizeRestoreOrCreateEpoch`. The test helper does it for now. Until then the value can lag after an enabled→disabled restart, but it never drops.
- The fuzz never reaches the 1a clock-branch window-row case (no grammar/confusable ops).
