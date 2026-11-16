# SHELL-C1 — Contract: shell bridge, shell ops, local-op map, parity scenarios

- Phase: SHELL · Model: fable · Risk: high
- Deps: none
- Branch: t/SHELL-C1-contract
- Owned paths (only edit these): contracts/api/shell/**, contracts/api/check.sh, contracts/api/SUMMARY.md, clients/generate.sh, clients/typescript-shell/** (generated)
- Module(s) for CI: contracts
- Gradle-heavy: no · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 1-6
- Contracts: contracts/api/openapi.yaml (sections 1-3, review, notes, Problem), contracts/sync/SYNC.md (3.4, 5.2, 7, 8.1, 10.4, 10.5), contracts/sync/scenario.schema.json
- Decisions: ADR-002, 012, 030, 033, 034, 035, 036

## Do
Additive only. No edit to openapi.yaml, contracts/sync, contracts/db, contracts/core.
1. `SHELL.md`: normative text of design sections 1-6: bridge message table (every `t`, every field, constraints, example),
   invariants (duplicate id, ordering, limits, readiness, reload), rules L-1..L-8 and P-1..P-4, the shell problem codes
   (`shell_offline` 503, `shell_dict_missing` 503, `shell_rebuilding` 503 + Retry-After, `shell_unsupported` 415), navigation
   path table and regex, back protocol with the 300 ms fallback, `LOCAL_API_VERSION` rules, parity normalisation.
   Edge cases to write down explicitly: unpaired, token revoked mid-session, clock ahead/behind, offline creation then
   server-side delete of the parent, duplicate create ids, snapshot rebuild, dictionary not installed, empty replica.
2. `bridge.schema.json`: JSON Schema of every message; examples under `examples/bridge/` (valid) and `examples/bridge-invalid/`.
3. `shell.yaml`: OpenAPI 3.1 for `/shell/v1/*` (the 16 operationIds listed in design section 5), every schema property with
   description, constraints, example; same Problem schema by `$ref` to openapi.yaml. Generate `clients/typescript-shell`.
4. `ops.json`: `{operationId: "local"|"local_or_proxy"|"proxy"|"hidden"}` for every operationId of openapi.yaml, per design section 5.
5. `parity.schema.json` + `parity/*.json`: the mandatory scenarios of design section 6 (inputs only; goldens are SHELL-P1's).
6. `check.sh`: validate examples against schemas; fail when ops.json misses or invents an operationId; lint shell.yaml;
   validate every parity seed op against contracts/sync/op.schema.json. Update SUMMARY.md (decisions, rejected, 3 second-look items).

## Acceptance (each bullet → at least one named test)
- [ ] `make check MODULE=contracts` green; fails when an operationId is removed from ops.json (negative case in check.sh self-test)
- [ ] Every bridge example validates; every invalid example is rejected
- [ ] Every parity seed op validates against op.schema.json; each mandatory scenario of design section 6 exists and names what it `covers`
- [ ] Cross-check table in SHELL.md: each learning-data table ↔ sync op ↔ local or proxied operation

## Out of scope
Any implementation. Any change to openapi.yaml (if one turns out to be needed, stop and report it).

## Notes from previous attempts
