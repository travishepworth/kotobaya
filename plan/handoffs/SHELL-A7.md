# HANDOFF SHELL-A7 — DONE

`make check MODULE=android` green. New tests: DeviceSettingsRoutesTest (14), DeviceEventSourcesTest (4).

## Built
- 11 routes in `shell/api/device/DeviceSettingsRoutes.kt` over seams in `DeviceSettingsOps.kt` (Dict/Storage/Notify/Push/AnkiConnect Ops, `Core*` over the existing Settings ports). Fakes in tests; 422s per contract.
- Dict: scope choice stored, one install at a time, `last_result`, `server_release_id`, `gap_fill` counts. `shell.dict` via `DictEvents` (<=2/s, final state delivered, no repeats).
- Notifications: permission via `NotificationPermissionLauncher` (25 s limit, API<33 answers at once). Capabilities list extended.
- ShellEvents wired: `ShellEventHub` (ShellModule.kt) is the sink; sources: status flow, local write, NetworkMonitor.onConnectivity, EventingAnkiStore (Takoboto), sync outcome.

## Edits outside owned paths (one-liners, please accept)
- `MainActivity`: `NotificationPermissionLauncher.register(this)`.
- `SyncFeature`: `ShellEventHub.onSyncOutcome(outcome)` in the coordinator's onOutcome.
- `TakobotoFeature` (AnkiStore wrap), `NetworkMonitor` (hook), `ShellWiring`, `ShellModule`.

## Notes
- No PENDING lines to delete (these are `shell` ops).
- Gap-fill count changes are announced after each sync outcome, not when a later gap-fill run ends (follow-up).
- Release artifact sizes for the scope choice: SPA reads `getDictRelease`; not duplicated here.
- On-device AnkiConnect adds emit no `note.created` (it uses TakobotoAnkiStore directly).
