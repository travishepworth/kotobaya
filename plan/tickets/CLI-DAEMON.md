# CLI-DAEMON — Daemon: FIFO + socket, SSE cache, config, systemd unit, state object

- Phase: P10 CLI daemon · Model: sonnet · Risk: medium
- Deps: T604 (merged)
- Branch: t/CLI-DAEMON
- Owned paths (only edit these): cli/
- Module(s) for CI: cli

## Read
- docs/CLI-DAEMON.md IN FULL. It is the source of truth (human-approved 2026-11-15). Follow it exactly; where it is silent, pick the most reversible option and note it in HANDOFF.
- plan/handoffs/T604*.md (existing cli/), contracts/api/openapi.yaml for the endpoints used (read-only unless you are the architect on ARCH-CARD-TEXT).

## Do
Everything in the design's Inputs, State object, Config and process sections, except journaling answers (CLI-REVIEW) and `watch` (CLI-WATCH). Counts/conn/day_ends_at ship now; card text fields stay absent until ARCH-CARD-TEXT lands (card has id/kind only).
## Acceptance (required tests from the doc)
- [ ] FIFO parsing: invalid/unknown input never crashes; emits a notice.
- [ ] Full-state-per-line invariant on every emitted line (validated against cli/schema/state.v1.json).
- [ ] Config precedence: env > config.toml; token_file / token_cmd.
- [ ] make check MODULE=cli green.

## Notes
- No new dependencies expected (stdlib tomllib/socket/os.mkfifo + the existing generated client). Any addition goes in HANDOFF.md with a reason.
- Never hand-edit clients/; regenerate.
