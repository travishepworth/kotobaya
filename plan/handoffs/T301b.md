# HANDOFF T301b

STATUS: DONE
BRANCH: t/T301b-pairing
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `net/`: OkHttp `ApiClient` (typed `ApiError`: Offline, Unreachable, Unauthorized, Forbidden, InvalidPairingCode, Validation, RateLimited, Server, BadBody), `ServerReachability.probe()`, `MetaLimitsStore` (`meta.*` in sync_state; `refresh(client)` for T303c).
- `pairing/`: `PairingUri`, `PairingRepository` (device_id in sync_state, hlc_regressed -> new id + retry, `unpair` clears token only), `TokenStore` (AES-GCM, Keystore key), Settings > Devices section + QR/manual UI.
- Token now lives only in `TokenStore`; `SettingsStore.deviceToken` is unused (cleared on pair).
- Flags: my `MetaLimits.sq`; one line in `FeatureModules.kt`; also edited `build.gradle.kts`, `AndroidManifest.xml` (INTERNET, ACCESS_NETWORK_STATE), `strings.xml`.

## Acceptance → tests
- Pairing writes id/token/URL; distinct typed errors (expired code, 422 kind, 401, 403, offline, unreachable) → `PairingRepositoryTest`
- Token not in plain prefs → `tokenIsNotReadableFromPlainPrefs`
- URI parser → `PairingUriTest`; MetaLimits restart/defaults → `MetaLimitsStoreTest`

## New dependencies
- okhttp 4.12.0 + mockwebserver (tests): the generated Kotlin client needs Kotlin 2.4/Ktor 3.5, incompatible with this module's 2.1.10
- play-services-code-scanner 16.1.0 (ML Kit QR, no CAMERA permission; needs Play services)

## For the reviewer (≤ 3 lines)
- Settings screen is still a placeholder, so the Devices section is registered but not yet shown; wire it when Settings renders `settingsSections`.
- Own AES-GCM over Keystore instead of androidx.security-crypto (deprecated, untestable in Robolectric).
- hlc_regressed retry mints a new id; resetting replica/outbox state is T303's job.
