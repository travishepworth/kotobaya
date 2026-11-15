# CLI-WATCH — kotobaya watch (json/waybar/template) + reconnect; widget examples

- Phase: P10 CLI daemon · Model: haiku (writer-light) · Risk: low
- Deps: CLI-DAEMON
- Branch: t/CLI-WATCH
- Owned paths (only edit these): cli/
- Module(s) for CI: cli

## Read
- docs/CLI-DAEMON.md IN FULL. It is the source of truth (human-approved 2026-11-15). Follow it exactly; where it is silent, pick the most reversible option and note it in HANDOFF.
- plan/handoffs/T604*.md (existing cli/), contracts/api/openapi.yaml for the endpoints used (read-only unless you are the architect on ARCH-CARD-TEXT).

## Do
`kotobaya watch [--format json|waybar|'<template>']`, reconnect loop with backoff emitting conn:offline while the daemon is down. Example waybar, eww and Hyprland (keybindings via echo … 1<>FIFO) configs in cli/README.md.
## Acceptance
- [ ] No tests required (per design). make check MODULE=cli green.

## Notes
- No new dependencies expected (stdlib tomllib/socket/os.mkfifo + the existing generated client). Any addition goes in HANDOFF.md with a reason.
- Never hand-edit clients/; regenerate.
