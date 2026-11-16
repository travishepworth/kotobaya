# SHELL-L1 — Local ops: identity, meta, settings, presets, note types, client errors

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-A2, SHELL-P2
- Branch: t/SHELL-L1-local-identity
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/identity/**, android/src/test/kotlin/kotobaya/android/shell/api/identity/**
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 3, 5
- Contracts: openapi.yaml (getPrincipal, getMeta, listSettings, updateSettings, listPresets, getPreset, listNoteTypes, getNoteType, reportClientErrors), contracts/db/SCHEMA.md 6, contracts/sync/OPS.md (settings.set)
- Decisions: ADR-034, 035, 036

## Do
- `getPrincipal`: device principal from the stored pairing (design section 3). `getMeta`: `api_version`, `core_version`, `server_device_id`,
  `log_epoch`, `high_water_seq` = local pull cursor, `server_time` = device now, `dict_release` = installed release or null, `limits` from `MetaLimits` cache.
- `listSettings` from the replica; `updateSettings` mints one `settings.set` per present key through `LocalWrites` (absent = unchanged; same value still writes).
  Applying `cards.reading_enabled` re-derives vocab word statuses exactly as the pull-apply path does (ADR-035) — reuse, do not re-implement.
- `listPresets`, `getPreset`, `listNoteTypes`, `getNoteType`: read-only views of replica rows.
- `reportClientErrors`: enqueue in `ClientErrorQueue`; answer as the server does (limits included).

## Acceptance (each bullet → at least one named test)
- [ ] Parity: settings/presets/note-types read scenarios equal the goldens
- [ ] `UpdateSettingsOpTest`: minted ops validate against op.schema.json; replaying the same request mints new ops but converges to the same state; ops applied in reverse order on a second store converge (LWW by HLC)
- [ ] `ReadingEnabledTest`: toggling the key locally yields the same word statuses as pulling the same op
- [ ] `PrincipalTest`: unpaired → 401; paired → scopes exactly read, write, review

## Out of scope
Preset/note-type writes (proxy).

## Notes from previous attempts

## Orchestrator note (from SHELL-A2)
Also owned: delete your ops' lines from PENDING in the android RouteTableTest (shell/api tests). Routes are top-level vals in shell/api/<group>/Routes.kt; get deps via call.services.
