# HANDOFF SHELL-A1

Status: DONE. Branch `t/SHELL-A1-bundle-spa` (ticket said `t/SHELL-A1-bundle-web`; I used the name the prompt gave).

- `web/scripts/shell-manifest.ts`: `buildManifest`/`writeShellManifest` produce `{web_version, build_sha, min_local_api:1, files:{path:sha256}}`. `web/vite.config.ts` wraps adapter-static's `adapt` to write `build/shell-manifest.json` after the site is written. A plain `closeBundle` hook runs before adapter-static writes `build/`.
- `android/build.gradle.kts`: `bundleWeb` task runs `pnpm install --frozen-lockfile` then `pnpm build`, depends on `:core:npmPackage`, and has declared inputs. Output is `build/generated/shellAssets/web/`, registered as a generated assets dir for every variant. `-PskipWebBundle` never calls pnpm and keeps the old output.
- Verified: after `:android:mergeOwnDebugAssets`, `web/index.html` and `web/shell-manifest.json` are in the merged assets. A second run reports `bundleWeb UP-TO-DATE`.
- Tests: `web/tests/shell-manifest.test.ts` (vitest, sha256 of every file). The Makefile `check-android` target checks both files are in the merged assets, then runs unit tests with `-PskipWebBundle`.
- `make check MODULE=android` and `MODULE=web` are green.

Notes:
- The web `svelte-check` has no `@types/node`. Adding it would mean a new dependency and an edit to `package.json`, which I don't own. I added `web/scripts/shell-manifest.node.d.ts` with minimal ambient declarations instead.
- I did not add the APK-assets check as a Gradle test. It is a Makefile script check.
- `clients/typescript-shell` is listed as an input; it is ignored if absent.
