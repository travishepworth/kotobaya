# HANDOFF T303c

STATUS: DONE
BRANCH: t/T303c-android-sync
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `android/.../sync/`: `SqlDelightDeviceStore` + `SqlLearningTables`, `DurableDriver` (WAL, synchronous=FULL), `HttpSyncTransport`, `SyncCoordinator`, `SyncWork`/`SyncWorker`, `NetworkMonitor`, `SyncStatusRepository`, `RejectedOpsStore`, `SyncFeature` (Koin, bootstrap)
- FLAG: one line in `di/FeatureModules.kt` (`syncFeature`, last). Outside my paths: `android/build.gradle.kts` gets a `work-testing` test dependency.
- Koin: the sync module replaces `AppDatabase` with one built on the durable driver (AppDatabase doesn't expose its driver, and `db/` isn't my path). It also replaces the `PushSyncTrigger` binding.

## Acceptance → tests
- T303b apply/replay/rebuild/log-reset on SQLite → `SqlStoreApplyTest`
- kill-mid-push, mint atomicity → `DurabilityTest`
- Coordinator → `SyncCoordinatorTest`
- WorkManager → `SyncWorkTest`

## New dependencies
- androidx.work:work-testing:2.10.0 (test only). The ticket requires it.

## For the reviewer (≤ 3 lines)
- Follow-up: open the driver in `db/Databases.kt`. Expedited work's notification uses `app_name`; T303e should add a "Syncing" string.
- For T304b: `SyncTriggers.reviewSessionEnded()`/`localChange()`, `OpMinter` from Koin.
- `/sync/*` has no `Kotobaya-Seq` header.
