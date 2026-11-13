# HANDOFF F-6

STATUS: DONE
BRANCH: t/F-6-sync-db-first-use
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- android/.../sync/DurableDriver.kt: `open` forces the lazy AndroidSqliteDriver open (schema create) with a plain read, outside any transaction.
- android/.../sync/SqlDelightDeviceStore.kt: `PRAGMA synchronous = FULL` re-asserted before each outermost transaction.
- New test FirstUseTransactionTest (Robolectric).

## Acceptance → tests
- fresh DB, first op is store.transaction + mint, persists after reopen → FirstUseTransactionTest
- make check android → green

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A `PRAGMA synchronous` query alone did NOT force schema creation under Robolectric; a `SELECT ... FROM sqlite_master` does (cause not fully understood).
- T305c unmerged: its workaround query is untouched and now harmless; delete it after merge.
