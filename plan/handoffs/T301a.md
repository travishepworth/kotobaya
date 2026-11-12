# HANDOFF T301a

STATUS: DONE
BRANCH: t/T301a-android-shell
CHECK: `make check MODULE=android` → green (6 tests)

## Changed (≤ 6 lines)
- Compose/Material3 single-activity shell: `nav/AppNav.kt` (bottom bar: Home, Review, Search, Decks, Settings), Home with status-chip slot.
- `di/FeatureModules.kt`: `FeatureEntry`, `FeatureRegistry`, `featureEntries` list. Later tickets add ONE line to `featureEntries` (feature routes with a shell route's path replace its placeholder).
- SQLDelight 2.0.2 compiles `contracts/db/sqldelight/{app,ref}` in place (no copies). `db/Databases.kt` opens AppDatabase; `DictionaryProvider` seam (empty).
- Extra queries in `android/src/main/sqldelight/app/.../SyncOps.sq` (the contract .sq files define no queries).
- Settings: SharedPreferences `SettingsStore` (URL, token, language, scope, budget). i18n: `T()` + strings.xml.

## Acceptance → tests
- App starts, placeholders render → `AppStartsTest` (Robolectric)
- Tables vs contract, pending_op/sync_state round-trip → `ContractSchemaTest`
- Registry route → `FeatureRegistryTest`; no literals → `NoHardCodedStringsTest`

## New dependencies
- Koin 4.0.0 (DI, per DECISIONS); Compose BOM 2024.12.01 + material3, activity-compose, navigation-compose (UI)
- SQLDelight 2.0.2 plugin, android-driver, sqlite-driver (tests, JDBC)
- Robolectric 4.14.1, junit4, compose ui-test-junit4/manifest (tests)

## For the reviewer (≤ 3 lines)
- Added `android/src/main/sqldelight/` (outside the listed owned paths, needed for queries); SharedPreferences stores the token unencrypted.
