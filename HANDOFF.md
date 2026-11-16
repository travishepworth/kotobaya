# HANDOFF SHELL-C1

STATUS: DONE
BRANCH: t/SHELL-C1-shell-contract
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- Attempt 3 (SHELL.md L-7, §4.3 createNote, §8.2, §10.7): installed `all` package is authoritative only when its release ≥ the last known server release; otherwise treated like `top60k` (gap fill fetches; offline → 503 `shell_dict_missing`, never 404/422)
- Server-404 negative results are per server release and dropped when it changes
- `searchDictionary` / `listKanjiWords`: online and (no package, not authoritative, or empty local page) → forward, overlay recomputed locally; `l.` cursor prefix
- shell.yaml: optional `ShellDictInstall.server_release_id`; client regenerated

## Acceptance → tests
- check green; removed operationId fails → `ops_map_complete`, `selftest_ops_missing_id`
- bridge examples → `bridge_examples_valid`, `bridge_examples_invalid`
- parity seed ops, mandatory scenarios → `parity_seed_ops`, `parity_seed_accepted`, `parity_mandatory_covered`
- cross-check → `shell_md_cross_check`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- "Merge" for search = the server's page with per-user overlay recomputed from the replica, not a union of two paged lists (the server's result is a superset).
- Parity harness pins last known server release = fixture package release, so `dict-entry-missing` stays an authoritative miss.
- Unknown server release (never synced) = not authoritative.
