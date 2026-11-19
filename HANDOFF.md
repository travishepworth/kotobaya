# HANDOFF CLI-KG-SCOPE

STATUS: DONE
BRANCH: t/CLI-KG-SCOPE-cli
CHECK: `make check MODULE=cli` → green (120 passed, mypy, ruff)

## Changed (≤ 6 lines)
- cli/src/kotobaya_cli/daemon/server.py: removed the `keep-going-day` state file (restore on start, save on toggle and day change); keep going is in memory only.
- cli/tests/test_daemon.py: `test_keep_going_resets_on_new_session` (restart clears it, no file written); `test_keep_going_dropped_on_new_day` now drives the day change in-process.
- cli/README.md: keep-going paragraph (ADR-039).

## Acceptance → tests
- Keep going resets on new session → test_daemon.py::test_keep_going_resets_on_new_session

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Session = daemon run. The daemon has no visit or idle notion, so idle reset is not implemented; the flag persists until restart or study-day change.
- Existing `$XDG_STATE_HOME/kotobaya/keep-going-day` files are now ignored and can be deleted.
- `until_stop` skipped: not present in the CLI client.
