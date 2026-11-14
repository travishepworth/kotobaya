# HANDOFF OBS-1c-web

STATUS: DONE
BRANCH: t/OBS-1c-web
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- web/src/lib/errors/reporter.ts: pure reporter (fold by key, ≤20/batch, ≤100 pending + dropped, 413 split, 429 Retry-After, 30s–15min backoff, 401 pause, other status → dropped); per-item pre-validation to C-23 limits; scrub of quoted user text, kb?_ tokens, queries.
- web/src/lib/errors/index.ts: generated MetaApi (no middleware, keepalive), window error/unhandledrejection, reportApiFailure; web/src/hooks.client.ts (installs + SvelteKit handleError).
- api/client.ts middleware reports 5xx and network errors only (never 4xx, never /client-errors); session.svelte.ts calls resume on login.

## Acceptance → tests
- batching/capping/dedupe/413 split/no-recursion + C-23 extras (per-item validation, privacy) → web/tests/error-reporter.test.ts

- Review minors: keepalive only when body < 60 KB, fetch failure on oversized multi-item body = 413 split; HMR dispose removes listeners and cancels the timer; scrub also redacts regex literals and non-ASCII text after `position`/`:`.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Version comes from `import.meta.env.VITE_APP_VERSION` (fallback `0.0.0-dev`); set by a separate ticket.
- Scrub heuristic: quoted text with non-ASCII/whitespace or >40 chars is replaced by `…`; short ASCII identifiers kept.
