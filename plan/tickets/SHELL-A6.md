# SHELL-A6 — Shell device ops I: info, sync status, sync now, events, pairing

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-A2
- Branch: t/SHELL-A6-device-ops-1
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/device/Routes.kt, android/src/main/kotlin/kotobaya/android/shell/api/device/Core*.kt, android/src/main/kotlin/kotobaya/android/shell/events/**, android/src/test/kotlin/kotobaya/android/shell/api/device/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 3, 5
- Contracts: contracts/api/shell/shell.yaml, SHELL.md (events, auth)
- Decisions: ADR-036

## Do
- `getShellInfo` (works unpaired), `pairShell` (body: server URL + code or a `kotobaya://pair` URI; `PairingUri` + `PairingRepository.pair`),
  `unpairShell` (`PairingRepository.unpair`; refuses with `409` while pending ops exist unless `force=true`),
  `getSyncStatus` (`SyncStatusRepository.current()`: last sync, pending count, stale flag, running, progress), `syncNow` (`SyncTriggers.syncNow()`).
- `ShellEvents`: emits to the bridge `sync.changed` after every sync cycle that applied ops and after every local write commit,
  `note.created` when `AnkiProvider`/AnkiConnect create a note, `shell.connectivity` on `NetworkMonitor` changes, `shell.sync` on status changes.

## Acceptance (each bullet → at least one named test)
- [ ] `PairRouteTest`: valid code pairs and stores the token; bad code returns the server problem; token never appears in any response
- [ ] `UnpairRouteTest`: pending ops → 409 without `force`; nothing is deleted on refusal
- [ ] `SyncRoutesTest`: status mirrors `SyncStatusRepository`; `syncNow` unreachable → 503 shell_offline
- [ ] `ShellEventsTest`: a pull that applies ops emits exactly one `sync.changed`; an AnkiProvider insert emits `note.created`

## Out of scope
Dictionary install, storage, notifications, push, AnkiConnect settings (A7).

## Notes from previous attempts

## Orchestrator note (from SHELL-A2)
Also owned: delete your ops' lines from PENDING in the android RouteTableTest (shell/api tests). Routes are top-level vals in shell/api/<group>/Routes.kt; get deps via call.services.

## Orchestrator note (from SHELL-L1)
Write sync_state keys `pairing.token_id`, `pairing.device_name` at pairing and `meta.server_version` after /meta (ShellIdentity reads them; nothing writes them yet). Owned: PairingRepository / MetaLimitsStore for those writes.
