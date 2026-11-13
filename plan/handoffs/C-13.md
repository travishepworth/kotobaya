# HANDOFF C-13

STATUS: DONE (attempt 2)
BRANCH: t/C-13-grammar-detect-kind
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- openapi: `grammar-detect` in `JobKind` (public, like media-parse), `JobPayloadGrammarDetect`, `JobResultGrammarDetect`; not forwarded to ntfy; grammar descriptions fixed; ntfy text wrapped.
- SCHEMA.md: allowed `media-publish` parent. Fan-out key release now takes `pg_advisory_xact_lock(7033001)`, so edits are never lost. Tombstoning a point with rules MUST enqueue.
- clients regenerated.

## Acceptance → tests
- additive, no drift → `make check MODULE=contracts`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- contracts/SUMMARY.md not written (harness refused); decisions in SCHEMA.md.
- Server runner must keep parse_status for this parent; T506a must follow key/snapshot rules.
