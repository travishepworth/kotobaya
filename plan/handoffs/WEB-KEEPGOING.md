# HANDOFF WEB-KEEPGOING

STATUS: DONE
BRANCH: t/WEB-KEEPGOING
CHECK: `make check MODULE=web` → green

## Changed
- web/src/lib/state/review.svelte.ts: keepGoing/moreAvailable per ReviewPage instance (one visit, never persisted); fetches with keepGoing; drops it and refetches if studyDay changes; keepGoingOn().
- web/src/routes/review/+page.svelte: "Done for now" + Keep going button when moreAvailable; "All caught up" (no button) in keep-going; ?more=1 starts in keep-going; "New" badge on card (isNew); counts/remaining hidden in keep-going (batch, not total).
- web/src/routes/+page.svelte: Today empty state shows "Done for now" + Keep going -> /review?more=1 from summary.moreAvailable (no extra call).
- i18n in review/messages.ts and routes/messages.ts.

## Acceptance → tests
- make check MODULE=web green → ran, green (no new tests; UI)

## New dependencies
- none

## For the reviewer
- In keep-going mode progress is done/(done+items.length), not counts.
- Badge uses --kb-color-state-new; not visually checked.
