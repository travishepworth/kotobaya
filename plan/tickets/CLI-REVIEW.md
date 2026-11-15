# CLI-REVIEW — Journal-first answer/undo, retry/replay, reviewed-elsewhere, five-button mapping

- Phase: P10 CLI daemon · Model: opus (writer-senior) · Risk: medium
- Deps: CLI-DAEMON; card text needs ARCH-CARD-TEXT
- Branch: t/CLI-REVIEW
- Owned paths (only edit these): cli/
- Module(s) for CI: cli

## Read
- docs/CLI-DAEMON.md IN FULL. It is the source of truth (human-approved 2026-11-15). Follow it exactly; where it is silent, pick the most reversible option and note it in HANDOFF.
- plan/handoffs/T604*.md (existing cli/), contracts/api/openapi.yaml for the endpoints used (read-only unless you are the architect on ARCH-CARD-TEXT).

## Do
Review flow section of the design, steps 1–7.
## Acceptance (required tests from the doc)
- [ ] Journal written (fsynced) before POST /review/answer.
- [ ] Retry/replay reuses the same id (no double review.logged).
- [ ] Daemon crash between journal and POST replays exactly once.
- [ ] Terminal 409/410/422 drops the entry with a notice.
- [ ] Current card replaced only when gone from the queue.
- [ ] Undo targets the last answered id.
- [ ] make check MODULE=cli green.

## Notes
- No new dependencies expected (stdlib tomllib/socket/os.mkfifo + the existing generated client). Any addition goes in HANDOFF.md with a reason.
- Never hand-edit clients/; regenerate.
