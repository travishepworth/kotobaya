# HANDOFF F-3

STATUS: DONE
BRANCH: t/F-3-server-c8 (rebased on main)
CHECK: `make check MODULE=server` → green (293 tests)

## Changed (≤ 6 lines)
- `CoreCardCache`: a refusal writes the refused cache, and a refused cache always gets a full replay. `QueueLoader` skips refused cards.
- `durable_seq` is now reported as `min(row, hw)`. With backups disabled, the row is raised with `greatest()` in every tx that inserts `change` and at startup step 3.
- **Edited outside my owned paths, as the coordinator asked:** `server/Startup.kt`.
  - New `backupsDisabled` constructor param, needed to make the call.
  - `DurableSeq.raiseOnStartup(dsl, backupsDisabled)` at the end of `finalizeRestoreOrCreateEpoch`.
  - `SyncWiring.startup` passes it from the env. The test-helper workaround is removed.
- `RemintScenarioTest` runs the remint-scenarios (honours `after_seq`) and checks the SimDevice selection port. The fuzz now covers decks, items, statuses and Re-mint windows.

## Acceptance → tests
- refused cache → `CardCacheTest`, `ReviewApiTest.aCardHoldingTheRefusedCacheIsNeverQueued`
- durable_seq:
  - `RestoreTest.durableSeqNeverDecreasesAcrossABackupsDisabledPeriod`, through the real startup.
  - `RestoreTest.theProductionStartupRaisesDurableSeqWhenBackupsAreDisabled`
- scenarios → `RemintScenarioTest`
- fuzz → `ConvergenceFuzzTest.remintWindowsConverge`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The fuzz never reaches the 1a clock-branch window-row case (no grammar/confusable ops).
