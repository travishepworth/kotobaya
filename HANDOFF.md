# HANDOFF T305a (attempt 2)

STATUS: DONE
BRANCH: t/T305a-ankiapi-provider
CHECK: `make check MODULE=android` → green (81 tests, after rebase on T308); `make apk` → both release APKs

## Changed (≤ 6 lines)
- Flavors `ankidroid` (default, `com.ichi2.anki`) / `own` (`kotobaya.android`); namespace `kotobaya.android`. One owner per device: uninstall AnkiDroid/other flavor first. `testDebugUnitTest` = aggregate (ankidroid tests, both debug APKs, 4 manifest verifies); every `assemble<Variant>` verifies its manifest.
- Fix 1: no in-memory fallback. `AnkiProvider` uses `attachStore` (tests) or `AnkiStoreHolder` (T305c must call `AnkiStoreHolder.set(store)` in Application/Koin startup). Waits ≤5 s on cold start, then IllegalStateException.
- csum over the first field, sfld HTML-stripped, strip input capped at 64 KiB. Permission is checked before logging (unpermitted caller: one short line).
- `NoteSelection` rejects leftovers/`or`, binds `sfld=?` by position. Release logs value shapes only (debuggable builds show values).
- Flag: 2 permission label strings in `strings.xml` (outside owned paths). Manifest rebased on T308: its permissions and DueReceiver kept.

## Acceptance → tests
- Manifests → `verify{Ankidroid,Own}{Debug,Release}AnkiApiManifest`
- Replay → `AnkiProviderReplayTest` (seeded fixtures; README lists known differences)
- Permission → `AnkiProviderPermissionTest`; no-store refusal → `AnkiProviderStoreResolutionTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `make apk` builds both flavors (can't narrow it without editing the Makefile apk line).
- Call-log debug screen is a follow-up (needs a FeatureModules line and strings). No `<queries>` entry was needed.
- Capture the human's Takoboto call log with the debug APK, because release builds hide values.
