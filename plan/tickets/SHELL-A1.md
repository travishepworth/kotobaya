# SHELL-A1 — Bundle the SPA into the APK (Gradle task)

- Phase: SHELL · Model: sonnet · Risk: low
- Deps: none
- Branch: t/SHELL-A1-bundle-web
- Owned paths (only edit these): android/build.gradle.kts, web/vite.config.ts, web/scripts/shell-manifest.* (new), Makefile (check-android target only)
- Module(s) for CI: android, web
- Gradle-heavy: yes · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 2
- Contracts: none
- Decisions: ADR-036

## Do
- web: after `vite build`, write `build/shell-manifest.json` = `{web_version (package.json version), build_sha (git short sha or "dev"),
  min_local_api: 1, files: {relative path: sha256 hex}}` (a small Vite `closeBundle` plugin or node script; no new dependency).
- android: task `bundleWeb` (Exec: `pnpm install --frozen-lockfile` then `pnpm build` in `web/`) with declared inputs
  (`web/src`, `web/static`, `assets/card`, `clients/typescript`, `clients/typescript-shell` if present, `web/package.json`, lockfile)
  and output dir `build/generated/shellAssets/web/`; add that dir's parent as an `assets` srcDir; `mergeAssets` depends on it.
  `-PskipWebBundle` skips the Exec and uses the existing output (unit-test runs must not need pnpm).
- APK asset path is `web/…` (the shell maps it to `/`).

## Acceptance (each bullet → at least one named test)
- [ ] Gradle test or script check: after `:android:bundleWeb`, `web/index.html` and `web/shell-manifest.json` are in the debug APK assets
- [ ] Manifest lists every emitted file with a correct sha256 (vitest on the manifest function)
- [ ] Second run with no web change is UP-TO-DATE; `-PskipWebBundle` runs without invoking pnpm
- [ ] `make check MODULE=android` and `MODULE=web` green

## Out of scope
Serving the assets (SHELL-A3). OTA (SHELL-Z2).

## Notes from previous attempts
