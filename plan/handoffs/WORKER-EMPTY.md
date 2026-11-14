# HANDOFF WORKER-EMPTY

STATUS: DONE
BRANCH: t/WORKER-EMPTY-import
CHECK: `make check MODULE=worker` → green (866 passed, mypy, ruff)

## Changed (≤ 6 lines)
- Root cause: the 11-13 run used the slugless URL; its 0-row overview page was cached (cache still holds such entries, now ignored) and read as "no next page". Earlier hotfix already guards page 1; now every followed page must carry rows (not cached, else RuntimeError). `pages.py`.
- `handler.py`: 0 words fetched → job fails, no media-publish. Stage progress/log lines: fetch 0–.5, map .5, occurrences .65, upload .8 (`handoff.py` `upload_progress`), publish .9.
- `plan.py`: `DeckStats.merged`; check asserts entries+merged+unmapped+skipped == fetched; summary logs `merged`.
- Review minors: worker `strategy: Recreate`; `runner/core.py` refunds SIGTERM release at most 3x (count in `payload._releases`); `fetch_title`/`_try_title` re-raise JobStopped/JobCancelled.

## Acceptance → tests
- Stages → `test_every_stage_reports_a_distinct_message_and_monotonic_progress`
- Cached/0-row page → `test_followed_page_with_zero_rows_fails_and_is_not_cached` (+ existing first-page tests)
- 0 rows → `test_zero_rows_fails_and_publishes_nothing`
- Balance → `test_summary_counts_balance_with_duplicate_vids`; refund cap → `test_release_refunds_attempt_only_three_times`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Refund counter lives in job payload (`_releases`) as no schema column exists. Media-publish child stages are server-side (not touched); no TTS in deck import.
- A followed "Next page" with 0 rows now fails the import instead of truncating.
