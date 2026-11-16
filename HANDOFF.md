# HANDOFF SHELL-C1

STATUS: DONE
BRANCH: t/SHELL-C1-shell-contract
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- `contracts/api/shell/`: SHELL.md (normative), bridge.schema.json + 28/43 examples, shell.yaml (16 ops), ops.json (123 ids), parity.schema.json + 16 scenarios, check_shell.py
- `contracts/api/check.sh`, `SUMMARY.md` (SHELL-C1 section); `clients/generate.sh` → `clients/typescript-shell`
- openapi.yaml, contracts/sync|db|core, existing clients: untouched

## Acceptance → tests
- check green; fails on removed operationId → `ops_map_complete`, `selftest_ops_missing_id` (also run for real once)
- bridge examples → `bridge_examples_valid`, `bridge_examples_invalid`
- parity seed ops, mandatory scenarios → `parity_seed_ops`, `parity_seed_accepted`, `parity_mandatory_covered`
- cross-check table → `shell_md_cross_check` (SHELL.md 11)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Differs from tickets: parity needs TWO devices (seed + under test) and `settings` is an index, not applied (P1 text says otherwise); `unpairShell` takes body `{force}`; events are `shell.sync`/`shell.dict`; fifth code `409 shell_pending_ops`; ShellInfo `auth_required`.
- My decisions: clock refusals → 422 at `/reviewed_at` for all writes; `createNote` by entry without dictionary → 503 `shell_dict_missing`; gate wait > 20 s → 503 `shell_rebuilding`.
- Parity `expect` values come from contract text, not a running server; P1 may disprove one. clients/README.md (not owned) lacks typescript-shell.
