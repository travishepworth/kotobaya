# HANDOFF T312 (attempt 2)

STATUS: DONE
BRANCH: t/T312-android-release
CHECK: `make check MODULE=android` → green; signed `make apk` (both flavors) verified with a throwaway key

## Changed (≤ 6 lines)
- `android/build.gradle.kts`: release signing from env; `defaultConfig` versionCode/versionName from `VERSION_CODE`/`VERSION_NAME` (approved edit).
- `.github/workflows/android-release.yml`: on `v*`, tag → `VERSION_NAME`, `VERSION_CODE = major*10000+minor*100+patch`; malformed tags fail first; `make apk`; signature and version checks; publishes `kotobaya-<ver>.apk` (default) and `kotobaya-own-id-<ver>.apk`.
- `android/signing/README.md`: keytool, secrets, Obtainium regex, lost-key procedure.
- Approved outside-owned edits: `.gitignore` (`*.jks`, `*.keystore`, `*.p12`); `Makefile` `apk` comment.

## Acceptance → tests
- Workflow YAML parses; tag derivation checked on 7 tags (valid, malformed, out-of-range).
- Signed build: `apksigner verify` passes for both flavors; `aapt2` shows versionCode 10203 / 1.2.3 for `VERSION_*=1.2.3`.
- No env → unsigned build succeeds.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Minor and patch must stay below 100 (versionCode formula); the workflow fails otherwise.
- actionlint is not installed here; the workflow was checked by YAML parse only.

## HUMAN_CHECKS
- Env: `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `VERSION_CODE`, `VERSION_NAME`.
- Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` (plus built-in `GITHUB_TOKEN`).
- Then push a tag, install via Obtainium, and confirm update detection.
