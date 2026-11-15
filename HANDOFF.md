# HANDOFF C-26

STATUS: DONE
BRANCH: t/C-26-keep-going
CHECK: `make check MODULE=contracts|core|server` → green

## Changed (≤ 6 lines)
- core contract: `QueueLimits.keepGoing`/`keepGoingNewBatch` (default 10), `ReviewQueue.moreAvailable`, computed `QueueItem.isNew`, rule 6 KDoc + example
- `DefaultQueueBuilder`: rule 6 (caps lifted, batch budget, reviews then new, moreAvailable via a second build only when empty)
- openapi: `keep_going` param on `/review/queue`, `ReviewQueue.more_available`, `QueueItem.is_new`; SUMMARYs, core README (Android notes), ADR-031
- server: ReviewApi/QueueLoader/ReviewViews wiring (batch = `limit`); clients regenerated

## Acceptance → tests
- keepGoing false unchanged; caps lifted, priority, siblings, reviews first, no study-ahead; is_new → `QueueKeepGoingTest` (13) + existing QueueBuilderTest untouched
- server keep_going past new_per_day, more_available both empty cases, is_new → `ReviewApiTest.keepGoingPassesTheNewLimitAndMoreAvailableTellsDoneFromOut`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Keep going puts all reviews before new (not the rule-5 interleave) and lifts per-deck review caps too.
- New budget per build is bounded (server `limit`, device 10); an add_kanji group may overshoot it so it never starves.
- `is_new` = state new, so a reset card shows "New" too.
