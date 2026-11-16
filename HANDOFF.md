# HANDOFF SHELL-L1

STATUS: DONE
BRANCH: t/SHELL-L1-local-identity
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `shell/api/identity/`: Routes (9 ops), Views (replica rows → Preset/NoteType/Setting JSON), ShellIdentity (+ `AppShellIdentity`), ClientErrorIntake (server-identical validation, 12 req / 120 items per 60 s, 413/422/429)
- `updateSettings` = one `settings.set` per key via `LocalWrites`; reading_enabled re-derivation is the applier's own path
- Outside owned paths (additive): `ShellModule.kt` registers ShellIdentity + ClientErrorIntake; RouteTableTest PENDING lines; ParityDevice services

## Acceptance → tests
- Parity read scenarios → no scenario covers only my ops; the two touching `updateSettings` (queue-reviews-unlimited, reading-retired-reenabled) stay pending on getReviewQueue/createNote (L2/L5). Reads covered by `IdentityReadsTest`
- UpdateSettingsOpTest, ReadingEnabledTest, PrincipalTest → as named

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `token_id`, device name and `server_version` are read from `sync_state` keys `pairing.token_id`, `pairing.device_name`, `meta.server_version`, which nothing writes yet (PairingRepository / MetaLimitsStore are not mine). Fallbacks: token_id = device id, name = Build.MODEL, "unknown". FOLLOWUP: write them at pair / after `/meta`.
- Presets/note types honor `include_deleted` per the contract (server ignores it for presets). Queued web reports flush as `client: android`; the request's `dropped` is ignored (queue has no API for it).
