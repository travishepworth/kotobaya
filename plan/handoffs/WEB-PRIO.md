# HANDOFF WEB-PRIO

STATUS: DONE
BRANCH: t/WEB-PRIO-order
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- web/src/lib/state/decks.svelte.ts: exported `compareDeckDrawOrder` (priority asc, then name); `/decks` list uses it.
- web/src/lib/decks/messages.ts: `decks.priorityLabel` now "Priority (lower numbers are introduced first)".
- web/src/routes/decks/messages.ts: `dk.priorityHint` now lower-first wording.
- web/src/routes/settings/messages.ts + StudySection.svelte: new `sx.minedPriorityHint` shown as hint under mined priority field.
- web/tests/decks/order.test.ts: sort test ([-10, 0, 5, 100]) and name tie-break.

## Acceptance → tests
- decks [-10, 0, 5, 100] sort in order → `compareDeckDrawOrder` › lists the lowest priority number first
- make check MODULE=web green → 87 tests passed, build ok

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Tie-break is by name (existing behaviour), not id; server orders equal priorities by id, so equal-priority decks may list in a different order than the draw.
- The mined-priority label itself lives in web/src/lib/settings/messages.ts (outside owned paths); only the hint was added, via routes/settings messages.
