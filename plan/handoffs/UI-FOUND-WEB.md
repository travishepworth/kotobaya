# HANDOFF UI-FOUND-WEB

STATUS: DONE
BRANCH: t/UI-FOUND-WEB-state-harness
CHECK: `make check MODULE=web` → green (37 tests, svelte-check 0 errors, build ok)

## Changed (≤ 6 lines)
- web/src/lib/state/*.svelte.ts: one state module per screen (fetching, caching, core calls, mutations); session moved here
- All .svelte views and components now import only from state/, components, messages, i18n and toasts
- Effects no longer read state they write (untrack or derived instead; e.g. note-type samples, coverage segment)
- fixtures/demo/: CC0 wire-form JSON + build.py + README (scenarios default/empty/due900)
- web/mock/demo-api.ts + `make dev-ui [SCENARIO=…]`; web/playwright + `make screenshots-web` → 672 images

## Acceptance → tests
- make check green, existing tests unchanged → CardPreview/NoteEditor bounded-effect tests pass
- Import rule → tests/architecture/view-imports.test.ts
- dev-ui / screenshots → harness run: 168 passed, 672 PNGs (not committed)

## New dependencies
- @playwright/test@1.65.0 (dev): screenshot harness. Uses system Chromium via CHROMIUM_PATH.

## For the reviewer (≤ 3 lines)
- Riskiest: the view rewrite (≈45 files, mostly `s.`/`vm.` prefixing); svelte-check and the tests pass, and I spot-checked screenshots, but there is no e2e coverage.
- The harness runs `vite build && vite preview`: the dev server's dependency re-optimisation reloaded pages mid-capture.
- eslint now parses .svelte.ts files. That surfaces existing prefer-svelte-reactivity errors; lint is not a gate.
