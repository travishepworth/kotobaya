# HANDOFF SMOKE-0110

STATUS: DONE
BRANCH: t/SMOKE-0110
CHECK: no module check applies (plan/smoke only); `bash -n` + `shellcheck` clean; ran only against an unreachable URL (fails fast, rc=1). Not run against a live server.

## Changed
- plan/smoke/smoke.sh: curl+jq smoke via `/api/v1`, bearer or session login (CSRF header), created-ids file + EXIT trap cleanup + `--cleanup <file>`.
- plan/smoke/README.md (usage, safety, checks, log grep).

## Acceptance → tests
- All listed checks implemented, PASS/FAIL/SKIP per line, nonzero exit on FAIL.

## New dependencies
- none (bash, curl, jq)

## For the reviewer
- Deviation: grading uses `POST /review/answer` (SPA path, emits review.logged via op-apply), not `/sync/push` (needs device-bound token and HLC minting); verified through `/sync/pull`. Needs `review` scope on the token beyond read/write/import.
- Card search uses `query=<term> nid:<id>`; if bare-text search does not match readings, the 食べる/たべる/タベル checks FAIL by design (unverified live).
- If you own a 食べる note, a free 食べ* entry is used instead; JLPT list build uses the first of N5..N1 you have not enabled.
