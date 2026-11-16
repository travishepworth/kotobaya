# SHELL-Z2 — Web bundle OTA (in scope; last)

- Phase: SHELL · Model: opus · Risk: med
- Deps: SHELL-A3, SHELL-W1
- Branch: t/SHELL-Z2-web-ota
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/ota/**, android/src/main/kotlin/kotobaya/android/shell/web/AssetSource.kt, android/src/test/kotlin/kotobaya/android/shell/ota/**, deploy/** (nginx: serve shell-manifest.json with no-cache), web/src/routes/settings (version row)
- Module(s) for CI: android, deploy
- Gradle-heavy: yes · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 2
- Contracts: contracts/api/shell/SHELL.md (versioning)
- Decisions: ADR-036

## Do
After a successful sync (unmetered or user-initiated), fetch `<server origin>/shell-manifest.json` with the device token. If `web_version`
is newer than the active bundle and `min_local_api <= LOCAL_API_VERSION`: download changed files to `files/web/<web_version>.tmp/`, verify every
sha256 and the total against the manifest, atomically rename, mark `pending`. Activate on the next cold start only. If `ready` is not received
within 10 s on two consecutive starts, mark the bundle `bad` (never retried for that version) and serve the APK bundle. Keep at most one
downloaded bundle. An APK update whose bundled `web_version` is newer wins and deletes the download. Capability `ota`; settings row shows active version and source.

## Acceptance (each bullet → at least one named test)
- [ ] `OtaVerifyTest`: wrong hash, missing file, truncated download, extra path (`../`) → rejected, active bundle unchanged
- [ ] `OtaCompatTest`: `min_local_api` above the shell's → ignored; older or equal version → ignored
- [ ] `OtaRollbackTest`: two starts without `ready` → bundled assets served and the version is blacklisted
- [ ] `OtaPathTest`: the asset source never serves a file outside the active bundle directory

## Out of scope
Signing bundles (the paired server is already trusted with all data; note in SUMMARY as a second-look item).

## Notes from previous attempts
