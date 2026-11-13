# HANDOFF AND-SETTINGS

STATUS: DONE
BRANCH: t/AND-SETTINGS-settings-tab
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- New `settings/ui/` (SettingsUi.kt, StudySettingsModel.kt), `strings_settings.xml`; `settingsUiFeature` replaces the Settings placeholder (one line in FeatureModules.kt). It renders ALL registered `settingsSections` inline (devices, sync first; about last), plus new Study (new/reviews per day, rollover hour via `settings.set` ops through OpMinter) and About (app version, flavor, server version via `GET /health`; no licence screen exists, so no link).
- Unpaired hook: `HomeScreen` (ui/Screens.kt) shows a prompt + "Pair this device" button opening Settings; AppNav.kt: `allRoutes(registry, navigate = {})` threads a navigate lambda to Home. No ApiClient/T.kt edits needed; no pairing/ edits.
- `KotobayaApp` made `open` (test subclass initialises WorkManager, which the dict section needs when composed). `AppStartsTest`: Settings dropped from placeholder list, `@After stopKoin()` added.

## Acceptance → tests
- Devices/Scan QR reachable + every section rendered + unpaired Home route → `SettingsTabTest` (Robolectric, real app; scanner launch itself not exercised)
- One settings op per change, out-of-range mints nothing → `StudySettingsModelTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Limits validated 0..int4 (stricter than SCHEMA's int4: negatives rejected); unchanged values mint nothing. Home's unpaired flag is `remember`ed per Home composition.
- AppStartsTest/AppNav edits will likely trivially conflict with AND-DECKS.
