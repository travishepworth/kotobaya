# HANDOFF T212b

STATUS: DONE
BRANCH: t/T212b-settings-server-r
CHECK: `make check MODULE=server` -> green (200+ tests).

## Changed
- `server/.../kotobaya/settings/`: `SettingsModule(db, opApply).handlers` (listSettings, updateSettings, listPresets, createPreset, getPreset, updatePreset, deletePreset, optimizePreset); not wired in Main.kt.
- Settings: one `settings.set` per key (ascending), null resets (row kept). Well-known keys type-checked per SCHEMA 6.1 (int via binary64 test, bool exact, string); 422 at `/values/<key>`; unknown keys stored as any JSON.
- Presets: API bounds validated, then `fsrs_params.created/updated/deleted` via OpApply; Default undeletable (409), deleted -> 410, idempotent create/delete. Optimize only enqueues `fsrs-optimize` (202/200 dedupe).

## Acceptance -> tests
- `server/src/test/.../settings/SettingsApiTest.kt`: round trip, int/bool/string/narrow keys, unknown keys, preset ops, optimize, `write` scope.

## New dependencies
- none

## For the reviewer
- OpApply canonicalises numbers, so `1.0` is stored as `1` (same value, valid by 6.1), not "as sent" literally.
