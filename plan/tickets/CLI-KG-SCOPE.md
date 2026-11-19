# CLI-KG-SCOPE — CLI daemon/kby: keep going per review visit (ADR-039)

- Phase: P10 · Model: haiku (writer-light) · Risk: low
- Deps: none
- Branch: t/CLI-KG-SCOPE-cli
- Owned paths: cli/**
- Module(s) for CI: worker-style pytest in cli (see cli/README or Makefile)

## Do
Where the CLI daemon persists keep going for the study day, scope it to one review session instead (reset when a new session starts / daemon restart). Show `until_stop` once SRV-28 is live if the widget has a counter (optional, skip if absent from client).
## Acceptance
- [ ] Test: keep going resets on new session.
