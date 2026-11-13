# HANDOFF C-13

STATUS: DONE
BRANCH: t/C-13-grammar-detect-kind
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- openapi: `grammar-detect` added to `JobKind` (public, like media-parse). New `JobPayloadGrammarDetect {media_id?}` and `JobResultGrammarDetect` (oneOf Media/FanOut, plus optional `skipped`). Not forwarded to ntfy. Grammar create/update descriptions corrected.
- SCHEMA.md: `grammar-detect` is now an allowed `media-publish` parent. New protocol section: server enqueues the fan-out (key `grammar-detect:all`) in op-apply. The fan-out releases its key before reading rules. Per-media reads rules, base and spans in one snapshot and re-reads them on stale_base. Publish keeps `parse_status`.
- clients regenerated.

## Acceptance → tests
- additive, no drift → `make check MODULE=contracts`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- contracts/SUMMARY.md not written (harness refused the file). Decisions are in SCHEMA.md "grammar-detect".
- Server runner needs a parent-kind branch (keep parse_status). T506a must: release key, single snapshot, re-read rules on re-plan, and add detection to import/parse publishes.
