# HANDOFF T213b

STATUS: DONE
BRANCH: t/T213b-core-js-facade
CHECK: `make check MODULE=core` → green; `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `web/src/lib/core/index.ts`: typed wrapper `renderCard`, `parseQuery` (discriminated union), `completeQuery`, `previewIntervals` (instants = epoch ms); converts plain TS ↔ Js* DTOs; documented as the single `@kotobaya/core` import point.
- `web/tests/core/wrapper.test.ts`: vitest smoke tests against the real built package.
- No change to `core/` (jsMain/jsTest/build.gradle.kts), `web/package.json` or the Makefile: `kotobayaCore()` already exists as `@JsExport` in commonMain `kotobaya/core/KotobayaCore.kt` (T108a, as contracts/core/README specifies), ESM + d.ts output already set, and `check-web` already runs `:core:npmPackage` first.

## Acceptance → tests
- renderCard vocab `<ruby>`, sanitised → `renderCard › returns sanitised html with ruby…`, `› strips script and handlers…`
- parseQuery ok / error with position → `parseQuery › accepts the spec example…`, `› returns errors with positions…`
- completeQuery → `completeQuery › completes deck names after deck:`
- previewIntervals four for new card → `previewIntervals › returns four intervals…`
- identity with T213a golden → `renderCard › equals the T213a golden across the JS boundary` (reads RenderGoldens.kt via `?raw`, so no drift)
- checks → both green

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `web/tests/core.test.ts` (not mine) still imports `@kotobaya/core` directly; the single-import rule applies to app code. Move/delete it later if wanted.
- No app module imports the wrapper yet, so `pnpm build` doesn't yet bundle core (vitest does resolve it through Vite).
