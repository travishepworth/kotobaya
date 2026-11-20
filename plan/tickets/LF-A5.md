# LF-A5 — Shell: search and card queries never forward

- Phase: P10 local-first · Model: opus · Risk: med
- Deps: C-31
- Branch: t/LF-A5-search-local
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/search/, shell/api/browse/, shell/api/struggle/DrillRoute.kt (query branch), tests under android/src/test/kotlin/kotobaya/android/{search,shell/api/browse}/
- Module(s) for CI: android

## Read
- Spec: Clients → search
- Contracts: contracts/core/search.ebnf, search-examples.tsv; SHELL.md L-3, L-7, L-9; openapi.yaml `listCards`, `listNotes`
- Decisions: ADR-050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Remove `CardQueryResult.Forward`: every term of the search grammar is evaluated on the device. Dictionary-backed terms (gloss/reading/frequency matches over many entries) use the installed package through an indexed lookup (temp table or FTS over the ref database) instead of the guard that gave up; keep keyset pagination semantics. Parsing stays in core (never re-implemented).

## Acceptance (each bullet → at least one named test)
- [ ] Every row of `search-examples.tsv` that the server answers is answered by the shell with the same ids (parity), with an `Upstream` that fails when called.
- [ ] Test: a term matching > 50 000 dictionary entries answers within the unit-test timeout and paginates stably.
- [ ] Test: missing dictionary package → the documented local answer (not a forward).
- [ ] `make check MODULE=android` green.

## Out of scope
Dictionary screens (LF-A6).

## Notes from previous attempts
