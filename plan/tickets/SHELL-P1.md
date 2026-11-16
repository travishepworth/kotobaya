# SHELL-P1 — Server parity runner and golden recording

- Phase: SHELL · Model: opus · Risk: med
- Deps: SHELL-C1
- Branch: t/SHELL-P1-parity-server
- Owned paths (only edit these): server/src/test/kotlin/kotobaya/parity/**, fixtures/shell-parity/** (new, with README)
- Module(s) for CI: server
- Gradle-heavy: yes (server) · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 6
- Contracts: contracts/api/shell/SHELL.md (parity), parity.schema.json, parity/*.json; contracts/sync/SYNC.md 5
- Decisions: ADR-033, 034, 035, 036

## Do
JUnit 5 + Testcontainers. For each scenario: fresh database; apply `settings` and `seed.log` through `POST /sync/push` as a paired test device;
run each step over HTTP with that device's token and the server clock fixed to `at_ms` (use the existing injectable clock; if a route has none,
return CONTRACT_CHANGE_REQUEST-style question in HANDOFF instead of patching main code). Normalise per SHELL.md.
`RECORD=1` writes `fixtures/shell-parity/golden/<id>.json`; otherwise assert equality with the stored golden.
Commit the recorded goldens.

## Acceptance (each bullet → at least one named test)
- [ ] `ParityGoldenTest`: every scenario in contracts/api/shell/parity has a golden and the server reproduces it
- [ ] A scenario whose seed op is rejected by the server fails loudly (no silent skip); the count of executed scenarios equals the count of files
- [ ] `make check MODULE=server` green

## Out of scope
Any server main-code change. Android runner (P2).

## Notes from previous attempts

## Orchestrator note (2026-11-16, from SHELL-C1 review)
Parity scenarios use TWO devices (seed device + device under test), and a scenario's `settings` is an index, not applied; follow contracts/api/shell/SHELL.md + parity.schema.json over any text above that differs.
