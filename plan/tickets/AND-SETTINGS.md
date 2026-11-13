# AND-SETTINGS — Android Settings tab

- Phase: P8 · Model: sonnet · Risk: med
- Deps: none
- Branch: t/AND-SETTINGS-settings-tab
- Owned paths (only edit these): `android/src/main/kotlin/kotobaya/android/settings/ui/` (new), `android/src/main/res/values/strings_settings.xml`, `android/src/test/kotlin/kotobaya/android/settings/ui/`, one line in `android/src/main/kotlin/kotobaya/android/di/FeatureModules.kt`, additive lines in `android/src/main/kotlin/kotobaya/android/i18n/T.kt`, additive methods in `android/src/main/kotlin/kotobaya/android/net/ApiClient.kt`. Small additive public accessors (no behaviour change) in `settings/SettingsStore.kt`, `notify/NotifyPrefs.kt`, `media/MediaSettings.kt`, `ankiapi/takoboto/TakobotoFeature.kt`, `ankiconnect/AnkiConnectFeature.kt`, `pairing/`, `sync/SyncFeature.kt` only if a setting has no public API; list each in HANDOFF.
- Module(s) for CI: android

## Read
- Spec: settings, sync, notifications, TTS, Takoboto/AnkiConnect sections of docs/SPEC.md
- Contracts: contracts/sync settings ops (settings.set / FSRS preset ops), contracts/db settings keys (SCHEMA 6.1), contracts/api health/version
- Existing code: `android/src/main/kotlin/kotobaya/android/nav/AppNav.kt` (Routes.SETTINGS="settings", SettingsSection in `di/FeatureModules.kt`), `settings/SettingsStore.kt`, `sync/SyncFeature.kt` (`syncNow()`), `sync/ui/SyncUiFeature.kt` + `SyncContent.kt`, `pairing/PairingFeature.kt`, `pairing/TokenStore.kt`, `notify/NotifyPrefs.kt`, `notify/NotifyFeature.kt`, `media/MediaSettings.kt`, `ankiapi/takoboto/TakobotoFeature.kt`, `ankiconnect/AnkiConnectFeature.kt`, core OpMinter

## Do
A Settings tab at route `settings`, sections:
1. **Server**: base URL, paired device name, connection status; **Re-pair** (opens the existing pairing flow; clears the token only after confirmation).
2. **Sync**: last sync, pending/rejected counts, **Sync now** (`syncNow()`), link to the existing Sync screen.
3. **Study**: daily new/review limits and day-boundary (rollover hour) — read from the replica's settings; changes **mint settings ops** via OpMinter (no direct table writes); validate ranges per SCHEMA 6.1.
4. **Notifications**: the NotifyPrefs toggles/times; reschedule alarms on change (reuse NotifyFeature).
5. **Audio / TTS**: MediaSettings (auto-play, prefetch, storage budget, TTS on/off if present).
6. **Integrations**: Takoboto stand-in and AnkiConnect server toggles (API key display/regenerate if the feature exposes it).
7. **About**: app version, flavor, server version (health), licences/attribution link if one exists.
Use existing SettingsSection entries if features already contribute sections; don't duplicate them.

## Acceptance
- [ ] Test: changing the daily new limit and the day boundary mints exactly one settings op each with the correct key and value (and nothing is written to replica tables directly); an out-of-range value mints nothing.
- [ ] The Settings tab replaces the placeholder; Sync now triggers syncNow (no UI tests required).
- [ ] `make check MODULE=android` green.

## Out of scope
Search and Decks tabs, server settings UI changes, FSRS preset editor (beyond what exists), new contracts.

## Rules (all three P8 Android tickets)
- Register the tab by adding a FeatureEntry whose NavRoute uses the shell route string (`Routes.X`); it replaces the PlaceholderScreen via allRoutes. Edits outside your package: **one line** in `di/FeatureModules.kt` featureEntries (and only if strictly needed, one line in `nav/AppNav.kt`). Two sibling tickets add lines there too: expect trivial rebases.
- Every user-visible string goes through i18n (`i18n/T.kt`, your own `res/values/strings_<feature>.xml`). Additive lines in `i18n/T.kt` are allowed.
- Learning-data mutations only via sync ops (core OpMinter, the same path ReviewFeature/notes use). No direct writes to replica tables (CLAUDE.md rule 8).
- Offline first: read the local replica / on-device data where it exists; fall back to the API through `net/ApiClient.kt` (hand-written OkHttp; add methods there only additively, for endpoints already in `contracts/api/openapi.yaml`). No new server endpoints, no contract edits; if you need one, return CONTRACT_CHANGE_REQUEST.
- No new dependencies without a reason in HANDOFF.
- Gradle: run checks as `flock /tmp/kotobaya-gradle.lock make check MODULE=android` (one Gradle build at a time on this machine). Check the rc directly; never pipe through tail.
