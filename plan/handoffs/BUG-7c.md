# HANDOFF BUG-7c

STATUS: DONE
BRANCH: t/BUG-7c-batch-headwords
CHECK: `make check MODULE=server` green (exit 0); `make check MODULE=web` green (exit 0)

## Changed (≤ 6 lines)
- Server: `listDictHeadwords` in dict/DictApi.kt (one query, `seq in (...)`, ascending, unknown omitted, dupes collapse, retired kept; missing/empty/repeated/bad/>500 -> 422), registered in `dictHandlers`.
- Main.kt: Netty `maxInitialLineLength` = 16384 via `configureRequestLimits()`; server now built with `connector { }` config.
- WiringAuditTest: allowlist entry removed (now empty).
- Web: `fetchHeadwords` (lib/cards/api.ts; chunks of 500, per-page-life cache, silent failure) called after each page load in /cards (headword map) and /notes (`HeadwordMap.putHeadwords`, Reading/Meaning columns).

## Acceptance → tests
- Mixed known/unknown, 422 cases -> server/src/test/kotlin/kotobaya/dict/DictHeadwordsTest.kt
- 500 seqs over real Netty (%2C) -> same file
- Web: UI only, no tests

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The Netty test does not prove it fails at the 4096 default (not checked).
