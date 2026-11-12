# HANDOFF T312 (attempt 1)

STATUS: BLOCKED
BRANCH: t/T312-android-release
CHECK: `make check MODULE=android` → green (signed and unsigned release builds verified with a throwaway key)

## Changed (≤ 6 lines)
- `android/build.gradle.kts`: `signingConfigs.release` + `buildTypes.release` read env (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); unsigned when absent.
- `.github/workflows/android-release.yml`: on `v*`, `make apk`, verify signatures and versions, publish `kotobaya-<ver>.apk` (default) and `kotobaya-own-id-<ver>.apk`.
- `android/signing/README.md`: keytool, four secrets, Obtainium regex, lost-key procedure.

## Acceptance → tests
- Workflow parses (YAML only; actionlint not installed); secrets are the four documented ones plus GITHUB_TOKEN.
- Signed build → `apksigner verify` passes for both flavors.
- No env → unsigned build succeeds.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Every tag currently fails the workflow's version check: `defaultConfig` still has versionCode 1 / versionName 0.0.0, and that block is outside my owned paths.
- Outside owned paths, not edited: `.gitignore` lacks `*.jks`/`*.keystore`; the Makefile `apk` comment still says "unsigned".

## If BLOCKED
QUESTION: May I edit `defaultConfig` in `android/build.gradle.kts` to set `versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1` and `versionName = System.getenv("VERSION_NAME") ?: "0.0.0"`? The workflow already exports both. Without this, Obtainium cannot detect updates. Options: approve the edit, or apply it yourself.
