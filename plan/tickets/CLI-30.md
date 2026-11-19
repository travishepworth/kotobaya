# CLI-30 — CLI: show four-part counts / waiting state if the CLI displays loop counts (ADR-049)

- Phase: P10 · Model: haiku (writer-light) · Risk: low · Deps: C-30, SRV-30
- Branch: t/CLI-30 · Owned: cli/**
- CI: cli tests (uv run pytest in cli/)

## Read
- docs/DECISIONS.md ADR-033, ADR-037, ADR-038, ADR-039, ADR-049; plan/handoffs/C-30.md
- contracts/api/SUMMARY.md "C-30 changes"; contracts/core/README.md "ADR-049"; core/src/commonMain/kotlin/kotobaya/contracts/queue/ReviewLoop.kt; docs/REVIEW-LOOP.md §14 (C3, C4, C6); parity scenario queue-loop-wait-before-stop

## Do
If the CLI shows review counts or loop phase, use until_stop_parts with fallback; otherwise no-op and say so in HANDOFF.

## Acceptance
- [ ] cli tests green.
