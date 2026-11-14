# HANDOFF OBS-1w

STATUS: DONE
BRANCH: t/OBS-1w-worker-logging
CHECK: `make check MODULE=worker` → green (859 passed, mypy and ruff clean)

## Changed (≤ 6 lines)
- New `worker/src/kotobaya_worker/logs.py`: JSON formatter (or `LOG_FORMAT=text` for key=value), `job_scope` adds job_id/kind to every record, `job_summary` level rule, excepthooks. Wired in `runner/__main__.py`.
- Runner logs start, success, cancel and failure with the stack (WARN on retry, ERROR on the last attempt) and stale-job reclaims; an unexpected loop error no longer kills it.
- Every handler logs a final `job_summary` with input, output and dropped counts: jpdb history, jpdb deck, media import and parse (covers parse and coverage), dict-refresh, vocab lists, deck-build, tts, optimizer, grammar-detect.
- `dict/fetch.py`: 4xx HTTPError is no longer retried. Stale `<name>.*.part` files older than 6 h are deleted on entry.

## Acceptance → tests
- count/zero WARN/ERROR → `tests/jobs/test_job_summaries.py`, `tests/test_logs.py`
- runner stack and job_id → `test_failures_log_stack_with_job_id_and_final_attempt_is_error`
- fetch minors → `test_http_4xx_is_not_retried`, `test_http_5xx_is_retried`, `test_stale_part_files_are_removed_fresh_ones_kept`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- 5xx and 429 are still retried; only 4xx is re-raised at once.
- Media WARNs whenever unmapped_tokens > 0, so it may be noisy. ERROR fires when unique_entries == 0.
- An optimizer that declines to publish, or grammar that finds 0 spans, stays INFO and is not an ERROR.
