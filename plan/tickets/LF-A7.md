# LF-A7 — Shell: startup and first-sync status

- Phase: P10 local-first · Model: sonnet · Risk: low
- Deps: C-31, LF-A1
- Branch: t/LF-A7-first-sync
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/sync/SyncStatusRepository.kt, shell/api/device/ (sync status view), shell/web/BridgeCore.kt (handler timing log only), tests beside them
- Module(s) for CI: android

## Read
- Spec: Sync and offline
- Contracts: shell.yaml `ShellSyncStatus.first_sync`; SHELL.md 2.5, 9.1
- Decisions: ADR-050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Expose `first_sync {done, applied, total}` from the engine's progress (per committed sub-page). App open triggers a background cycle and never gates a request on it. Log (ScreenLog, no payloads) any bridge request whose handler took > 250 ms with its operationId, so the next stall is diagnosable on the device.

## Acceptance (each bullet → at least one named test)
- [ ] Test: fresh pairing → `first_sync.done = false` with rising `applied`; after the complete pull `done = true` and it never goes back to false (also after a failed later cycle).
- [ ] Test: an interrupted first sync resumes from the cursor and still ends `done = true`.
- [ ] Test: slow-handler log line carries operationId and duration, no body.
- [ ] `make check MODULE=android` green.

## Out of scope
The Setting-up screen (LF-W3).

## Notes from previous attempts
