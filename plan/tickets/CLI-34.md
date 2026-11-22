# CLI-34 — CLI/waybar number = until_stop (ADR-054)

- Phase: P11 · Model: sonnet (writer) · Risk: low
- Deps: C-34, LOOP-34 (CLI card-state work committed on main)
- Branch: t/CLI-34-until-stop
- Owned paths (only edit these): cli/src/kotobaya_cli/, cli/bin/kby, cli/schema/state.v1.json, cli/README.md, cli/tests/
- Module(s) for CI: cli

## Read
- Contracts: openapi.yaml `ReviewLoop` C3 (after C-34)
- Decisions: ADR-054

## Do
The daemon/serve/watch `text` number ("語 12") and any total shown by kby/watch = `loop.until_stop` (learning + review + new doable now), not `ready.total` (session.py:320 `"ready"`, backend.py:241). Header parts: learning, review, new; `later` only as a hint. Keep the `ready` field in state JSON for compatibility; document that `text` uses until_stop.

## Acceptance
- [ ] cli test: state with ready 2, later 13, new 0 → text number 2; ready 2, new 10 → 12.
- [ ] `make check MODULE=cli` green.

## Out of scope
Core, server, web, android (LOOP-34).
- Reviewer note (LOOP-34 r1): watch.py:173 waybar shows a "N later" segment; ADR-054: later is never a chip. Drop it from segments, keep it in the tooltip.
