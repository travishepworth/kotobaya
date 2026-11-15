# HANDOFF API-GUIDE

STATUS: BLOCKED
BRANCH: t/API-GUIDE (worktree branch worktree-agent-a6291d3b817aff898)
CHECK: n/a (docs-only, no module per ticket)

## Changed (≤ 6 lines)
- docs/API-GUIDE.md (new): setup, scopes, 31 recipes in 11 sections, search cheat sheet; each names its operationId.
- docs/RUNBOOK.md: one link line in intro.
- cli/README.md: not changed (file absent on this branch).

## Acceptance → tests
- Every curl path, method, query name and body key exists in openapi.yaml → checker run on 40 calls, 0 unknown; 2 nested `items[]` false positives on addDeckItems (script in scratchpad, not committed).
- Link in cli/README.md → NOT DONE (see question).
- Link in docs/RUNBOOK.md → `grep API-GUIDE docs/RUNBOOK.md`.

## New dependencies
- none

## For the reviewer
- `scheduler.mined_priority` is from contracts/db/SCHEMA.md, not openapi; used only as a settings key.
- Recipes are not run against a live server; jq fields checked against schemas.

## If BLOCKED
QUESTION: cli/README.md does not exist on this branch (origin/main e76d2711), and CLI-REVIEW owns cli/. Options: (a) I create cli/README.md with only the link line; (b) defer that link to CLI-WATCH, which writes cli/README.md per docs/CLI-DAEMON.md. I recommend (b). Everything else is done and committed.
