# HANDOFF T305a

STATUS: DONE
BRANCH: t/T305a-ankiapi-provider
CHECK: `make check MODULE=android` → green (31 tests); `make apk` → both release APKs

## Changed (≤ 6 lines)
- Flavors `ankidroid` (default, `com.ichi2.anki`) / `own` (`kotobaya.android`); namespace stays `kotobaya.android`. Only one app per device can own the authority+permission: uninstall AnkiDroid or the other flavor first.
- `testDebugUnitTest` is now an aggregate task (ankidroid unit tests + assemble both debug flavors + `verify<Variant>AnkiApiManifest`), so Makefile is unchanged. APKs: `kotobaya-<type>.apk` (default), `kotobaya-own-<type>.apk`.
- `ankiapi/provider/`: `AnkiProvider` on an `AnkiStore` interface (Koin lookup for T305c, else `InMemoryAnkiStore`), table-driven `AnkiColumns`, `NoteSelection`, bounded `ProviderCallLog` (logcat `KotobayaAnkiApi`, 500 lines, `!!` for unknowns).
- Manifest: permission + provider (spec=2 meta-data). Flag: 2 permission label strings added to `strings.xml` (outside owned paths).

## Acceptance → tests
- Both flavors build, manifests right → `verifyAnkidroidDebugAnkiApiManifest`, `verifyOwnDebugAnkiApiManifest` (in check)
- Call-log replay → `AnkiProviderReplayTest` over `src/test/resources/ankiapi/*.json` (seeded, README documents format)
- Permission refusal → `AnkiProviderPermissionTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `make apk` (`assembleRelease`) builds both flavors; can't target one without editing the apk Makefile line.
- Call-log debug screen not built: needs a `FeatureModules.kt` line + strings (not owned). Follow-up.
- Duplicate deck name returns the existing id (AnkiDroid may throw); unparseable note selection returns no rows.
