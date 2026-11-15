# HANDOFF CLI-WATCH

STATUS: DONE
BRANCH: t/CLI-WATCH (also checked out as worktree-agent-a8d8c6464a6a0cb78; rebased on origin/main incl. API-GUIDE)
CHECK: `make check MODULE=cli` → green (45 pytest, mypy --strict, ruff)

## Changed (≤ 6 lines)
- `cli/src/kotobaya_cli/watch.py` (new): subscribe to daemon socket, relay state lines, json/waybar/template renderers, offline state and backoff reconnect.
- `cli/src/kotobaya_cli/cli.py`: `kotobaya watch [--format json|waybar|TEMPLATE]`; format validated at parse time.
- `cli/README.md` (new): config.toml, PAT scopes (read + review), systemd enable, watch, waybar/eww/Hyprland examples, API-GUIDE link.

## Acceptance → tests
- No tests required by ticket → manual smoke against a fake socket (drop, reconnect, offline once, junk line skipped, broken pipe exits 0).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decisions: offline line = last known state with conn "offline" and notice cleared, emitted once per outage; reconnect backoff 0.5 s doubling to 10 s; template fields are flat (`{new}`, `{front}`…), unknown fields rejected at startup.
- watch imports `Paths` from daemon/server (no daemon, review, journal or backend code touched).
