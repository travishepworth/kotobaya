# SHELL-A7 — Shell device ops II: dictionary install, storage, notifications, push, AnkiConnect

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-A6
- Branch: t/SHELL-A7-device-ops-2
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/device/Device*.kt, android/src/main/kotlin/kotobaya/android/shell/api/device/Routes.kt, android/src/test/kotlin/kotobaya/android/shell/api/device/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 3, 5
- Contracts: contracts/api/shell/shell.yaml
- Decisions: ADR-036

## Do
Thin handlers over existing features, replacing their Compose settings sections:
`getDictInstall`/`startDictInstall` (`dict/DictInstaller`, `DictFeature`; progress as `shell.dict` events), `getStorage`/`updateStorage`
(`media/MediaFeature`, `PrefetchPlanner`, `BlobStore` breakdown and budget), `getNotificationState`/`requestNotificationPermission`/`updateNotificationPrefs`
(`notify/NotifyPrefs`; the permission request goes through an activity-result launcher registered by MainActivity),
`getPushState`/`updatePushState` (`push/PushRegistrar`), `getAnkiConnectState`/`updateAnkiConnectState` (`ankiconnect/AnkiConnectFeature`).

## Acceptance (each bullet → at least one named test)
- [ ] One route test per operation against a fake of the feature (state round-trips; invalid body → 422)
- [ ] `startDictInstall` twice while running starts one install
- [ ] `requestNotificationPermission` on API < 33 answers granted without launching anything

## Out of scope
The SPA screens (W2).

## Notes from previous attempts

## Orchestrator note (2026-11-16)
Dictionary row: show the installed scope with a choice of top60k / all (size from getDictRelease artifacts) and the SHELL-L9 gap-fill counts (cached, still missing).

## Orchestrator note (from SHELL-A2)
Also owned: delete your ops' lines from PENDING in the android RouteTableTest (shell/api tests). Routes are top-level vals in shell/api/<group>/Routes.kt; get deps via call.services.

## Orchestrator note (from SHELL-A6) — also wire ShellEvents (owned here: ShellModule, ShellWiring, LocalWrites after-commit hook, NetworkMonitor, AnkiStoreHolder/TakobotoFeature)
- `ShellEvents(scope, bridge::send, ...)`; `watch(SyncStatusRepository.status)`; `onSyncOutcome` from the SyncCoordinator onOutcome; `onLocalWrite` in LocalWrites after commit; `onConnectivity` from NetworkMonitor; wrap the AnkiStore held by AnkiStoreHolder in `EventingAnkiStore(store, store::add, ...)` so Takoboto adds emit note.created.
- Test: an event reaches a fake bridge for each source.
