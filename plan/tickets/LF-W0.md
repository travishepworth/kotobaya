# LF-W0 — Web: fix 3 shell tests red on t/LF-int (route gating, ops.json mode)

- Phase: P10 local-first · Model: sonnet · Risk: med
- Deps: none (branch from origin/t/LF-int)
- Branch: t/LF-W0-web-shell-drift
- Owned paths (only edit these): web/tests/shell/, web/src/lib/state/shell.svelte.ts, the web route-gating / access module that tests/shell/access.test.ts imports, web/src/lib/api/shell/ (ops.json reading only)
- Module(s) for CI: web

## Read
- Contracts: contracts/shell/ (shell.yaml, ops.json, SHELL.md — route gating / modes sections) as changed by C-31
- Decisions: ADR-050, ADR-051 (local-first shell for everything)
- Context: plan/handoffs/C-31.md if present

## Do
On t/LF-int, `make check MODULE=web` has exactly 3 failures, all pre-existing (not caused by any LF writer):
- tests/shell/access.test.ts › route gating table › needs-connection only while offline
- tests/shell/access.test.ts › route gating table › stats, media and the import mapping are gated; note-types, home and review are not
- tests/shell/shellState.test.ts › browser › not present, online, no capabilities; modeOf still reads ops.json
Likely cause: C-31 changed which ops are local (ops.json / route table) for local-first. Decide per failure whether the test expectation is stale (update it to match the C-31 contract) or the web code is (fix the code). The contract is the source of truth; never edit contracts/. If the contract itself looks wrong, return CONTRACT_CHANGE_REQUEST.

## Acceptance (each bullet → at least one named test)
- [ ] The 3 tests pass, each matching the C-31 contract (say in HANDOFF which side you changed and why).
- [ ] `make check MODULE=web` fully green.

## Out of scope
Outbox (LF-W1), prefetch/stores (LF-W2), first-sync screen (LF-W3), review route.

## Notes from previous attempts
