# HANDOFF WORKER-PROGRESS

STATUS: DONE
BRANCH: t/WORKER-PROGRESS-import
CHECK: `make check MODULE=worker` → green; `make check MODULE=deploy` → green

## Changed (≤ 6 lines)
- jpdb_deck/client/pages.py: `on_page` callback per page, INFO log every 10 pages + summary line. handler.py: `page_progress` (0..0.5 asymptotic, DB write ≤1/2 s, "fetching deck: page N (M words)").
- runner/core.py: `JobStopped`; `JobContext.check_cancelled()` now also raises it when SIGTERM stop flag is set → job released to `queued` (attempts-1, lock cleared). Heartbeat thread releases an uncooperative job after `shutdown_budget` (20 s) on its own connection.
- Chart: worker `terminationGracePeriodSeconds` (default 45).

## Acceptance → tests
- 3-page scrape progress + summary log → test_page_progress_per_page_and_summary_log
- stop flag → queued, attempts unchanged, locked_by null → test_stop_flag_releases_long_handler_to_queued, test_stop_flag_budget_releases_uncooperative_handler

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decision: stop check lives in `check_cancelled` (all handlers, incl. dict-refresh, honour SIGTERM at their existing stage/page boundaries) rather than editing each handler.
- Release decrements attempts, so a stopped job's attempt is not consumed. Claim order/concurrency untouched.
