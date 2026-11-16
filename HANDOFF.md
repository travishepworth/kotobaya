# HANDOFF SHELL-A6

STATUS: DONE
BRANCH: t/SHELL-A6-device-ops
CHECK: `make check MODULE=android` → green

## Changed
- `shell/api/device/Routes.kt` + `CoreDeviceOps.kt`/`CoreDeviceViews.kt`: getShellInfo, pairShell, unpairShell (409 shell_pending_ops), getSyncStatus, syncNow (202 / 503 shell_offline). Handlers use a `DeviceOps` service if registered, else build `CoreDeviceOps.from(services)` from Koin (no ShellModule edit needed).
- `shell/events/ShellEvents.kt`: sync.changed, shell.sync (coalesced 1/s, last delivered), note.created, shell.connectivity; `EventingAnkiStore` decorator.
- `PairingRepository` (name param, `retryOnHlcRegressed`, writes `pairing.token_id`/`pairing.device_name`); `MetaLimitsStore.refresh` writes `meta.server_version`.

## Acceptance → tests
- PairRouteTest, UnpairRouteTest, SyncRoutesTest, ShellEventsTest (all in `shell/api/device/`).

## New dependencies
- none

## For the reviewer
- ShellEvents is NOT wired (ShellModule/ShellWiring/LocalWrites hook/NetworkMonitor/TakobotoFeature not owned): needs `ShellEvents(scope, bridge::send, ...)`, `watch(SyncStatusRepository.status)`, `onSyncOutcome` in the coordinator's onOutcome, `onLocalWrite` in LocalWrites' after-commit, `onConnectivity` from NetworkMonitor, and `AnkiStore` held by AnkiStoreHolder wrapped in `EventingAnkiStore(store, store::add, ...)`. Follow-up ticket needed.
- Capabilities listed: sync, pairing, takoboto only (A7 adds the rest). `web_version` read from `assets/web/shell-manifest.json`.
- pairShell does not mint a new device id on hlc_regressed (contract); the old launcher still does.
