# PORTRAIT-AND — Shell: Lock to portrait (device preference)

- Phase: SHELL · Model: sonnet · Risk: low
- Deps: LOOP-C (shell.yaml display ops), SHELL-A6 and SHELL-A7 merged (device Routes.kt)
- Branch: t/PORTRAIT-AND-lock-portrait
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/device/Display*.kt, android/src/main/kotlin/kotobaya/android/shell/api/device/Routes.kt (two route lines + capability), the shell activity file (orientation apply only), android/src/test/kotlin/kotobaya/android/shell/api/device/Display*Test.kt, RouteTableTest PENDING lines
- Module(s) for CI: android
- Gradle-heavy: yes · First phone test: yes (rotate the phone)

## Read
- Contracts: contracts/api/shell/shell.yaml (`getDisplayPrefs`, `updateDisplayPrefs`, `ShellDisplay`), SHELL.md 6.3, §12
- Design: docs/REVIEW-LOOP.md §6

## Do
- Store `lock_portrait` (default false) in the existing device preferences store. `GET` returns it; `PATCH` validates (`additionalProperties` false, at least one property → `422` otherwise), stores, and applies at once on the UI thread.
- Activity: at create and on change set `requestedOrientation` to `SCREEN_ORIENTATION_PORTRAIT` when true, `SCREEN_ORIENTATION_UNSPECIFIED` when false. WebView state must survive the rotation that unlocking may cause (no reload).
- Add `display` to `ShellInfo.capabilities`. No op is minted, nothing is synced.

## Acceptance (each bullet → at least one named test)
- [ ] `DisplayRouteTest.defaultFalse`, `.patchPersistsAndApplies` (Robolectric: activity `requestedOrientation` changes), `.emptyPatch422`
- [ ] `DisplayRouteTest.unpaired401` / rebuild `503` as SHELL.md §12
- [ ] `flock /tmp/kotobaya-gradle.lock make check MODULE=android` green

## Out of scope
The SPA row (PORTRAIT-WEB); tablet-specific behaviour.

## Notes from previous attempts
