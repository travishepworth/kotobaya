# HANDOFF ADR033-CLI

STATUS: DONE
BRANCH: worktree-agent-a5f2b24f65c4c69d9 (main merged in first for the contract commit)
CHECK: `make check-cli` equivalent (pytest 105, mypy --strict, ruff) → green

## Changed
- backend.py/session.py: `next_learning_due_at` -> snapshot -> state `next_due_at` (only with no card; schema updated). Counts pass through unchanged (no client count logic).
- server.py: timer wait = min(timer, day end, next due + 1 s when queue empty); timer wakes to recompute after each fetch. Keep going unchanged.
- watch.py: `{next_due_at}`, `{next_due_in}` (m:ss, empty when none). kby: state `waiting`, serve text `語 m:ss`, default conf `text.waiting = 語 next in {next_due_in}`; new fields only emitted when waiting, so old configs/output are byte-identical.

## Acceptance → tests
- waiting + countdown, refetch at due instant returns card, absent → done → test_daemon.py (3 new), test_kby.py waiting test

## New dependencies
- none

## For the reviewer
- Countdown: daemon `tick()` runs every 1 s and re-emits the state only while waiting and when the m:ss text changed (no extra lines in other states); kby/watch compute `next_due_in` at render time, so it counts down. Test: test_waiting_re_emits_once_per_changed_second_with_a_decreasing_countdown.

## User reinstall steps
1. `cd <repo>/cli && uv tool install --reinstall .`
2. `cp cli/bin/kby ~/.local/bin/kby` (or wherever kby lives; add `text.waiting = 語 next in {next_due_in}` to custom widget confs, otherwise `text` is used)
3. `systemctl --user restart kotobaya` (daemon), then restart the widget/`kby serve`.
