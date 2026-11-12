# HANDOFF T109b

STATUS: DONE
BRANCH: t/T109b-worker-runner
CHECK: `make check MODULE=worker` → green

## Changed (≤ 6 lines)
- worker/src/kotobaya_worker/runner/core.py: Registry (closed JobKind list), Runner (SKIP LOCKED claim, backoff+jitter retries, stale reclaim, heartbeat thread, cancel), JobContext (progress, is_cancelled/check_cancelled, JobCancelled).
- runner/__main__.py: `python -m kotobaya_worker.runner` runs /healthz thread + loop; empty `build_registry()` for later tickets. DATABASE_URL via dict/dburl.
- worker/tests/runner/: tests (own PG17 docker fixture, C.UTF-8).

## Acceptance → tests
- 1000 jobs, 3 runners → test_1000_jobs_three_concurrent_runners_exactly_once
- Retry/backoff, exhausted → test_retry_backoff_then_success_attempts_3, test_retries_exhausted_ends_failed
- Crash recovery → test_crash_recovery_stale_heartbeat_reclaimed_once (+ out-of-attempts variant)
- Cancellation → test_cancel_running_job

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Every post-claim write is guarded by status='running' AND locked_by=me; pg_notify on kotobaya_events ({"type":"job.updated"}) is only a hint. Claim and stale reclaim only cover registered kinds.
- Added tests/runner/__init__.py to avoid duplicate `conftest` module under mypy. Health/readiness and Dockerfile CMD untouched (not owned).
