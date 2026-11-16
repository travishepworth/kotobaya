# HANDOFF RC-FOLLOW-WEB

STATUS: DONE
BRANCH: t/RC-FOLLOW-WEB
CHECK: `make check MODULE=web` → green

## Changed
- web/src/lib/state/review.svelte.ts: `clockSkewed` flag set in `restore()` on a clock 422; `send()` omits reviewedAt once set (rest of page visit).
- web/tests/review/sessionEnd.svelte.test.ts: new test.

## Acceptance → tests
- second answer after clock 422 sent without reviewed_at, no notice → "after a clock 422 the next answers are sent without reviewed_at and with no notice"

## New dependencies
- none

## For the reviewer
- Flag is per ReviewPage instance (page visit); answers already queued before the 422 keep their reviewedAt.
