# HANDOFF LF-W0
Status: DONE. `make check MODULE=web` green (303 tests, svelte-check, build).

## Changes (tests only; no web code changed)
- Cause: C-31 made getStatsSummary/Heatmap/Retention `local` and the other stats/media/sentence reads `cached`; /stats, /media, /import/mapping each have a non-proxy op, so by the rule "gated only when every op is proxy" no route is gated. Code (`ui/access.ts`) was right; the tests were stale. Contract (ops.json, SHELL.md) is the source of truth.
- `web/tests/shell/access.test.ts`: gating-table test now asserts `gatedRoutes()` is empty, no listed path is gated, and `isProxyOnly` logic directly (listJpdbVocab proxy; mixed with cached is not). `needsConnection` test now expects false online and offline.
- `web/tests/shell/shellState.test.ts`: getStatsSummary is `local`; added getStatsForecast `cached`, parseText `proxy`.
- `web/tests/shell/syncView.test.ts`: added required `firstSync` to the fixture (svelte-check error, C-31 type change).

No new dependencies. No contract change needed.
