# HANDOFF C-26 (attempt 2)

STATUS: DONE
BRANCH: t/C-26-keep-going
CHECK: `make check MODULE=contracts|core|server` → green

## Changed (≤ 6 lines)
- Core contract and builder: `QueueLimits.keepGoing`/`keepGoingNewBatch`, `ReviewQueue.moreAvailable`, `QueueItem.isNew`, and rule 6. Rule 6 now has a normative "Scope" paragraph, notes that `newShownThisSession` keeps counting, and says "done for now" because hitting only the session cap also sets moreAvailable.
- OpenAPI: added the `keep_going` param, `more_available` and `is_new`. Added the same scope sentence (param, description, core README, ADR-031). Reworded the empty-items line to point at the three end states ("for this `kinds`"). Documented that in keep-going mode `counts.new` is the batch.
- New: `StatsSummary.more_available`, served from StatsModule's existing build.
- ReviewApi now uses core's `queue.moreAvailable` when all kinds are requested; it does the extra build only when a kinds filter is set. Clients regenerated.

## Acceptance → tests
- Core → `QueueKeepGoingTest` (13 tests; the exclusions test now also covers NEVER_FORGET and IGNORED)
- Server → `ReviewApiTest.keepGoingPassesTheNewLimitAndMoreAvailableTellsDoneFromOut` (now also checks /stats/summary and the kinds-filter path)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Scope is enforced by clients (WEB-KEEPGOING and AND-KEEPGOING must honour it). The server can't know about visits, so it relies on the client comparing `study_day`.
