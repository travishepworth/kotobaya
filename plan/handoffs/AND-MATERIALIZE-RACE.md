# HANDOFF AND-MATERIALIZE-RACE

STATUS: DONE
BRANCH: t/AND-MATERIALIZE-RACE
CHECK: `make check MODULE=android` → green

**Verdict: the failure came from the test harness. Production has no race.** Evidence: AndroidSqliteDriver 2.0.2 (bytecode) begins with `beginTransactionNonExclusive` (BEGIN IMMEDIATE) on the pool's one primary connection, so writers run one at a time before their first read. The JDBC `IN_MEMORY` harness uses `StaticConnectionManager`, which keeps a plain shared `transaction` field. A second thread's transaction joins the first, so there is no isolation. Shell fetches get a fresh `LoadGate` each, so only the DB serializes them. That has now been measured: 50 of 50 iterations had the second writer parked in `SQLiteConnectionPool.waitForConnection`.

## Changed
- New `MaterializeRaceTest`: runs on the production driver (WAL file) and holds each writer between its duplicate check and its mint.
- `ParityDevice`: takes optional `driver` and `noteIds` parameters.
- Removed the shared-connection concurrent case from `QueueMaterializeIdempotentTest`.
- `SqlDelightDeviceStore`: added a KDoc note on isolation. No code change.

## Acceptance → tests
- Two fetches, 50 iterations → `twoConcurrentFetchesOnTheProductionDriverCreateOneNotePerDrawnEntry`
- Takoboto add vs materialize, both orders → `aTakobotoAddRacingAQueueMaterializeOnTheProductionDriverCreatesOneNote`
- Fails when no transaction is used → `theSameRaceDuplicatesWhenCheckAndMintAreNotOneTransaction`
- Whole class ran green 8 times.

## New dependencies
- none

## For the reviewer
- The gate checks thread stack frames for `SQLiteConnectionPool.waitForConnection`. This depends on Robolectric running native SQLite.
