# HANDOFF LOOP-CLI

STATUS: DONE
BRANCH: t/LOOP-CLI
CHECK: `make check MODULE=cli` → green (120 tests, mypy, ruff)

## Changed (≤ 6 lines)
- `daemon/backend.py`, `session.py`: `Loop` dataclass on `QueueSnapshot`; missing `loop` → `BackendError("too_old")` → `conn: "server_too_old"` (no fallback).
- `daemon/server.py`, `review.py`: C2 keep-going day persisted in `<state>/kotobaya/keep-going-day` (restore on start, drop on `study_day` mismatch); C1 `serve_due_first` after each answer; C4 timer only in `waiting`.
- `schema/state.v1.json` (additive): phase, ready, later_today, later_next_due_at, backlog, backlog_beyond_limit, reviews_*, new_*; `conn` gains `server_too_old`.
- `bin/kby`, `watch.py`: number = ready; tooltip lines; class = phase without a card; `server_too_old` state. Old states render byte-identically.

## Acceptance → tests
- five phases → `test_daemon.py::test_status_per_phase`
- restart / new day → `test_keep_going_day_survives_restart`, `test_keep_going_dropped_on_new_day`
- C1 → `test_due_card_served_before_cached_new`
- widget → `test_kby.py::test_widget_ready_not_later`, `::test_server_too_old` (+ backend `loop` mapping test)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- C1 uses the preview `due_at` (interval_days <= 0) of the pressed button as the answered card's due, since POST answers return nothing here; the refetch waits up to 3 s for the POST. Not run against a live LOOP-SRV.
- Changed `test_review.py` keep-going servers to send `loop` (waiting no longer offers keep going: it is goal_reached/done_for_today only).
