# HANDOFF OBS-1w2

STATUS: DONE
BRANCH: t/OBS-1w2
CHECK: `make check MODULE=worker` → green

## Changed (≤ 6 lines)
- logs.py: `job_summary` gains `max_level` / `warn_as_info`; invalid LOG_LEVEL -> INFO + warning; main excepthook chains to `sys.__excepthook__`.
- media/pipeline/plan.py: unmapped tokens WARN only when > 5% of tokens (ERROR untouched).
- jpdb_history/handler.py: plan summary capped at INFO; only final summary WARN/ERROR.
- runner/core.py: heartbeat thread runs in `copy_context()` (no other pools exist).

## Acceptance → tests
- items 1,2,4,6 → test_media_few_unmapped_tokens_is_info, test_jpdb_history_end_to_end_one_warning_and_result_unchanged, test_invalid_log_level_falls_back_to_info (existing plan-level assertion updated to INFO)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Plan summary at INFO means a zero-card import ERRORs once (final) instead of twice.
- Below-ratio media summaries keep outcome "dropped" in fields but log at INFO.
