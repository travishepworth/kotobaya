# HANDOFF CLI-KG-SCOPE

STATUS: DONE
BRANCH: t/CLI-KG-SCOPE-cli
CHECK: `make check MODULE=cli` → green (124 passed, mypy, ruff)

## Changed (≤ 6 lines)
- cli/src/kotobaya_cli/daemon/server.py: keep going in memory only (no `keep-going-day` file); idle reset `_expire_keep_going` on each command and queue fetch; the press, answers, undo, skip touch the idle clock.
- cli/src/kotobaya_cli/config.py: `KEEP_GOING_IDLE_S = 600`, `load_keep_going_idle` (env `KOTOBAYA_KEEP_GOING_IDLE_S` > config.toml `keep_going_idle_s`).
- cli/src/kotobaya_cli/cli.py: passes the idle setting to the daemon.
- cli/README.md: setting and keep-going lifetime (ADR-039).

## Acceptance → tests
- Keep going resets on new session (restart) → test_daemon.py::test_keep_going_resets_on_new_session
- Idle 11 min → next build keep_going=false → test_daemon.py::test_keep_going_ends_after_ten_idle_minutes
- Idle 9 min keeps it → test_daemon.py::test_keep_going_survives_nine_idle_minutes
- Press after long pause does not expire at once → test_daemon.py::test_idle_clock_starts_at_the_keep_going_press
- Setting default/env/config/validation → test_config.py::test_keep_going_idle_default_env_and_config

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Idle clock starts at the keep-going press (else a press after a long pause would expire at once). Reveal does not count as activity.
- Expiry is checked only on command or fetch (no timer), so the widget may show keep going until the next event.
- Stale `keep-going-day` files are ignored and can be deleted.
