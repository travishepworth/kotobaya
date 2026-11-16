# HANDOFF RC-CLI

STATUS: DONE
BRANCH: t/RC-CLI
CHECK: `make check MODULE=cli` → green (107 passed, mypy, ruff)

## Changed (≤ 6 lines)
- backend.py `classify`: new kind "degraded" (read timeout, 5xx/408/429, unparsable body); only unreachable stays "offline".
- server.py: degraded fetch failure keeps conn (answers still POST) and schedules backoff refetch 2/5/10/30 s; refresh/keep_going run as background tasks (`state.loading`, `Daemon.idle()`); past-due-with-no-card timer backs off exponentially.
- session.py: check_current no longer swaps the card if the snapshot is full (20) or the card is revealed; `loading` in state + schema/state.v1.json + README.

## Acceptance → tests
- fetch timeout not offline-blocking, answer posts, refetch scheduled → test_queue_fetch_timeout_keeps_answers_working_and_schedules_a_refetch
- keep_going doesn't block reveal → test_keep_going_does_not_block_a_concurrent_reveal
- suite green → yes

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Item 3 is a heuristic (no per-card server check): keep when snapshot is full or card revealed; a revealed card gone elsewhere gets a terminal 409 notice on answer.
- Two keep_going tests now await `daemon.idle()` since the command returns before the fetch.
- Schema gained optional `loading` (additive).
